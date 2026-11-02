package com.yossi.chesscraft.chess.engine;

import com.yossi.chesscraft.chess.Attacks;
import com.yossi.chesscraft.chess.Piece;
import com.yossi.chesscraft.chess.Position;

/**
 * Tapered hand-crafted evaluation: material, piece-square tables, mobility, pawn
 * structure (cached), passed pawns, king safety, rook files, bishop pair, endgame
 * mop-up and drawish-material scaling. Scores are from the side to move.
 */
public final class Evaluator {
	public static final int[] MG_VALUE = {88, 320, 335, 480, 990, 0};
	public static final int[] EG_VALUE = {118, 300, 320, 540, 1010, 0};
	private static final int[] PHASE_WEIGHT = {0, 1, 1, 2, 4, 0};
	private static final int MAX_PHASE = 24;

	// Piece-square tables written from White's view with rank 8 on top.
	private static final int[] MG_PAWN = {
			0, 0, 0, 0, 0, 0, 0, 0,
			60, 60, 60, 65, 65, 60, 60, 60,
			12, 14, 24, 32, 32, 24, 14, 12,
			4, 6, 12, 26, 26, 12, 6, 4,
			0, 0, 6, 22, 22, 6, 0, 0,
			4, -4, -8, 2, 2, -8, -4, 4,
			4, 10, 10, -22, -22, 10, 10, 4,
			0, 0, 0, 0, 0, 0, 0, 0};
	private static final int[] EG_PAWN = {
			0, 0, 0, 0, 0, 0, 0, 0,
			70, 70, 65, 60, 60, 65, 70, 70,
			40, 40, 35, 30, 30, 35, 40, 40,
			20, 18, 14, 10, 10, 14, 18, 20,
			8, 6, 4, 2, 2, 4, 6, 8,
			2, 2, 0, 0, 0, 0, 2, 2,
			0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0};
	private static final int[] MG_KNIGHT = {
			-60, -40, -30, -30, -30, -30, -40, -60,
			-40, -20, 0, 5, 5, 0, -20, -40,
			-30, 5, 15, 20, 20, 15, 5, -30,
			-30, 5, 18, 25, 25, 18, 5, -30,
			-30, 0, 15, 22, 22, 15, 0, -30,
			-30, 5, 12, 15, 15, 12, 5, -30,
			-40, -20, 0, 5, 5, 0, -20, -40,
			-55, -35, -30, -30, -30, -30, -35, -55};
	private static final int[] EG_KNIGHT = {
			-50, -35, -25, -20, -20, -25, -35, -50,
			-35, -15, -5, 0, 0, -5, -15, -35,
			-25, -5, 10, 15, 15, 10, -5, -25,
			-20, 0, 15, 20, 20, 15, 0, -20,
			-20, 0, 15, 20, 20, 15, 0, -20,
			-25, -5, 10, 15, 15, 10, -5, -25,
			-35, -15, -5, 0, 0, -5, -15, -35,
			-50, -35, -25, -20, -20, -25, -35, -50};
	private static final int[] MG_BISHOP = {
			-20, -10, -10, -10, -10, -10, -10, -20,
			-10, 0, 0, 0, 0, 0, 0, -10,
			-10, 0, 5, 10, 10, 5, 0, -10,
			-10, 5, 5, 12, 12, 5, 5, -10,
			-10, 0, 12, 12, 12, 12, 0, -10,
			-10, 10, 10, 10, 10, 10, 10, -10,
			-10, 12, 0, 2, 2, 0, 12, -10,
			-20, -10, -14, -10, -10, -14, -10, -20};
	private static final int[] EG_BISHOP = {
			-15, -10, -8, -6, -6, -8, -10, -15,
			-10, -4, 0, 2, 2, 0, -4, -10,
			-8, 0, 6, 8, 8, 6, 0, -8,
			-6, 2, 8, 12, 12, 8, 2, -6,
			-6, 2, 8, 12, 12, 8, 2, -6,
			-8, 0, 6, 8, 8, 6, 0, -8,
			-10, -4, 0, 2, 2, 0, -4, -10,
			-15, -10, -8, -6, -6, -8, -10, -15};
	private static final int[] MG_ROOK = {
			5, 5, 8, 10, 10, 8, 5, 5,
			15, 20, 20, 20, 20, 20, 20, 15,
			-5, 0, 0, 0, 0, 0, 0, -5,
			-5, 0, 0, 0, 0, 0, 0, -5,
			-5, 0, 0, 0, 0, 0, 0, -5,
			-5, 0, 0, 0, 0, 0, 0, -5,
			-8, -2, 0, 0, 0, 0, -2, -8,
			-4, -2, 2, 8, 8, 4, -2, -4};
	private static final int[] EG_ROOK = {
			6, 6, 6, 6, 6, 6, 6, 6,
			10, 10, 10, 10, 10, 10, 10, 10,
			2, 2, 2, 2, 2, 2, 2, 2,
			0, 0, 0, 0, 0, 0, 0, 0,
			-2, -2, -2, -2, -2, -2, -2, -2,
			-4, -4, -4, -4, -4, -4, -4, -4,
			-6, -6, -6, -6, -6, -6, -6, -6,
			-6, -4, -2, 0, 0, -2, -4, -6};
	private static final int[] MG_QUEEN = {
			-20, -10, -10, -5, -5, -10, -10, -20,
			-10, -5, 0, 0, 0, 0, -5, -10,
			-10, 0, 5, 5, 5, 5, 0, -10,
			-5, 0, 5, 5, 5, 5, 0, -5,
			-5, 0, 5, 5, 5, 5, 0, -5,
			-10, 2, 5, 5, 5, 5, 2, -10,
			-10, 0, 4, 2, 2, 0, 0, -10,
			-20, -10, -10, 0, -5, -10, -10, -20};
	private static final int[] EG_QUEEN = {
			-20, -10, -10, -5, -5, -10, -10, -20,
			-10, 0, 5, 5, 5, 5, 0, -10,
			-10, 5, 10, 12, 12, 10, 5, -10,
			-5, 5, 12, 18, 18, 12, 5, -5,
			-5, 5, 12, 18, 18, 12, 5, -5,
			-10, 5, 10, 12, 12, 10, 5, -10,
			-10, 0, 5, 5, 5, 5, 0, -10,
			-20, -10, -10, -5, -5, -10, -10, -20};
	private static final int[] MG_KING = {
			-40, -50, -50, -60, -60, -50, -50, -40,
			-40, -50, -50, -60, -60, -50, -50, -40,
			-40, -50, -50, -60, -60, -50, -50, -40,
			-35, -45, -45, -55, -55, -45, -45, -35,
			-25, -35, -35, -45, -45, -35, -35, -25,
			-12, -22, -22, -28, -28, -22, -22, -12,
			15, 15, -8, -15, -15, -8, 15, 15,
			18, 32, 8, -10, -2, 6, 34, 20};
	private static final int[] EG_KING = {
			-55, -40, -30, -22, -22, -30, -40, -55,
			-30, -15, -5, 2, 2, -5, -15, -30,
			-25, -5, 15, 25, 25, 15, -5, -25,
			-25, -5, 25, 35, 35, 25, -5, -25,
			-25, -5, 25, 35, 35, 25, -5, -25,
			-25, -5, 15, 25, 25, 15, -5, -25,
			-35, -20, -5, 0, 0, -5, -20, -35,
			-55, -35, -25, -25, -25, -25, -35, -55};

	private static final int[][] MG_TABLES = {MG_PAWN, MG_KNIGHT, MG_BISHOP, MG_ROOK, MG_QUEEN, MG_KING};
	private static final int[][] EG_TABLES = {EG_PAWN, EG_KNIGHT, EG_BISHOP, EG_ROOK, EG_QUEEN, EG_KING};

	/** MG_PST[piece][sq] / EG_PST[piece][sq] include material. */
	private static final int[][] MG_PST = new int[Piece.COUNT][64];
	private static final int[][] EG_PST = new int[Piece.COUNT][64];

	private static final int[] PASSED_MG = {0, 2, 6, 12, 26, 48, 80, 0};
	private static final int[] PASSED_EG = {0, 8, 14, 26, 50, 90, 145, 0};

	private static final long[] FILE_MASK = new long[8];
	private static final long[] ADJACENT_FILES = new long[8];
	/** PASSED_MASK[color][sq]: squares ahead on own and adjacent files. */
	private static final long[][] PASSED_MASK = new long[2][64];
	/** FRONT_SPAN[color][sq]: squares strictly ahead on the same file. */
	private static final long[][] FRONT_SPAN = new long[2][64];
	private static final int[][] DISTANCE = new int[64][64];
	private static final int[] CENTER_DISTANCE = new int[64];
	private static final int[] KING_SAFETY = new int[100];

	private static final long DARK_SQUARES = 0xAA55AA55AA55AA55L;

	static {
		for (int p = 0; p < Piece.COUNT; p++) {
			int color = Piece.color(p), type = Piece.type(p);
			for (int sq = 0; sq < 64; sq++) {
				// Tables are drawn rank 8 first; convert to a1 = 0 indexing for White, mirror for Black.
				int tableIdx = color == Piece.WHITE ? (sq ^ 56) : sq;
				MG_PST[p][sq] = MG_VALUE[type] + MG_TABLES[type][tableIdx];
				EG_PST[p][sq] = EG_VALUE[type] + EG_TABLES[type][tableIdx];
			}
		}
		for (int f = 0; f < 8; f++) {
			FILE_MASK[f] = Attacks.FILE_A << f;
		}
		for (int f = 0; f < 8; f++) {
			ADJACENT_FILES[f] = (f > 0 ? FILE_MASK[f - 1] : 0) | (f < 7 ? FILE_MASK[f + 1] : 0);
		}
		for (int sq = 0; sq < 64; sq++) {
			int f = sq & 7, r = sq >> 3;
			long span = FILE_MASK[f] | ADJACENT_FILES[f];
			long aheadWhite = r < 7 ? -1L << ((r + 1) * 8) : 0;
			long aheadBlack = r > 0 ? -1L >>> ((8 - r) * 8) : 0;
			PASSED_MASK[Piece.WHITE][sq] = span & aheadWhite;
			PASSED_MASK[Piece.BLACK][sq] = span & aheadBlack;
			FRONT_SPAN[Piece.WHITE][sq] = FILE_MASK[f] & aheadWhite;
			FRONT_SPAN[Piece.BLACK][sq] = FILE_MASK[f] & aheadBlack;
			for (int o = 0; o < 64; o++) {
				DISTANCE[sq][o] = Math.max(Math.abs(f - (o & 7)), Math.abs(r - (o >> 3)));
			}
			int fc = Math.max(3 - f, f - 4), rc = Math.max(3 - r, r - 4);
			CENTER_DISTANCE[sq] = fc + rc;
		}
		for (int i = 0; i < 100; i++) {
			KING_SAFETY[i] = Math.min(500, i * i / 3);
		}
	}

	private final long[] pawnKeys = new long[1 << 14];
	private final int[] pawnMg = new int[1 << 14];
	private final int[] pawnEg = new int[1 << 14];
	private final long[] pawnPassed = new long[1 << 14];

	/** Static evaluation from the side to move's perspective. */
	public int evaluate(Position p) {
		int mg = 0, eg = 0, phase = 0;
		for (int pc = 0; pc < Piece.COUNT; pc++) {
			long b = p.bb[pc];
			int sign = Piece.color(pc) == Piece.WHITE ? 1 : -1;
			phase += PHASE_WEIGHT[Piece.type(pc)] * Long.bitCount(b);
			while (b != 0) {
				int sq = Long.numberOfTrailingZeros(b);
				b &= b - 1;
				mg += sign * MG_PST[pc][sq];
				eg += sign * EG_PST[pc][sq];
			}
		}

		// Pawn structure (cached by pawn-only hash).
		long wp = p.bb[Piece.make(Piece.WHITE, Piece.PAWN)];
		long bp = p.bb[Piece.make(Piece.BLACK, Piece.PAWN)];
		long pawnKey = wp * 0x9E3779B97F4A7C15L ^ Long.rotateLeft(bp * 0xC2B2AE3D27D4EB4FL, 31);
		int slot = (int) (pawnKey >>> 50);
		if (pawnKeys[slot] != pawnKey) {
			computePawns(wp, bp, slot);
			pawnKeys[slot] = pawnKey;
		}
		mg += pawnMg[slot];
		eg += pawnEg[slot];
		long passed = pawnPassed[slot];

		int[] terms = new int[2];
		evalPieces(p, Piece.WHITE, terms);
		mg += terms[0];
		eg += terms[1];
		terms[0] = terms[1] = 0;
		evalPieces(p, Piece.BLACK, terms);
		mg -= terms[0];
		eg -= terms[1];

		eg += passedPawnDynamics(p, passed & wp, Piece.WHITE) - passedPawnDynamics(p, passed & bp, Piece.BLACK);

		// Bishop pair
		if (Long.bitCount(p.bb[Piece.make(Piece.WHITE, Piece.BISHOP)]) >= 2) {
			mg += 28;
			eg += 50;
		}
		if (Long.bitCount(p.bb[Piece.make(Piece.BLACK, Piece.BISHOP)]) >= 2) {
			mg -= 28;
			eg -= 50;
		}

		if (phase > MAX_PHASE) phase = MAX_PHASE;
		int score = (mg * phase + eg * (MAX_PHASE - phase)) / MAX_PHASE;

		score += mopUp(p, score);
		score = scaleDrawish(p, score);

		int stm = p.side == Piece.WHITE ? score : -score;
		return stm + 12;
	}

	private void computePawns(long wp, long bp, int slot) {
		int mg = 0, eg = 0;
		long passed = 0;
		for (int color = 0; color < 2; color++) {
			long own = color == Piece.WHITE ? wp : bp;
			long enemy = color == Piece.WHITE ? bp : wp;
			int sign = color == Piece.WHITE ? 1 : -1;
			long b = own;
			while (b != 0) {
				int sq = Long.numberOfTrailingZeros(b);
				b &= b - 1;
				int f = sq & 7;
				int relRank = color == Piece.WHITE ? (sq >> 3) : 7 - (sq >> 3);
				boolean isolated = (own & ADJACENT_FILES[f]) == 0;
				boolean doubled = (own & FRONT_SPAN[color][sq]) != 0;
				boolean isPassed = (enemy & PASSED_MASK[color][sq]) == 0 && !doubled;
				long rankMask = 0xFFL << (sq & ~7);
				boolean phalanx = (own & ADJACENT_FILES[f] & rankMask) != 0;
				long behind = color == Piece.WHITE ? rankMask >>> 8 : rankMask << 8;
				boolean supported = (own & ADJACENT_FILES[f] & behind) != 0;
				if (isolated) {
					mg -= 10 * sign;
					eg -= 16 * sign;
				}
				if (doubled) {
					mg -= 10 * sign;
					eg -= 22 * sign;
				}
				if (phalanx || supported) {
					int bonus = 4 + relRank * (phalanx ? 3 : 2);
					mg += bonus * sign;
					eg += bonus * sign;
				}
				if (isPassed) {
					passed |= 1L << sq;
					mg += PASSED_MG[relRank] * sign;
					eg += PASSED_EG[relRank] * sign;
				}
			}
		}
		pawnMg[slot] = mg;
		pawnEg[slot] = eg;
		pawnPassed[slot] = passed;
	}

	/** King proximity, blockade and unstoppable checks for passed pawns (endgame term). */
	private static int passedPawnDynamics(Position p, long passers, int color) {
		int score = 0;
		int them = color ^ 1;
		int ownKing = p.kingSquare(color), enemyKing = p.kingSquare(them);
		boolean enemyHasPieces = p.hasNonPawnMaterial(them);
		while (passers != 0) {
			int sq = Long.numberOfTrailingZeros(passers);
			passers &= passers - 1;
			int relRank = color == Piece.WHITE ? (sq >> 3) : 7 - (sq >> 3);
			int front = sq + (color == Piece.WHITE ? 8 : -8);
			int weight = Math.max(0, relRank - 2);
			score += weight * (5 * DISTANCE[front][enemyKing] - 2 * DISTANCE[front][ownKing]);
			if (p.board[front] != Piece.NONE) score -= weight * 6;
			if (!enemyHasPieces) {
				int promo = (sq & 7) + (color == Piece.WHITE ? 56 : 0);
				int pawnDist = 7 - relRank;
				if (relRank == 1) pawnDist--;
				int tempo = p.side == color ? 0 : 1;
				if (Math.min(5, pawnDist) < DISTANCE[enemyKing][promo] - tempo
						&& (FRONT_SPAN[color][sq] & p.all) == 0) {
					score += 500;
				}
			}
		}
		return score;
	}

	private static void evalPieces(Position p, int color, int[] terms) {
		int them = color ^ 1;
		long own = p.occ[color];
		long enemyPawns = p.bb[Piece.make(them, Piece.PAWN)];
		long ownPawns = p.bb[Piece.make(color, Piece.PAWN)];
		long enemyPawnAttacks = color == Piece.WHITE
				? ((enemyPawns >>> 7) & ~Attacks.FILE_A) | ((enemyPawns >>> 9) & ~Attacks.FILE_H)
				: ((enemyPawns << 7) & ~Attacks.FILE_H) | ((enemyPawns << 9) & ~Attacks.FILE_A);
		long mobilityArea = ~own & ~enemyPawnAttacks;
		int enemyKing = p.kingSquare(them);
		long kingZone = Attacks.KING[enemyKing] | (1L << enemyKing);
		kingZone |= them == Piece.WHITE ? kingZone << 8 : kingZone >>> 8;

		int mg = 0, eg = 0;
		int attackers = 0, attackUnits = 0;

		long b = p.bb[Piece.make(color, Piece.KNIGHT)];
		while (b != 0) {
			int sq = Long.numberOfTrailingZeros(b);
			b &= b - 1;
			long att = Attacks.KNIGHT[sq];
			int mob = Long.bitCount(att & mobilityArea);
			mg += (mob - 4) * 5;
			eg += (mob - 4) * 5;
			long z = att & kingZone;
			if (z != 0) {
				attackers++;
				attackUnits += 2 * Long.bitCount(z);
			}
			// Outpost: protected by own pawn and not attackable by enemy pawns.
			int relRank = color == Piece.WHITE ? (sq >> 3) : 7 - (sq >> 3);
			if (relRank >= 3 && relRank <= 5 && (Attacks.PAWN[them][sq] & ownPawns) != 0
					&& (PASSED_MASK[color][sq] & ~FILE_MASK[sq & 7] & enemyPawns) == 0) {
				mg += 22;
				eg += 10;
			}
		}
		b = p.bb[Piece.make(color, Piece.BISHOP)];
		while (b != 0) {
			int sq = Long.numberOfTrailingZeros(b);
			b &= b - 1;
			long att = Attacks.bishop(sq, p.all);
			int mob = Long.bitCount(att & mobilityArea);
			mg += (mob - 6) * 5;
			eg += (mob - 6) * 5;
			long z = att & kingZone;
			if (z != 0) {
				attackers++;
				attackUnits += 2 * Long.bitCount(z);
			}
			// Bad bishop: own pawns on its color.
			long sameColor = ((1L << sq) & DARK_SQUARES) != 0 ? DARK_SQUARES : ~DARK_SQUARES;
			int blockers = Long.bitCount(ownPawns & sameColor);
			mg -= blockers * 2;
			eg -= blockers * 4;
		}
		b = p.bb[Piece.make(color, Piece.ROOK)];
		while (b != 0) {
			int sq = Long.numberOfTrailingZeros(b);
			b &= b - 1;
			long att = Attacks.rook(sq, p.all);
			int mob = Long.bitCount(att & mobilityArea);
			mg += (mob - 7) * 2;
			eg += (mob - 7) * 4;
			long z = att & kingZone;
			if (z != 0) {
				attackers++;
				attackUnits += 3 * Long.bitCount(z);
			}
			long file = FILE_MASK[sq & 7];
			if ((file & ownPawns) == 0) {
				if ((file & enemyPawns) == 0) {
					mg += 28;
					eg += 12;
				} else {
					mg += 13;
					eg += 6;
				}
			}
			int relRank = color == Piece.WHITE ? (sq >> 3) : 7 - (sq >> 3);
			int enemyKingRank = them == Piece.WHITE ? (enemyKing >> 3) : 7 - (enemyKing >> 3);
			if (relRank == 6 && enemyKingRank == 7) {
				mg += 14;
				eg += 24;
			}
		}
		b = p.bb[Piece.make(color, Piece.QUEEN)];
		while (b != 0) {
			int sq = Long.numberOfTrailingZeros(b);
			b &= b - 1;
			long att = Attacks.queen(sq, p.all);
			int mob = Long.bitCount(att & mobilityArea);
			mg += (mob - 13);
			eg += (mob - 13) * 2;
			long z = att & kingZone;
			if (z != 0) {
				attackers++;
				attackUnits += 5 * Long.bitCount(z);
			}
		}

		// Attack on the enemy king: only meaningful with several attackers and a queen around.
		if (attackers >= 2 && p.bb[Piece.make(color, Piece.QUEEN)] != 0) {
			mg += KING_SAFETY[Math.min(99, attackUnits)];
		} else if (attackers >= 2) {
			mg += KING_SAFETY[Math.min(99, attackUnits)] / 3;
		}

		// Own king shelter.
		int king = p.kingSquare(color);
		int kf = king & 7;
		int kRelRank = color == Piece.WHITE ? (king >> 3) : 7 - (king >> 3);
		if (kRelRank <= 1) {
			int shelter = 0;
			for (int f = Math.max(0, kf - 1); f <= Math.min(7, kf + 1); f++) {
				long filePawns = ownPawns & FILE_MASK[f];
				long ahead = filePawns & FRONT_SPAN[color][king - kf + f];
				if (ahead == 0) {
					shelter -= 22;
					if ((enemyPawns & FILE_MASK[f]) == 0) shelter -= 14;
				} else {
					int nearest = color == Piece.WHITE ? Long.numberOfTrailingZeros(ahead) : 63 - Long.numberOfLeadingZeros(ahead);
					int dist = Math.abs((nearest >> 3) - (king >> 3));
					shelter += dist == 1 ? 12 : dist == 2 ? 5 : -6;
				}
			}
			mg += shelter;
		}
		terms[0] += mg;
		terms[1] += eg;
	}

	/** Drives a lone king to the edge (or the right corner for B+N) when one side is winning. */
	private static int mopUp(Position p, int whiteScore) {
		if (Math.abs(whiteScore) < 250) return 0;
		int strong = whiteScore > 0 ? Piece.WHITE : Piece.BLACK;
		int weak = strong ^ 1;
		if ((p.occ[weak] & ~p.bb[Piece.make(weak, Piece.KING)]) != 0) return 0;
		int wk = p.kingSquare(weak), sk = p.kingSquare(strong);
		int bonus = 10 * CENTER_DISTANCE[wk] + 4 * (14 - 2 * DISTANCE[wk][sk]);
		long bishops = p.bb[Piece.make(strong, Piece.BISHOP)];
		boolean bishopKnight = Long.bitCount(bishops) == 1 && Long.bitCount(p.bb[Piece.make(strong, Piece.KNIGHT)]) == 1
				&& (p.occ[strong] & ~bishops & ~p.bb[Piece.make(strong, Piece.KNIGHT)] & ~p.bb[Piece.make(strong, Piece.KING)]) == 0;
		if (bishopKnight) {
			boolean dark = (bishops & DARK_SQUARES) != 0;
			int c1 = dark ? 0 : 7, c2 = dark ? 63 : 56;
			bonus += 20 * (7 - Math.min(DISTANCE[wk][c1], DISTANCE[wk][c2]));
		}
		return strong == Piece.WHITE ? bonus : -bonus;
	}

	private static int scaleDrawish(Position p, int whiteScore) {
		if (whiteScore == 0) return 0;
		int strong = whiteScore > 0 ? Piece.WHITE : Piece.BLACK;
		int weak = strong ^ 1;
		if (p.bb[Piece.make(strong, Piece.PAWN)] != 0) {
			// Opposite-colored bishops with nothing else but pawns.
			long wb = p.bb[Piece.make(Piece.WHITE, Piece.BISHOP)], bb = p.bb[Piece.make(Piece.BLACK, Piece.BISHOP)];
			long nonPawn = p.all & ~p.bb[0] & ~p.bb[6] & ~p.bb[5] & ~p.bb[11];
			if (Long.bitCount(wb) == 1 && Long.bitCount(bb) == 1 && nonPawn == (wb | bb)
					&& ((wb & DARK_SQUARES) != 0) != ((bb & DARK_SQUARES) != 0)) {
				return whiteScore / 2;
			}
			return whiteScore;
		}
		int strongMat = nonPawnMaterial(p, strong), weakMat = nonPawnMaterial(p, weak);
		// Two knights cannot force mate against a bare king.
		if (strongMat == 2 * MG_VALUE[Piece.KNIGHT] && p.bb[Piece.make(strong, Piece.BISHOP)] == 0
				&& p.bb[Piece.make(weak, Piece.PAWN)] == 0 && weakMat == 0) {
			return whiteScore / 16;
		}
		if (strongMat - weakMat < 400) return whiteScore / (strongMat <= MG_VALUE[Piece.BISHOP] ? 16 : 4);
		return whiteScore;
	}

	private static int nonPawnMaterial(Position p, int color) {
		int m = 0;
		for (int t = Piece.KNIGHT; t <= Piece.QUEEN; t++) {
			m += MG_VALUE[t] * Long.bitCount(p.bb[Piece.make(color, t)]);
		}
		return m;
	}
}
