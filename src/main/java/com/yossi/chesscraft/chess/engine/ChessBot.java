package com.yossi.chesscraft.chess.engine;

import com.yossi.chesscraft.chess.ChessGame;
import com.yossi.chesscraft.chess.Move;
import com.yossi.chesscraft.chess.Position;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Picks moves at a target playing strength from 100 to 3200 Elo.
 * Below 2000 the engine scores every root move at a shallow depth and chooses with
 * Gaussian noise plus occasional random moves; from 2000 up it plays the best move found
 * under growing node and time budgets. The Elo labels are approximate.
 */
public final class ChessBot {
	public static final int MIN_ELO = 100;
	public static final int MAX_ELO = 3200;

	/** Strength settings for one level. */
	public record Level(int elo, int depth, double noise, double randomMove, long maxNodes, long timeMs) {
		boolean usesRootScoring() {
			return elo < 2000;
		}
	}

	public record Choice(int move, int score, int depth, long nodes, boolean fromBook) {
	}

	private static final int[][] SHALLOW = {
			// elo, depth, noise (cp), random-move per-mille
			{100, 1, 500, 450},
			{400, 1, 300, 220},
			{700, 2, 190, 100},
			{1000, 2, 120, 50},
			{1300, 3, 75, 25},
			{1600, 4, 45, 10},
			{1900, 5, 25, 0},
	};
	private static final long[][] DEEP = {
			// elo, nodes, ms
			{2000, 15_000, 400},
			{2200, 50_000, 700},
			{2400, 150_000, 1000},
			{2600, 500_000, 1500},
			{2800, 1_500_000, 2000},
			{3000, 5_000_000, 3000},
			{3200, Long.MAX_VALUE, 5000},
	};

	private final Searcher searcher;

	public ChessBot() {
		searcher = new Searcher(20);
	}

	public static Level level(int elo) {
		elo = Math.max(MIN_ELO, Math.min(MAX_ELO, elo));
		if (elo < 2000) {
			int[] lo = SHALLOW[0], hi = SHALLOW[SHALLOW.length - 1];
			for (int i = 0; i + 1 < SHALLOW.length; i++) {
				if (elo >= SHALLOW[i][0] && elo <= SHALLOW[i + 1][0]) {
					lo = SHALLOW[i];
					hi = SHALLOW[i + 1];
					break;
				}
			}
			if (elo > hi[0]) lo = hi;
			double t = hi[0] == lo[0] ? 0 : (elo - lo[0]) / (double) (hi[0] - lo[0]);
			int depth = t < 0.5 ? lo[1] : hi[1];
			double noise = lo[2] + (hi[2] - lo[2]) * t;
			double random = (lo[3] + (hi[3] - lo[3]) * t) / 1000.0;
			return new Level(elo, depth, noise, random, Long.MAX_VALUE, 0);
		}
		for (int i = 0; i + 1 < DEEP.length; i++) {
			if (elo <= DEEP[i + 1][0]) {
				long[] lo = DEEP[i], hi = DEEP[i + 1];
				double t = (elo - lo[0]) / (double) (hi[0] - lo[0]);
				long nodes = hi[1] == Long.MAX_VALUE
						? (t >= 1 ? Long.MAX_VALUE : (long) (lo[1] * Math.pow(4, t)))
						: (long) (lo[1] * Math.pow(hi[1] / (double) lo[1], t));
				long ms = (long) (lo[2] + (hi[2] - lo[2]) * t);
				return new Level(elo, 99, 0, 0, nodes, ms);
			}
		}
		return new Level(MAX_ELO, 99, 0, 0, Long.MAX_VALUE, 5000);
	}

	/**
	 * Chooses a move for the side to move.
	 *
	 * @param clockBudgetMs time this move may take from the game clock, or a negative value for no clock
	 */
	public Choice choose(ChessGame game, int elo, long clockBudgetMs, Random rnd, AtomicBoolean cancel) {
		Position pos = game.position();
		int[] legal = pos.legalMoves();
		if (legal.length == 0) return new Choice(Move.NONE, 0, 0, 0, false);
		Level lv = level(elo);

		if (game.startFen().equals(Position.START_FEN) && game.moves().size() < 18 && (elo >= 600 || rnd.nextBoolean())) {
			int book = OpeningBook.pick(pos, game.uciMoves(), rnd);
			if (book != Move.NONE) return new Choice(book, 0, 0, 0, true);
		}
		if (legal.length == 1) return new Choice(legal[0], 0, 0, 0, false);

		if (lv.usesRootScoring()) {
			if (rnd.nextDouble() < lv.randomMove()) {
				return new Choice(legal[rnd.nextInt(legal.length)], 0, 0, 0, false);
			}
			int[] ms = new int[256], sc = new int[256];
			int n = searcher.scoreRootMoves(pos, lv.depth(), ms, sc, cancel);
			int best = 0;
			double bestNoisy = Double.NEGATIVE_INFINITY;
			for (int i = 0; i < n; i++) {
				double noisy = sc[i] + rnd.nextGaussian() * lv.noise();
				// Even weak bots take a forced mate they can see and avoid walking into mate.
				if (sc[i] >= Searcher.MATE_BOUND) noisy += 100_000;
				if (sc[i] <= -Searcher.MATE_BOUND && lv.elo() >= 800) noisy -= 100_000;
				if (noisy > bestNoisy) {
					bestNoisy = noisy;
					best = i;
				}
			}
			return new Choice(ms[best], sc[best], lv.depth(), 0, false);
		}

		Searcher.Limits limits = new Searcher.Limits();
		limits.maxNodes = lv.maxNodes();
		long time = lv.timeMs();
		if (clockBudgetMs >= 0) time = Math.min(time, Math.max(50, clockBudgetMs));
		limits.softTimeMs = time / 2;
		limits.hardTimeMs = time;
		Searcher.Result r = searcher.search(pos, limits, cancel);
		return new Choice(r.bestMove, r.score, r.depth, r.nodes, false);
	}

	/** Search at full strength for a fixed time (used for hints). */
	public Searcher.Result analyse(Position pos, long timeMs, AtomicBoolean cancel) {
		Searcher.Limits limits = new Searcher.Limits();
		limits.softTimeMs = timeMs / 2;
		limits.hardTimeMs = timeMs;
		return searcher.search(pos, limits, cancel);
	}

	/** How long to think given remaining clock time and increment. */
	public static long clockBudget(long remainingMs, long incrementMs, int moveNumber) {
		int movesLeft = Math.max(20, 45 - moveNumber / 2);
		long budget = remainingMs / movesLeft + incrementMs * 3 / 4;
		return Math.max(50, Math.min(budget, remainingMs / 3));
	}
}
