package com.yossi.chesscraft.chess;

/**
 * Piece encoding helpers. A piece is {@code color * 6 + type}, giving indices 0..11;
 * {@link #NONE} marks an empty square.
 */
public final class Piece {
	public static final int WHITE = 0;
	public static final int BLACK = 1;

	public static final int PAWN = 0;
	public static final int KNIGHT = 1;
	public static final int BISHOP = 2;
	public static final int ROOK = 3;
	public static final int QUEEN = 4;
	public static final int KING = 5;

	public static final int NONE = -1;
	public static final int COUNT = 12;

	private static final String FEN_CHARS = "PNBRQKpnbrqk";
	private static final String[] TYPE_NAMES = {"pawn", "knight", "bishop", "rook", "queen", "king"};

	private Piece() {
	}

	public static int make(int color, int type) {
		return color * 6 + type;
	}

	public static int color(int piece) {
		return piece / 6;
	}

	public static int type(int piece) {
		return piece % 6;
	}

	public static char fenChar(int piece) {
		return FEN_CHARS.charAt(piece);
	}

	public static int fromFenChar(char c) {
		return FEN_CHARS.indexOf(c);
	}

	/** Upper-case SAN letter for a piece type, empty for pawns. */
	public static String sanLetter(int type) {
		return type == PAWN ? "" : String.valueOf("PNBRQK".charAt(type));
	}

	public static String typeName(int type) {
		return TYPE_NAMES[type];
	}

	public static String colorName(int color) {
		return color == WHITE ? "white" : "black";
	}

	/** Registry-style id such as {@code white_knight}. */
	public static String id(int piece) {
		return colorName(color(piece)) + "_" + typeName(type(piece));
	}
}
