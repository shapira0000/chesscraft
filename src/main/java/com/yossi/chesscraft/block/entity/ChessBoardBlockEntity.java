package com.yossi.chesscraft.block.entity;

import com.yossi.chesscraft.ChessCraft;
import com.yossi.chesscraft.block.ChessBoardBlock;
import com.yossi.chesscraft.bot.BotService;
import com.yossi.chesscraft.chess.ChessGame;
import com.yossi.chesscraft.chess.Move;
import com.yossi.chesscraft.chess.Piece;
import com.yossi.chesscraft.chess.Position;
import com.yossi.chesscraft.chess.engine.ChessBot;
import com.yossi.chesscraft.chess.engine.Evaluator;
import com.yossi.chesscraft.chess.engine.Searcher;
import com.yossi.chesscraft.network.ChessActionPayload;
import com.yossi.chesscraft.registry.ModBlockEntities;
import com.yossi.chesscraft.registry.ModBlocks;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * State of one chess board: the physically stored pieces (which stand on their standard
 * squares automatically), an optional free-form custom arrangement (unlimited virtual pieces),
 * the running game, seats, clocks, game options, draw offers, hints and the engine task.
 * Everything is synced to clients through the block entity update packet.
 */
public class ChessBoardBlockEntity extends BlockEntity {
	public enum Phase { SETUP, PLAYING, FINISHED }

	/** STANDARD: the stored pieces in the starting position. CUSTOM: any arrangement, unlimited pieces. */
	public enum BoardMode { STANDARD, CUSTOM }

	public enum Mode {
		VS_BOT("Play Bot"), TWO_PLAYERS("Play a Friend"), HOTSEAT("Same Screen"), BOT_VS_BOT("Bot vs Bot");

		public final String label;

		Mode(String label) {
			this.label = label;
		}
	}

	/** Maximum stored pieces per type (pawn..king): one full army per color. */
	public static final int[] CAP_BY_TYPE = {8, 2, 2, 2, 1, 1};

	private static final Random RANDOM = new Random();

	// ---- persistent state
	private final int[] stock = new int[Piece.COUNT];
	private Phase phase = Phase.SETUP;
	private BoardMode boardMode = BoardMode.STANDARD;
	private final int[] custom = new int[64];
	private int customSide = Piece.WHITE;
	private @Nullable ChessGame game;
	private Mode mode = Mode.VS_BOT;
	private int colorChoice;
	private final @Nullable UUID[] seat = new UUID[2];
	private final String[] seatName = {"", ""};
	private final boolean[] bot = new boolean[2];
	private final int[] elo = {1200, 1200};
	private int clockBase;
	private int clockInc;
	private boolean allowUndo = true;
	private boolean allowHints = true;
	private final long[] clockMs = new long[2];
	private boolean clockRunning;
	private long turnStartedAt = System.currentTimeMillis();
	private int drawOfferBy = -1;
	private long gameId;
	private int hintMove = Move.NONE;
	private int hintPly = -1;
	private boolean thinking;

	// ---- server-only engine state
	private @Nullable Future<ChessBot.Choice> botTask;
	private @Nullable AtomicBoolean botCancel;
	private long botReadyAt;
	private long botTaskGame;
	private int botTaskPly;
	private @Nullable Future<Searcher.Result> hintTask;
	private long hintTaskGame;
	private int hintTaskPly;

	// ---- client-only bookkeeping (sync time, move animation, game end)
	public int syncVersion;
	public long syncMillis;
	public int animMove = Move.NONE;
	public long animStartMillis;
	public int @Nullable [] animPrevBoard;
	public long finishMillis;

	public ChessBoardBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.CHESS_BOARD, pos, state);
		Arrays.fill(custom, Piece.NONE);
	}

	// =================================================================== read access

	public Phase phase() {
		return phase;
	}

	public BoardMode boardMode() {
		return boardMode;
	}

	public Mode mode() {
		return mode;
	}

	public @Nullable ChessGame game() {
		return game;
	}

	public int stock(int piece) {
		return stock[piece];
	}

	public int totalStock() {
		int n = 0;
		for (int s : stock) n += s;
		return n;
	}

	public int customSide() {
		return customSide;
	}

	public @Nullable UUID seat(int color) {
		return seat[color];
	}

	public boolean isBot(int color) {
		return bot[color];
	}

	public int elo(int color) {
		return elo[color];
	}

	public int clockBase() {
		return clockBase;
	}

	public int clockInc() {
		return clockInc;
	}

	public boolean allowUndo() {
		return allowUndo && mode == Mode.VS_BOT;
	}

	public boolean allowHints() {
		return allowHints && mode == Mode.VS_BOT;
	}

	public int drawOfferBy() {
		return drawOfferBy;
	}

	public boolean thinking() {
		return thinking;
	}

	public long gameId() {
		return gameId;
	}

	public String seatLabel(int color) {
		if (bot[color]) return "Bot (" + elo[color] + ")";
		return seat[color] == null ? "Waiting..." : seatName[color];
	}

	public boolean waitingForOpponent() {
		return phase == Phase.PLAYING && mode == Mode.TWO_PLAYERS && (seat[0] == null || seat[1] == null);
	}

	/** Hint for the current position, or {@link Move#NONE}. */
	public int hint() {
		return game != null && hintPly == game.moves().size() ? hintMove : Move.NONE;
	}

	/** The stored pieces on their standard squares (missing pieces leave their squares empty). */
	public int[] standardArrangement() {
		int[] out = new int[64];
		Arrays.fill(out, Piece.NONE);
		Position start = Position.startPosition();
		int[] used = new int[Piece.COUNT];
		// Fill from the middle out so a partial set still looks natural (pawns d/e first, etc.).
		int[] fileOrder = {3, 4, 2, 5, 1, 6, 0, 7};
		for (int rank : new int[]{0, 1, 6, 7}) {
			for (int f : fileOrder) {
				int sq = rank * 8 + f;
				int p = start.board[sq];
				if (p != Piece.NONE && used[p] < stock[p]) {
					out[sq] = p;
					used[p]++;
				}
			}
		}
		return out;
	}

	/** Pieces currently standing on the board (setup arrangement or game position). */
	public int[] displayBoard() {
		if (phase == Phase.SETUP || game == null) {
			return boardMode == BoardMode.STANDARD ? standardArrangement() : custom;
		}
		return game.position().board;
	}

	/** Remaining clock time in ms at the given wall-clock time (server or client view). */
	public long remainingMs(int color, long now) {
		long base = clockMs[color];
		if (clockRunning && phase == Phase.PLAYING && game != null && game.sideToMove() == color) {
			base -= now - (level != null && level.isClientSide() ? syncMillis : turnStartedAt);
		}
		return Math.max(0, base);
	}

	public boolean hasClock() {
		return clockBase > 0;
	}

	// =================================================================== piece storage

	public void insertPieces(ServerPlayer player, ItemStack stack, int piece) {
		int cap = CAP_BY_TYPE[Piece.type(piece)];
		String name = Piece.colorName(Piece.color(piece)) + " " + Piece.typeName(Piece.type(piece));
		int space = cap - stock[piece];
		if (space <= 0) {
			tell(player, "This board already holds every " + name + " (" + cap + "/" + cap + ").", ChatFormatting.YELLOW);
			return;
		}
		int n = Math.min(space, player.getAbilities().instabuild ? space : stack.getCount());
		stock[piece] += n;
		if (!player.getAbilities().instabuild) stack.shrink(n);
		tell(player, "Added " + n + " " + name + (n > 1 ? "s" : "") + " (" + stock[piece] + "/" + cap + ")", ChatFormatting.GREEN);
		playSound(SoundEvents.CHISELED_BOOKSHELF_INSERT, 0.8f, 1.3f);
		sync();
	}

	/** Adds pieces directly (commands and tests); clamps to one full army. */
	public void addStock(int piece, int count) {
		stock[piece] = Math.max(0, Math.min(CAP_BY_TYPE[Piece.type(piece)], stock[piece] + count));
		sync();
	}

	private void withdraw(ServerPlayer player, int piece, int count) {
		if (phase != Phase.SETUP) {
			tell(player, "Pieces can only be taken out before a game starts.", ChatFormatting.RED);
			return;
		}
		if (piece < 0) {
			for (int p = 0; p < Piece.COUNT; p++) {
				give(player, p, stock[p]);
				stock[p] = 0;
			}
		} else {
			int n = Math.min(count, stock[piece]);
			if (n <= 0) return;
			give(player, piece, n);
			stock[piece] -= n;
		}
		playSound(SoundEvents.CHISELED_BOOKSHELF_PICKUP, 0.8f, 1.2f);
		sync();
	}

	private static void give(ServerPlayer player, int piece, int count) {
		if (count <= 0) return;
		ItemStack stack = new ItemStack(ModBlocks.PIECE_ITEMS[piece], count);
		ChessCraft.give(player, stack);
	}

	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		cancelTasks();
		if (level == null) return;
		// Only the physically stored pieces drop; custom arrangements are virtual.
		for (int p = 0; p < Piece.COUNT; p++) {
			if (stock[p] > 0) {
				Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5,
						new ItemStack(ModBlocks.PIECE_ITEMS[p], stock[p]));
			}
			stock[p] = 0;
		}
	}

	@Override
	public void setRemoved() {
		super.setRemoved();
		cancelTasks();
	}

	// =================================================================== actions

	public void handleAction(ServerPlayer player, ChessActionPayload a) {
		switch (a.action()) {
			case ChessActionPayload.MOVE -> playerMove(player, a.a(), a.b(), a.c());
			case ChessActionPayload.SET_BOARD_MODE -> {
				if (phase == Phase.SETUP) {
					boardMode = a.a() == 1 ? BoardMode.CUSTOM : BoardMode.STANDARD;
					sync();
				}
			}
			case ChessActionPayload.CUSTOM_PLACE -> customPlace(a.a(), a.b());
			case ChessActionPayload.CUSTOM_MOVE -> customMove(a.a(), a.b());
			case ChessActionPayload.CUSTOM_CLEAR -> {
				if (phase == Phase.SETUP) {
					Arrays.fill(custom, Piece.NONE);
					sync();
				}
			}
			case ChessActionPayload.CUSTOM_RESET -> {
				if (phase == Phase.SETUP) {
					System.arraycopy(Position.startPosition().board, 0, custom, 0, 64);
					customSide = Piece.WHITE;
					sync();
				}
			}
			case ChessActionPayload.START -> start(player, a.text());
			case ChessActionPayload.JOIN -> join(player, a.a());
			case ChessActionPayload.RESIGN -> resign(player);
			case ChessActionPayload.OFFER_DRAW -> offerDraw(player);
			case ChessActionPayload.ANSWER_DRAW -> answerDraw(player, a.a() != 0);
			case ChessActionPayload.UNDO -> undo(player);
			case ChessActionPayload.UNDO_TO -> undoTo(player, a.a());
			case ChessActionPayload.HINT -> hint(player);
			case ChessActionPayload.NEW_GAME -> newGame(player);
			case ChessActionPayload.REMATCH -> rematch(player);
			case ChessActionPayload.WITHDRAW -> withdraw(player, a.a(), a.b());
			default -> {
			}
		}
	}

	private boolean isSeated(ServerPlayer p) {
		UUID id = p.getUUID();
		return id.equals(seat[0]) || id.equals(seat[1]);
	}

	/** Seated humans manage the game; anyone may when no seated human is online. */
	private boolean canManage(ServerPlayer p) {
		if (isSeated(p)) return true;
		if (!(level instanceof ServerLevel sl)) return false;
		for (int c = 0; c < 2; c++) {
			if (!bot[c] && seat[c] != null && sl.getServer().getPlayerList().getPlayer(seat[c]) != null) return false;
		}
		return true;
	}

	private boolean controls(ServerPlayer p, int color) {
		return !bot[color] && seat[color] != null && seat[color].equals(p.getUUID());
	}

	/** Custom mode: put a (virtual) piece on a square, replacing what is there; NONE clears it. */
	private void customPlace(int sq, int piece) {
		if (phase != Phase.SETUP || sq < 0 || sq > 63) return;
		if (piece < Piece.NONE || piece >= Piece.COUNT) return;
		if (piece != Piece.NONE && Piece.type(piece) == Piece.KING) {
			// Only one king per color: placing a king moves the existing one.
			for (int i = 0; i < 64; i++) {
				if (custom[i] == piece) custom[i] = Piece.NONE;
			}
		}
		custom[sq] = piece;
		if (piece != Piece.NONE) playSound(SoundEvents.CHERRY_WOOD_PLACE, 0.5f, 1.5f);
		sync();
	}

	private void customMove(int from, int to) {
		if (phase != Phase.SETUP || from < 0 || from > 63 || to < 0 || to > 63 || from == to) return;
		int piece = custom[from];
		if (piece == Piece.NONE) return;
		custom[from] = Piece.NONE;
		custom[to] = piece;
		playSound(SoundEvents.CHERRY_WOOD_PLACE, 0.5f, 1.4f);
		sync();
	}

	private static boolean isPawn(int piece) {
		return piece != Piece.NONE && Piece.type(piece) == Piece.PAWN;
	}

	/** Builds a FEN from the setup arrangement. */
	public String setupFen() {
		int[] b = boardMode == BoardMode.STANDARD ? standardArrangement() : custom;
		int side = Piece.WHITE; // White always moves first, also in custom positions
		StringBuilder sb = new StringBuilder();
		for (int rank = 7; rank >= 0; rank--) {
			int empty = 0;
			for (int file = 0; file < 8; file++) {
				int pc = b[rank * 8 + file];
				if (pc == Piece.NONE) {
					empty++;
				} else {
					if (empty > 0) sb.append(empty);
					empty = 0;
					sb.append(Piece.fenChar(pc));
				}
			}
			if (empty > 0) sb.append(empty);
			if (rank > 0) sb.append('/');
		}
		sb.append(side == Piece.WHITE ? " w KQkq - 0 1" : " b KQkq - 0 1");
		return sb.toString();
	}

	/** Pieces still missing for a full standard set, as readable text; empty when complete. */
	public String missingPieces() {
		List<String> parts = new ArrayList<>();
		for (int p = 0; p < Piece.COUNT; p++) {
			int miss = CAP_BY_TYPE[Piece.type(p)] - stock[p];
			if (miss > 0) parts.add(miss + " " + Piece.colorName(Piece.color(p)) + " " + Piece.typeName(Piece.type(p)) + (miss > 1 ? "s" : ""));
		}
		return String.join(", ", parts);
	}

	/** Why the setup cannot be played, or null when it is a legal starting position. */
	public @Nullable String validateSetup() {
		if (boardMode == BoardMode.STANDARD) {
			String miss = missingPieces();
			return miss.isEmpty() ? null : "Missing: " + miss + ". Right-click the board with pieces to add them.";
		}
		int wk = 0, bk = 0;
		for (int p : custom) {
			if (p == Piece.make(Piece.WHITE, Piece.KING)) wk++;
			if (p == Piece.make(Piece.BLACK, Piece.KING)) bk++;
		}
		if (wk != 1 || bk != 1) return "Each side needs exactly one king.";
		for (int f = 0; f < 8; f++) {
			if (isPawn(custom[f]) || isPawn(custom[56 + f])) return "Pawns cannot stand on the first or last rank.";
		}
		Position pos = Position.fromFen(setupFen());
		if (pos.isAttacked(pos.kingSquare(pos.side ^ 1), pos.side)) return "The side not to move is in check.";
		ChessGame g = new ChessGame(pos.toFen());
		if (g.isOver()) return "This position is already finished (" + reasonText(g) + ").";
		return null;
	}

	/** Start options: "opp=BOT|WATCH|FRIEND|LOCAL;color=0|1|2;eloW=;eloB=;base=sec;inc=sec;undo=0|1;hints=0|1". */
	private void start(ServerPlayer player, String options) {
		if (phase != Phase.SETUP) return;
		String error = validateSetup();
		if (error != null) {
			tell(player, error, ChatFormatting.RED);
			return;
		}
		Map<String, String> o = new HashMap<>();
		for (String kv : options.split(";")) {
			int i = kv.indexOf('=');
			if (i > 0) o.put(kv.substring(0, i).trim(), kv.substring(i + 1).trim());
		}
		Mode m = switch (o.getOrDefault("opp", "BOT")) {
			case "FRIEND" -> Mode.TWO_PLAYERS;
			case "LOCAL" -> Mode.HOTSEAT;
			default -> Mode.VS_BOT;
		};
		int color = Math.floorMod(intOr(o.get("color"), 0), 3);
		int base = Math.max(0, Math.min(7200, intOr(o.get("base"), 0)));
		int inc = Math.max(0, Math.min(180, intOr(o.get("inc"), 0)));
		allowUndo = intOr(o.get("undo"), 1) != 0;
		allowHints = intOr(o.get("hints"), 1) != 0;
		beginGame(player, m, color, clampElo(intOr(o.get("eloW"), 1200)), clampElo(intOr(o.get("eloB"), 1200)), base, inc, setupFen());
	}

	private static int intOr(@Nullable String s, int fallback) {
		if (s == null) return fallback;
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static int clampElo(int e) {
		e = Math.max(ChessBot.MIN_ELO, Math.min(ChessBot.MAX_ELO, e));
		return Math.round(e / 50f) * 50;
	}

	private void beginGame(ServerPlayer player, Mode m, int color, int eloW, int eloB, int base, int inc, String fen) {
		cancelTasks();
		mode = m;
		colorChoice = color;
		int human = color == 2 ? RANDOM.nextInt(2) : (color & 1);
		Arrays.fill(seat, null);
		Arrays.fill(bot, false);
		seatName[0] = seatName[1] = "";
		elo[0] = eloW;
		elo[1] = eloB;
		switch (m) {
			case VS_BOT -> {
				takeSeat(player, human);
				bot[human ^ 1] = true;
				elo[human ^ 1] = eloW;
			}
			case TWO_PLAYERS -> takeSeat(player, human);
			case HOTSEAT -> {
				takeSeat(player, Piece.WHITE);
				takeSeat(player, Piece.BLACK);
			}
			case BOT_VS_BOT -> {
				bot[0] = bot[1] = true;
				seat[0] = player.getUUID();
				seatName[0] = player.getName().getString();
			}
		}
		game = new ChessGame(fen);
		phase = Phase.PLAYING;
		gameId = RANDOM.nextLong();
		clockBase = base;
		clockInc = inc;
		clockMs[0] = clockMs[1] = base * 1000L;
		clockRunning = false;
		turnStartedAt = System.currentTimeMillis();
		drawOfferBy = -1;
		hintPly = -1;
		thinking = false;
		playSound(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.6f, 1.2f);
		sync();
	}

	private void takeSeat(ServerPlayer player, int color) {
		seat[color] = player.getUUID();
		seatName[color] = player.getName().getString();
	}

	private void join(ServerPlayer player, int color) {
		if (phase != Phase.PLAYING || mode != Mode.TWO_PLAYERS || game == null) return;
		color &= 1;
		if (seat[color] != null) {
			tell(player, "That seat is taken.", ChatFormatting.RED);
			return;
		}
		if (isSeated(player)) {
			tell(player, "You are already playing in this game.", ChatFormatting.RED);
			return;
		}
		takeSeat(player, color);
		turnStartedAt = System.currentTimeMillis();
		announce(player.getName().getString() + " joined as " + Piece.colorName(color) + ". Good luck!");
		sync();
	}

	private void playerMove(ServerPlayer player, int from, int to, int promo) {
		if (phase != Phase.PLAYING || game == null || waitingForOpponent()) return;
		int side = game.sideToMove();
		if (!controls(player, side)) {
			tell(player, "It is not your turn.", ChatFormatting.RED);
			return;
		}
		if (from < 0 || from > 63 || to < 0 || to > 63) return;
		int move = game.position().findMove(from, to, promo < Piece.KNIGHT || promo > Piece.QUEEN ? Piece.QUEEN : promo);
		if (move == Move.NONE) {
			tell(player, "Illegal move.", ChatFormatting.RED);
			return;
		}
		applyMove(move);
	}

	private void applyMove(int move) {
		if (game == null) return;
		long now = System.currentTimeMillis();
		int mover = game.sideToMove();
		if (hasClock()) {
			if (clockRunning) clockMs[mover] -= now - turnStartedAt;
			clockMs[mover] += clockInc * 1000L;
			clockRunning = true;
		}
		turnStartedAt = now;
		boolean capture = Move.isCapture(move);
		game.play(move);
		if (drawOfferBy == (mover ^ 1)) drawOfferBy = -1;
		thinking = false;

		if (game.isOver()) {
			finish();
		} else if (game.position().inCheck()) {
			playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 0.5f, 1.6f);
		}
		playSound(capture ? SoundEvents.BAMBOO_WOOD_PLACE : SoundEvents.CHERRY_WOOD_PLACE, capture ? 0.9f : 0.7f, capture ? 0.8f : 1.3f);
		sync();
	}

	private void finish() {
		if (game == null) return;
		phase = Phase.FINISHED;
		clockRunning = false;
		thinking = false;
		drawOfferBy = -1;
		cancelTasks();
		announce(resultText());
		boolean decisive = game.result() != ChessGame.Result.DRAW;
		playSound(decisive ? SoundEvents.PLAYER_LEVELUP : SoundEvents.NOTE_BLOCK_CHIME.value(), 0.7f, decisive ? 1.0f : 0.9f);
	}

	public String resultText() {
		if (game == null || !game.isOver()) return "";
		String why = reasonText(game);
		return switch (game.result()) {
			case WHITE_WINS -> why + " - " + seatLabel(Piece.WHITE) + " (White) wins!";
			case BLACK_WINS -> why + " - " + seatLabel(Piece.BLACK) + " (Black) wins!";
			default -> "Draw by " + why.toLowerCase();
		};
	}

	public static String reasonText(ChessGame g) {
		return switch (g.reason()) {
			case CHECKMATE -> "Checkmate";
			case RESIGNATION -> "Resignation";
			case TIMEOUT -> "Time out";
			case STALEMATE -> "Stalemate";
			case REPETITION -> "Threefold repetition";
			case FIFTY_MOVES -> "Fifty-move rule";
			case INSUFFICIENT_MATERIAL -> "Insufficient material";
			case AGREEMENT -> "Agreement";
			case ABANDONED -> "Abandonment";
			default -> "";
		};
	}

	private void resign(ServerPlayer player) {
		if (phase != Phase.PLAYING || game == null) return;
		int color;
		if (mode == Mode.HOTSEAT && isSeated(player)) color = game.sideToMove();
		else if (controls(player, Piece.WHITE)) color = Piece.WHITE;
		else if (controls(player, Piece.BLACK)) color = Piece.BLACK;
		else return;
		game.resign(color);
		finish();
		sync();
	}

	private void offerDraw(ServerPlayer player) {
		if (phase != Phase.PLAYING || game == null || waitingForOpponent()) return;
		int color = controls(player, Piece.WHITE) ? Piece.WHITE : controls(player, Piece.BLACK) ? Piece.BLACK : -1;
		if (color < 0) return;
		if (mode == Mode.HOTSEAT) {
			game.agreeDraw();
			finish();
			sync();
			return;
		}
		if (mode == Mode.VS_BOT) {
			int botColor = color ^ 1;
			int eval = new Evaluator().evaluate(game.position());
			int botView = game.sideToMove() == botColor ? eval : -eval;
			boolean accept = botView < -120 || (Math.abs(botView) < 40 && game.moves().size() >= 60);
			if (accept) {
				game.agreeDraw();
				finish();
			} else {
				tell(player, "The bot declines the draw offer.", ChatFormatting.YELLOW);
			}
			sync();
			return;
		}
		drawOfferBy = color;
		announce(player.getName().getString() + " offers a draw.");
		sync();
	}

	private void answerDraw(ServerPlayer player, boolean accept) {
		if (phase != Phase.PLAYING || game == null || drawOfferBy < 0) return;
		if (!controls(player, drawOfferBy ^ 1)) return;
		if (accept) {
			game.agreeDraw();
			finish();
		} else {
			announce(player.getName().getString() + " declines the draw.");
			drawOfferBy = -1;
		}
		sync();
	}

	/** Take-backs exist only against the bot (and only when enabled for the game). */
	private void undo(ServerPlayer player) {
		if (game == null || phase == Phase.SETUP || !isSeated(player)) return;
		if (!allowUndo()) {
			tell(player, "Take-backs are off for this game.", ChatFormatting.RED);
			return;
		}
		cancelTasks();
		thinking = false;
		int human = bot[0] ? Piece.BLACK : Piece.WHITE;
		int plies = game.sideToMove() == human ? 2 : 1;
		boolean any = false;
		for (int i = 0; i < plies; i++) {
			if (!game.undo()) break;
			any = true;
		}
		if (!any) {
			tell(player, "Nothing to take back.", ChatFormatting.RED);
			return;
		}
		phase = game.isOver() ? Phase.FINISHED : Phase.PLAYING;
		turnStartedAt = System.currentTimeMillis();
		hintPly = -1;
		sync();
	}

	/** Continues the game from an earlier move: everything after it is taken back (bot games only). */
	private void undoTo(ServerPlayer player, int plies) {
		if (game == null || phase == Phase.SETUP || !isSeated(player)) return;
		if (!allowUndo()) {
			tell(player, "Take-backs are off for this game.", ChatFormatting.RED);
			return;
		}
		if (plies < 0 || plies >= game.moves().size()) return;
		cancelTasks();
		thinking = false;
		while (game.moves().size() > plies) {
			if (!game.undo()) {
				tell(player, "This game can no longer be continued.", ChatFormatting.RED);
				break;
			}
		}
		phase = game.isOver() ? Phase.FINISHED : Phase.PLAYING;
		turnStartedAt = System.currentTimeMillis();
		hintPly = -1;
		drawOfferBy = -1;
		sync();
	}

	private void hint(ServerPlayer player) {
		if (phase != Phase.PLAYING || game == null || !controls(player, game.sideToMove())) return;
		if (!allowHints()) {
			tell(player, "Hints are off for this game.", ChatFormatting.RED);
			return;
		}
		if (hintTask != null) return;
		ChessGame copy = ChessGame.replay(game.startFen(), game.uciMoves());
		hintTaskGame = gameId;
		hintTaskPly = game.moves().size();
		hintTask = BotService.submit(engine -> engine.analyse(copy.position(), 1500, null));
	}

	private void newGame(ServerPlayer player) {
		if (phase == Phase.SETUP) return;
		if (phase == Phase.PLAYING && game != null && mode == Mode.TWO_PLAYERS && !waitingForOpponent()
				&& game.moves().size() >= 2 && !canManageAbandoned()) {
			tell(player, "Resign or agree a draw to end a game between two players.", ChatFormatting.RED);
			return;
		}
		if (!canManage(player)) {
			tell(player, "Only the players of this game can end it.", ChatFormatting.RED);
			return;
		}
		cancelTasks();
		phase = Phase.SETUP;
		game = null;
		thinking = false;
		clockRunning = false;
		drawOfferBy = -1;
		sync();
	}

	private boolean canManageAbandoned() {
		if (!(level instanceof ServerLevel sl)) return false;
		for (int c = 0; c < 2; c++) {
			if (seat[c] == null || sl.getServer().getPlayerList().getPlayer(seat[c]) == null) return true;
		}
		return false;
	}

	private void rematch(ServerPlayer player) {
		if (phase != Phase.FINISHED || game == null || !canManage(player)) return;
		String fen = game.startFen();
		Mode m = mode;
		if (m == Mode.TWO_PLAYERS && seat[0] != null && seat[1] != null) {
			UUID w = seat[0], b = seat[1];
			String wn = seatName[0], bn = seatName[1];
			beginGame(player, m, 0, elo[0], elo[1], clockBase, clockInc, fen);
			seat[0] = b;
			seatName[0] = bn;
			seat[1] = w;
			seatName[1] = wn;
			sync();
			return;
		}
		int humanColor = bot[0] ? Piece.BLACK : Piece.WHITE;
		int botElo = bot[0] ? elo[0] : elo[1];
		if (m == Mode.VS_BOT) beginGame(player, m, humanColor ^ 1, botElo, botElo, clockBase, clockInc, fen);
		else if (m == Mode.BOT_VS_BOT) beginGame(player, m, 0, elo[1], elo[0], clockBase, clockInc, fen);
		else beginGame(player, m, 0, elo[0], elo[1], clockBase, clockInc, fen);
	}

	// =================================================================== ticking

	public static void serverTick(Level level, BlockPos pos, BlockState state, ChessBoardBlockEntity be) {
		be.tickServer();
	}

	private void tickServer() {
		if (hintTask != null && hintTask.isDone()) {
			try {
				Searcher.Result r = hintTask.get();
				if (game != null && hintTaskGame == gameId && hintTaskPly == game.moves().size() && r.bestMove != Move.NONE) {
					hintMove = r.bestMove;
					hintPly = hintTaskPly;
					sync();
				}
			} catch (Exception e) {
				ChessCraft.LOGGER.warn("Chess hint task failed at {}", worldPosition, e);
			}
			hintTask = null;
		}

		if (phase != Phase.PLAYING || game == null) return;
		long now = System.currentTimeMillis();
		int side = game.sideToMove();
		if (thinking && botTask == null && !bot[side]) {
			thinking = false;
			sync();
		}

		if (hasClock() && clockRunning && clockMs[side] - (now - turnStartedAt) <= 0) {
			clockMs[side] = 0;
			game.timeout(side);
			finish();
			sync();
			return;
		}

		if (!bot[side] || waitingForOpponent()) return;
		if (botTask == null) {
			startBot(now);
		} else if (botTask.isDone() && now >= botReadyAt) {
			ChessBot.Choice choice = null;
			try {
				choice = botTask.get();
			} catch (Exception e) {
				ChessCraft.LOGGER.warn("Chess bot task failed at {}", worldPosition, e);
			}
			botTask = null;
			botCancel = null;
			if (choice != null && botTaskGame == gameId && botTaskPly == game.moves().size()
					&& choice.move() != Move.NONE && game.position().isLegal(choice.move())) {
				applyMove(choice.move());
			} else {
				ChessCraft.LOGGER.debug("Discarded bot result for {}", worldPosition);
				thinking = false;
			}
		}
	}

	private void startBot(long now) {
		if (game == null) return;
		int side = game.sideToMove();
		ChessGame copy = ChessGame.replay(game.startFen(), game.uciMoves());
		int level = elo[side];
		long budget = -1;
		if (hasClock()) {
			budget = ChessBot.clockBudget(remainingMs(side, now), clockInc * 1000L, copy.moves().size() / 2);
		}
		AtomicBoolean cancel = new AtomicBoolean();
		long seed = RANDOM.nextLong();
		long finalBudget = budget;
		botCancel = cancel;
		botTaskGame = gameId;
		botTaskPly = game.moves().size();
		botTask = BotService.submit(engine -> engine.choose(copy, level, finalBudget, new Random(seed), cancel));
		long delay = (mode == Mode.BOT_VS_BOT ? 700 : 450) + RANDOM.nextInt(600);
		if (budget >= 0) delay = Math.min(delay, budget / 3);
		botReadyAt = now + delay;
		thinking = true;
		sync();
	}

	private void cancelTasks() {
		if (botCancel != null) botCancel.set(true);
		botTask = null;
		botCancel = null;
		hintTask = null;
	}

	// =================================================================== helpers

	private void tell(ServerPlayer player, String msg, ChatFormatting color) {
		ChessCraft.actionBar(player, Component.literal(msg).withStyle(color));
	}

	/** Chat message to every player within 16 blocks of the board. */
	private void announce(String msg) {
		if (!(level instanceof ServerLevel sl)) return;
		Component c = Component.literal("[Chess] ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(msg).withStyle(ChatFormatting.WHITE));
		for (ServerPlayer p : sl.players()) {
			if (p.distanceToSqr(Vec3.atCenterOf(worldPosition)) < 16 * 16) p.sendSystemMessage(c);
		}
	}

	private void playSound(SoundEvent sound, float volume, float pitch) {
		if (level != null) level.playSound(null, worldPosition, sound, SoundSource.BLOCKS, volume, pitch);
	}

	private void sync() {
		setChanged();
		if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
	}

	// =================================================================== persistence & sync

	@Override
	protected void saveAdditional(ValueOutput out) {
		super.saveAdditional(out);
		long now = System.currentTimeMillis();
		out.putIntArray("Stock", stock.clone());
		out.putString("Phase", phase.name());
		out.putString("BoardMode", boardMode.name());
		out.putIntArray("Custom", custom.clone());
		out.putInt("CustomSide", customSide);
		out.putString("Mode", mode.name());
		out.putInt("ColorChoice", colorChoice);
		for (int c = 0; c < 2; c++) {
			out.putString("Seat" + c, seat[c] == null ? "" : seat[c].toString());
			out.putString("Name" + c, seatName[c]);
			out.putBoolean("Bot" + c, bot[c]);
			out.putInt("Elo" + c, elo[c]);
		}
		out.putInt("ClockBase", clockBase);
		out.putInt("ClockInc", clockInc);
		out.putBoolean("AllowUndo", allowUndo);
		out.putBoolean("AllowHints", allowHints);
		out.putLong("WhiteMs", remainingMs(Piece.WHITE, now));
		out.putLong("BlackMs", remainingMs(Piece.BLACK, now));
		out.putBoolean("ClockRunning", clockRunning);
		out.putInt("DrawOffer", drawOfferBy);
		out.putLong("GameId", gameId);
		out.putInt("Hint", hint());
		out.putBoolean("Thinking", thinking);
		if (game != null) {
			out.putString("StartFen", game.startFen());
			out.putString("Moves", String.join(" ", game.uciMoves()));
			out.putString("Result", game.result().name());
			out.putString("Reason", game.reason().name());
		}
	}

	@Override
	protected void loadAdditional(ValueInput in) {
		super.loadAdditional(in);
		int[] oldBoard = displayBoard().clone();
		long oldGameId = gameId;
		int oldMoves = game == null ? -1 : game.moves().size();
		Phase oldPhase = phase;

		int[] st = in.getIntArray("Stock").orElse(new int[0]);
		for (int p = 0; p < Piece.COUNT; p++) {
			stock[p] = p < st.length ? Math.max(0, Math.min(CAP_BY_TYPE[Piece.type(p)], st[p])) : 0;
		}
		phase = parseEnum(Phase.class, in.getStringOr("Phase", "SETUP"), Phase.SETUP);
		boardMode = parseEnum(BoardMode.class, in.getStringOr("BoardMode", "STANDARD"), BoardMode.STANDARD);
		int[] cu = in.getIntArray("Custom").orElse(new int[0]);
		for (int sq = 0; sq < 64; sq++) {
			int v = sq < cu.length ? cu[sq] : Piece.NONE;
			custom[sq] = v >= 0 && v < Piece.COUNT ? v : Piece.NONE;
		}
		customSide = in.getIntOr("CustomSide", 0) & 1;
		mode = parseEnum(Mode.class, in.getStringOr("Mode", "VS_BOT"), Mode.VS_BOT);
		colorChoice = in.getIntOr("ColorChoice", 0);
		for (int c = 0; c < 2; c++) {
			String s = in.getStringOr("Seat" + c, "");
			try {
				seat[c] = s.isEmpty() ? null : UUID.fromString(s);
			} catch (IllegalArgumentException e) {
				seat[c] = null;
			}
			seatName[c] = in.getStringOr("Name" + c, "");
			bot[c] = in.getBooleanOr("Bot" + c, false);
			elo[c] = in.getIntOr("Elo" + c, 1200);
		}
		clockBase = in.getIntOr("ClockBase", 0);
		clockInc = in.getIntOr("ClockInc", 0);
		allowUndo = in.getBooleanOr("AllowUndo", true);
		allowHints = in.getBooleanOr("AllowHints", true);
		clockMs[0] = in.getLongOr("WhiteMs", 0);
		clockMs[1] = in.getLongOr("BlackMs", 0);
		clockRunning = in.getBooleanOr("ClockRunning", false);
		turnStartedAt = System.currentTimeMillis();
		drawOfferBy = in.getIntOr("DrawOffer", -1);
		gameId = in.getLongOr("GameId", 0);
		thinking = in.getBooleanOr("Thinking", false);

		String fen = in.getStringOr("StartFen", "");
		if (fen.isEmpty()) {
			game = null;
		} else {
			String movesStr = in.getStringOr("Moves", "").trim();
			List<String> moves = movesStr.isEmpty() ? List.of() : List.of(movesStr.split(" "));
			try {
				ChessGame g = ChessGame.replay(fen, new ArrayList<>(moves));
				ChessGame.Result r = parseEnum(ChessGame.Result.class, in.getStringOr("Result", "ONGOING"), ChessGame.Result.ONGOING);
				ChessGame.Reason why = parseEnum(ChessGame.Reason.class, in.getStringOr("Reason", "NONE"), ChessGame.Reason.NONE);
				if (r != g.result()) g.forceResult(r, why);
				game = g;
			} catch (IllegalArgumentException e) {
				game = null;
				phase = Phase.SETUP;
			}
		}
		int h = in.getIntOr("Hint", Move.NONE);
		hintMove = h;
		hintPly = h != Move.NONE && game != null ? game.moves().size() : -1;
		if (phase != Phase.SETUP && game == null) phase = Phase.SETUP;

		// Client bookkeeping: animate a single new move of the same game; note when a game ends.
		syncVersion++;
		syncMillis = System.currentTimeMillis();
		int newMoves = game == null ? -1 : game.moves().size();
		if (game != null && oldGameId == gameId && oldPhase != Phase.SETUP && newMoves == oldMoves + 1) {
			animMove = game.lastMove();
			animStartMillis = syncMillis;
			animPrevBoard = oldBoard;
		} else if (oldGameId != gameId || newMoves < oldMoves || phase == Phase.SETUP) {
			animMove = Move.NONE;
			animPrevBoard = null;
		}
		if (phase == Phase.FINISHED && oldPhase != Phase.FINISHED) {
			finishMillis = oldGameId == gameId && oldMoves >= 0 ? syncMillis : syncMillis - 10_000;
		}
		if (level != null && !level.isClientSide()) thinking = false;
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> type, String name, E fallback) {
		try {
			return Enum.valueOf(type, name);
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveCustomOnly(registries);
	}

	public Direction facing() {
		return getBlockState().getValue(ChessBoardBlock.FACING);
	}
}
