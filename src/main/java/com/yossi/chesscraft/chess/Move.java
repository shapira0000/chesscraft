package com.yossi.chesscraft.chess;

/**
 * Moves are packed into an int: bits 0-5 from square, 6-11 to square, 12-15 flags.
 * Squares run a1 = 0 ... h8 = 63.
 */
public final class Move {
	public static final int NONE = 0;

	public static final int QUIET = 0;
	public static final int DOUBLE_PUSH = 1;
	public static final int CASTLE_KING = 2;
	public static final int CASTLE_QUEEN = 3;
	public static final int CAPTURE = 4;
	public static final int EN_PASSANT = 5;
	/** Promotion flags: 8..11 quiet (N, B, R, Q), 12..15 capturing. */
	public static final int PROMO = 8;
	public static final int PROMO_CAPTURE = 12;

	private Move() {
	}

	public static int of(int from, int to, int flags) {
		return from | (to << 6) | (flags << 12);
	}

	public static int from(int move) {
		return move & 63;
	}

	public static int to(int move) {
		return (move >>> 6) & 63;
	}

	public static int flags(int move) {
		return (move >>> 12) & 15;
	}

	public static boolean isCapture(int move) {
		return (flags(move) & 4) != 0;
	}

	public static boolean isPromotion(int move) {
		return (flags(move) & 8) != 0;
	}

	public static boolean isCastle(int move) {
		int f = flags(move);
		return f == CASTLE_KING || f == CASTLE_QUEEN;
	}

	public static boolean isEnPassant(int move) {
		return flags(move) == EN_PASSANT;
	}

	public static boolean isQuiet(int move) {
		return (flags(move) & 12) == 0;
	}

	/** Promotion piece type (KNIGHT..QUEEN); only meaningful for promotions. */
	public static int promoType(int move) {
		return (flags(move) & 3) + Piece.KNIGHT;
	}

	public static String squareName(int sq) {
		return "" + (char) ('a' + (sq & 7)) + (char) ('1' + (sq >> 3));
	}

	public static int parseSquare(String s) {
		if (s.length() != 2) return -1;
		int file = s.charAt(0) - 'a';
		int rank = s.charAt(1) - '1';
		if (file < 0 || file > 7 || rank < 0 || rank > 7) return -1;
		return rank * 8 + file;
	}

	public static String toUci(int move) {
		if (move == NONE) return "0000";
		String s = squareName(from(move)) + squareName(to(move));
		if (isPromotion(move)) s += "nbrq".charAt(promoType(move) - Piece.KNIGHT);
		return s;
	}
}
