package com.yossi.chesscraft.client;

import static com.yossi.chesscraft.client.ui.ChessUi.*;

import com.yossi.chesscraft.ChessCraft;
import com.yossi.chesscraft.block.entity.ChessBoardBlockEntity;
import com.yossi.chesscraft.block.entity.ChessBoardBlockEntity.BoardMode;
import com.yossi.chesscraft.block.entity.ChessBoardBlockEntity.Mode;
import com.yossi.chesscraft.block.entity.ChessBoardBlockEntity.Phase;
import com.yossi.chesscraft.chess.ChessGame;
import com.yossi.chesscraft.chess.Move;
import com.yossi.chesscraft.chess.Piece;
import com.yossi.chesscraft.chess.Position;
import com.yossi.chesscraft.chess.engine.ChessBot;
import com.yossi.chesscraft.chess.engine.GameReview;
import com.yossi.chesscraft.client.ui.ChessUi;
import com.yossi.chesscraft.network.ChessActionPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The chess screen, styled after chess websites: a pieces column, the board and a side panel
 * with pages (new game, bot, friend, time, settings), the live game panel and a result window.
 */
public class ChessScreen extends Screen {
	private static final Identifier SQUARE_LIGHT = ChessCraft.id("textures/gui/square_light.png");
	private static final Identifier SQUARE_DARK = ChessCraft.id("textures/gui/square_dark.png");
	private static final Identifier FRAME = ChessCraft.id("textures/gui/frame_wood.png");
	private static final Identifier MOVE_DOT = ChessCraft.id("textures/gui/move_dot.png");
	private static final Identifier CAPTURE_RING = ChessCraft.id("textures/gui/capture_ring.png");
	private static final Identifier[] SPRITES = new Identifier[Piece.COUNT];

	static {
		for (int p = 0; p < Piece.COUNT; p++) {
			SPRITES[p] = ChessCraft.id("textures/gui/pieces/" + Piece.id(p) + ".png");
		}
	}

	private static final long GUI_ANIM_MS = 220;
	private static final int[][] MINUTE_TIMES = {{0, 0}, {60, 0}, {180, 0}, {300, 0}, {600, 0}, {1800, 0}, {3600, 0}};
	private static final int[][] INCREMENT_TIMES = {{60, 1}, {120, 1}, {180, 2}, {300, 3}, {600, 5}, {900, 10}, {1800, 20}, {3600, 30}};

	private enum Page { HOME, MODE, BOT, FRIEND, TIME, SETTINGS }

	private final BlockPos pos;
	private final ClientChessSettings settings = ClientChessSettings.get();
	private @Nullable ChessBoardBlockEntity be;
	private int seenVersion = -1;
	private String layoutKey = "";
	private @Nullable ChessGame view;
	private Page page = Page.HOME;

	// Layout
	private int sq, frame, boardX, boardY, boardPx, colX, colW, panelX, panelY, panelW, panelH;
	private boolean flipped, manualFlip;

	// Interaction
	private final List<ChessUi.Widget> widgets = new ArrayList<>();
	private final List<ChessUi.Widget> modalWidgets = new ArrayList<>();
	private ChessUi.@Nullable Widget pressed;
	private int selected = -1;
	private int[] targets = new int[0];
	private boolean premoveSelect;
	private int premoveFrom = -1, premoveTo = -1;
	private boolean dragging;
	private int dragFrom = -1;
	private int dragPiece = Piece.NONE;
	private int promoFrom = -1, promoTo = -1;
	private int pendingMove = Move.NONE;
	private int suppressAnim = Move.NONE;
	private int @Nullable [] setupPreview;
	private int moveScroll = Integer.MAX_VALUE;
	private int listTop, listBottom;
	private boolean modalDismissed;
	// Board annotations (chess.com style): orange arrows and red squares.
	private final List<int[]> arrows = new ArrayList<>();
	private final java.util.Set<Integer> marked = new java.util.HashSet<>();
	private int annoFrom = -1;
	private int annoButton = -1;
	private int leftPressSq = -1;
	private int seenMoves = -1;
	private int mouseX, mouseY;
	// Browsing earlier positions during a game (-1 = live).
	private int browsePly = -1;
	private final List<int[]> moveHits = new ArrayList<>();
	private @Nullable List<Position> plyPositions;
	private ChessUi.@Nullable Widget continueButton;
	// Game review
	private boolean reviewing;
	private boolean reviewMoves;
	private int reviewPly;
	private long modalStart = -1;

	public ChessScreen(BlockPos pos) {
		super(Component.literal("Chess"));
		this.pos = pos;
	}

	// =================================================================== state

	private @Nullable ChessBoardBlockEntity board() {
		if (minecraft == null || minecraft.level == null) return null;
		return minecraft.level.getBlockEntity(pos) instanceof ChessBoardBlockEntity b ? b : null;
	}

	private @Nullable UUID me() {
		return minecraft != null && minecraft.player != null ? minecraft.player.getUUID() : null;
	}

	private boolean seatedAs(int color) {
		UUID id = me();
		return be != null && id != null && id.equals(be.seat(color)) && !be.isBot(color);
	}

	private boolean isSeated() {
		UUID id = me();
		return be != null && id != null && (id.equals(be.seat(0)) || id.equals(be.seat(1)));
	}

	/** My single color in a normal game (-1 when spectating or on a shared screen). */
	private int singleColor() {
		boolean w = seatedAs(Piece.WHITE), b = seatedAs(Piece.BLACK);
		if (w && !b) return Piece.WHITE;
		if (b && !w) return Piece.BLACK;
		return -1;
	}

	private boolean canMoveNow() {
		if (be == null || view == null || be.phase() != Phase.PLAYING || be.waitingForOpponent() || view.isOver()) return false;
		return seatedAs(view.sideToMove()) && pendingMove == Move.NONE && !browsing();
	}

	private boolean browsing() {
		return browsePly >= 0 && view != null && browsePly < view.moves().size() && be != null && be.phase() != Phase.SETUP;
	}

	/** Position after the first k moves of the current game (cached per sync). */
	private Position positionAt(int k) {
		if (plyPositions == null && view != null) {
			List<Position> list = new ArrayList<>();
			Position p = Position.fromFen(view.startFen());
			list.add(p.copy());
			for (int m : view.moves()) {
				p.makeMove(m);
				list.add(p.copy());
			}
			plyPositions = list;
		}
		return plyPositions.get(Math.max(0, Math.min(k, plyPositions.size() - 1)));
	}

	/** Moves the history view; the end of the game returns to the live position. */
	private void browse(int k) {
		if (view == null) return;
		int n = view.moves().size();
		browsePly = k >= n ? -1 : Math.max(0, k);
		clearSelection();
		int cur = browsePly < 0 ? n : browsePly;
		moveScroll = Math.max(0, (cur - 1) / 2 - 2);
	}

	private boolean canContinueFromHere() {
		return browsing() && be != null && be.allowUndo() && isSeated();
	}

	private boolean canPremove() {
		if (!settings.premoves || be == null || view == null || be.phase() != Phase.PLAYING || be.waitingForOpponent()) return false;
		int mine = singleColor();
		return mine >= 0 && view.sideToMove() != mine && !view.isOver();
	}

	private void refreshState() {
		be = board();
		if (be == null) {
			onClose();
			return;
		}
		if (be.syncVersion != seenVersion) {
			seenVersion = be.syncVersion;
			ChessGame g = be.game();
			view = g == null ? null : ChessGame.replay(g.startFen(), g.uciMoves());
			if (view != null && g != null && view.result() != g.result()) view.forceResult(g.result(), g.reason());
			if (pendingMove != Move.NONE && view != null && view.lastMove() == pendingMove) suppressAnim = pendingMove;
			pendingMove = Move.NONE;
			setupPreview = null;
			if (!premoveSelect) {
				selected = -1;
				targets = new int[0];
			}
			promoFrom = promoTo = -1;
			moveScroll = Integer.MAX_VALUE;
			if (!manualFlip) flipped = singleColor() == Piece.BLACK;
			if (view == null || !view.isOver()) {
				modalDismissed = false;
				modalStart = -1;
			} else if (modalStart < 0) {
				modalStart = System.currentTimeMillis();
			}
			int moveCount = view == null ? -1 : view.moves().size();
			plyPositions = null;
			if (browsePly >= moveCount) browsePly = -1;
			if (moveCount != seenMoves) {
				seenMoves = moveCount;
				arrows.clear();
				marked.clear();
			}
			playPremove();
			String key = layoutKey();
			if (!key.equals(layoutKey)) {
				layoutKey = key;
				rebuildWidgets();
			}
		}
	}

	/** Sends a queued premove once it becomes our turn (dropped silently if it is no longer legal). */
	private void playPremove() {
		if (premoveFrom < 0 || view == null || !canMoveNow()) return;
		int m = view.position().findMove(premoveFrom, premoveTo, Piece.QUEEN);
		premoveFrom = premoveTo = -1;
		if (m != Move.NONE) sendMove(m);
	}

	private String layoutKey() {
		if (be == null) return "";
		return be.phase() + "|" + be.mode() + "|" + be.boardMode() + "|" + be.waitingForOpponent() + "|" + isSeated()
				+ "|" + be.drawOfferBy() + "|" + singleColor() + "|" + page + "|" + width + "x" + height
				+ "|" + (view != null && view.isOver()) + "|" + modalDismissed + "|" + reviewing + "|" + reviewMoves;
	}

	private void send(int action, int a, int b, int c) {
		ClientPlayNetworking.send(new ChessActionPayload(pos, action, a, b, c));
	}

	private void sendText(int action, String text) {
		ClientPlayNetworking.send(new ChessActionPayload(pos, action, 0, 0, 0, text));
	}

	private void setPage(Page p) {
		page = p;
		settings.save();
		rebuildWidgets();
	}

	// =================================================================== layout & widgets

	@Override
	protected void init() {
		be = board();
		if (be == null) return;
		if (view == null && be.game() != null) {
			ChessGame g = be.game();
			view = ChessGame.replay(g.startFen(), g.uciMoves());
			if (view.result() != g.result()) view.forceResult(g.result(), g.reason());
			if (view.isOver() && modalStart < 0) modalStart = System.currentTimeMillis() - 1000;
		}
		seenVersion = be.syncVersion;
		if (be.phase() != Phase.FINISHED) reviewing = false;
		layoutKey = layoutKey();
		if (!manualFlip) flipped = singleColor() == Piece.BLACK;

		boolean setup = be.phase() == Phase.SETUP;
		panelW = Math.max(176, Math.min(220, width * 30 / 100));
		colW = setup ? 60 : reviewing ? 14 : 0;
		int colGap = colW > 0 ? 6 : 0;
		int gaps = 10 + colGap + 16;
		int byHeight = (int) ((height - 16) / 8.7);
		int byWidth = (int) ((width - colW - panelW - gaps) / 8.7);
		sq = Math.max(12, Math.min(46, Math.min(byHeight, byWidth)));
		frame = Math.max(6, sq / 3);
		boardPx = sq * 8 + frame * 2;
		int total = colW + colGap + boardPx + 10 + panelW;
		int x0 = Math.max(4, (width - total) / 2);
		colX = x0;
		boardX = x0 + colW + colGap;
		boardY = Math.max(8, (height - boardPx) / 2);
		panelX = boardX + boardPx + 10;
		panelY = 8;
		panelH = height - 16;

		widgets.clear();
		modalWidgets.clear();
		if (setup) {
			buildColumn();
			switch (page) {
				case MODE -> buildModePage();
				case BOT -> buildBotPage();
				case FRIEND -> buildFriendPage();
				case TIME -> buildTimePage();
				case SETTINGS -> buildSettingsPage();
				default -> buildHomePage();
			}
			buildStartButton();
		} else if (be.waitingForOpponent()) {
			buildWaitingPage();
		} else if (reviewing) {
			buildReviewPage();
		} else {
			buildGamePage();
			if (be.phase() == Phase.FINISHED) buildModal();
		}
	}

	private int cx() {
		return panelX + 8;
	}

	private int cw() {
		return panelW - 16;
	}

	private <T extends ChessUi.Widget> T add(T w) {
		widgets.add(w);
		return w;
	}

	private int header(String title, boolean back) {
		int y = panelY + 8;
		if (back) {
			add(new IconButton(cx(), y, 18, "back", Style.SECONDARY, () -> setPage(Page.HOME))).tooltip("Back");
		}
		return y + 26;
	}

	private void buildColumn() {
		if (be == null || be.boardMode() != BoardMode.CUSTOM) return;
		int s = 18;
		int y = panelY + panelH - s - 8;
		int x = colX + (colW - 2 * s - 6) / 2;
		add(new IconButton(x, y, s, "reset", Style.SECONDARY, () -> send(ChessActionPayload.CUSTOM_RESET, 0, 0, 0)))
				.tooltip("Reset to the starting position");
		add(new IconButton(x + s + 6, y, s, "trash", Style.SECONDARY, () -> send(ChessActionPayload.CUSTOM_CLEAR, 0, 0, 0)))
				.tooltip("Clear the board");
	}

	private void buildHomePage() {
		int y = panelY + 8 + 22;
		int startTop = panelY + panelH - 8 - 28 - 24;
		int cardH = Math.max(20, Math.min(30, (startTop - y - 4 * 5) / 5));
		add(new Card(cx(), y, cw(), cardH, be != null && be.boardMode() == BoardMode.CUSTOM ? "custom" : "standard", 0, "Mode",
				() -> be != null && be.boardMode() == BoardMode.CUSTOM ? "Custom" : "Standard",
				() -> false, () -> setPage(Page.MODE)))
				.tooltip("Choose between the standard setup and a custom position.");
		y += cardH + 5;
		add(new Card(cx(), y, cw(), cardH, "bot", 0, "Play Bot", () -> "Level " + settings.eloWhite,
				() -> "BOT".equals(settings.opponent),
				() -> {
					settings.opponent = "BOT";
					setPage(Page.BOT);
				})).tooltip("Play against the computer.");
		y += cardH + 5;
		add(new Card(cx(), y, cw(), cardH, "friend", 0, "Play a Friend",
				() -> settings.sameScreen ? "Same screen" : "Online",
				() -> "FRIEND".equals(settings.opponent),
				() -> {
					settings.opponent = "FRIEND";
					setPage(Page.FRIEND);
				})).tooltip("Play against another player.");
		y += cardH + 5;
		add(new Card(cx(), y, cw(), cardH, timeIcon(settings.clockBase), 0, "Time", settings::timeLabel,
				() -> false, () -> setPage(Page.TIME))).tooltip("Choose the time control.");
		y += cardH + 5;
		add(new Card(cx(), y, cw(), cardH, "gear", 0, "Settings",
				() -> settings.premoves ? "Premoves on" : "Premoves off", () -> false, () -> setPage(Page.SETTINGS)))
				.tooltip("Premoves, legal-move dots and more.");
	}

	private void buildModePage() {
		int y = header("Mode", true);
		int h = 40;
		add(new Card(cx(), y, cw(), h, "standard", 0, "Standard", () -> "The pieces stored in the board",
				() -> be != null && be.boardMode() == BoardMode.STANDARD,
				() -> {
					send(ChessActionPayload.SET_BOARD_MODE, 0, 0, 0);
					setPage(Page.HOME);
				})).tooltip("Play from the normal starting position with the pieces you put in the board.");
		y += h + 6;
		add(new Card(cx(), y, cw(), h, "custom", 0, "Custom", () -> "Any position, unlimited pieces",
				() -> be != null && be.boardMode() == BoardMode.CUSTOM,
				() -> {
					send(ChessActionPayload.SET_BOARD_MODE, 1, 0, 0);
					setPage(Page.HOME);
				})).tooltip("Drag pieces from the left column anywhere on the board. White always moves first.");
	}

	private void buildBotPage() {
		int y = header("Play Bot", true);
		add(new Slider(cx(), y, cw(), 28, "Bot level", ChessBot.MIN_ELO, ChessBot.MAX_ELO, 50,
				() -> settings.eloWhite, v -> settings.eloWhite = v, ChessScreen::levelName))
				.tooltip("Bot strength from 100 to 3200 Elo (approximate).");
		y += 32;
		add(new ColorChoice(cx(), y, cw(), 24));
		y += 28;
		add(new Toggle(cx(), y, cw(), 18, "Takebacks", () -> settings.botTakebacks, v -> settings.botTakebacks = v))
				.tooltip("Allow taking moves back against the bot.");
		y += 22;
		add(new Toggle(cx(), y, cw(), 18, "Hints", () -> settings.botHints, v -> settings.botHints = v))
				.tooltip("Allow asking the engine for the best move.");
	}

	private void buildFriendPage() {
		int y = header("Play a Friend", true);
		add(new Segmented(cx(), y, cw(), 18, List.of("Online", "Same screen"),
				() -> settings.sameScreen ? 1 : 0, i -> {
					settings.sameScreen = i == 1;
					rebuildWidgets();
				}).tooltips(List.of("Another player joins this board", "Both sides play on this screen")));
		y += 24 + 34;
		if (!settings.sameScreen) add(new ColorChoice(cx(), y, cw(), 24));
	}

	private void buildTimePage() {
		int y = header("Time", true);
		add(new Segmented(cx(), y, cw(), 18, List.of("Minutes", "Increment"),
				() -> settings.timeTab, i -> {
					settings.timeTab = i;
					rebuildWidgets();
				}).tooltips(List.of("A fixed amount of time per player", "Extra seconds are added after every move")));
		y += 26;
		int[][] options = settings.timeTab == 0 ? MINUTE_TIMES : INCREMENT_TIMES;
		int cols = 3, gap = 4;
		int bw = (cw() - gap * (cols - 1)) / cols, bh = 20;
		for (int i = 0; i < options.length; i++) {
			int[] t = options[i];
			int bx = cx() + (i % cols) * (bw + gap), by = y + (i / cols) * (bh + gap + 2);
			String label = ClientChessSettings.timeLabel(t[0], t[1]);
			Button b = add(new Button(bx, by, bw, bh, label, t[0] == 0 ? null : timeIcon(t[0]), Style.SECONDARY, () -> {
				settings.clockBase = t[0];
				settings.clockInc = t[1];
				settings.save();
			}));
			b.selected = () -> settings.clockBase == t[0] && settings.clockInc == t[1];
			b.bold = false;
			b.tooltip(timeTooltip(t[0], t[1]));
		}
	}

	private static String timeTooltip(int base, int inc) {
		if (base == 0) return "No clock";
		String m = base >= 3600 ? (base / 3600) + " hour" : (base / 60) + (base == 60 ? " minute" : " minutes");
		return m + " per player" + (inc > 0 ? " + " + inc + (inc == 1 ? " second" : " seconds") + " per move" : "");
	}

	private void buildSettingsPage() {
		int y = header("Settings", true);
		add(new Toggle(cx(), y, cw(), 18, "Premoves", () -> settings.premoves, v -> settings.premoves = v))
				.tooltip("Queue your next move while the opponent is thinking.");
		y += 22;
		add(new Toggle(cx(), y, cw(), 18, "Show legal moves", () -> settings.showLegalMoves, v -> settings.showLegalMoves = v))
				.tooltip("Show dots where the selected piece can move.");
		y += 22;
		add(new Toggle(cx(), y, cw(), 18, "Highlight last move", () -> settings.highlightLastMove, v -> settings.highlightLastMove = v))
				.tooltip("Tint the squares of the last move.");
		y += 28;
		add(new Button(cx(), y, cw(), 20, "Take all pieces out", "out", Style.SECONDARY,
				() -> send(ChessActionPayload.WITHDRAW, -1, 0, 0)))
				.tooltip("Moves every piece stored in the board into your inventory.");
	}

	private void buildStartButton() {
		Button start = add(new Button(cx(), panelY + panelH - 8 - 28, cw(), 28, "Start Game", "play", Style.PRIMARY, () -> {
			settings.save();
			sendText(ChessActionPayload.START, settings.startOptions());
		}));
		start.textScale = 1.25f;
		start.enabledWhen(() -> be != null && be.validateSetup() == null);
		start.tooltip("Start the game");
	}

	private void buildWaitingPage() {
		header("Play a Friend", false);
		if (be == null) return;
		int y = panelY + panelH - 8 - 28;
		if (isSeated()) {
			Button wait = add(new Button(cx(), y, cw(), 28, "Waiting for opponent", "clock", Style.AMBER, () -> {
			}));
			wait.textScale = 1.1f;
			wait.tooltip("Another player needs to open this board and press Join");
			add(new Button(cx(), y - 26, cw(), 20, "Cancel", null, Style.GHOST, () -> send(ChessActionPayload.NEW_GAME, 0, 0, 0)))
					.tooltip("Cancel the game");
		} else {
			int open = be.seat(Piece.WHITE) == null ? Piece.WHITE : Piece.BLACK;
			Button join = add(new Button(cx(), y, cw(), 28, "Join as " + (open == Piece.WHITE ? "White" : "Black"), "play",
					Style.PRIMARY, () -> send(ChessActionPayload.JOIN, open, 0, 0)));
			join.textScale = 1.2f;
			join.tooltip("Take the free seat and play");
		}
	}

	private int actionBarHeight() {
		return be != null && be.phase() == Phase.FINISHED ? 50 : 24;
	}

	private void buildGamePage() {
		if (be == null) return;
		int barY = panelY + panelH - 8 - actionBarHeight();
		Mode mode = be.mode();
		boolean seated = isSeated();
		List<ChessUi.Widget> row = new ArrayList<>();
		int s = 22;
		// History navigation under the move list.
		int navY = barY - 6 - 30 - 6 - 18;
		String[] labels = {"«", "‹", "›", "»"};
		String[] tips = {"First move", "Previous move (Left arrow)", "Next move (Right arrow)", "Back to the game"};
		int ngap = 4, nbw = (cw() - 3 * ngap) / 4;
		for (int i = 0; i < 4; i++) {
			final int which = i;
			Button nb = add(new Button(cx() + i * (nbw + ngap), navY, nbw, 18, labels[i], null, Style.SECONDARY, () -> {
				int cur = browsing() ? browsePly : view != null ? view.moves().size() : 0;
				switch (which) {
					case 0 -> browse(0);
					case 1 -> browse(cur - 1);
					case 2 -> browse(cur + 1);
					default -> browse(Integer.MAX_VALUE);
				}
			}));
			nb.textScale = 1.3f;
			nb.tooltip(tips[i]);
			nb.enabledWhen(() -> view != null && !view.moves().isEmpty());
		}
		continueButton = add(new Button(cx(), navY - 4 - 18, cw(), 18, "Continue from here", "play", Style.SECONDARY, () -> {
			if (browsing()) {
				send(ChessActionPayload.UNDO_TO, browsePly, 0, 0);
				browsePly = -1;
			}
		}).tooltip("Take back every move after this position and keep playing from here"));
		continueButton.visible = canContinueFromHere();
		if (be.phase() == Phase.FINISHED) {
			int half = (cw() - 4) / 2;
			int by = barY + 26;
			add(new Button(cx(), by, half, 24, "Rematch", "rematch", Style.SECONDARY, () -> send(ChessActionPayload.REMATCH, 0, 0, 0)))
					.enabledWhen(() -> seated).tooltip("Play again with colors swapped");
			add(new Button(cx() + half + 4, by, cw() - half - 4, 24, "New Game", null, Style.SECONDARY,
					() -> send(ChessActionPayload.NEW_GAME, 0, 0, 0))).tooltip("Back to the game setup");
			row.add(new IconButton(0, barY, s, "review", Style.SECONDARY, this::openReview).tooltip("Game review"));
			row.add(new IconButton(0, barY, s, "copy", Style.SECONDARY, this::copyPgn).tooltip("Copy PGN"));
			if (be.allowUndo() && seated) {
				row.add(new IconButton(0, barY, s, "undo", Style.SECONDARY, () -> send(ChessActionPayload.UNDO, 0, 0, 0)).tooltip("Take back"));
			}
			row.add(new IconButton(0, barY, s, "flip", Style.SECONDARY, this::flip).tooltip("Flip board (F)"));
		} else if (!seated) {
			row.add(new IconButton(0, barY, s, "flip", Style.SECONDARY, this::flip).tooltip("Flip board (F)"));
		} else if (be.drawOfferBy() >= 0 && !seatedAs(be.drawOfferBy()) && mode == Mode.TWO_PLAYERS) {
			int half = (cw() - 4) / 2;
			add(new Button(cx(), barY, half, s, "Accept draw", "check", Style.SECONDARY, () -> send(ChessActionPayload.ANSWER_DRAW, 1, 0, 0)))
					.tooltip("End the game as a draw");
			add(new Button(cx() + half + 4, barY, cw() - half - 4, s, "Decline", null, Style.SECONDARY,
					() -> send(ChessActionPayload.ANSWER_DRAW, 0, 0, 0))).tooltip("Keep playing");
		} else {
			if (be.allowUndo()) {
				row.add(new IconButton(0, barY, s, "undo", Style.SECONDARY, () -> send(ChessActionPayload.UNDO, 0, 0, 0))
						.enabledWhen(() -> view != null && !view.moves().isEmpty()).tooltip("Take back"));
			}
			if (be.allowHints()) {
				row.add(new IconButton(0, barY, s, "hint", Style.SECONDARY, () -> send(ChessActionPayload.HINT, 0, 0, 0))
						.enabledWhen(() -> canMoveNow() && be != null && be.hint() == Move.NONE).tooltip("Hint"));
			}
			row.add(new IconButton(0, barY, s, "draw", Style.SECONDARY, () -> send(ChessActionPayload.OFFER_DRAW, 0, 0, 0))
					.enabledWhen(() -> be != null && be.drawOfferBy() < 0).tooltip("Offer a draw"));
			row.add(new IconButton(0, barY, s, "flag", Style.SECONDARY, () -> send(ChessActionPayload.RESIGN, 0, 0, 0)).tooltip("Resign"));
			row.add(new IconButton(0, barY, s, "flip", Style.SECONDARY, this::flip).tooltip("Flip board (F)"));
		}
		if (!row.isEmpty()) {
			int total = 0;
			for (ChessUi.Widget w : row) total += w.w;
			int gap = Math.min(8, (cw() - total) / Math.max(1, row.size() - 1));
			total += gap * (row.size() - 1);
			int x = cx() + (cw() - total) / 2;
			for (ChessUi.Widget w : row) {
				w.x = x;
				x += w.w + gap;
				add(w);
			}
		}
	}

	private void buildModal() {
		if (modalDismissed) return;
		int[] r = modalRect();
		int x = r[0] + 12, w = r[2] - 24, y = r[1] + r[3] - 12 - 22;
		int half = (w - 4) / 2;
		modalWidgets.add(new Button(x, y - 26, w, 24, "Rematch", "rematch", Style.PRIMARY,
				() -> send(ChessActionPayload.REMATCH, 0, 0, 0)).enabledWhen(this::isSeated).tooltip("Play again with colors swapped"));
		modalWidgets.add(new Button(x, y, half, 22, "Game Review", "review", Style.SECONDARY, this::openReview)
				.tooltip("See the evaluation, best moves and mistakes"));
		modalWidgets.add(new Button(x + half + 4, y, w - half - 4, 22, "New Game", null, Style.SECONDARY,
				() -> send(ChessActionPayload.NEW_GAME, 0, 0, 0)).tooltip("Back to the game setup"));
		modalWidgets.add(new IconButton(r[0] + r[2] - 22, r[1] + 6, 16, "close", Style.GHOST, () -> {
			modalDismissed = true;
			rebuildWidgets();
		}).tooltip("Close and view the board"));
	}

	private int[] modalRect() {
		int w = Math.min(boardPx - 16, 220), h = Math.min(boardPx - 16, 176);
		return new int[]{boardX + (boardPx - w) / 2, boardY + (boardPx - h) / 2, w, h};
	}

	private static String levelName(int elo) {
		if (elo < 600) return "Beginner";
		if (elo < 1000) return "Novice";
		if (elo < 1400) return "Intermediate";
		if (elo < 1800) return "Club";
		if (elo < 2200) return "Expert";
		if (elo < 2600) return "Master";
		return "Grandmaster";
	}

	private static String timeIcon(int base) {
		if (base <= 0) return "clock";
		if (base < 180) return "bullet";
		if (base < 600) return "blitz";
		if (base < 3600) return "rapid";
		return "classical";
	}

	private void flip() {
		manualFlip = true;
		flipped = !flipped;
	}

	private void copyPgn() {
		if (be == null || view == null || minecraft == null) return;
		minecraft.keyboardHandler.setClipboard(view.toPgn(be.seatLabel(Piece.WHITE), be.seatLabel(Piece.BLACK)));
		if (minecraft.player != null) minecraft.player.displayClientMessage(Component.literal("PGN copied to clipboard."), true);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void tick() {
		refreshState();
	}

	@Override
	public void removed() {
		settings.save();
		super.removed();
	}

	// =================================================================== game review

	private static final Identifier ARROWHEAD = ChessCraft.id("textures/gui/ui/arrowhead.png");
	private static final int[] KIND_COLORS = {0xFF26C2A3, 0xFF749BBF, 0xFF81B64C, 0xFF81B64C, 0xFF95B776, 0xFFA88865,
			0xFFF7C631, 0xFFFFA459, 0xFFFF7769, 0xFFFA412D};
	private final List<int[]> reviewHits = new ArrayList<>();
	private float evalBarAnim = 0.5f;

	private void openReview() {
		if (be == null || be.game() == null) return;
		ReviewSession.start(pos, be.gameId(), be.game());
		reviewing = true;
		reviewMoves = false;
		reviewPly = view != null ? view.moves().size() : 0;
		modalDismissed = true;
		clearSelection();
		rebuildWidgets();
	}

	private @Nullable ReviewSession review() {
		if (be == null || be.game() == null) return null;
		return ReviewSession.existing(pos, be.gameId(), be.game());
	}

	private int reviewMoveCount() {
		ReviewSession r = review();
		return r == null ? 0 : r.game.moves().size();
	}

	private void setReviewPly(int ply) {
		reviewPly = Math.max(0, Math.min(reviewMoveCount(), ply));
		moveScroll = Math.max(0, (reviewPly - 1) / 2 - 3);
	}

	private void buildReviewPage() {
		add(new IconButton(cx(), panelY + 8, 18, "back", Style.SECONDARY, () -> {
			if (reviewMoves) reviewMoves = false;
			else reviewing = false;
			rebuildWidgets();
		})).tooltip("Back");
		int bottom = panelY + panelH - 8;
		if (!reviewMoves) {
			add(new Button(cx(), bottom - 24, cw(), 24, "Start Review", "play", Style.SECONDARY, () -> {
				reviewMoves = true;
				setReviewPly(Math.min(1, reviewMoveCount()));
				rebuildWidgets();
			})).enabledWhen(() -> review() != null && review().ready()).tooltip("Step through the game move by move");
			return;
		}
		String[] labels = {"«", "‹", "›", "»"};
		String[] tips = {"First move", "Previous move (Left arrow)", "Next move (Right arrow)", "Last move"};
		int gap = 4, bw = (cw() - 3 * gap) / 4;
		for (int i = 0; i < 4; i++) {
			final int which = i;
			Button b = add(new Button(cx() + i * (bw + gap), bottom - 22, bw, 22, labels[i], null, Style.SECONDARY, () -> {
				switch (which) {
					case 0 -> setReviewPly(0);
					case 1 -> setReviewPly(reviewPly - 1);
					case 2 -> setReviewPly(reviewPly + 1);
					default -> setReviewPly(reviewMoveCount());
				}
			}));
			b.textScale = 1.4f;
			b.tooltip(tips[i]);
		}
	}

	/** {explanationY, graphY, graphH, listTop, listBottom}. */
	private int[] reviewLayout() {
		int explY = panelY + 34;
		int graphY = explY + 36;
		int graphH = 34;
		int listTop = graphY + graphH + 6;
		int listBottom = panelY + panelH - 8 - 22 - 6;
		return new int[]{explY, graphY, graphH, listTop, listBottom};
	}

	private static void badge(GuiGraphics g, GameReview.Kind kind, int x, int y, int size) {
		Identifier id = ChessCraft.id("textures/gui/ui/badges/" + kind.name().toLowerCase() + ".png");
		g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, size, size, 64, 64, 64, 64);
	}

	private static String phrase(GameReview.Kind kind) {
		return switch (kind) {
			case BRILLIANT -> "is brilliant";
			case GREAT -> "is a great move";
			case BEST -> "is the best move";
			case EXCELLENT -> "is excellent";
			case GOOD -> "is good";
			case BOOK -> "is a book move";
			case INACCURACY -> "is an inaccuracy";
			case MISTAKE -> "is a mistake";
			case MISS -> "is a missed win";
			case BLUNDER -> "is a blunder";
		};
	}

	private GameReview.@Nullable MoveInfo reviewMove() {
		ReviewSession r = review();
		if (r == null || !r.ready() || reviewPly <= 0 || r.result == null) return null;
		return r.result.moves.get(reviewPly - 1);
	}

	private void drawReviewPanel(GuiGraphics g, int mx, int my) {
		text(g, font, "Game Review", cx() + 24, panelY + 10, TEXT, true, 1.25f);
		ReviewSession r = review();
		if (r == null) return;
		if (!r.ready() || r.result == null) {
			int y = panelY + 46;
			centered(g, font, r.failed ? "The review could not be completed." : "Analyzing the game...", cx() + cw() / 2, y,
					r.failed ? RED : TEXT, true, 1f);
			y += 18;
			roundRect(g, cx(), y, cw(), 10, 5, BROWN_DARK);
			int fill = Math.round(cw() * r.done / (float) Math.max(1, r.total));
			if (fill > 0) roundRect(g, cx(), y, Math.max(10, fill), 10, 5, CREAM);
			centered(g, font, "Position " + r.done + " of " + r.total, cx() + cw() / 2, y + 16, MUTED, false, 0.9f);
			return;
		}
		if (!reviewMoves) drawReviewSummary(g, r.result);
		else drawReviewMoves(g, r, r.result, mx, my);
	}

	private void drawReviewSummary(GuiGraphics g, GameReview.Result res) {
		if (be == null) return;
		int y = panelY + 34;
		int half = (cw() - 6) / 2;
		for (int c = 0; c < 2; c++) {
			int x = cx() + c * (half + 6);
			roundRect(g, x, y, half, 36, 7, CARD);
			int ac = c == Piece.WHITE ? 0xFFEDE6D8 : 0xFF1E1B19;
			circle(g, x + 14, y + 18, 10, ac);
			if (be.isBot(c)) icon(g, "bot", x + 7, y + 11, 14, c == Piece.WHITE ? 0xFF3A3632 : 0xFFE8E0D0);
			else drawPiece(g, Piece.make(c, Piece.KING), x + 5, y + 9, 18);
			text(g, font, fit(font, be.seatLabel(c), half - 32, false), x + 28, y + 5, MUTED, false, 0.8f);
			text(g, font, String.format("%.1f", res.accuracy[c]), x + 28, y + 16, TEXT, true, 1.35f);
		}
		centered(g, font, "Accuracy", cx() + cw() / 2, y + 39, MUTED, false, 0.75f);
		y += 54;
		int rows = GameReview.Kind.values().length;
		int bottom = panelY + panelH - 8 - 24 - 6;
		int rowH = Math.max(9, Math.min(13, (bottom - y) / rows));
		roundRect(g, cx(), y - 2, cw(), rowH * rows + 4, 7, SURFACE);
		for (GameReview.Kind k : GameReview.Kind.values()) {
			int ry = y + k.ordinal() * rowH;
			int color = KIND_COLORS[k.ordinal()];
			String w = String.valueOf(res.counts[0][k.ordinal()]);
			String b = String.valueOf(res.counts[1][k.ordinal()]);
			text(g, font, w, cx() + 22 - textWidth(font, w, true, 0.9f), ry + 1, color, true, 0.9f);
			int bs = rowH - 2;
			badge(g, k, cx() + cw() / 2 - 34, ry, bs);
			text(g, font, k.label, cx() + cw() / 2 - 34 + bs + 4, ry + 1, TEXT, false, 0.9f);
			text(g, font, b, cx() + cw() - 22, ry + 1, color, true, 0.9f);
		}
	}

	private void drawReviewMoves(GuiGraphics g, ReviewSession r, GameReview.Result res, int mx, int my) {
		int[] L = reviewLayout();
		int x = cx(), w = cw();
		// Explanation of the current move.
		roundRect(g, x, L[0], w, 30, 7, CARD);
		GameReview.MoveInfo m = reviewMove();
		String eval = GameReview.format(res.whiteEval[reviewPly]);
		int ew = textWidth(font, eval, true, 0.9f) + 8;
		roundRect(g, x + w - ew - 4, L[0] + 8, ew, 14, 5, res.whiteEval[reviewPly] >= 0 ? 0xFFEDE6D8 : 0xFF1E1B19);
		text(g, font, eval, x + w - ew, L[0] + 11, res.whiteEval[reviewPly] >= 0 ? 0xFF1C1A18 : TEXT, true, 0.9f);
		if (m == null) {
			text(g, font, "Starting position", x + 8, L[0] + 10, TEXT, true, 1f);
		} else {
			badge(g, m.kind, x + 5, L[0] + 5, 20);
			int tw = w - ew - 36;
			text(g, font, fit(font, m.san + " " + phrase(m.kind), tw, true), x + 29, L[0] + 4, KIND_COLORS[m.kind.ordinal()], true, 1f);
			boolean showBest = m.kind != GameReview.Kind.BEST && m.kind != GameReview.Kind.BOOK && m.kind != GameReview.Kind.BRILLIANT
					&& m.kind != GameReview.Kind.GREAT && !m.bestSan.isEmpty() && !m.bestSan.equals(m.san);
			if (showBest) text(g, font, fit(font, "Best was " + m.bestSan, tw, false), x + 29, L[0] + 16, MUTED, false, 0.9f);
		}
		// Evaluation graph (white area from the bottom).
		int gy = L[1], gh = L[2];
		int n = res.whiteEval.length - 1;
		roundRect(g, x, gy, w, gh, 5, 0xFF403D39);
		for (int px = 0; px < w; px++) {
			float t = n == 0 ? 0 : px / (float) (w - 1) * n;
			int i0 = (int) Math.floor(t), i1 = Math.min(n, i0 + 1);
			double win = GameReview.winChance(res.whiteEval[i0]) * (1 - (t - i0)) + GameReview.winChance(res.whiteEval[i1]) * (t - i0);
			int hh = (int) Math.round(win / 100 * gh);
			if (hh > 0) g.fill(x + px, gy + gh - hh, x + px + 1, gy + gh, 0xFFEDE6D8);
		}
		g.fill(x, gy + gh / 2, x + w, gy + gh / 2 + 1, 0x40808080);
		for (GameReview.MoveInfo mi : res.moves) {
			if (mi.kind != GameReview.Kind.BLUNDER && mi.kind != GameReview.Kind.MISTAKE && mi.kind != GameReview.Kind.BRILLIANT
					&& mi.kind != GameReview.Kind.MISS) continue;
			int px = x + Math.round((mi.ply + 1) / (float) Math.max(1, n) * (w - 1));
			double win = GameReview.winChance(res.whiteEval[mi.ply + 1]);
			int py = gy + gh - (int) Math.round(win / 100 * gh);
			circle(g, px, Math.max(gy + 3, Math.min(gy + gh - 3, py)), 3, KIND_COLORS[mi.kind.ordinal()]);
		}
		int cxp = x + Math.round(reviewPly / (float) Math.max(1, n) * (w - 1));
		g.fill(cxp, gy, cxp + 1, gy + gh, 0xFF81B64C);
		// Move list with badges.
		reviewHits.clear();
		int top = L[3], bottom = L[4];
		roundRect(g, x, top, w, bottom - top, 7, SURFACE);
		int rowH = 12;
		int visible = Math.max(1, (bottom - top - 4) / rowH);
		int rows = (res.moves.size() + 1) / 2;
		moveScroll = Math.max(0, Math.min(moveScroll, Math.max(0, rows - visible)));
		for (int r0 = 0; r0 < visible && moveScroll + r0 < rows; r0++) {
			int row = moveScroll + r0;
			int ry = top + 3 + r0 * rowH;
			if (row % 2 == 1) g.fill(x + 3, ry - 1, x + w - 3, ry + rowH - 1, 0x10FFFFFF);
			text(g, font, (row + 1) + ".", x + 5, ry + 1, DIM, false, 0.9f);
			for (int side = 0; side < 2; side++) {
				int idx = row * 2 + side;
				if (idx >= res.moves.size()) break;
				GameReview.MoveInfo mi = res.moves.get(idx);
				int colX0 = x + 24 + side * ((w - 28) / 2);
				int sw = textWidth(font, mi.san, true, 0.9f);
				boolean cur = idx + 1 == reviewPly;
				if (cur) roundRect(g, colX0 - 2, ry - 1, sw + 16, rowH, 3, 0xFF5A5450);
				badge(g, mi.kind, colX0, ry, 10);
				text(g, font, mi.san, colX0 + 12, ry + 1, TEXT, cur, 0.9f);
				reviewHits.add(new int[]{colX0 - 2, ry - 1, sw + 16, rowH, idx + 1});
			}
		}
		reviewHits.add(new int[]{x, gy, w, gh, -1});
	}

	/** Clicks on the review graph or move list; returns true when handled. */
	private boolean reviewClick(double mx, double my) {
		if (!reviewing || !reviewMoves) return false;
		for (int[] h : reviewHits) {
			if (mx >= h[0] && mx < h[0] + h[2] && my >= h[1] && my < h[1] + h[3]) {
				if (h[4] >= 0) {
					reviewPly = h[4];
				} else {
					int n = reviewMoveCount();
					reviewPly = Math.max(0, Math.min(n, Math.round((float) (mx - h[0]) / Math.max(1, h[2] - 1) * n)));
				}
				return true;
			}
		}
		return false;
	}

	private void drawEvalBar(GuiGraphics g) {
		ReviewSession r = review();
		if (colW == 0 || r == null || !r.ready() || r.result == null) return;
		int cp = r.result.whiteEval[Math.min(reviewPly, r.result.whiteEval.length - 1)];
		float target = GameReview.isMate(cp) ? (cp > 0 ? 1f : 0f) : (float) (GameReview.winChance(cp) / 100);
		evalBarAnim += (target - evalBarAnim) * 0.2f;
		int x = colX, y = boardY, h = boardPx, w = colW;
		roundRect(g, x, y, w, h, 4, 0xFF403D39);
		int wh = Math.round(h * evalBarAnim);
		if (flipped) roundRect(g, x, y, w, Math.max(0, wh), 4, 0xFFEDE6D8);
		else roundRect(g, x, y + h - wh, w, Math.max(0, wh), 4, 0xFFEDE6D8);
		String label = GameReview.format(cp).replace("+", "");
		boolean whiteAhead = cp >= 0;
		boolean atBottom = whiteAhead != flipped;
		int ty = atBottom ? y + h - 9 : y + 4;
		centered(g, font, label, x + w / 2, ty, whiteAhead ? 0xFF1C1A18 : TEXT, true, 0.55f);
	}

	/** Square tint for the reviewed move, coloured by its classification (0 = none). */
	private int reviewTint(int s) {
		GameReview.MoveInfo m = reviewMove();
		if (m == null || (s != Move.from(m.move) && s != Move.to(m.move))) return 0;
		return withAlpha(KIND_COLORS[m.kind.ordinal()], 0x80);
	}

	private void drawReviewOverlay(GuiGraphics g) {
		GameReview.MoveInfo m = reviewMove();
		if (m == null) return;
		boolean showBest = m.kind != GameReview.Kind.BEST && m.kind != GameReview.Kind.BOOK && m.kind != GameReview.Kind.BRILLIANT
				&& m.kind != GameReview.Kind.GREAT && m.bestMove != Move.NONE && m.bestMove != m.move;
		if (showBest) drawArrow(g, Move.from(m.bestMove), Move.to(m.bestMove), 0xC081B64C);
		int to = Move.to(m.move);
		int size = Math.max(10, sq * 9 / 20);
		badge(g, m.kind, sqX(to) + sq - size * 3 / 4, sqY(to) - size / 4, size);
	}

	private static final int ARROW_COLOR = 0xCCFFAA00;

	/** Arrow between two squares; knight jumps get an L-shaped arrow like on chess sites. */
	private void drawArrow(GuiGraphics g, int from, int to, int color) {
		float x0 = sqX(from) + sq / 2f, y0 = sqY(from) + sq / 2f;
		float x1 = sqX(to) + sq / 2f, y1 = sqY(to) + sq / 2f;
		float thick = Math.max(3, sq / 5f);
		int df = Math.abs((from & 7) - (to & 7)), dr = Math.abs((from >> 3) - (to >> 3));
		if ((df == 1 && dr == 2) || (df == 2 && dr == 1)) {
			// Longer leg first, then the short leg with the head.
			boolean verticalFirst = Math.abs(y1 - y0) > Math.abs(x1 - x0);
			float cx = verticalFirst ? x0 : x1, cy = verticalFirst ? y1 : y0;
			segment(g, x0, y0, cx, cy, thick, sq * 0.18f, -thick / 2, false, color);
			segment(g, cx, cy, x1, y1, thick, -thick / 2, 0, true, color);
			return;
		}
		segment(g, x0, y0, x1, y1, thick, sq * 0.18f, 0, true, color);
	}

	/** One straight arrow piece; start trims the beginning, extend lengthens the end, head adds a tip. */
	private void segment(GuiGraphics g, float x0, float y0, float x1, float y1, float thick, float start, float extend,
			boolean head, int color) {
		float len = (float) Math.hypot(x1 - x0, y1 - y0);
		if (len < 1) return;
		float headLen = head ? Math.max(8, sq / 2.2f) : 0;
		g.pose().pushMatrix();
		g.pose().translate(x0, y0);
		g.pose().rotate((float) Math.atan2(y1 - y0, x1 - x0));
		int end = Math.round(len - extend - headLen + (head ? 1 : 0));
		g.fill(Math.round(start), Math.round(-thick / 2), end, Math.round(thick / 2), color);
		if (head) {
			g.blit(RenderPipelines.GUI_TEXTURED, ARROWHEAD, Math.round(len - headLen), Math.round(-headLen / 2), 0, 0,
					Math.round(headLen), Math.round(headLen), 64, 64, 64, 64, color);
		}
		g.pose().popMatrix();
	}

	private void toggleArrow(int from, int to) {
		for (int i = 0; i < arrows.size(); i++) {
			if (arrows.get(i)[0] == from && arrows.get(i)[1] == to) {
				arrows.remove(i);
				return;
			}
		}
		arrows.add(new int[]{from, to});
	}

	// =================================================================== geometry

	private int squareAt(double mx, double my) {
		int bx = boardX + frame, by = boardY + frame;
		if (mx < bx || my < by || mx >= bx + sq * 8 || my >= by + sq * 8) return -1;
		int col = (int) ((mx - bx) / sq), row = (int) ((my - by) / sq);
		int file = flipped ? 7 - col : col;
		int rank = flipped ? row : 7 - row;
		return rank * 8 + file;
	}

	/** Test hook: view the position after k moves. */
	public void testBrowse(int k) {
		browse(k);
	}

	/** Test hooks for the game review. */
	public void testOpenReview() {
		openReview();
	}

	public boolean testReviewReady() {
		ReviewSession r = review();
		return r != null && r.ready();
	}

	public void testReviewMove(int ply) {
		reviewMoves = true;
		setReviewPly(ply);
		rebuildWidgets();
	}

	/** Opens a setup page by name (used by automated tests). */
	public void openPage(String name) {
		setPage(Page.valueOf(name));
	}

	/** Screen position of a square's centre (used by automated tests). */
	public int[] squareCenter(int s) {
		return new int[]{sqX(s) + sq / 2, sqY(s) + sq / 2};
	}

	private int sqX(int s) {
		int file = s & 7;
		return boardX + frame + (flipped ? 7 - file : file) * sq;
	}

	private int sqY(int s) {
		int rank = s >> 3;
		return boardY + frame + (flipped ? rank : 7 - rank) * sq;
	}

	/** Column cell for a palette/inventory piece: {x, y, w, h}. */
	private int[] cellRect(int i) {
		int top = panelY + 22;
		int bottom = panelY + panelH - 8 - (be != null && be.boardMode() == BoardMode.CUSTOM ? 24 : 16);
		int cw = (colW - 14) / 2;
		int ch = Math.min(cw + 12, (bottom - top) / 6 - 3);
		int col = i / 6, row = i % 6;
		return new int[]{colX + 5 + col * (cw + 4), top + row * (ch + 3), cw, ch};
	}

	private static int columnPiece(int i) {
		return Piece.make(i / 6, Piece.KING - (i % 6));
	}

	private int cellAt(double mx, double my) {
		if (colW == 0) return -1;
		for (int i = 0; i < Piece.COUNT; i++) {
			int[] r = cellRect(i);
			if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) return i;
		}
		return -1;
	}

	// =================================================================== rendering

	@Override
	public void render(GuiGraphics g, int mx, int my, float pt) {
		mouseX = mx;
		mouseY = my;
		refreshState();
		if (be == null) return;
		drawPanels(g);
		if (reviewing) drawEvalBar(g);
		else drawColumn(g, mx, my);
		drawBoard(g);
		if (reviewing) {
			drawReviewOverlay(g);
			drawReviewPanel(g, mx, my);
		} else {
			drawPanelContent(g);
		}
		for (ChessUi.Widget w : widgets) w.render(g, font, mx, my);
		g.nextStratum();
		drawDrag(g, mx, my);
		drawPromotion(g, mx, my);
		drawModal(g, mx, my);
		drawTooltips(g, mx, my);
	}

	private void drawPanels(GuiGraphics g) {
		if (colW > 0 && !reviewing) {
			roundRect(g, colX, panelY + 2, colW, panelH, 9, 0x50000000);
			roundRect(g, colX, panelY, colW, panelH, 9, PANEL);
		}
		roundRect(g, panelX, panelY + 2, panelW, panelH, 10, 0x50000000);
		roundRect(g, panelX, panelY, panelW, panelH, 10, PANEL);
	}

	private void drawColumn(GuiGraphics g, int mx, int my) {
		if (colW == 0 || be == null) return;
		boolean custom = be.boardMode() == BoardMode.CUSTOM;
		if (custom) {
			centered(g, font, "Pieces", colX + colW / 2 - 6, panelY + 8, TEXT, true, 1f);
			icon(g, "infinity", colX + colW / 2 + 15, panelY + 7, 10, TEXT);
		} else {
			int total = be.totalStock();
			centered(g, font, total + "/32", colX + colW / 2, panelY + 8, total == 32 ? GREEN : AMBER, true, 1f);
		}
		for (int i = 0; i < Piece.COUNT; i++) {
			int p = columnPiece(i);
			int[] r = cellRect(i);
			boolean hover = mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
			// Black pieces sit on a lighter tile so they stay readable.
			int tile = hover ? BROWN_HOVER : BROWN;
			roundRect(g, r[0], r[1], r[2], r[3], 6, tile);
			int size = Math.min(r[2] - 2, r[3] - (custom ? 2 : 9));
			drawPiece(g, p, r[0] + (r[2] - size) / 2, r[1] + 1, size);
			if (!custom) {
				int have = be.stock(p), cap = ChessBoardBlockEntity.CAP_BY_TYPE[Piece.type(p)];
				int c = have == cap ? GREEN : have == 0 ? RED : AMBER;
				centered(g, font, have + "/" + cap, r[0] + r[2] / 2, r[1] + r[3] - 7, c, true, 0.65f);
			}
		}
		if (!custom) {
			String missing = be.missingPieces();
			centered(g, font, missing.isEmpty() ? "Full set" : "Missing", colX + colW / 2, panelY + panelH - 14,
					missing.isEmpty() ? GREEN : RED, false, 0.8f);
		}
	}

	private void blitScaled(GuiGraphics g, Identifier tex, int x, int y, int w, int h, int texSize) {
		g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0, 0, w, h, texSize, texSize, texSize, texSize);
	}

	private void drawBoard(GuiGraphics g) {
		roundRect(g, boardX - 2, boardY, boardPx + 4, boardPx + 4, 8, 0x60000000);
		blitScaled(g, FRAME, boardX, boardY, boardPx, boardPx, 64);
		g.renderOutline(boardX + frame - 1, boardY + frame - 1, sq * 8 + 2, sq * 8 + 2, 0xFFE2B65C);

		int[] boardArr = displayBoard();
		boolean playing = be != null && be.phase() != Phase.SETUP && !reviewing;
		boolean history = playing && browsing();
		int lastMove = view != null && playing && settings.highlightLastMove ? view.lastMove() : Move.NONE;
		if (history) lastMove = browsePly > 0 ? view.moves().get(browsePly - 1) : Move.NONE;
		Position shown = history ? positionAt(browsePly) : view != null ? view.position() : null;
		int checkSq = -1;
		if (shown != null && playing && shown.inCheck()) checkSq = shown.kingSquare(shown.side);
		int hint = be != null ? be.hint() : Move.NONE;

		for (int s = 0; s < 64; s++) {
			int x = sqX(s), y = sqY(s);
			boolean light = ((s & 7) + (s >> 3)) % 2 == 1;
			blitScaled(g, light ? SQUARE_LIGHT : SQUARE_DARK, x, y, sq, sq, 32);
			if (lastMove != Move.NONE && pendingMove == Move.NONE && (s == Move.from(lastMove) || s == Move.to(lastMove))) {
				g.fill(x, y, x + sq, y + sq, 0x66F2D046);
			}
			if (hint != Move.NONE && (s == Move.from(hint) || s == Move.to(hint))) g.fill(x, y, x + sq, y + sq, 0x7040B0FF);
			if (s == premoveFrom || s == premoveTo) g.fill(x, y, x + sq, y + sq, 0x80D2453A);
			if (reviewing) {
				int tint = reviewTint(s);
				if (tint != 0) g.fill(x, y, x + sq, y + sq, tint);
			}
			if (marked.contains(s)) g.fill(x, y, x + sq, y + sq, 0xCCEB6150);
			if (s == checkSq) {
				g.fill(x, y, x + sq, y + sq, 0x90E03020);
				g.renderOutline(x, y, sq, sq, 0xFFFF5040);
			}
			if (s == selected) g.fill(x, y, x + sq, y + sq, premoveSelect ? 0x70D2453A : 0x7068C0FF);
		}
		for (int i = 0; i < 8; i++) {
			int file = flipped ? 7 - i : i;
			int rank = flipped ? i : 7 - i;
			text(g, font, String.valueOf((char) ('A' + file)), boardX + frame + i * sq + sq / 2 - 2,
					boardY + frame + sq * 8 + Math.max(1, frame / 2 - 3), 0xFFE8D2A6, true, 0.7f);
			text(g, font, String.valueOf((char) ('1' + rank)), boardX + Math.max(1, frame / 2 - 2),
					boardY + frame + i * sq + sq / 2 - 3, 0xFFE8D2A6, true, 0.7f);
		}

		long elapsed = System.currentTimeMillis() - (be != null ? be.animStartMillis : 0);
		int anim = be != null ? be.animMove : Move.NONE;
		int[] prev = be != null ? be.animPrevBoard : null;
		boolean animating = playing && !history && anim != Move.NONE && prev != null && elapsed >= 0 && elapsed < GUI_ANIM_MS
				&& anim != suppressAnim && pendingMove == Move.NONE;
		int hide = dragging ? dragFrom : -1;
		for (int s = 0; s < 64; s++) {
			int p = animating ? prev[s] : boardArr[s];
			if (p == Piece.NONE || s == hide) continue;
			if (animating && (s == Move.from(anim) || s == castleRookFrom(anim))) continue;
			if (animating && s == Move.to(anim) && Move.isCapture(anim) && elapsed > GUI_ANIM_MS * 0.6) continue;
			drawPiece(g, p, sqX(s), sqY(s), sq);
		}
		if (animating) {
			float t = elapsed / (float) GUI_ANIM_MS;
			t = 1 - (1 - t) * (1 - t);
			int from = Move.from(anim), to = Move.to(anim);
			if (prev[from] != Piece.NONE) drawPiece(g, prev[from], lerpI(sqX(from), sqX(to), t), lerpI(sqY(from), sqY(to), t), sq);
			int rf = castleRookFrom(anim);
			if (rf >= 0 && prev[rf] != Piece.NONE) {
				int rt = Move.flags(anim) == Move.CASTLE_KING ? from + 1 : from - 1;
				drawPiece(g, prev[rf], lerpI(sqX(rf), sqX(rt), t), lerpI(sqY(rf), sqY(rt), t), sq);
			}
		}
		if (settings.showLegalMoves || premoveSelect) {
			for (int m : targets) {
				int t = Move.to(m);
				boolean capture = boardArr[t] != Piece.NONE || Move.isEnPassant(m);
				blitScaled(g, capture ? CAPTURE_RING : MOVE_DOT, sqX(t), sqY(t), sq, sq, 64);
			}
		}
		for (int[] a : arrows) drawArrow(g, a[0], a[1], ARROW_COLOR);
		if (annoFrom >= 0) {
			int hover = squareAt(mouseX, mouseY);
			if (hover >= 0 && hover != annoFrom) drawArrow(g, annoFrom, hover, ARROW_COLOR);
		}
	}

	private static int lerpI(int a, int b, float t) {
		return Math.round(a + (b - a) * t);
	}

	private static int castleRookFrom(int move) {
		if (Move.flags(move) == Move.CASTLE_KING) return Move.from(move) + 3;
		if (Move.flags(move) == Move.CASTLE_QUEEN) return Move.from(move) - 4;
		return -1;
	}

	/** Board contents to draw: setup (with local edits), game position, or the position after a sent move. */
	private int[] displayBoard() {
		if (be == null) return new int[64];
		if (be.phase() == Phase.SETUP) return setupPreview != null ? setupPreview : be.displayBoard();
		if (reviewing) {
			ReviewSession r = review();
			if (r != null) return r.positions.get(Math.min(reviewPly, r.positions.size() - 1)).board;
		}
		if (view == null) return be.displayBoard();
		if (browsing()) return positionAt(browsePly).board;
		if (pendingMove != Move.NONE) {
			Position p = view.position().copy();
			p.makeMove(pendingMove);
			return p.board;
		}
		return view.position().board;
	}

	private void drawPiece(GuiGraphics g, int piece, int x, int y, int size) {
		int pad = Math.max(0, size / 16);
		blitScaled(g, SPRITES[piece], x + pad, y + pad, size - 2 * pad, size - 2 * pad, 128);
	}

	private void drawPanelContent(GuiGraphics g) {
		if (be == null) return;
		if (be.phase() == Phase.SETUP) {
			String title = switch (page) {
				case MODE -> "Mode";
				case BOT -> "Play Bot";
				case FRIEND -> "Play a Friend";
				case TIME -> "Time";
				case SETTINGS -> "Settings";
				default -> "New Game";
			};
			text(g, font, title, cx() + (page == Page.HOME ? 0 : 24), panelY + 10, TEXT, true, 1.25f);
			if (page == Page.FRIEND) {
				String info = settings.sameScreen
						? "Both sides play on this screen, taking turns."
						: "Start, then another player opens this board and joins the other side.";
				wrapped(g, font, info, cx(), panelY + 8 + 26 + 24, cw(), MUTED);
			}
			String err = be.validateSetup();
			if (err != null) {
				int y = panelY + panelH - 8 - 28 - 22;
				List<?> lines = font.split(styled(err, false), Math.round(cw() / 0.8f));
				if (lines.size() > 2) err = be.boardMode() == BoardMode.STANDARD ? "Some pieces are missing (see the left column)." : err;
				g.pose().pushMatrix();
				g.pose().translate(cx(), y);
				g.pose().scale(0.8f, 0.8f);
				int yy = 0;
				for (var line : font.split(styled(err, false), Math.round(cw() / 0.8f))) {
					g.drawString(font, line, 0, yy, RED, false);
					yy += 10;
					if (yy >= 20) break;
				}
				g.pose().popMatrix();
			}
			return;
		}
		if (be.waitingForOpponent()) {
			text(g, font, "Play a Friend", cx(), panelY + 10, TEXT, true, 1.25f);
			int y = panelY + 44;
			int pulse = (int) (System.currentTimeMillis() / 400 % 4);
			centered(g, font, "Waiting for an opponent" + ".".repeat(pulse), cx() + cw() / 2, y, AMBER, true, 1f);
			y += 18;
			y = wrapped(g, font, "Another player can open this board and press Join.", cx(), y, cw(), MUTED) + 8;
			drawPlayerCard(g, Piece.WHITE, cx(), y, cw());
			drawPlayerCard(g, Piece.BLACK, cx(), y + 36, cw());
			return;
		}
		drawGamePanel(g);
	}

	private void drawGamePanel(GuiGraphics g) {
		if (be == null || view == null) return;
		int top = flipped ? Piece.WHITE : Piece.BLACK;
		int y = panelY + 8;
		drawPlayerCard(g, top, cx(), y, cw());
		y += 36;
		drawStatus(g, cx(), y, cw());
		y += 18;
		int bottomCard = panelY + panelH - 8 - actionBarHeight() - 6 - 30;
		int navY = bottomCard - 6 - 18;
		boolean cont = canContinueFromHere();
		if (continueButton != null) continueButton.visible = cont;
		listTop = y;
		listBottom = (cont ? navY - 4 - 18 : navY) - 4;
		drawMoveList(g, cx(), listTop, cw(), listBottom - listTop);
		drawPlayerCard(g, top ^ 1, cx(), bottomCard, cw());
	}

	private void drawStatus(GuiGraphics g, int x, int y, int w) {
		if (be == null || view == null) return;
		String text;
		int color;
		if (browsing()) {
			text = "Viewing move " + browsePly + " of " + view.moves().size();
			color = 0xFF749BBF;
		} else if (view.isOver()) {
			text = be.resultText();
			color = GREEN;
		} else {
			int side = view.sideToMove();
			String who = side == Piece.WHITE ? "White" : "Black";
			if (be.isBot(side)) {
				text = be.thinking() ? who + " is thinking" + ".".repeat((int) (System.currentTimeMillis() / 350 % 4)) : who + " to move";
				color = AMBER;
			} else if (canMoveNow()) {
				text = "Your turn";
				color = GREEN;
			} else {
				text = who + " to move";
				color = MUTED;
			}
			if (view.position().inCheck()) {
				text = "Check! " + text;
				color = RED;
			}
			if (be.drawOfferBy() >= 0) text += "  (draw offered)";
			if (premoveFrom >= 0) text += "  (premove set)";
		}
		roundRect(g, x, y, w, 14, 7, SURFACE);
		circle(g, x + 8, y + 7, 3, color);
		String shown = fit(font, text, w - 20, false);
		text(g, font, shown, x + 15, y + 3, TEXT, false, 1f);
	}

	private void drawPlayerCard(GuiGraphics g, int color, int x, int y, int w) {
		if (be == null) return;
		boolean toMove = view != null && !view.isOver() && be.phase() == Phase.PLAYING && view.sideToMove() == color
				&& !be.waitingForOpponent();
		roundRect(g, x, y, w, 30, 7, toMove ? 0xFF3F4A35 : CARD);
		int ac = color == Piece.WHITE ? 0xFFEDE6D8 : 0xFF2A2624;
		circle(g, x + 15, y + 15, 11, ac);
		if (be.isBot(color)) {
			icon(g, "bot", x + 8, y + 8, 14, color == Piece.WHITE ? 0xFF3A3632 : 0xFFE8E0D0);
		} else {
			drawPiece(g, Piece.make(color, Piece.KING), x + 5, y + 5, 20);
		}
		String name = fit(font, be.seatLabel(color), w - 80, true);
		text(g, font, name, x + 30, y + 5, TEXT, true, 1f);
		int[] captured = capturedBy(color);
		int cx = x + 30;
		for (int type = Piece.QUEEN; type >= Piece.PAWN; type--) {
			for (int k = 0; k < captured[type]; k++) {
				drawPiece(g, Piece.make(color ^ 1, type), cx, y + 16, 11);
				cx += type == Piece.PAWN ? 4 : 6;
			}
		}
		int diff = material(color) - material(color ^ 1);
		if (diff > 0) text(g, font, "+" + diff, cx + 6, y + 18, MUTED, false, 0.8f);
		if (be.hasClock()) {
			long ms = be.remainingMs(color, System.currentTimeMillis());
			String clock = formatClock(ms);
			int cw = textWidth(font, clock, true, 1f) + 10;
			int bg = toMove ? (ms < 10_000 ? RED : 0xFFF2EEE6) : 0xFF2B2926;
			int fg = toMove ? (ms < 10_000 ? TEXT : 0xFF1C1A18) : MUTED;
			roundRect(g, x + w - cw - 5, y + 7, cw, 16, 5, bg);
			text(g, font, clock, x + w - cw, y + 11, fg, true, 1f);
		}
	}

	private void drawMoveList(GuiGraphics g, int x, int y, int w, int h) {
		if (view == null || h < 12) return;
		roundRect(g, x, y, w, h, 7, SURFACE);
		List<String> sans = view.sanMoves();
		Position start = Position.fromFen(view.startFen());
		List<String[]> rows = new ArrayList<>();
		int moveNo = start.fullmove;
		int i = 0;
		if (start.side == Piece.BLACK && !sans.isEmpty()) {
			rows.add(new String[]{moveNo + ".", "...", sans.get(0)});
			i = 1;
			moveNo++;
		}
		for (; i < sans.size(); i += 2) {
			rows.add(new String[]{moveNo + ".", sans.get(i), i + 1 < sans.size() ? sans.get(i + 1) : ""});
			moveNo++;
		}
		int rowH = 11;
		int visible = Math.max(1, (h - 6) / rowH);
		moveScroll = Math.max(0, Math.min(moveScroll, Math.max(0, rows.size() - visible)));
		int offset = start.side == Piece.BLACK ? 1 : 0;
		int last = (browsing() ? browsePly - 1 : sans.size() - 1) + offset;
		moveHits.clear();
		for (int r = 0; r < visible && moveScroll + r < rows.size(); r++) {
			int idx = moveScroll + r;
			String[] row = rows.get(idx);
			int ry = y + 3 + r * rowH;
			if (idx % 2 == 1) g.fill(x + 3, ry - 1, x + w - 3, ry + rowH - 1, 0x10FFFFFF);
			text(g, font, row[0], x + 6, ry + 1, DIM, false, 1f);
			int c1 = x + 28, c2 = x + 28 + (w - 34) / 2;
			int ply1 = idx * 2, ply2 = idx * 2 + 1;
			drawSan(g, row[1], c1, ry, ply1 == last);
			drawSan(g, row[2], c2, ry, ply2 == last);
			if (ply1 - offset >= 0) moveHits.add(new int[]{c1 - 3, ry - 1, (w - 34) / 2, rowH, ply1 - offset + 1});
			if (!row[2].isEmpty()) moveHits.add(new int[]{c2 - 3, ry - 1, (w - 34) / 2, rowH, ply2 - offset + 1});
		}
		if (rows.isEmpty()) centered(g, font, "No moves yet", x + w / 2, y + 6, DIM, false, 1f);
	}

	private void drawSan(GuiGraphics g, String san, int x, int y, boolean current) {
		if (san.isEmpty()) return;
		if (current) roundRect(g, x - 3, y - 1, textWidth(font, san, true, 1f) + 6, 11, 3, 0xFF4F4C48);
		text(g, font, san, x, y + 1, TEXT, current, 1f);
	}

	private int[] capturedBy(int color) {
		int[] out = new int[6];
		if (view == null) return out;
		Position start = Position.fromFen(view.startFen());
		Position now = view.position();
		for (int t = Piece.PAWN; t <= Piece.QUEEN; t++) {
			out[t] = Math.max(0, start.pieceCount(Piece.make(color ^ 1, t)) - now.pieceCount(Piece.make(color ^ 1, t)));
		}
		return out;
	}

	private int material(int color) {
		if (view == null) return 0;
		int[] v = {1, 3, 3, 5, 9, 0};
		int m = 0;
		for (int t = Piece.PAWN; t <= Piece.QUEEN; t++) m += v[t] * view.position().pieceCount(Piece.make(color, t));
		return m;
	}

	private static String formatClock(long ms) {
		long s = ms / 1000;
		if (ms < 10_000) return String.format("%d.%d", s, (ms % 1000) / 100);
		if (s >= 3600) return String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
		return String.format("%d:%02d", s / 60, s % 60);
	}

	private void drawDrag(GuiGraphics g, int mx, int my) {
		if (!dragging) return;
		int p = dragPiece != Piece.NONE ? dragPiece : (selected >= 0 ? displayBoard()[selected] : Piece.NONE);
		if (p != Piece.NONE) drawPiece(g, p, mx - sq / 2, my - sq / 2, sq);
	}

	private void drawPromotion(GuiGraphics g, int mx, int my) {
		if (promoFrom < 0 || view == null) return;
		int color = view.sideToMove();
		int x = sqX(promoTo);
		int dir = sqY(promoTo) < boardY + frame + sq * 4 ? 1 : -1;
		int[] types = {Piece.QUEEN, Piece.ROOK, Piece.BISHOP, Piece.KNIGHT};
		g.fill(boardX, boardY, boardX + boardPx, boardY + boardPx, 0x80000000);
		int y0 = dir > 0 ? sqY(promoTo) : sqY(promoTo) - 3 * sq;
		roundRect(g, x - 3, y0 - 3, sq + 6, sq * 4 + 6, 8, 0xFFF2EEE6);
		for (int i = 0; i < 4; i++) {
			int y = sqY(promoTo) + dir * i * sq;
			if (mx >= x && mx < x + sq && my >= y && my < y + sq) roundRect(g, x, y, sq, sq, 6, 0xFFD9D2C3);
			drawPiece(g, Piece.make(color, types[i]), x, y, sq);
		}
	}

	private boolean modalVisible() {
		return be != null && be.phase() == Phase.FINISHED && view != null && view.isOver() && !modalDismissed && !reviewing;
	}

	private void drawModal(GuiGraphics g, int mx, int my) {
		if (!modalVisible() || be == null || view == null) return;
		float t = Math.min(1f, (System.currentTimeMillis() - modalStart) / 280f);
		float pop = 1 - (1 - t) * (1 - t) * (1 - t);
		int[] r = modalRect();
		g.fill(boardX, boardY, boardX + boardPx, boardY + boardPx, withAlpha(0x000000, Math.round(110 * t)));
		g.pose().pushMatrix();
		float s = 0.86f + 0.14f * pop;
		g.pose().translate(r[0] + r[2] / 2f, r[1] + r[3] / 2f);
		g.pose().scale(s, s);
		g.pose().translate(-(r[0] + r[2] / 2f), -(r[1] + r[3] / 2f));

		int x = r[0], y = r[1], w = r[2], h = r[3];
		roundRect(g, x, y + 3, w, h, 12, 0x70000000);
		roundRect(g, x, y, w, h, 12, 0xFF2B2926);
		ChessGame.Result res = view.result();
		int mine = singleColor();
		boolean won = mine >= 0 && ((res == ChessGame.Result.WHITE_WINS && mine == Piece.WHITE)
				|| (res == ChessGame.Result.BLACK_WINS && mine == Piece.BLACK));
		boolean lost = mine >= 0 && res != ChessGame.Result.DRAW && !won;
		int headColor = res == ChessGame.Result.DRAW ? 0xFF6B7B8C : lost ? 0xFF8E3B30 : 0xFF5D8A35;
		roundRect(g, x, y, w, 50, 12, headColor);
		g.fill(x, y + 38, x + w, y + 50, headColor);
		String title = switch (view.reason()) {
			case CHECKMATE -> "CHECKMATE!";
			case STALEMATE -> "STALEMATE";
			case TIMEOUT -> "TIME OUT";
			case RESIGNATION -> "RESIGNED";
			default -> res == ChessGame.Result.DRAW ? "DRAW" : "GAME OVER";
		};
		float ts = Math.min(2.0f, (w - 30) / (float) Math.max(1, textWidth(font, title, true, 1f)));
		centered(g, font, title, x + w / 2, y + 9, TEXT, true, ts);
		String sub;
		if (res == ChessGame.Result.DRAW) sub = "Draw by " + ChessBoardBlockEntity.reasonText(view).toLowerCase();
		else if (won) sub = "You won!";
		else if (lost) sub = "You lost";
		else sub = (res == ChessGame.Result.WHITE_WINS ? "White" : "Black") + " won";
		centered(g, font, sub, x + w / 2, y + 12 + Math.round(9 * ts), 0xFFF2EEE6, false, 1f);

		// Players and score.
		int py = y + 62;
		String score = res == ChessGame.Result.WHITE_WINS ? "1 - 0" : res == ChessGame.Result.BLACK_WINS ? "0 - 1" : "½ - ½";
		int ax = x + w / 4, bx = x + w * 3 / 4;
		avatar(g, Piece.WHITE, ax, py + 14, res == ChessGame.Result.WHITE_WINS);
		avatar(g, Piece.BLACK, bx, py + 14, res == ChessGame.Result.BLACK_WINS);
		centered(g, font, score, x + w / 2, py + 10, TEXT, true, 1.4f);
		centered(g, font, fit(font, be.seatLabel(Piece.WHITE), w / 2 - 8, false), ax, py + 32, MUTED, false, 0.8f);
		centered(g, font, fit(font, be.seatLabel(Piece.BLACK), w / 2 - 8, false), bx, py + 32, MUTED, false, 0.8f);

		for (ChessUi.Widget wd : modalWidgets) wd.render(g, font, mx, my);
		g.pose().popMatrix();
	}

	private void avatar(GuiGraphics g, int color, int cx, int cy, boolean winner) {
		if (be == null) return;
		if (winner) {
			circle(g, cx, cy, 15, GREEN);
			icon(g, "crown", cx - 7, cy - 27, 14, 0xFFF2C14E);
		}
		circle(g, cx, cy, 13, color == Piece.WHITE ? 0xFFEDE6D8 : 0xFF1E1B19);
		if (be.isBot(color)) icon(g, "bot", cx - 8, cy - 8, 16, color == Piece.WHITE ? 0xFF3A3632 : 0xFFE8E0D0);
		else drawPiece(g, Piece.make(color, Piece.KING), cx - 11, cy - 11, 22);
	}

	private void drawTooltips(GuiGraphics g, int mx, int my) {
		List<ChessUi.Widget> source = modalVisible() ? modalWidgets : widgets;
		for (ChessUi.Widget w : source) {
			String tip = w.visible ? w.tooltipAt(mx, my) : null;
			if (tip != null) {
				g.setTooltipForNextFrame(font, styled(tip, false), mx, my);
				return;
			}
		}
		if (be == null || be.phase() != Phase.SETUP) return;
		int i = cellAt(mx, my);
		if (i < 0) return;
		int p = columnPiece(i);
		String name = Piece.colorName(Piece.color(p)) + " " + Piece.typeName(Piece.type(p));
		name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
		String extra = be.boardMode() == BoardMode.CUSTOM ? " - drag onto the board"
				: " - " + be.stock(p) + "/" + ChessBoardBlockEntity.CAP_BY_TYPE[Piece.type(p)] + " in the board (right-click: take one out)";
		g.setTooltipForNextFrame(font, styled(name + extra, false), mx, my);
	}

	// =================================================================== input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		refreshState();
		double mx = event.x(), my = event.y();
		int button = event.button();
		if (be == null) return false;
		if (promoFrom >= 0) {
			handlePromotionClick(mx, my);
			return true;
		}
		if (modalVisible()) {
			for (ChessUi.Widget w : modalWidgets) {
				if (w.click(mx, my, button)) return true;
			}
			int[] r = modalRect();
			if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) return true;
		}
		for (ChessUi.Widget w : List.copyOf(widgets)) {
			if (w.visible && w.click(mx, my, button)) {
				pressed = w;
				return true;
			}
		}

		if (be.phase() == Phase.SETUP) return setupClick(mx, my, button);
		if (reviewing) return reviewClick(mx, my);
		if (button == 0) {
			for (int[] h : moveHits) {
				if (mx >= h[0] && mx < h[0] + h[2] && my >= h[1] && my < h[1] + h[3]) {
					browse(h[4]);
					return true;
				}
			}
			if (browsing() && squareAt(mx, my) >= 0) {
				browse(Integer.MAX_VALUE);
				return true;
			}
		}

		int s = squareAt(mx, my);
		if (s < 0) {
			clearSelection();
			premoveFrom = premoveTo = -1;
			return false;
		}
		if (button == 1) {
			// Right button: drag for an arrow, click to mark the square red.
			clearSelection();
			premoveFrom = premoveTo = -1;
			annoFrom = s;
			annoButton = 1;
			return true;
		}
		if (button != 0) return true;
		leftPressSq = s;
		if (selected >= 0 && tryMove(selected, s)) return true;
		selectSquare(s);
		return true;
	}

	private boolean setupClick(double mx, double my, int button) {
		if (be == null) return false;
		boolean custom = be.boardMode() == BoardMode.CUSTOM;
		int cell = cellAt(mx, my);
		if (cell >= 0) {
			int p = columnPiece(cell);
			if (!custom && button == 1) send(ChessActionPayload.WITHDRAW, p, 1, 0);
			if (custom && button == 0) {
				dragging = true;
				dragFrom = -1;
				dragPiece = p;
			}
			return true;
		}
		int s = squareAt(mx, my);
		if (s < 0 || !custom) return s >= 0;
		int[] b = displayBoard();
		if (button == 1) {
			editSetup(s, Piece.NONE);
			send(ChessActionPayload.CUSTOM_PLACE, s, Piece.NONE, 0);
		} else if (b[s] != Piece.NONE) {
			dragging = true;
			dragFrom = s;
			dragPiece = b[s];
		}
		return true;
	}

	private void editSetup(int sq, int piece) {
		int[] b = displayBoard().clone();
		if (piece != Piece.NONE && Piece.type(piece) == Piece.KING) {
			for (int i = 0; i < 64; i++) {
				if (b[i] == piece) b[i] = Piece.NONE;
			}
		}
		b[sq] = piece;
		setupPreview = b;
	}

	private void clearSelection() {
		selected = -1;
		targets = new int[0];
		premoveSelect = false;
	}

	private void selectSquare(int s) {
		clearSelection();
		if (view == null) return;
		int p = view.position().board[s];
		if (p == Piece.NONE) return;
		if (canMoveNow() && Piece.color(p) == view.sideToMove()) {
			selected = s;
			dragging = true;
			dragFrom = s;
			dragPiece = Piece.NONE;
			List<Integer> t = new ArrayList<>();
			for (int m : view.legalMoves()) {
				if (Move.from(m) == s && (!Move.isPromotion(m) || Move.promoType(m) == Piece.QUEEN)) t.add(m);
			}
			targets = t.stream().mapToInt(Integer::intValue).toArray();
		} else if (canPremove() && Piece.color(p) == singleColor()) {
			selected = s;
			premoveSelect = true;
			dragging = true;
			dragFrom = s;
			dragPiece = Piece.NONE;
			Position copy = view.position().copy();
			copy.makeNullMove();
			List<Integer> t = new ArrayList<>();
			for (int m : copy.legalMoves()) {
				if (Move.from(m) == s && (!Move.isPromotion(m) || Move.promoType(m) == Piece.QUEEN)) t.add(m);
			}
			targets = t.stream().mapToInt(Integer::intValue).toArray();
		}
	}

	/** Tries to move the selected piece; opens the promotion picker, or queues a premove. */
	private boolean tryMove(int from, int to) {
		for (int m : targets) {
			if (Move.from(m) == from && Move.to(m) == to) {
				if (premoveSelect) {
					premoveFrom = from;
					premoveTo = to;
					clearSelection();
					dragging = false;
					return true;
				}
				if (Move.isPromotion(m)) {
					promoFrom = from;
					promoTo = to;
					dragging = false;
					return true;
				}
				sendMove(m);
				return true;
			}
		}
		return false;
	}

	private void sendMove(int move) {
		send(ChessActionPayload.MOVE, Move.from(move), Move.to(move), Move.isPromotion(move) ? Move.promoType(move) : 0);
		pendingMove = move;
		clearSelection();
		dragging = false;
	}

	private void handlePromotionClick(double mx, double my) {
		int x = sqX(promoTo);
		int dir = sqY(promoTo) < boardY + frame + sq * 4 ? 1 : -1;
		int[] types = {Piece.QUEEN, Piece.ROOK, Piece.BISHOP, Piece.KNIGHT};
		for (int i = 0; i < 4; i++) {
			int y = sqY(promoTo) + dir * i * sq;
			if (mx >= x && mx < x + sq && my >= y && my < y + sq && view != null) {
				int m = view.position().findMove(promoFrom, promoTo, types[i]);
				promoFrom = promoTo = -1;
				if (m != Move.NONE) sendMove(m);
				return;
			}
		}
		promoFrom = promoTo = -1;
		clearSelection();
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (pressed != null) pressed.drag(event.x(), event.y());
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (pressed != null) {
			pressed.release();
			pressed = null;
			settings.save();
		}
		if (be != null && annoFrom >= 0 && event.button() == annoButton) {
			int s = squareAt(event.x(), event.y());
			int from = annoFrom, btn = annoButton;
			annoFrom = annoButton = -1;
			if (s >= 0 && s != from) {
				toggleArrow(from, s);
				leftPressSq = -1;
				return true;
			}
			if (btn == 1) {
				if (s == from && !marked.remove(s)) marked.add(s);
				return true;
			}
		}
		if (event.button() == 0 && leftPressSq >= 0) {
			// A plain left click on the board clears the annotations.
			if (squareAt(event.x(), event.y()) == leftPressSq) {
				arrows.clear();
				marked.clear();
			}
			leftPressSq = -1;
		}
		if (be == null || !dragging) return super.mouseReleased(event);
		dragging = false;
		int s = squareAt(event.x(), event.y());
		if (be.phase() == Phase.SETUP) {
			int piece = dragPiece;
			int from = dragFrom;
			dragPiece = Piece.NONE;
			dragFrom = -1;
			if (piece == Piece.NONE) return true;
			if (s >= 0 && from >= 0 && s != from) {
				editSetup(from, Piece.NONE);
				int[] b = setupPreview;
				if (b != null) b[s] = piece;
				send(ChessActionPayload.CUSTOM_MOVE, from, s, 0);
			} else if (s >= 0 && from < 0) {
				editSetup(s, piece);
				send(ChessActionPayload.CUSTOM_PLACE, s, piece, 0);
			} else if (s < 0 && from >= 0) {
				editSetup(from, Piece.NONE);
				send(ChessActionPayload.CUSTOM_PLACE, from, Piece.NONE, 0);
			}
			return true;
		}
		dragFrom = -1;
		if (selected >= 0 && s >= 0 && s != selected && !tryMove(selected, s)) clearSelection();
		return true;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		if (reviewing && reviewMoves && mx >= panelX && mx <= panelX + panelW) {
			int[] L = reviewLayout();
			if (my >= L[3] && my <= L[4]) {
				moveScroll = Math.max(0, moveScroll - (int) Math.signum(dy));
				return true;
			}
		}
		if (my >= listTop && my <= listBottom && mx >= panelX && mx <= panelX + panelW) {
			moveScroll = Math.max(0, moveScroll - (int) Math.signum(dy) * 2);
			return true;
		}
		return super.mouseScrolled(mx, my, dx, dy);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (promoFrom >= 0 && event.isEscape()) {
			promoFrom = promoTo = -1;
			return true;
		}
		if (event.isEscape() && page != Page.HOME && be != null && be.phase() == Phase.SETUP) {
			setPage(Page.HOME);
			return true;
		}
		if (!reviewing && be != null && be.phase() != Phase.SETUP && view != null && (event.key() == 262 || event.key() == 263)) {
			int cur = browsing() ? browsePly : view.moves().size();
			browse(cur + (event.key() == 262 ? 1 : -1));
			return true;
		}
		if (reviewing && reviewMoves && (event.key() == 262 || event.key() == 263)) {
			setReviewPly(reviewPly + (event.key() == 262 ? 1 : -1));
			return true;
		}
		if (event.key() == 70 && be != null) {
			flip();
			return true;
		}
		return super.keyPressed(event);
	}

	// =================================================================== small widgets

	/** Three round choices: play White, random, or Black. */
	private final class ColorChoice extends ChessUi.Widget {
		ColorChoice(int x, int y, int w, int h) {
			super(x, y, w, h);
		}

		@Override
		public void render(GuiGraphics g, Font font, int mx, int my) {
			roundRect(g, x, y, w, h, 6, BROWN);
			text(g, font, "Play as", x + 7, y + (h - 8) / 2, TEXT, false, 1f);
			for (int i = 0; i < 3; i++) {
				int cx = x + w - 14 - (2 - i) * 24, cy = y + h / 2;
				boolean sel = settings.color == i;
				boolean hover = Math.abs(mx - cx) <= 10 && Math.abs(my - cy) <= 10;
				if (sel) circle(g, cx, cy, 11, CREAM);
				circle(g, cx, cy, 9, i == 0 ? 0xFFEDE6D8 : i == 2 ? 0xFF1E1B19 : 0xFF6E6A65);
				if (hover && !sel) circle(g, cx, cy, 9, 0x30FFFFFF);
				if (i == 1) icon(g, "side", cx - 6, cy - 6, 12, TEXT);
				else drawPiece(g, Piece.make(i == 0 ? Piece.WHITE : Piece.BLACK, Piece.KING), cx - 8, cy - 8, 16);
			}
		}

		@Override
		public @Nullable String tooltipAt(double mx, double my) {
			if (!hovered(mx, my)) return null;
			String[] names = {"Play as White", "Random color", "Play as Black"};
			for (int i = 0; i < 3; i++) {
				int cx = x + w - 14 - (2 - i) * 24, cy = y + h / 2;
				if (Math.abs(mx - cx) <= 11 && Math.abs(my - cy) <= 11) return names[i];
			}
			return null;
		}

		@Override
		public boolean click(double mx, double my, int button) {
			if (!hovered(mx, my)) return false;
			for (int i = 0; i < 3; i++) {
				int cx = x + w - 14 - (2 - i) * 24, cy = y + h / 2;
				if (Math.abs(mx - cx) <= 11 && Math.abs(my - cy) <= 11) {
					settings.color = i;
					return true;
				}
			}
			return true;
		}
	}
}
