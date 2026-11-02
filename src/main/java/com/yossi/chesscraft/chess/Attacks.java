package com.yossi.chesscraft.chess;

/**
 * Precomputed attack tables. Sliding pieces use magic bitboards whose magics are
 * found once at class load with a fixed seed, so the tables are deterministic.
 */
public final class Attacks {
	public static final long FILE_A = 0x0101010101010101L;
	public static final long FILE_H = FILE_A << 7;
	public static final long RANK_1 = 0xFFL;
	public static final long RANK_8 = RANK_1 << 56;

	public static final long[] KNIGHT = new long[64];
	public static final long[] KING = new long[64];
	/** PAWN[color][sq]: squares a pawn of that color on sq attacks. */
	public static final long[][] PAWN = new long[2][64];

	private static final long[] ROOK_MASK = new long[64];
	private static final long[] BISHOP_MASK = new long[64];
	private static final long[] ROOK_MAGIC = new long[64];
	private static final long[] BISHOP_MAGIC = new long[64];
	private static final int[] ROOK_SHIFT = new int[64];
	private static final int[] BISHOP_SHIFT = new int[64];
	private static final long[][] ROOK_TABLE = new long[64][];
	private static final long[][] BISHOP_TABLE = new long[64][];

	private static final int[][] ROOK_DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
	private static final int[][] BISHOP_DIRS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

	private static long seed = 0x9E3779B97F4A7C15L;

	static {
		for (int sq = 0; sq < 64; sq++) {
			int f = sq & 7, r = sq >> 3;
			KNIGHT[sq] = steps(f, r, new int[][]{{1, 2}, {2, 1}, {2, -1}, {1, -2}, {-1, -2}, {-2, -1}, {-2, 1}, {-1, 2}});
			KING[sq] = steps(f, r, new int[][]{{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}});
			PAWN[Piece.WHITE][sq] = steps(f, r, new int[][]{{-1, 1}, {1, 1}});
			PAWN[Piece.BLACK][sq] = steps(f, r, new int[][]{{-1, -1}, {1, -1}});
		}
		for (int sq = 0; sq < 64; sq++) {
			ROOK_MASK[sq] = relevantMask(sq, ROOK_DIRS);
			BISHOP_MASK[sq] = relevantMask(sq, BISHOP_DIRS);
			ROOK_SHIFT[sq] = 64 - Long.bitCount(ROOK_MASK[sq]);
			BISHOP_SHIFT[sq] = 64 - Long.bitCount(BISHOP_MASK[sq]);
			ROOK_TABLE[sq] = new long[1 << Long.bitCount(ROOK_MASK[sq])];
			BISHOP_TABLE[sq] = new long[1 << Long.bitCount(BISHOP_MASK[sq])];
			ROOK_MAGIC[sq] = findMagic(sq, ROOK_MASK[sq], ROOK_SHIFT[sq], ROOK_DIRS, ROOK_TABLE[sq]);
			BISHOP_MAGIC[sq] = findMagic(sq, BISHOP_MASK[sq], BISHOP_SHIFT[sq], BISHOP_DIRS, BISHOP_TABLE[sq]);
		}
	}

	private Attacks() {
	}

	public static long rook(int sq, long occ) {
		return ROOK_TABLE[sq][(int) (((occ & ROOK_MASK[sq]) * ROOK_MAGIC[sq]) >>> ROOK_SHIFT[sq])];
	}

	public static long bishop(int sq, long occ) {
		return BISHOP_TABLE[sq][(int) (((occ & BISHOP_MASK[sq]) * BISHOP_MAGIC[sq]) >>> BISHOP_SHIFT[sq])];
	}

	public static long queen(int sq, long occ) {
		return rook(sq, occ) | bishop(sq, occ);
	}

	private static long steps(int f, int r, int[][] deltas) {
		long bb = 0;
		for (int[] d : deltas) {
			int nf = f + d[0], nr = r + d[1];
			if (nf >= 0 && nf < 8 && nr >= 0 && nr < 8) bb |= 1L << (nr * 8 + nf);
		}
		return bb;
	}

	/** Ray attacks for magic generation and as a reference implementation. */
	static long slide(int sq, long occ, int[][] dirs) {
		long bb = 0;
		int f0 = sq & 7, r0 = sq >> 3;
		for (int[] d : dirs) {
			int f = f0 + d[0], r = r0 + d[1];
			while (f >= 0 && f < 8 && r >= 0 && r < 8) {
				long bit = 1L << (r * 8 + f);
				bb |= bit;
				if ((occ & bit) != 0) break;
				f += d[0];
				r += d[1];
			}
		}
		return bb;
	}

	private static long relevantMask(int sq, int[][] dirs) {
		long bb = 0;
		int f0 = sq & 7, r0 = sq >> 3;
		for (int[] d : dirs) {
			int f = f0 + d[0], r = r0 + d[1];
			// Stop one short of the edge: edge squares never block anything further.
			while (f + d[0] >= 0 && f + d[0] < 8 && r + d[1] >= 0 && r + d[1] < 8) {
				bb |= 1L << (r * 8 + f);
				f += d[0];
				r += d[1];
			}
		}
		return bb;
	}

	private static long nextRandom() {
		seed ^= seed >>> 12;
		seed ^= seed << 25;
		seed ^= seed >>> 27;
		return seed * 0x2545F4914F6CDD1DL;
	}

	private static long findMagic(int sq, long mask, int shift, int[][] dirs, long[] table) {
		int n = Long.bitCount(mask);
		int size = 1 << n;
		long[] occs = new long[size];
		long[] atts = new long[size];
		long subset = 0;
		int i = 0;
		do {
			occs[i] = subset;
			atts[i] = slide(sq, subset, dirs);
			i++;
			subset = (subset - mask) & mask;
		} while (subset != 0);

		int[] epoch = new int[size];
		int attempt = 0;
		while (true) {
			long magic = nextRandom() & nextRandom() & nextRandom();
			if (Long.bitCount((mask * magic) & 0xFF00000000000000L) < 6) continue;
			attempt++;
			boolean ok = true;
			for (int k = 0; k < size && ok; k++) {
				int idx = (int) ((occs[k] * magic) >>> shift);
				if (epoch[idx] != attempt) {
					epoch[idx] = attempt;
					table[idx] = atts[k];
				} else if (table[idx] != atts[k]) {
					ok = false;
				}
			}
			if (ok) return magic;
		}
	}
}
