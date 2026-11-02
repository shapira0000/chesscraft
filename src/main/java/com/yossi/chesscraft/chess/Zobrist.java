package com.yossi.chesscraft.chess;

/** Zobrist keys, generated from a fixed seed so hashes are stable across runs. */
public final class Zobrist {
	public static final long[][] PIECE = new long[Piece.COUNT][64];
	public static final long[] CASTLING = new long[16];
	public static final long[] EP_FILE = new long[8];
	public static final long SIDE;

	static {
		long s = 0x6A09E667F3BCC909L;
		for (int p = 0; p < Piece.COUNT; p++) {
			for (int sq = 0; sq < 64; sq++) {
				s = splitMix(s);
				PIECE[p][sq] = mix(s);
			}
		}
		for (int i = 0; i < 16; i++) {
			s = splitMix(s);
			CASTLING[i] = mix(s);
		}
		for (int i = 0; i < 8; i++) {
			s = splitMix(s);
			EP_FILE[i] = mix(s);
		}
		s = splitMix(s);
		SIDE = mix(s);
	}

	private Zobrist() {
	}

	private static long splitMix(long s) {
		return s + 0x9E3779B97F4A7C15L;
	}

	private static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}
}
