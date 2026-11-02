package com.yossi.chesscraft.chess;

/** Standard Algebraic Notation output. */
public final class Notation {
	private Notation() {
	}

	/** SAN for a legal move in the given position (position is restored afterwards). */
	public static String toSan(Position pos, int move) {
		StringBuilder sb = new StringBuilder();
		int from = Move.from(move), to = Move.to(move);
		int type = Piece.type(pos.board[from]);
		if (Move.flags(move) == Move.CASTLE_KING) {
			sb.append("O-O");
		} else if (Move.flags(move) == Move.CASTLE_QUEEN) {
			sb.append("O-O-O");
		} else if (type == Piece.PAWN) {
			if (Move.isCapture(move)) sb.append((char) ('a' + (from & 7))).append('x');
			sb.append(Move.squareName(to));
			if (Move.isPromotion(move)) sb.append('=').append(Piece.sanLetter(Move.promoType(move)));
		} else {
			sb.append(Piece.sanLetter(type));
			boolean ambiguous = false, sameFile = false, sameRank = false;
			for (int other : pos.legalMoves()) {
				int of = Move.from(other);
				if (other != move && Move.to(other) == to && of != from && Piece.type(pos.board[of]) == type) {
					ambiguous = true;
					if ((of & 7) == (from & 7)) sameFile = true;
					if ((of >> 3) == (from >> 3)) sameRank = true;
				}
			}
			if (ambiguous) {
				if (!sameFile) sb.append((char) ('a' + (from & 7)));
				else if (!sameRank) sb.append((char) ('1' + (from >> 3)));
				else sb.append(Move.squareName(from));
			}
			if (Move.isCapture(move)) sb.append('x');
			sb.append(Move.squareName(to));
		}
		pos.makeMove(move);
		if (pos.inCheck()) sb.append(pos.legalMoves().length == 0 ? '#' : '+');
		pos.unmakeMove();
		return sb.toString();
	}
}
