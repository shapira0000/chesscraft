package com.yossi.chesscraft.chess.engine;

import com.yossi.chesscraft.chess.ChessGame;
import com.yossi.chesscraft.chess.Move;
import com.yossi.chesscraft.chess.Notation;
import com.yossi.chesscraft.chess.Piece;
import com.yossi.chesscraft.chess.Position;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * Post-game review: every position is analysed by the engine, evaluations are turned into
 * winning chances, and each move is classified (brilliant ... blunder) by how much winning
 * chance it gave away. Accuracy uses the same win-chance loss per move.
 */
public final class GameReview {
	public enum Kind {
		BRILLIANT("Brilliant"), GREAT("Great"), BEST("Best"), EXCELLENT("Excellent"), GOOD("Good"), BOOK("Book"),
		INACCURACY("Inaccuracy"), MISTAKE("Mistake"), MISS("Miss"), BLUNDER("Blunder");

		public final String label;

		Kind(String label) {
			this.label = label;
		}
	}

	/** Centipawn value used for forced mates (minus the distance to mate). */
	public static final int MATE_CP = 10_000;

	public static final class MoveInfo {
		public int ply;
		public int move;
		public String san;
		public int mover;
		public Kind kind;
		public int bestMove;
		public String bestSan;
		/** Win-chance (0..100) the mover gave away with this move. */
		public double winLoss;
	}

	public static final class Result {
		/** Evaluation in centipawns from White's point of view, one per position (start .. end). */
		public int[] whiteEval;
		public final List<MoveInfo> moves = new ArrayList<>();
		public final double[] accuracy = new double[2];
		public final int[][] counts = new int[2][Kind.values().length];
	}

	private GameReview() {
	}

	/** Win chance in percent (0..100) for a centipawn score, as used by common chess sites. */
	public static double winChance(int cp) {
		int c = Math.max(-1000, Math.min(1000, cp));
		return 50 + 50 * (2 / (1 + Math.exp(-0.00368208 * c)) - 1);
	}

	/** Search score (side to move) to centipawns, mapping mates to +/-(MATE_CP - plies). */
	static int toCp(int score) {
		if (score >= Searcher.MATE_BOUND) return MATE_CP - (Searcher.MATE - score);
		if (score <= -Searcher.MATE_BOUND) return -MATE_CP + (Searcher.MATE + score);
		return score;
	}

	public static boolean isMate(int cp) {
		return Math.abs(cp) >= MATE_CP - 500;
	}

	/** "+1.4", "-0.3", "M3" or "-M2" from White's point of view. */
	public static String format(int whiteCp) {
		if (isMate(whiteCp)) {
			int plies = MATE_CP - Math.abs(whiteCp);
			int moves = Math.max(1, (plies + 1) / 2);
			return (whiteCp > 0 ? "M" : "-M") + moves;
		}
		return String.format("%s%.1f", whiteCp >= 0 ? "+" : "", whiteCp / 100.0);
	}

	/**
	 * Analyses a finished game.
	 *
	 * @param msPerPosition engine time for each position (a second, shorter search checks "only moves")
	 * @param progress receives the number of positions done
	 */
	public static Result analyze(ChessGame game, Searcher searcher, long msPerPosition, IntConsumer progress, AtomicBoolean cancel) {
		Position pos = Position.fromFen(game.startFen());
		List<Integer> moves = game.moves();
		int n = moves.size();
		int[] stmCp = new int[n + 1];
		int[] best = new int[n + 1];
		Result out = new Result();
		out.whiteEval = new int[n + 1];

		Position cur = pos.copy();
		List<Position> positions = new ArrayList<>();
		for (int i = 0; i <= n; i++) {
			positions.add(cur.copy());
			if (cancel != null && cancel.get()) return null;
			Position p = positions.get(i);
			int[] legal = p.legalMoves();
			if (legal.length == 0) {
				stmCp[i] = p.inCheck() ? -MATE_CP : 0;
				best[i] = Move.NONE;
			} else {
				Searcher.Result r = searcher.search(p, limits(msPerPosition), cancel);
				stmCp[i] = toCp(r.score);
				best[i] = r.bestMove;
				if (ChessGame.isInsufficientMaterial(p)) stmCp[i] = 0;
			}
			out.whiteEval[i] = p.side == Piece.WHITE ? stmCp[i] : -stmCp[i];
			if (progress != null) progress.accept(i + 1);
			if (i < n) cur.makeMove(moves.get(i));
		}

		boolean inBook = game.startFen().equals(Position.START_FEN);
		List<String> history = new ArrayList<>();
		int[] gain = new int[40];
		Kind previous = null;
		for (int i = 0; i < n; i++) {
			Position p = positions.get(i);
			int move = moves.get(i);
			MoveInfo info = new MoveInfo();
			info.ply = i;
			info.move = move;
			info.mover = p.side;
			info.san = game.sanMoves().get(i);
			info.bestMove = best[i];
			info.bestSan = best[i] == Move.NONE ? "" : Notation.toSan(p, best[i]);

			double before = winChance(stmCp[i]);
			double after = winChance(-stmCp[i + 1]);
			// Engine noise between depths can make a move look better than "best"; never count a gain.
			info.winLoss = Math.max(0, before - after);
			boolean mateLost = stmCp[i] >= MATE_CP - 500 && -stmCp[i + 1] < MATE_CP - 500 && -stmCp[i + 1] < 300;

			String uci = Move.toUci(move);
			inBook = inBook && OpeningBook.contains(history, uci);
			history.add(uci);

			Kind kind;
			if (inBook) {
				kind = Kind.BOOK;
			} else if (move == best[i] || info.winLoss < 0.6) {
				kind = Kind.BEST;
			} else if (info.winLoss < 2.5 && !mateLost) {
				kind = Kind.EXCELLENT;
			} else if (info.winLoss < 5 && !mateLost) {
				kind = Kind.GOOD;
			} else if (info.winLoss < 10 && !mateLost) {
				kind = Kind.INACCURACY;
			} else if (info.winLoss < 20 && !mateLost) {
				kind = Kind.MISTAKE;
			} else {
				kind = Kind.BLUNDER;
			}

			if (kind == Kind.BEST || kind == Kind.EXCELLENT) {
				// A sacrifice that keeps the advantage; forced mates count even from a winning position.
				if (isSacrifice(p, move, gain) && after >= 45 && (before < 97 || isMate(-stmCp[i + 1]))) {
					kind = Kind.BRILLIANT;
				} else if (kind == Kind.BEST && before > 8 && before < 92 && p.legalMoves().length > 1) {
					Searcher.Result second = searcher.searchExcluding(p, limits(msPerPosition / 2), best[i], cancel);
					if (second.bestMove != Move.NONE && before - winChance(toCp(second.score)) >= 18) kind = Kind.GREAT;
				}
			}
			// Missing the punishment of the opponent's mistake.
			if ((kind == Kind.INACCURACY || kind == Kind.MISTAKE || kind == Kind.BLUNDER)
					&& (previous == Kind.MISTAKE || previous == Kind.BLUNDER) && before >= 65 && after >= 40) {
				kind = Kind.MISS;
			}
			info.kind = kind;
			previous = kind;
			out.moves.add(info);
			out.counts[info.mover][kind.ordinal()]++;
		}

		for (int c = 0; c < 2; c++) {
			double sum = 0;
			int count = 0;
			for (MoveInfo m : out.moves) {
				if (m.mover != c) continue;
				double acc = m.kind == Kind.BOOK ? 100 : 103.1668 * Math.exp(-0.04354 * m.winLoss) - 3.1669;
				sum += Math.max(0, Math.min(100, acc));
				count++;
			}
			out.accuracy[c] = count == 0 ? 100 : sum / count;
		}
		return out;
	}

	private static Searcher.Limits limits(long ms) {
		Searcher.Limits l = new Searcher.Limits();
		l.softTimeMs = Math.max(20, ms / 2);
		l.hardTimeMs = Math.max(40, ms);
		l.maxDepth = 18;
		return l;
	}

	/** A non-pawn piece left where the opponent wins material by taking it. */
	static boolean isSacrifice(Position before, int move, int[] gain) {
		Position p = before.copy();
		int moved = Move.isPromotion(move) ? Move.promoType(move) : Piece.type(p.board[Move.from(move)]);
		if (moved == Piece.PAWN || moved == Piece.KING) return false;
		int captured = Move.isCapture(move) && !Move.isEnPassant(move) ? Piece.type(p.board[Move.to(move)]) : -1;
		p.makeMove(move);
		if (p.inCheck() && p.legalMoves().length == 0) return false;
		int to = Move.to(move);
		for (int reply : p.legalMoves()) {
			if (Move.to(reply) == to && Move.isCapture(reply) && Searcher.see(p, reply, gain) >= 200) {
				return captured < 0 || captured < moved;
			}
		}
		return false;
	}
}
