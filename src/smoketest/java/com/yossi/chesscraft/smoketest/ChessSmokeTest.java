package com.yossi.chesscraft.smoketest;

import com.yossi.chesscraft.block.ChessBoardBlock;
import com.yossi.chesscraft.block.entity.ChessBoardBlockEntity;
import com.yossi.chesscraft.chess.ChessGame;
import com.yossi.chesscraft.chess.Move;
import com.yossi.chesscraft.chess.Piece;
import com.yossi.chesscraft.client.ChessScreen;
import com.yossi.chesscraft.network.ChessActionPayload;
import com.yossi.chesscraft.registry.ModBlocks;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Development-only in-game check for the NeoForge and Forge ports (Fabric uses its client game test).
 * Never packed into release jars: it is only compiled with -Pchess_smoketest=true.
 *
 * Joins a world (started with --quickPlaySingleplayer), places a board, inserts every piece, opens the screen
 * through the normal client hook, starts a game against the bot and plays e2-e4 over the real network channel,
 * then waits for the bot's reply. Screenshots go to <run>/screenshots/smoke_*.png. Prints [ChessSmoke] lines and
 * "SMOKE TEST PASSED" or "SMOKE TEST FAILED", then closes the game.
 */
public final class ChessSmokeTest {
	private static final BlockPos BASE = new BlockPos(0, -60, 5);
	private static final BlockPos BOARD = BASE.above();

	private static int stage;
	private static int wait;
	private static int ticks;
	private static int bootTicks;

	private ChessSmokeTest() {
	}

	private static void log(String msg) {
		System.out.println("[ChessSmoke] " + msg);
	}

	private static void fail(Minecraft mc, String msg) {
		log("SMOKE TEST FAILED: " + msg);
		stage = 99;
		mc.stop();
	}

	private static void shot(Minecraft mc, String name) {
//#if mc>=26.2
//$		var target = mc.gameRenderer.mainRenderTarget();
//#else
		var target = mc.getMainRenderTarget();
//#endif
		Screenshot.grab(mc.gameDirectory, "smoke_" + name + ".png", target, 1, msg -> log("screenshot " + name));
	}

	private static void command(MinecraftServer server, String cmd) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
	}

	private static <T> T onServer(MinecraftServer server, java.util.function.Function<MinecraftServer, T> job) {
		AtomicReference<T> out = new AtomicReference<>();
		server.executeBlocking(() -> out.set(job.apply(server)));
		return out.get();
	}

	/** Called every client tick by the loader hook. */
	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		MinecraftServer server = mc.getSingleplayerServer();
		if (stage == 99) return;
		if (stage == 0 && ++bootTicks > 20 * 180) {
			fail(mc, "never entered the world; screen: " + currentScreen(mc));
			return;
		}
		if (mc.player == null || mc.level == null || server == null) return;
		ticks++;
		if (ticks > 20 * 120) {
			fail(mc, "timed out at stage " + stage);
			return;
		}
		if (wait > 0) {
			wait--;
			return;
		}
		switch (stage) {
			case 0 -> {
				wait = 60;
				stage = 1;
			}
			case 1 -> {
				String result = onServer(server, s -> {
					ServerLevel level = s.overworld();
					ServerPlayer player = s.getPlayerList().getPlayers().get(0);
					command(s, "gamemode creative @a");
					command(s, "time set noon");
					command(s, "weather clear");
					command(s, "fill -3 -60 2 3 -55 8 air");
					level.setBlock(BASE, ModBlocks.TABLE_BASE.defaultBlockState(), Block.UPDATE_ALL);
					BlockState board = ModBlocks.BOARD.defaultBlockState().setValue(ChessBoardBlock.FACING, Direction.SOUTH);
					level.setBlock(BOARD, board, Block.UPDATE_ALL);
					board.getBlock().setPlacedBy(level, BOARD, board, player, new ItemStack(ModBlocks.BOARD_ITEM));
					if (!(level.getBlockEntity(BOARD) instanceof ChessBoardBlockEntity be)) return "no board block entity";
					for (int piece = 0; piece < Piece.COUNT; piece++) {
						be.insertPieces(player, new ItemStack(ModBlocks.PIECE_ITEMS[piece], 8), piece);
					}
					command(s, "tp @a 0.5 -58.6 2.0 facing 0.5 -59.2 5.5");
					return "";
				});
				if (!result.isEmpty()) {
					fail(mc, result);
					return;
				}
				log("board placed and filled");
				wait = 60;
				stage = 2;
			}
			case 2 -> {
				shot(mc, "1_world");
				wait = 10;
				stage = 3;
			}
			case 3 -> {
				ChessBoardBlock.openScreen.accept(BOARD);
				if (!(currentScreen(mc) instanceof ChessScreen)) {
					fail(mc, "chess screen did not open");
					return;
				}
				log("screen opened");
				wait = 30;
				stage = 4;
			}
			case 4 -> {
				shot(mc, "2_setup_screen");
				ChessActionPayload.sendToServer(new ChessActionPayload(BOARD, ChessActionPayload.START, 0, 0, 0,
						"opp=BOT;color=0;eloW=1200;eloB=600;base=600;inc=0;undo=1;hints=1"));
				wait = 20;
				stage = 5;
			}
			case 5 -> {
				ChessActionPayload.sendToServer(new ChessActionPayload(BOARD, ChessActionPayload.MOVE,
						Move.parseSquare("e2"), Move.parseSquare("e4"), 0));
				wait = 20;
				stage = 6;
			}
			case 6 -> {
				int moves = onServer(server, s -> s.overworld().getBlockEntity(BOARD) instanceof ChessBoardBlockEntity be
						&& be.game() != null ? be.game().uciMoves().size() : -1);
				if (moves >= 2) {
					String line = onServer(server, s -> {
						ChessGame g = ((ChessBoardBlockEntity) s.overworld().getBlockEntity(BOARD)).game();
						return String.join(" ", g.uciMoves());
					});
					log("network ok, moves: " + line);
					wait = 30;
					stage = 7;
				} else if (ticks > 20 * 100) {
					fail(mc, "no bot reply (moves=" + moves + ")");
				}
			}
			case 7 -> {
				shot(mc, "3_game_screen");
				wait = 10;
				stage = 8;
			}
			case 8 -> {
				closeScreen(mc);
				wait = 20;
				stage = 9;
			}
			case 9 -> {
				shot(mc, "4_world_game");
				wait = 20;
				stage = 10;
			}
			case 10 -> {
				log("SMOKE TEST PASSED");
				stage = 99;
				mc.stop();
			}
			default -> {
			}
		}
	}

	private static net.minecraft.client.gui.screens.@org.jspecify.annotations.Nullable Screen currentScreen(Minecraft mc) {
//#if mc>=26.2
//$		return mc.gui.screen();
//#else
		return mc.screen;
//#endif
	}

	private static void closeScreen(Minecraft mc) {
//#if mc>=26.2
//$		mc.gui.setScreen(null);
//#else
		mc.setScreen(null);
//#endif
	}
}
