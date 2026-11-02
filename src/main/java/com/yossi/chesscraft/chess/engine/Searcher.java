package com.yossi.chesscraft.chess.engine;

import com.yossi.chesscraft.chess.ChessGame;
import com.yossi.chesscraft.chess.Move;
import com.yossi.chesscraft.chess.Piece;
import com.yossi.chesscraft.chess.Position;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Alpha-beta searcher: iterative deepening with aspiration windows, PVS, transposition
 * table, null-move pruning, reverse futility, razoring, late move reductions and pruning,
 * SEE-based pruning, killer/counter/history ordering and a quiescence search.
 * One instance is used by one thread at a time.
 */
public final class Searcher {
	public static final int INF = 32000;
	public static final int MATE = 31000;
	public static final int MATE_BOUND = MATE - 500;
	private static final int MAX_PLY = 100;
	private static final int NO_EVAL = -INF - 1;

	private static final int[] SEE_VALUE = {100, 320, 330, 500, 950, 20000};
	private static final int[][] LMR = new int[64][64];

	static {
		for (int d = 1; d < 64; d++) {
			for (int m = 1; m < 64; m++) {
				LMR[d][m] = (int) (0.8 + Math.log(d) * Math.log(m) / 2.2);
			}
		}
	}

	/** Search limits; unset values mean unlimited. */
	public static final class Limits {
		public int maxDepth = MAX_PLY - 4;
		public long maxNodes = Long.MAX_VALUE;
		/** Stop starting new iterations after this many ms. */
		public long softTimeMs = Long.MAX_VALUE;
		/** Abort the running iteration after this many ms. */
		public long hardTimeMs = Long.MAX_VALUE;
	}

	public static final class Result {
		public int bestMove = Move.NONE;
		public int score;
		public int depth;
		public long nodes;
		public long timeMs;
		public int[] pv = new int[0];
	}

	private final TranspositionTable tt;
	private final Evaluator evaluator = new Evaluator();
	private Position pos;

	private final int[][] moves = new int[MAX_PLY + 2][256];
	private final int[][] scores = new int[MAX_PLY + 2][256];
	private final int[][] quiets = new int[MAX_PLY + 2][64];
	private final int[][] killers = new int[MAX_PLY + 2][2];
	private final int[][] history = new int[Piece.COUNT][64];
	private final int[][] counters = new int[Piece.COUNT][64];
	private final int[] evalStack = new int[MAX_PLY + 2];
	private final int[][] pv = new int[MAX_PLY + 2][MAX_PLY + 2];
	private final int[] pvLen = new int[MAX_PLY + 2];
	private final int[] seeGain = new int[40];

	private int excludedRoot = Move.NONE;
	private long nodes;
	private long maxNodes;
	private long hardDeadline;
	private boolean aborted;
	private boolean canAbort;
	private AtomicBoolean cancel;

	public Searcher() {
		this(20);
	}

	public Searcher(int ttLog2) {
		tt = new TranspositionTable(ttLog2);
	}

	public void clearTable() {
		tt.clear();
	}

	public Evaluator evaluator() {
		return evaluator;
	}

	/**
	 * Best line when one root move is not allowed (for "second best" analysis). Returns a result
	 * without a move when the excluded move was the only legal one.
	 */
	public Result searchExcluding(Position root, Limits limits, int excluded, AtomicBoolean cancelFlag) {
		excludedRoot = excluded;
		try {
			return search(root, limits, cancelFlag);
		} finally {
			excludedRoot = Move.NONE;
		}
	}

	/** Iterative deepening search of the given position (the position is not modified). */
	public Result search(Position root, Limits limits, AtomicBoolean cancelFlag) {
		long start = System.currentTimeMillis();
		prepare(root, limits, cancelFlag, start);
		Result result = new Result();
		int[] legal = pos.legalMoves();
		if (excludedRoot != Move.NONE) {
			legal = java.util.Arrays.stream(legal).filter(m -> m != excludedRoot).toArray();
			if (legal.length == 0) {
				result.score = -INF;
				return result;
			}
		}
		if (legal.length == 0) {
			result.score = pos.inCheck() ? -MATE : 0;
			return result;
		}
		result.bestMove = legal[0];

		int prevScore = 0;
		for (int depth = 1; depth <= limits.maxDepth; depth++) {
			int score;
			int delta = 22;
			int alpha = -INF, beta = INF;
			if (depth >= 5 && Math.abs(prevScore) < MATE_BOUND) {
				alpha = prevScore - delta;
				beta = prevScore + delta;
			}
			while (true) {
				score = negamax(depth, alpha, beta, 0, true, false);
				if (aborted) break;
				if (score <= alpha) {
					beta = (alpha + beta) / 2;
					alpha = Math.max(-INF, score - delta);
				} else if (score >= beta) {
					beta = Math.min(INF, score + delta);
				} else {
					break;
				}
				delta += delta / 2 + 10;
				if (delta > 900) {
					alpha = -INF;
					beta = INF;
				}
			}
			if (aborted) break;

			prevScore = score;
			result.score = score;
			result.depth = depth;
			if (pvLen[0] > 0 && pv[0][0] != Move.NONE) {
				result.bestMove = pv[0][0];
				result.pv = java.util.Arrays.copyOf(pv[0], pvLen[0]);
			}
			canAbort = true;

			long elapsed = System.currentTimeMillis() - start;
			if (elapsed >= limits.softTimeMs || nodes >= maxNodes) break;
			if (legal.length == 1 && depth >= 4) break;
			if (Math.abs(score) >= MATE_BOUND && depth >= (MATE - Math.abs(score)) + 4) break;
		}
		result.nodes = nodes;
		result.timeMs = System.currentTimeMillis() - start;
		return result;
	}

	/**
	 * Exact scores for every legal root move at a fixed depth (used for weaker levels).
	 * Returns pairs in {@code outMoves}/{@code outScores}; the count is returned.
	 */
	public int scoreRootMoves(Position root, int depth, int[] outMoves, int[] outScores, AtomicBoolean cancelFlag) {
		Limits limits = new Limits();
		limits.maxDepth = depth;
		// Warm the table and history with a normal search first.
		search(root, limits, cancelFlag);
		prepare(root, limits, cancelFlag, System.currentTimeMillis());
		canAbort = false;
		int[] legal = pos.legalMoves();
		for (int i = 0; i < legal.length; i++) {
			pos.makeMove(legal[i]);
			int s = depth <= 1 ? -quiesce(-INF, INF, 1) : -negamax(depth - 1, -INF, INF, 1, true, true);
			pos.unmakeMove();
			outMoves[i] = legal[i];
			outScores[i] = s;
		}
		return legal.length;
	}

	private void prepare(Position root, Limits limits, AtomicBoolean cancelFlag, long start) {
		pos = root.copy();
		cancel = cancelFlag;
		nodes = 0;
		maxNodes = limits.maxNodes;
		hardDeadline = limits.hardTimeMs == Long.MAX_VALUE ? Long.MAX_VALUE : start + limits.hardTimeMs;
		aborted = false;
		canAbort = false;
		tt.newSearch();
		for (int[] k : killers) {
			k[0] = k[1] = Move.NONE;
		}
		for (int[] h : history) {
			for (int i = 0; i < 64; i++) h[i] /= 4;
		}
	}

	private void checkLimits() {
		if (!canAbort) return;
		if ((cancel != null && cancel.get()) || nodes >= maxNodes || System.currentTimeMillis() >= hardDeadline) {
			aborted = true;
		}
	}

	private static int toTT(int score, int ply) {
		if (score >= MATE_BOUND) return score + ply;
		if (score <= -MATE_BOUND) return score - ply;
		return score;
	}

	private static int fromTT(int score, int ply) {
		if (score >= MATE_BOUND) return score - ply;
		if (score <= -MATE_BOUND) return score + ply;
		return score;
	}

	private int negamax(int depth, int alpha, int beta, int ply, boolean pvNode, boolean allowNull) {
		boolean root = ply == 0;
		pvLen[ply] = ply;
		if ((++nodes & 1023) == 0) checkLimits();
		if (aborted) return 0;

		if (!root) {
			if (pos.halfmove >= 100 || pos.isRepetition() || ChessGame.isInsufficientMaterial(pos)) return 0;
			alpha = Math.max(alpha, -MATE + ply);
			beta = Math.min(beta, MATE - ply - 1);
			if (alpha >= beta) return alpha;
		}

		boolean inCheck = pos.inCheck();
		if (inCheck) depth++;
		if (depth <= 0) return quiesce(alpha, beta, ply);
		if (ply >= MAX_PLY) return evaluator.evaluate(pos);

		int ttMove = Move.NONE;
		int ttEval = NO_EVAL;
		if (tt.probe(pos.hash)) {
			ttMove = tt.move;
			ttEval = tt.eval;
			int ttScore = fromTT(tt.score, ply);
			if (!pvNode && tt.depth >= depth) {
				if (tt.bound == TranspositionTable.EXACT
						|| (tt.bound == TranspositionTable.LOWER && ttScore >= beta)
						|| (tt.bound == TranspositionTable.UPPER && ttScore <= alpha)) {
					return ttScore;
				}
			}
		}

		int staticEval = NO_EVAL;
		if (!inCheck) staticEval = ttEval != NO_EVAL && ttEval > -INF ? ttEval : evaluator.evaluate(pos);
		evalStack[ply] = staticEval;
		boolean improving = !inCheck && ply >= 2 && evalStack[ply - 2] != NO_EVAL && staticEval > evalStack[ply - 2];

		if (!pvNode && !inCheck) {
			// Reverse futility pruning
			if (depth <= 7 && Math.abs(beta) < MATE_BOUND
					&& staticEval - (80 * depth - (improving ? 50 : 0)) >= beta) {
				return staticEval;
			}
			// Null move pruning
			if (allowNull && depth >= 3 && staticEval >= beta && pos.hasNonPawnMaterial(pos.side)) {
				int r = 3 + depth / 4 + Math.min(3, (staticEval - beta) / 200);
				pos.makeNullMove();
				int s = -negamax(depth - 1 - r, -beta, -beta + 1, ply + 1, false, false);
				pos.unmakeNullMove();
				if (aborted) return 0;
				if (s >= beta) return s >= MATE_BOUND ? beta : s;
			}
			// Razoring
			if (depth <= 3 && staticEval + 260 * depth <= alpha) {
				int q = quiesce(alpha, alpha + 1, ply);
				if (aborted) return 0;
				if (q <= alpha) return q;
			}
		}

		// Internal iterative reduction: no hash move means ordering is poor here.
		if (ttMove == Move.NONE && depth >= 5) depth--;

		int[] list = moves[ply];
		int[] sc = scores[ply];
		int n = pos.generate(list, 0, false);
		scoreMoves(list, sc, n, ttMove, ply, false);

		int origAlpha = alpha;
		int bestScore = -INF;
		int bestMove = Move.NONE;
		int legal = 0, quietCount = 0, nQuiets = 0;
		int prevMove = pos.lastMove();
		int prevPiece = prevMove != Move.NONE ? pos.board[Move.to(prevMove)] : Piece.NONE;

		for (int i = 0; i < n; i++) {
			pickBest(list, sc, i, n);
			int m = list[i];
			if (root && m == excludedRoot) continue;
			boolean quiet = Move.isQuiet(m);
			int piece = pos.board[Move.from(m)];

			if (!root && bestScore > -MATE_BOUND && !inCheck) {
				if (quiet) {
					int lmpLimit = (3 + depth * depth) / (improving ? 1 : 2);
					if (depth <= 5 && quietCount >= lmpLimit) continue;
					if (depth <= 4 && staticEval + 100 + 110 * depth <= alpha) continue;
				} else if (depth <= 6 && sc[i] < 0 && see(m) < -95 * depth) {
					continue;
				}
			}

			pos.makeMove(m);
			if (pos.leftKingInCheck()) {
				pos.unmakeMove();
				continue;
			}
			legal++;
			if (quiet) quietCount++;
			boolean givesCheck = pos.inCheck();
			int newDepth = depth - 1;
			int score;
			if (legal == 1) {
				score = -negamax(newDepth, -beta, -alpha, ply + 1, pvNode, true);
			} else {
				int r = 0;
				if (depth >= 3 && quiet && !inCheck && !givesCheck && legal > 2) {
					r = LMR[Math.min(depth, 63)][Math.min(legal, 63)];
					if (pvNode) r--;
					if (!improving) r++;
					if (m == killers[ply][0] || m == killers[ply][1]) r--;
					r -= history[piece][Move.to(m)] / 6000;
					r = Math.max(0, Math.min(newDepth - 1, r));
				}
				score = -negamax(newDepth - r, -alpha - 1, -alpha, ply + 1, false, true);
				if (score > alpha && r > 0) score = -negamax(newDepth, -alpha - 1, -alpha, ply + 1, false, true);
				if (pvNode && score > alpha && score < beta) score = -negamax(newDepth, -beta, -alpha, ply + 1, true, true);
			}
			pos.unmakeMove();
			if (aborted) return 0;

			if (score > bestScore) {
				bestScore = score;
				if (score > alpha) {
					alpha = score;
					bestMove = m;
					pv[ply][ply] = m;
					for (int j = ply + 1; j < pvLen[ply + 1]; j++) pv[ply][j] = pv[ply + 1][j];
					pvLen[ply] = Math.max(ply + 1, pvLen[ply + 1]);
					if (score >= beta) {
						if (quiet) {
							if (killers[ply][0] != m) {
								killers[ply][1] = killers[ply][0];
								killers[ply][0] = m;
							}
							int bonus = Math.min(1600, depth * depth * 16);
							updateHistory(piece, Move.to(m), bonus);
							for (int q = 0; q < nQuiets; q++) {
								int qm = quiets[ply][q];
								updateHistory(pos.board[Move.from(qm)], Move.to(qm), -bonus);
							}
							if (prevPiece != Piece.NONE) counters[prevPiece][Move.to(prevMove)] = m;
						}
						break;
					}
				}
			}
			if (quiet && nQuiets < 64) quiets[ply][nQuiets++] = m;
		}

		if (legal == 0) return inCheck ? -MATE + ply : 0;

		int bound = bestScore >= beta ? TranspositionTable.LOWER
				: bestScore > origAlpha ? TranspositionTable.EXACT : TranspositionTable.UPPER;
		tt.store(pos.hash, bestMove, toTT(bestScore, ply), staticEval == NO_EVAL ? -INF : staticEval, depth, bound);
		return bestScore;
	}

	private void updateHistory(int piece, int to, int bonus) {
		int h = history[piece][to];
		history[piece][to] = h + bonus - h * Math.abs(bonus) / 16384;
	}

	private int quiesce(int alpha, int beta, int ply) {
		pvLen[ply] = ply;
		if ((++nodes & 1023) == 0) checkLimits();
		if (aborted) return 0;
		if (ply >= MAX_PLY) return evaluator.evaluate(pos);

		boolean inCheck = pos.inCheck();
		int ttMove = Move.NONE;
		int ttEval = NO_EVAL;
		if (tt.probe(pos.hash)) {
			ttMove = tt.move;
			ttEval = tt.eval;
			int ttScore = fromTT(tt.score, ply);
			if (tt.bound == TranspositionTable.EXACT
					|| (tt.bound == TranspositionTable.LOWER && ttScore >= beta)
					|| (tt.bound == TranspositionTable.UPPER && ttScore <= alpha)) {
				return ttScore;
			}
		}

		int best;
		int standPat = 0;
		if (!inCheck) {
			standPat = ttEval != NO_EVAL && ttEval > -INF ? ttEval : evaluator.evaluate(pos);
			if (standPat >= beta) return standPat;
			if (standPat > alpha) alpha = standPat;
			best = standPat;
		} else {
			best = -INF;
		}

		int[] list = moves[ply];
		int[] sc = scores[ply];
		int n = pos.generate(list, 0, !inCheck);
		scoreMoves(list, sc, n, ttMove, ply, true);
		int legal = 0;
		for (int i = 0; i < n; i++) {
			pickBest(list, sc, i, n);
			int m = list[i];
			if (!inCheck) {
				if (!Move.isPromotion(m)) {
					int victim = Move.isEnPassant(m) ? SEE_VALUE[Piece.PAWN] : SEE_VALUE[Piece.type(pos.board[Move.to(m)])];
					if (standPat + victim + 200 <= alpha) continue;
				}
				if (see(m) < 0) continue;
			}
			pos.makeMove(m);
			if (pos.leftKingInCheck()) {
				pos.unmakeMove();
				continue;
			}
			legal++;
			int score = -quiesce(-beta, -alpha, ply + 1);
			pos.unmakeMove();
			if (aborted) return 0;
			if (score > best) {
				best = score;
				if (score > alpha) {
					alpha = score;
					if (score >= beta) break;
				}
			}
		}
		if (inCheck && legal == 0) return -MATE + ply;
		return best;
	}

	private void scoreMoves(int[] list, int[] sc, int n, int ttMove, int ply, boolean qs) {
		int prevMove = pos.lastMove();
		int counter = Move.NONE;
		if (!qs && prevMove != Move.NONE) {
			int prevPiece = pos.board[Move.to(prevMove)];
			if (prevPiece != Piece.NONE) counter = counters[prevPiece][Move.to(prevMove)];
		}
		for (int i = 0; i < n; i++) {
			int m = list[i];
			if (m == ttMove) {
				sc[i] = 3_000_000;
			} else if (Move.isCapture(m)) {
				int attacker = Piece.type(pos.board[Move.from(m)]);
				int victim = Move.isEnPassant(m) ? Piece.PAWN : Piece.type(pos.board[Move.to(m)]);
				int mvvLva = SEE_VALUE[victim] * 10 - attacker;
				boolean good = qs || SEE_VALUE[victim] >= SEE_VALUE[attacker] || see(m) >= 0;
				sc[i] = (good ? 2_000_000 : -2_000_000) + mvvLva + (Move.isPromotion(m) ? 9000 : 0);
			} else if (Move.isPromotion(m)) {
				sc[i] = Move.promoType(m) == Piece.QUEEN ? 1_900_000 : -2_500_000;
			} else if (m == killers[ply][0]) {
				sc[i] = 1_800_000;
			} else if (m == killers[ply][1]) {
				sc[i] = 1_700_000;
			} else if (m == counter) {
				sc[i] = 1_600_000;
			} else {
				sc[i] = history[pos.board[Move.from(m)]][Move.to(m)];
			}
		}
	}

	private static void pickBest(int[] list, int[] sc, int i, int n) {
		int best = i;
		for (int j = i + 1; j < n; j++) {
			if (sc[j] > sc[best]) best = j;
		}
		if (best != i) {
			int t = list[i];
			list[i] = list[best];
			list[best] = t;
			t = sc[i];
			sc[i] = sc[best];
			sc[best] = t;
		}
	}

	int see(int move) {
		return see(pos, move, seeGain);
	}

	/** Static exchange evaluation of a capture (or quiet move) on its target square; gain needs 40 slots. */
	public static int see(Position pos, int move, int[] seeGain) {
		if (Move.isCastle(move)) return 0;
		int from = Move.from(move), to = Move.to(move);
		long occ = pos.all;
		int target;
		if (Move.isEnPassant(move)) {
			target = Piece.PAWN;
			occ ^= 1L << (to + (pos.side == Piece.WHITE ? -8 : 8));
		} else {
			target = pos.board[to] == Piece.NONE ? -1 : Piece.type(pos.board[to]);
		}
		int attackerType = Piece.type(pos.board[from]);
		int d = 0;
		seeGain[0] = target < 0 ? 0 : SEE_VALUE[target];
		if (Move.isPromotion(move)) {
			attackerType = Move.promoType(move);
			seeGain[0] += SEE_VALUE[attackerType] - SEE_VALUE[Piece.PAWN];
		}
		long fromSet = 1L << from;
		long attackers = pos.attackersTo(to, occ);
		int stm = pos.side;
		while (true) {
			d++;
			seeGain[d] = SEE_VALUE[attackerType] - seeGain[d - 1];
			if (Math.max(-seeGain[d - 1], seeGain[d]) < 0) break;
			occ ^= fromSet;
			attackers = pos.attackersTo(to, occ) & occ;
			stm ^= 1;
			long mine = attackers & pos.occ[stm];
			if (mine == 0) break;
			fromSet = 0;
			for (int t = Piece.PAWN; t <= Piece.KING; t++) {
				long b = mine & pos.bb[Piece.make(stm, t)];
				if (b != 0) {
					if (t == Piece.KING && (attackers & pos.occ[stm ^ 1]) != 0) break;
					fromSet = b & -b;
					attackerType = t;
					break;
				}
			}
			if (fromSet == 0 || d >= 38) break;
		}
		while (--d > 0) seeGain[d - 1] = -Math.max(-seeGain[d - 1], seeGain[d]);
		return seeGain[0];
	}
}
