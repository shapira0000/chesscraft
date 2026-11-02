package com.yossi.chesscraft.gametest;

import com.yossi.chesscraft.block.ChessPieceBlock;
import com.yossi.chesscraft.block.entity.ChessBoardBlockEntity;
import com.yossi.chesscraft.chess.Move;
import com.yossi.chesscraft.chess.Piece;
import com.yossi.chesscraft.client.ChessScreen;
import com.yossi.chesscraft.network.ChessActionPayload;
import com.yossi.chesscraft.registry.ModBlocks;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;

/**
 * End-to-end check in a real client: place the table and board with mouse input, insert
 * pieces, open the screen, play moves by clicking, watch the bot reply, then break the
 * board in survival and count the drops. Screenshots go to build/run/clientGameTest/screenshots.
 */
public class ChessClientGameTest implements FabricClientGameTest {
	private static final BlockPos BASE = new BlockPos(0, -60, 5);
	private static final BlockPos BOARD = BASE.above();

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			TestServerContext server = sp.getServer();
			server.runCommand("gamemode creative @a");
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			server.runCommand("setblock 0 -60 5 chesscraft:chess_table_base");
			server.runOnServer(ChessClientGameTest::decorations);

			// Place the board on the base with a real right-click.
			server.runCommand("item replace entity @a weapon.mainhand with chesscraft:chess_board");
			server.runCommand("tp @a 0.5 -60 2.9 facing 0.5 -60.67 5.5");
			context.waitTicks(10);
			context.runOnClient(mc -> log("aim before place: " + mc.hitResult + " " + (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh ? bh.getBlockPos() + " " + bh.getDirection() : "")
					+ " hand=" + mc.player.getMainHandItem()));
			context.getInput().pressMouse(1);
			context.waitTicks(5);
			server.runOnServer(s -> log("after place: board=" + s.overworld().getBlockState(BOARD) + " base=" + s.overworld().getBlockState(BASE)
					+ " hand=" + s.getPlayerList().getPlayers().get(0).getMainHandItem()));
			server.runOnServer(s -> {
				ServerLevel level = s.overworld();
				for (int x = -1; x <= 1; x++) {
					for (int z = -1; z <= 1; z++) {
						check(level.getBlockState(BOARD.offset(x, 0, z)).is(ModBlocks.BOARD), "board part missing at " + x + "," + z);
					}
				}
				check(level.getBlockEntity(BOARD) instanceof ChessBoardBlockEntity, "board block entity missing");
			});
			log("board placed with all 9 parts");

			// Insert pawns by right-clicking in survival: the stack goes in up to the 8-pawn cap.
			server.runCommand("gamemode survival @a");
			server.runCommand("item replace entity @a weapon.mainhand with chesscraft:white_pawn 10");
			server.runCommand("tp @a 0.5 -60 2.6 facing 0.5 -60.42 5.5");
			context.waitTicks(5);
			context.getInput().pressMouse(1);
			context.waitTicks(5);
			check(stock(server, Piece.make(Piece.WHITE, Piece.PAWN)) == 8, "right-click should store 8 pawns");
			check(server.computeOnServer(s -> s.getPlayerList().getPlayers().get(0).getMainHandItem().getCount()) == 2, "2 pawns should stay in hand");
			context.getInput().pressMouse(1);
			context.waitTicks(5);
			check(stock(server, Piece.make(Piece.WHITE, Piece.PAWN)) == 8, "cap must hold at 8");
			check(server.computeOnServer(s -> s.overworld().getBlockState(BOARD.above()).isAir()), "no piece may be placed on the board");
			server.runCommand("gamemode creative @a");
			log("piece insertion ok (stack in, capped at 8)");

			// With only the pawns inserted, the screen shows what is missing and Start is pale.
			context.setScreen(() -> new ChessScreen(BOARD));
			context.waitTicks(10);
			context.takeScreenshot("chess_00_missing_pieces");
			context.setScreen(() -> null);
			server.runOnServer(s -> {
				ChessBoardBlockEntity be = (ChessBoardBlockEntity) s.overworld().getBlockEntity(BOARD);
				for (int p = 0; p < Piece.COUNT; p++) be.addStock(p, 8);
			});

			context.runOnClient(mc -> mc.options.hideGui = true);
			server.runCommand("tp @a 0.5 -56.6 1.6 facing 0.5 -59.3 5.6");
			context.waitTicks(20);
			context.takeScreenshot("chess_01_empty_board");

			// Open the screen with an empty hand.
			context.runOnClient(mc -> mc.options.hideGui = false);
			server.runCommand("item replace entity @a weapon.mainhand with air");
			server.runCommand("tp @a 0.5 -60 2.6 facing 0.5 -60.42 5.5");
			context.waitTicks(5);
			context.getInput().pressMouse(1);
			context.waitForScreen(ChessScreen.class);
			log("screen opened by right-click");
			context.waitTicks(5);
			context.takeScreenshot("chess_02_setup_screen");
			for (String page : new String[]{"BOT", "FRIEND", "TIME", "SETTINGS"}) {
				context.runOnClient(mc -> ((ChessScreen) mc.screen).openPage(page));
				context.waitTicks(3);
				context.takeScreenshot("chess_02_page_" + page.toLowerCase());
			}
			context.runOnClient(mc -> {
				com.yossi.chesscraft.client.ClientChessSettings.get().timeTab = 1;
				((ChessScreen) mc.screen).openPage("TIME");
			});
			context.waitTicks(3);
			context.takeScreenshot("chess_02_page_time_increment");
			context.runOnClient(mc -> {
				com.yossi.chesscraft.client.ClientChessSettings.get().timeTab = 0;
				((ChessScreen) mc.screen).openPage("HOME");
			});
			// Custom mode: five white queens are fine (virtual pieces).
			server.runOnServer(s -> {
				act(s, ChessActionPayload.SET_BOARD_MODE, 1, 0, 0, "");
				act(s, ChessActionPayload.CUSTOM_RESET, 0, 0, 0, "");
				for (String q : new String[]{"c4", "d5", "e4", "f5"}) act(s, ChessActionPayload.CUSTOM_PLACE, sq(q), Piece.make(Piece.WHITE, Piece.QUEEN), 0, "");
			});
			context.waitTicks(5);
			check(server.computeOnServer(s -> board(s).validateSetup() == null), "custom position with 5 queens should be playable");
			context.takeScreenshot("chess_02_custom_mode");
			server.runOnServer(s -> act(s, ChessActionPayload.SET_BOARD_MODE, 0, 0, 0, ""));
			context.waitTicks(3);

			// Start vs bot (white) with a 5+3 clock, then play 1. e4 by clicking squares.
			server.runOnServer(s -> act(s, ChessActionPayload.START, 0, 0, 0, "opp=BOT;color=0;eloW=1500;eloB=1500;base=300;inc=3;undo=1;hints=1"));
			context.waitTicks(5);
			clickSquare(context, "e2");
			context.takeScreenshot("chess_03_selected_e2");
			clickSquare(context, "e4");
			context.waitFor(mc -> movesPlayed(mc) >= 2, 600);
			log("click move and bot reply ok");
			clickSquare(context, "g1");
			clickSquare(context, "f3");
			context.waitFor(mc -> movesPlayed(mc) >= 4, 600);
			context.waitTicks(10);
			context.takeScreenshot("chess_04_game_screen");
			// Annotations like chess.com: right-drag arrows (one knight-shaped) and right-click red squares.
			drag(context, "b1", "c3", 1);
			drag(context, "d2", "d4", 1);
			drag(context, "f8", "b4", 1);
			drag(context, "e5", "e5", 1);
			drag(context, "c4", "c4", 1);
			context.waitTicks(3);
			context.takeScreenshot("chess_04b_arrows");
			drag(context, "a3", "a3", 0);
			// Browse back to move 2, then continue the game from there (bot game with take-backs).
			context.runOnClient(mc -> ((ChessScreen) mc.screen).testBrowse(2));
			context.waitTicks(3);
			context.takeScreenshot("chess_04c_browse");
			server.runOnServer(s -> act(s, ChessActionPayload.UNDO_TO, 2, 0, 0, ""));
			waitServer(context, server, s -> board(s).game().moves().size() == 2, 100);
			log("continue from move 2 ok");
			server.runOnServer(s -> act(s, ChessActionPayload.MOVE, sq("g1"), sq("f3"), 0, ""));
			waitServer(context, server, s -> board(s).game().moves().size() >= 4, 600);
			context.waitTicks(10);

			// In-world views.
			context.setScreen(() -> null);
			context.runOnClient(mc -> mc.options.hideGui = true);
			server.runCommand("tp @a 0.5 -56.6 1.6 facing 0.5 -59.3 5.6");
			context.waitTicks(20);
			context.takeScreenshot("chess_05_world_from_white");
			server.runCommand("tp @a 0.5 -54.8 5.1 facing 0.5 -59.2 5.5");
			context.waitTicks(20);
			context.takeScreenshot("chess_06_world_top");
			server.runOnServer(s -> act(s, ChessActionPayload.MOVE, sq("f1"), sq("c4"), 0, ""));
			context.waitTicks(4);
			context.takeScreenshot("chess_07_anim_top");
			context.waitFor(mc -> movesPlayed(mc) >= 6, 600);
			server.runCommand("tp @a 3.2 -57.2 2.2 facing 0.5 -59.3 5.6");
			context.waitTicks(20);
			context.takeScreenshot("chess_08_world_angle");
			server.runCommand("tp @a 6.0 -59.0 9.6 facing 6.0 -59.6 13.5");
			context.waitTicks(15);
			context.takeScreenshot("chess_09_decor");
			server.runCommand("tp @a 5.5 -59.3 12.6 facing 5.5 -61.0 15.5");
			context.waitTicks(15);
			context.takeScreenshot("chess_09b_white_pieces");
			server.runCommand("tp @a 5.5 -59.3 19.4 facing 5.5 -61.0 16.5");
			context.waitTicks(15);
			context.takeScreenshot("chess_09c_black_pieces");

			server.runOnServer(s -> act(s, ChessActionPayload.RESIGN, 0, 0, 0, ""));
			server.runOnServer(s -> act(s, ChessActionPayload.NEW_GAME, 0, 0, 0, ""));
			context.runOnClient(mc -> mc.options.hideGui = false);
			// Play a friend: after Start the board waits for a second player.
			server.runOnServer(s -> act(s, ChessActionPayload.START, 0, 0, 0, "opp=FRIEND;color=0;base=600;inc=0"));
			context.setScreen(() -> new ChessScreen(BOARD));
			context.waitTicks(10);
			context.takeScreenshot("chess_10b_waiting_for_friend");
			context.setScreen(() -> null);
			server.runOnServer(s -> act(s, ChessActionPayload.NEW_GAME, 0, 0, 0, ""));
			rulesScenarios(context, server);
			reviewScenario(context, server);

			// Break a corner part in survival: the whole board goes, dropping the board and all 32 pieces.
			server.runCommand("gamemode survival @a");
			server.runCommand("tp @a -0.5 -60 2.0 facing -0.5 -60.52 4.5");
			context.waitTicks(5);
			context.getInput().holdMouseFor(0, 60);
			context.waitTicks(20);
			server.runOnServer(s -> {
				ServerLevel level = s.overworld();
				for (int x = -1; x <= 1; x++) {
					for (int z = -1; z <= 1; z++) {
						check(level.getBlockState(BOARD.offset(x, 0, z)).isAir(), "board part left at " + x + "," + z);
					}
				}
				Map<String, Integer> drops = new HashMap<>();
				for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(BOARD).inflate(6))) {
					drops.merge(BuiltInRegistries.ITEM.getKey(e.getItem().getItem()).getPath(), e.getItem().getCount(), Integer::sum);
				}
				log("drops after breaking: " + drops);
				check(drops.getOrDefault("chess_board", 0) == 1, "expected one board drop");
				int pieces = drops.entrySet().stream().filter(en -> !en.getKey().equals("chess_board")).mapToInt(Map.Entry::getValue).sum();
				check(pieces == 32, "expected 32 pieces, got " + pieces);
			});
			log("ALL CHECKS PASSED");
		}
	}

	/** Take-back, hint, promotion and checkmate through the real block entity actions. */
	private static void rulesScenarios(ClientGameTestContext context, TestServerContext server) {
		// Take-back against the bot: undo removes the bot reply and our move.
		server.runOnServer(s -> act(s, ChessActionPayload.START, 0, 0, 0, "opp=BOT;color=0;eloW=1000;base=0;undo=1;hints=1"));
		log("rules: phase=" + server.computeOnServer(s -> board(s).phase() + " mode=" + board(s).mode() + " white=" + board(s).seatLabel(0)));
		server.runOnServer(s -> act(s, ChessActionPayload.MOVE, sq("d2"), sq("d4"), 0, ""));
		context.waitTicks(20);
		log("after d4: " + server.computeOnServer(s -> board(s).game().sanMoves() + " side=" + board(s).game().sideToMove()
				+ " thinking=" + board(s).thinking() + " bot0=" + board(s).isBot(0) + " bot1=" + board(s).isBot(1) + " phase=" + board(s).phase()));
		waitServer(context, server, s -> board(s).game().moves().size() >= 2, 600);
		context.waitTicks(3);
		log("bot replied: " + server.computeOnServer(s -> board(s).game().sanMoves()));
		server.runOnServer(s -> act(s, ChessActionPayload.HINT, 0, 0, 0, ""));
		context.waitFor(mc -> mc.level.getBlockEntity(BOARD) instanceof ChessBoardBlockEntity be && be.hint() != Move.NONE, 400);
		log("hint received: " + server.computeOnServer(s -> Move.toUci(board(s).hint())));
		server.runOnServer(s -> act(s, ChessActionPayload.UNDO, 0, 0, 0, ""));
		context.waitTicks(3);
		check(server.computeOnServer(s -> board(s).game().moves().size()) == 0, "undo should take back both moves");
		log("undo ok");
		server.runOnServer(s -> act(s, ChessActionPayload.NEW_GAME, 0, 0, 0, ""));

		// Promotion to a knight on a custom setup, same-screen mode.
		server.runOnServer(s -> {
			act(s, ChessActionPayload.SET_BOARD_MODE, 1, 0, 0, "");
			act(s, ChessActionPayload.CUSTOM_CLEAR, 0, 0, 0, "");
			act(s, ChessActionPayload.CUSTOM_PLACE, sq("e1"), Piece.make(Piece.WHITE, Piece.KING), 0, "");
			act(s, ChessActionPayload.CUSTOM_PLACE, sq("h8"), Piece.make(Piece.BLACK, Piece.KING), 0, "");
			act(s, ChessActionPayload.CUSTOM_PLACE, sq("b7"), Piece.make(Piece.WHITE, Piece.PAWN), 0, "");
			act(s, ChessActionPayload.CUSTOM_PLACE, sq("a1"), Piece.make(Piece.WHITE, Piece.ROOK), 0, "");
			act(s, ChessActionPayload.START, 0, 0, 0, "opp=LOCAL;base=0");
			act(s, ChessActionPayload.MOVE, sq("b7"), sq("b8"), Piece.KNIGHT, "");
		});
		check(server.computeOnServer(s -> board(s).game().position().board[sq("b8")]) == Piece.make(Piece.WHITE, Piece.KNIGHT),
				"under-promotion to knight failed");
		log("promotion ok: " + server.computeOnServer(s -> board(s).game().sanMoves()));
		// Walk the king over and mate with the rook on the h-file: ... 9. Rh1#.
		server.runOnServer(s -> {
			act(s, ChessActionPayload.MOVE, sq("h8"), sq("h7"), 0, "");
			act(s, ChessActionPayload.MOVE, sq("e1"), sq("f2"), 0, "");
			act(s, ChessActionPayload.MOVE, sq("h7"), sq("h8"), 0, "");
			act(s, ChessActionPayload.MOVE, sq("f2"), sq("g3"), 0, "");
			act(s, ChessActionPayload.MOVE, sq("h8"), sq("h7"), 0, "");
			act(s, ChessActionPayload.MOVE, sq("g3"), sq("h4"), 0, "");
			act(s, ChessActionPayload.MOVE, sq("h7"), sq("h8"), 0, "");
			act(s, ChessActionPayload.MOVE, sq("h4"), sq("h5"), 0, "");
			act(s, ChessActionPayload.MOVE, sq("h8"), sq("h7"), 0, "");
			act(s, ChessActionPayload.MOVE, sq("b8"), sq("d7"), 0, "");
			act(s, ChessActionPayload.MOVE, sq("h7"), sq("g7"), 0, "");
			act(s, ChessActionPayload.MOVE, sq("h5"), sq("g5"), 0, "");
			act(s, ChessActionPayload.MOVE, sq("g7"), sq("h7"), 0, "");
			act(s, ChessActionPayload.MOVE, sq("g5"), sq("f6"), 0, "");
			act(s, ChessActionPayload.MOVE, sq("h7"), sq("h6"), 0, "");
			act(s, ChessActionPayload.MOVE, sq("a1"), sq("h1"), 0, "");
		});
		String result = server.computeOnServer(s -> board(s).phase() + " " + board(s).resultText());
		log("mate line: " + server.computeOnServer(s -> board(s).game().sanMoves()) + " -> " + result);
		check(result.startsWith("FINISHED Checkmate"), "expected checkmate, got " + result);
		context.waitTicks(5);
		context.runOnClient(mc -> mc.options.hideGui = false);
		context.setScreen(() -> new ChessScreen(BOARD));
		context.waitTicks(15);
		context.takeScreenshot("chess_11_game_over");
		context.setScreen(() -> null);
		context.runOnClient(mc -> mc.options.hideGui = true);
		server.runCommand("gamemode spectator @a");
		server.runCommand("tp @a 0.5 -57.6 2.6 facing 0.5 -60.7 5.4");
		context.waitTicks(30);
		context.takeScreenshot("chess_12_board_after_win");
		server.runCommand("gamemode creative @a");
		context.runOnClient(mc -> mc.options.hideGui = false);
		server.runOnServer(s -> act(s, ChessActionPayload.NEW_GAME, 0, 0, 0, ""));
		server.runOnServer(s -> act(s, ChessActionPayload.SET_BOARD_MODE, 0, 0, 0, ""));
		log("rules scenarios ok");
	}

	/** A short game with a hanging queen, reviewed on the client. */
	private static void reviewScenario(ClientGameTestContext context, TestServerContext server) {
		server.runOnServer(s -> {
			act(s, ChessActionPayload.START, 0, 0, 0, "opp=LOCAL;base=0");
			for (String[] m : new String[][]{{"e2", "e4"}, {"e7", "e5"}, {"g1", "f3"}, {"b8", "c6"}, {"f1", "c4"}, {"d8", "h4"}, {"f3", "h4"}}) {
				act(s, ChessActionPayload.MOVE, sq(m[0]), sq(m[1]), 0, "");
			}
			act(s, ChessActionPayload.RESIGN, 0, 0, 0, "");
		});
		check(server.computeOnServer(s -> board(s).phase()) == ChessBoardBlockEntity.Phase.FINISHED, "review game should be over");
		context.runOnClient(mc -> mc.options.hideGui = false);
		context.setScreen(() -> new ChessScreen(BOARD));
		context.waitTicks(10);
		context.runOnClient(mc -> ((ChessScreen) mc.screen).testOpenReview());
		context.waitTicks(4);
		context.takeScreenshot("chess_13_review_analyzing");
		context.waitFor(mc -> mc.screen instanceof ChessScreen cs && cs.testReviewReady(), 1200);
		context.waitTicks(4);
		context.takeScreenshot("chess_14_review_summary");
		context.runOnClient(mc -> ((ChessScreen) mc.screen).testReviewMove(6));
		context.waitTicks(15);
		context.takeScreenshot("chess_15_review_blunder");
		context.runOnClient(mc -> ((ChessScreen) mc.screen).testReviewMove(7));
		context.waitTicks(15);
		context.takeScreenshot("chess_16_review_best");
		context.setScreen(() -> null);
		server.runOnServer(s -> act(s, ChessActionPayload.NEW_GAME, 0, 0, 0, ""));
		log("review scenario ok");
	}

	private static ChessBoardBlockEntity board(MinecraftServer s) {
		return (ChessBoardBlockEntity) s.overworld().getBlockEntity(BOARD);
	}

	private static void log(String s) {
		System.out.println("[ChessTest] " + s);
	}

	private static void check(boolean ok, String msg) {
		if (!ok) throw new AssertionError("[ChessTest] " + msg);
	}

	private static int sq(String name) {
		return Move.parseSquare(name);
	}

	private static void clickSquare(ClientGameTestContext context, String square) {
		context.runOnClient(mc -> {
			if (!(mc.screen instanceof ChessScreen screen)) throw new AssertionError("chess screen not open");
			int[] c = screen.squareCenter(sq(square));
			MouseButtonEvent ev = new MouseButtonEvent(c[0], c[1], new MouseButtonInfo(0, 0));
			screen.mouseClicked(ev, false);
			screen.mouseReleased(ev);
		});
		context.waitTicks(2);
	}

	/** Press on one square and release on another with the given mouse button. */
	private static void drag(ClientGameTestContext context, String from, String to, int button) {
		context.runOnClient(mc -> {
			ChessScreen screen = (ChessScreen) mc.screen;
			int[] a = screen.squareCenter(sq(from)), b = screen.squareCenter(sq(to));
			screen.mouseClicked(new MouseButtonEvent(a[0], a[1], new MouseButtonInfo(button, 0)), false);
			screen.mouseReleased(new MouseButtonEvent(b[0], b[1], new MouseButtonInfo(button, 0)));
		});
		context.waitTicks(1);
	}

	private static int stock(TestServerContext server, int piece) {
		return server.computeOnServer(s -> ((ChessBoardBlockEntity) s.overworld().getBlockEntity(BOARD)).stock(piece));
	}

	/** Polls a condition on the server thread once per tick. */
	private static void waitServer(ClientGameTestContext context, TestServerContext server,
			java.util.function.Predicate<MinecraftServer> condition, int timeoutTicks) {
		for (int i = 0; i < timeoutTicks; i++) {
			if (server.computeOnServer(condition::test)) return;
			context.waitTick();
		}
		throw new AssertionError("[ChessTest] timed out waiting on the server");
	}

	private static int movesPlayed(Minecraft mc) {
		if (mc.level != null && mc.level.getBlockEntity(BOARD) instanceof ChessBoardBlockEntity be && be.game() != null) {
			return be.game().moves().size();
		}
		return 0;
	}

	private static void act(MinecraftServer server, int action, int a, int b, int c, String text) {
		ServerPlayer player = server.getPlayerList().getPlayers().get(0);
		if (server.overworld().getBlockEntity(BOARD) instanceof ChessBoardBlockEntity be) {
			be.handleAction(player, new ChessActionPayload(BOARD, action, a, b, c, text));
		} else {
			throw new AssertionError("board block entity missing");
		}
	}

	private static void decorations(MinecraftServer server) {
		ServerLevel level = server.overworld();
		for (int type = 0; type < 6; type++) {
			level.setBlock(new BlockPos(3 + type, -60, 15), ModBlocks.PIECES[Piece.make(Piece.WHITE, type)].defaultBlockState(), Block.UPDATE_ALL);
			level.setBlock(new BlockPos(3 + type, -60, 17), ModBlocks.PIECES[Piece.make(Piece.BLACK, type)].defaultBlockState()
					.setValue(ChessPieceBlock.ROTATION, 8), Block.UPDATE_ALL);
		}
		for (int r = 0; r < 4; r++) {
			level.setBlock(new BlockPos(3 + r * 2, -60, 22), ModBlocks.PIECES[Piece.make(Piece.WHITE, Piece.KNIGHT)].defaultBlockState()
					.setValue(ChessPieceBlock.ROTATION, r * 4), Block.UPDATE_ALL);
		}
	}
}
