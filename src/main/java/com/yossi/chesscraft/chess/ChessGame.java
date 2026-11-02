package com.yossi.chesscraft.chess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A game: start position, move list and result. Applies the full set of FIDE end
 * conditions; threefold repetition and the fifty-move rule end the game automatically.
 */
public final class ChessGame {
	public enum Result {
		ONGOING, WHITE_WINS, BLACK_WINS, DRAW;

		public boolean isOver() {
			return this != ONGOING;
		}
	}

	public enum Reason {
		NONE, CHECKMATE, RESIGNATION, TIMEOUT, STALEMATE, REPETITION, FIFTY_MOVES, INSUFFICIENT_MATERIAL, AGREEMENT, ABANDONED
	}

	private final String startFen;
	private Position position;
	private final List<Integer> moves = new ArrayList<>();
	private final List<String> sans = new ArrayList<>();
	private Result result = Result.ONGOING;
	private Reason reason = Reason.NONE;

	public ChessGame() {
		this(Position.START_FEN);
	}

	public ChessGame(String startFen) {
		this.position = Position.fromFen(startFen);
		this.startFen = position.toFen();
		updateResult();
	}

	/** Rebuilds a game from its start FEN and UCI move list (used for syncing). */
	public static ChessGame replay(String startFen, List<String> uciMoves) {
		ChessGame game = new ChessGame(startFen);
		for (String uci : uciMoves) {
			int m = game.position.parseUci(uci);
			if (m == Move.NONE || !game.play(m)) break;
		}
		return game;
	}

	public String startFen() {
		return startFen;
	}

	public Position position() {
		return position;
	}

	public List<Integer> moves() {
		return Collections.unmodifiableList(moves);
	}

	public List<String> sanMoves() {
		return Collections.unmodifiableList(sans);
	}

	public List<String> uciMoves() {
		List<String> out = new ArrayList<>(moves.size());
		for (int m : moves) out.add(Move.toUci(m));
		return out;
	}

	public Result result() {
		return result;
	}

	public Reason reason() {
		return reason;
	}

	public boolean isOver() {
		return result.isOver();
	}

	public int sideToMove() {
		return position.side;
	}

	public int lastMove() {
		return moves.isEmpty() ? Move.NONE : moves.get(moves.size() - 1);
	}

	public int[] legalMoves() {
		return result.isOver() ? new int[0] : position.legalMoves();
	}

	/** Plays a move if the game is running and the move is legal. */
	public boolean play(int move) {
		if (result.isOver() || !position.isLegal(move)) return false;
		sans.add(Notation.toSan(position, move));
		position.makeMove(move);
		moves.add(move);
		updateResult();
		return true;
	}

	/** Takes back the last move; reopens a game that ended by a board condition. */
	public boolean undo() {
		if (moves.isEmpty()) return false;
		if (result.isOver() && !isBoardResult(reason)) return false;
		position.unmakeMove();
		moves.remove(moves.size() - 1);
		sans.remove(sans.size() - 1);
		result = Result.ONGOING;
		reason = Reason.NONE;
		updateResult();
		return true;
	}

	private static boolean isBoardResult(Reason r) {
		return r == Reason.CHECKMATE || r == Reason.STALEMATE || r == Reason.REPETITION
				|| r == Reason.FIFTY_MOVES || r == Reason.INSUFFICIENT_MATERIAL || r == Reason.NONE;
	}

	public void resign(int color) {
		if (result.isOver()) return;
		finish(color == Piece.WHITE ? Result.BLACK_WINS : Result.WHITE_WINS, Reason.RESIGNATION);
	}

	public void agreeDraw() {
		if (!result.isOver()) finish(Result.DRAW, Reason.AGREEMENT);
	}

	public void abandon() {
		if (!result.isOver()) finish(Result.DRAW, Reason.ABANDONED);
	}

	/** The given color ran out of time. It is a draw if the opponent cannot possibly mate. */
	public void timeout(int color) {
		if (result.isOver()) return;
		if (!canEverMate(color ^ 1)) finish(Result.DRAW, Reason.TIMEOUT);
		else finish(color == Piece.WHITE ? Result.BLACK_WINS : Result.WHITE_WINS, Reason.TIMEOUT);
	}

	/** Restores a stored result without validation (sync / save loading). */
	public void forceResult(Result r, Reason why) {
		result = r;
		reason = why;
	}

	private void finish(Result r, Reason why) {
		result = r;
		reason = why;
	}

	private void updateResult() {
		Position p = position;
		if (p.legalMoves().length == 0) {
			if (p.inCheck()) finish(p.side == Piece.WHITE ? Result.BLACK_WINS : Result.WHITE_WINS, Reason.CHECKMATE);
			else finish(Result.DRAW, Reason.STALEMATE);
		} else if (isInsufficientMaterial(p)) {
			finish(Result.DRAW, Reason.INSUFFICIENT_MATERIAL);
		} else if (p.repetitionCount() >= 2) {
			finish(Result.DRAW, Reason.REPETITION);
		} else if (p.halfmove >= 100) {
			finish(Result.DRAW, Reason.FIFTY_MOVES);
		}
	}

	/** Dead positions: K v K, K+minor v K, and bishops-only positions with all bishops on one color. */
	public static boolean isInsufficientMaterial(Position p) {
		long heavy = 0;
		for (int c = 0; c < 2; c++) {
			heavy |= p.bb[Piece.make(c, Piece.PAWN)] | p.bb[Piece.make(c, Piece.ROOK)] | p.bb[Piece.make(c, Piece.QUEEN)];
		}
		if (heavy != 0) return false;
		long knights = p.bb[Piece.make(0, Piece.KNIGHT)] | p.bb[Piece.make(1, Piece.KNIGHT)];
		long bishops = p.bb[Piece.make(0, Piece.BISHOP)] | p.bb[Piece.make(1, Piece.BISHOP)];
		int minors = Long.bitCount(knights | bishops);
		if (minors <= 1) return true;
		if (knights == 0) {
			long light = 0x55AA55AA55AA55AAL;
			return (bishops & light) == 0 || (bishops & ~light) == 0;
		}
		return false;
	}

	/** Whether the color has enough material to deliver mate in some legal sequence. */
	private boolean canEverMate(int color) {
		Position p = position;
		long own = p.occ[color] & ~p.bb[Piece.make(color, Piece.KING)];
		if (own == 0) return false;
		long minors = p.bb[Piece.make(color, Piece.KNIGHT)] | p.bb[Piece.make(color, Piece.BISHOP)];
		boolean singleMinor = own == minors && Long.bitCount(minors) == 1;
		long enemy = p.occ[color ^ 1] & ~p.bb[Piece.make(color ^ 1, Piece.KING)];
		// A lone minor can only mate if the defender has pieces that can block their own king.
		return !(singleMinor && enemy == 0);
	}

	public String resultTag() {
		return switch (result) {
			case WHITE_WINS -> "1-0";
			case BLACK_WINS -> "0-1";
			case DRAW -> "1/2-1/2";
			default -> "*";
		};
	}

	public String toPgn(String white, String black) {
		StringBuilder sb = new StringBuilder();
		sb.append("[Event \"ChessCraft game\"]\n");
		sb.append("[White \"").append(white).append("\"]\n");
		sb.append("[Black \"").append(black).append("\"]\n");
		sb.append("[Result \"").append(resultTag()).append("\"]\n");
		if (!startFen.equals(Position.START_FEN)) {
			sb.append("[SetUp \"1\"]\n[FEN \"").append(startFen).append("\"]\n");
		}
		sb.append('\n');
		Position start = Position.fromFen(startFen);
		int moveNo = start.fullmove;
		boolean blackToMove = start.side == Piece.BLACK;
		for (int i = 0; i < sans.size(); i++) {
			if (!blackToMove) sb.append(moveNo).append(". ");
			else if (i == 0) sb.append(moveNo).append("... ");
			sb.append(sans.get(i)).append(' ');
			if (blackToMove) moveNo++;
			blackToMove = !blackToMove;
		}
		sb.append(resultTag());
		return sb.toString();
	}
}
