package com.yossi.chesscraft.client;

import com.yossi.chesscraft.ChessCraft;
import com.yossi.chesscraft.chess.ChessGame;
import com.yossi.chesscraft.chess.Position;
import com.yossi.chesscraft.chess.engine.GameReview;
import com.yossi.chesscraft.chess.engine.Searcher;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * A post-game review computed on this client in a background thread. The latest session is
 * kept so reopening the board shows the finished review instantly.
 */
public final class ReviewSession {
	private static final long MS_PER_POSITION = 260;
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "ChessCraft-Review");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});
	private static @Nullable Searcher searcher;
	private static @Nullable ReviewSession current;

	public final ChessGame game;
	/** positions.get(k) is the position after k moves. */
	public final List<Position> positions = new ArrayList<>();
	public final int total;
	public volatile int done;
	public volatile GameReview.@Nullable Result result;
	public volatile boolean failed;
	private final String key;
	private final AtomicBoolean cancel = new AtomicBoolean();

	private ReviewSession(String key, ChessGame game) {
		this.key = key;
		this.game = game;
		Position p = Position.fromFen(game.startFen());
		positions.add(p.copy());
		for (int m : game.moves()) {
			p.makeMove(m);
			positions.add(p.copy());
		}
		this.total = positions.size();
	}

	private static String keyOf(BlockPos pos, long gameId, ChessGame game) {
		return pos.asLong() + ":" + gameId + ":" + String.join(" ", game.uciMoves());
	}

	/** The review for this game if one was started. */
	public static @Nullable ReviewSession existing(BlockPos pos, long gameId, ChessGame game) {
		ReviewSession s = current;
		return s != null && s.key.equals(keyOf(pos, gameId, game)) ? s : null;
	}

	/** Returns the review for this game, starting the analysis when needed. */
	public static ReviewSession start(BlockPos pos, long gameId, ChessGame game) {
		ReviewSession s = existing(pos, gameId, game);
		if (s != null) return s;
		if (current != null) current.cancel.set(true);
		ReviewSession session = new ReviewSession(keyOf(pos, gameId, game), ChessGame.replay(game.startFen(), game.uciMoves()));
		current = session;
		EXECUTOR.submit(() -> {
			try {
				if (searcher == null) searcher = new Searcher(20);
				session.result = GameReview.analyze(session.game, searcher, MS_PER_POSITION, d -> session.done = d, session.cancel);
				if (session.result == null) session.failed = true;
			} catch (Throwable t) {
				ChessCraft.LOGGER.error("Game review failed", t);
				session.failed = true;
			}
		});
		return session;
	}

	public boolean ready() {
		return result != null;
	}
}
