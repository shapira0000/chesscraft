package com.yossi.chesscraft.chess;

import java.util.Arrays;

/**
 * A chess position with bitboards plus a mailbox, incremental Zobrist hashing and a
 * make/unmake history. Move generation is pseudo-legal; {@link #legalMoves} filters it.
 */
public final class Position {
	public static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

	public static final int WK = 1, WQ = 2, BK = 4, BQ = 8;

	/** Upper bound on moves made from one position object (game plus search). */
	private static final int MAX_HISTORY = 6200;

	/** Castling rights that survive a move touching each square. */
	private static final int[] CASTLE_KEEP = new int[64];

	static {
		Arrays.fill(CASTLE_KEEP, 15);
		CASTLE_KEEP[0] &= ~WQ;
		CASTLE_KEEP[7] &= ~WK;
		CASTLE_KEEP[4] &= ~(WK | WQ);
		CASTLE_KEEP[56] &= ~BQ;
		CASTLE_KEEP[63] &= ~BK;
		CASTLE_KEEP[60] &= ~(BK | BQ);
	}

	public final long[] bb = new long[Piece.COUNT];
	public final long[] occ = new long[2];
	public long all;
	public final int[] board = new int[64];

	public int side;
	public int castling;
	/** En passant target square, set only when an enemy pawn could capture there. */
	public int ep = -1;
	public int halfmove;
	public int fullmove = 1;
	public long hash;

	private int ply;
	private final long[] hHash = new long[MAX_HISTORY];
	private final int[] hMove = new int[MAX_HISTORY];
	private final int[] hCaptured = new int[MAX_HISTORY];
	/** Packed castling | (ep + 1) << 4 | halfmove << 11. */
	private final int[] hState = new int[MAX_HISTORY];

	public Position() {
		Arrays.fill(board, Piece.NONE);
	}

	public static Position startPosition() {
		return fromFen(START_FEN);
	}

	// ------------------------------------------------------------------ setup

	/** Parses a FEN string; throws {@link IllegalArgumentException} on malformed input. */
	public static Position fromFen(String fen) {
		Position p = new Position();
		String[] parts = fen.trim().split("\\s+");
		if (parts.length < 2) throw new IllegalArgumentException("FEN needs at least board and side");
		String[] ranks = parts[0].split("/");
		if (ranks.length != 8) throw new IllegalArgumentException("FEN board must have 8 ranks");
		for (int r = 0; r < 8; r++) {
			int rank = 7 - r;
			int file = 0;
			for (char c : ranks[r].toCharArray()) {
				if (c >= '1' && c <= '8') {
					file += c - '0';
				} else {
					int piece = Piece.fromFenChar(c);
					if (piece < 0 || file > 7) throw new IllegalArgumentException("Bad FEN piece: " + c);
					p.addPiece(rank * 8 + file, piece);
					file++;
				}
			}
			if (file != 8) throw new IllegalArgumentException("FEN rank " + (rank + 1) + " has wrong length");
		}
		p.side = parts[1].equals("b") ? Piece.BLACK : Piece.WHITE;
		int rights = 0;
		if (parts.length > 2) {
			for (char c : parts[2].toCharArray()) {
				switch (c) {
					case 'K' -> rights |= WK;
					case 'Q' -> rights |= WQ;
					case 'k' -> rights |= BK;
					case 'q' -> rights |= BQ;
					default -> {
					}
				}
			}
		}
		p.castling = rights & p.possibleCastling();
		if (parts.length > 3 && !parts[3].equals("-")) {
			int sq = Move.parseSquare(parts[3]);
			if (sq >= 0 && p.epCapturePossible(sq)) p.ep = sq;
		}
		if (parts.length > 4) p.halfmove = parseIntOr(parts[4], 0);
		if (parts.length > 5) p.fullmove = Math.max(1, parseIntOr(parts[5], 1));
		p.hash = p.computeHash();
		return p;
	}

	private static int parseIntOr(String s, int fallback) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** Castling rights consistent with king and rook placement. */
	public int possibleCastling() {
		int r = 0;
		if (board[4] == Piece.make(Piece.WHITE, Piece.KING)) {
			if (board[7] == Piece.make(Piece.WHITE, Piece.ROOK)) r |= WK;
			if (board[0] == Piece.make(Piece.WHITE, Piece.ROOK)) r |= WQ;
		}
		if (board[60] == Piece.make(Piece.BLACK, Piece.KING)) {
			if (board[63] == Piece.make(Piece.BLACK, Piece.ROOK)) r |= BK;
			if (board[56] == Piece.make(Piece.BLACK, Piece.ROOK)) r |= BQ;
		}
		return r;
	}

	private boolean epCapturePossible(int sq) {
		int us = side, them = us ^ 1;
		int rank = sq >> 3;
		if (rank != (us == Piece.WHITE ? 5 : 2)) return false;
		int pawnSq = sq + (us == Piece.WHITE ? -8 : 8);
		if (board[pawnSq] != Piece.make(them, Piece.PAWN) || board[sq] != Piece.NONE) return false;
		return (Attacks.PAWN[them][sq] & bb[Piece.make(us, Piece.PAWN)]) != 0;
	}

	public String toFen() {
		StringBuilder sb = new StringBuilder();
		for (int rank = 7; rank >= 0; rank--) {
			int empty = 0;
			for (int file = 0; file < 8; file++) {
				int pc = board[rank * 8 + file];
				if (pc == Piece.NONE) {
					empty++;
				} else {
					if (empty > 0) sb.append(empty);
					empty = 0;
					sb.append(Piece.fenChar(pc));
				}
			}
			if (empty > 0) sb.append(empty);
			if (rank > 0) sb.append('/');
		}
		sb.append(side == Piece.WHITE ? " w " : " b ");
		if (castling == 0) sb.append('-');
		if ((castling & WK) != 0) sb.append('K');
		if ((castling & WQ) != 0) sb.append('Q');
		if ((castling & BK) != 0) sb.append('k');
		if ((castling & BQ) != 0) sb.append('q');
		sb.append(' ').append(ep >= 0 ? Move.squareName(ep) : "-");
		sb.append(' ').append(halfmove).append(' ').append(fullmove);
		return sb.toString();
	}

	public long computeHash() {
		long h = 0;
		for (int sq = 0; sq < 64; sq++) {
			if (board[sq] != Piece.NONE) h ^= Zobrist.PIECE[board[sq]][sq];
		}
		h ^= Zobrist.CASTLING[castling];
		if (ep >= 0) h ^= Zobrist.EP_FILE[ep & 7];
		if (side == Piece.BLACK) h ^= Zobrist.SIDE;
		return h;
	}

	/** Deep copy including move history (needed for repetition checks in search). */
	public Position copy() {
		Position p = new Position();
		System.arraycopy(bb, 0, p.bb, 0, bb.length);
		System.arraycopy(occ, 0, p.occ, 0, 2);
		System.arraycopy(board, 0, p.board, 0, 64);
		p.all = all;
		p.side = side;
		p.castling = castling;
		p.ep = ep;
		p.halfmove = halfmove;
		p.fullmove = fullmove;
		p.hash = hash;
		p.ply = ply;
		System.arraycopy(hHash, 0, p.hHash, 0, ply);
		System.arraycopy(hMove, 0, p.hMove, 0, ply);
		System.arraycopy(hCaptured, 0, p.hCaptured, 0, ply);
		System.arraycopy(hState, 0, p.hState, 0, ply);
		return p;
	}

	// ------------------------------------------------------------------ piece bookkeeping

	private void addPiece(int sq, int piece) {
		long bit = 1L << sq;
		bb[piece] |= bit;
		occ[Piece.color(piece)] |= bit;
		all |= bit;
		board[sq] = piece;
		hash ^= Zobrist.PIECE[piece][sq];
	}

	private void removePiece(int sq) {
		int piece = board[sq];
		long bit = 1L << sq;
		bb[piece] &= ~bit;
		occ[Piece.color(piece)] &= ~bit;
		all &= ~bit;
		board[sq] = Piece.NONE;
		hash ^= Zobrist.PIECE[piece][sq];
	}

	private void movePiece(int from, int to) {
		int piece = board[from];
		long mask = (1L << from) | (1L << to);
		bb[piece] ^= mask;
		occ[Piece.color(piece)] ^= mask;
		all ^= mask;
		board[from] = Piece.NONE;
		board[to] = piece;
		hash ^= Zobrist.PIECE[piece][from] ^ Zobrist.PIECE[piece][to];
	}

	// ------------------------------------------------------------------ make / unmake

	public void makeMove(int move) {
		int from = Move.from(move), to = Move.to(move), flags = Move.flags(move);
		int us = side, them = us ^ 1;
		int piece = board[from];

		hHash[ply] = hash;
		hMove[ply] = move;
		hState[ply] = castling | ((ep + 1) << 4) | (halfmove << 11);

		if (ep >= 0) hash ^= Zobrist.EP_FILE[ep & 7];
		ep = -1;
		halfmove++;

		int captured = Piece.NONE;
		if (flags == Move.EN_PASSANT) {
			int capSq = to + (us == Piece.WHITE ? -8 : 8);
			captured = board[capSq];
			removePiece(capSq);
		} else if ((flags & 4) != 0) {
			captured = board[to];
			removePiece(to);
		}
		hCaptured[ply] = captured;

		movePiece(from, to);

		if ((flags & 8) != 0) {
			removePiece(to);
			addPiece(to, Piece.make(us, Move.promoType(move)));
		}

		if (Piece.type(piece) == Piece.PAWN || captured != Piece.NONE) halfmove = 0;

		if (flags == Move.DOUBLE_PUSH) {
			int epSq = (from + to) >> 1;
			if ((Attacks.PAWN[us][epSq] & bb[Piece.make(them, Piece.PAWN)]) != 0) {
				ep = epSq;
				hash ^= Zobrist.EP_FILE[epSq & 7];
			}
		} else if (flags == Move.CASTLE_KING) {
			if (us == Piece.WHITE) movePiece(7, 5);
			else movePiece(63, 61);
		} else if (flags == Move.CASTLE_QUEEN) {
			if (us == Piece.WHITE) movePiece(0, 3);
			else movePiece(56, 59);
		}

		int newCastling = castling & CASTLE_KEEP[from] & CASTLE_KEEP[to];
		if (newCastling != castling) {
			hash ^= Zobrist.CASTLING[castling] ^ Zobrist.CASTLING[newCastling];
			castling = newCastling;
		}

		side = them;
		hash ^= Zobrist.SIDE;
		if (us == Piece.BLACK) fullmove++;
		ply++;
	}

	public void unmakeMove() {
		ply--;
		int move = hMove[ply];
		int from = Move.from(move), to = Move.to(move), flags = Move.flags(move);
		side ^= 1;
		int us = side;

		if ((flags & 8) != 0) {
			removePiece(to);
			addPiece(to, Piece.make(us, Piece.PAWN));
		}
		movePiece(to, from);

		if (flags == Move.CASTLE_KING) {
			if (us == Piece.WHITE) movePiece(5, 7);
			else movePiece(61, 63);
		} else if (flags == Move.CASTLE_QUEEN) {
			if (us == Piece.WHITE) movePiece(3, 0);
			else movePiece(59, 56);
		}

		int captured = hCaptured[ply];
		if (captured != Piece.NONE) {
			if (flags == Move.EN_PASSANT) addPiece(to + (us == Piece.WHITE ? -8 : 8), captured);
			else addPiece(to, captured);
		}

		int state = hState[ply];
		castling = state & 15;
		ep = ((state >>> 4) & 127) - 1;
		halfmove = state >>> 11;
		hash = hHash[ply];
		if (us == Piece.BLACK) fullmove--;
	}

	public void makeNullMove() {
		hHash[ply] = hash;
		hMove[ply] = Move.NONE;
		hCaptured[ply] = Piece.NONE;
		hState[ply] = castling | ((ep + 1) << 4) | (halfmove << 11);
		if (ep >= 0) hash ^= Zobrist.EP_FILE[ep & 7];
		ep = -1;
		halfmove++;
		side ^= 1;
		hash ^= Zobrist.SIDE;
		ply++;
	}

	public void unmakeNullMove() {
		ply--;
		side ^= 1;
		int state = hState[ply];
		castling = state & 15;
		ep = ((state >>> 4) & 127) - 1;
		halfmove = state >>> 11;
		hash = hHash[ply];
	}

	public int historyLength() {
		return ply;
	}

	public int lastMove() {
		return ply > 0 ? hMove[ply - 1] : Move.NONE;
	}

	/** True if the current position occurred before since the last irreversible move. */
	public boolean isRepetition() {
		int stop = Math.max(0, ply - halfmove);
		for (int i = ply - 2; i >= stop; i -= 2) {
			if (hHash[i] == hash) return true;
		}
		return false;
	}

	/** Number of earlier occurrences of the current position in the history. */
	public int repetitionCount() {
		int count = 0;
		int stop = Math.max(0, ply - halfmove);
		for (int i = ply - 2; i >= stop; i -= 2) {
			if (hHash[i] == hash) count++;
		}
		return count;
	}

	// ------------------------------------------------------------------ attacks

	public int kingSquare(int color) {
		return Long.numberOfTrailingZeros(bb[Piece.make(color, Piece.KING)]);
	}

	public boolean isAttacked(int sq, int by) {
		if ((Attacks.PAWN[by ^ 1][sq] & bb[Piece.make(by, Piece.PAWN)]) != 0) return true;
		if ((Attacks.KNIGHT[sq] & bb[Piece.make(by, Piece.KNIGHT)]) != 0) return true;
		if ((Attacks.KING[sq] & bb[Piece.make(by, Piece.KING)]) != 0) return true;
		long queens = bb[Piece.make(by, Piece.QUEEN)];
		if ((Attacks.bishop(sq, all) & (bb[Piece.make(by, Piece.BISHOP)] | queens)) != 0) return true;
		return (Attacks.rook(sq, all) & (bb[Piece.make(by, Piece.ROOK)] | queens)) != 0;
	}

	/** All pieces of both colors attacking sq given an occupancy. */
	public long attackersTo(int sq, long occupancy) {
		long bishops = bb[Piece.make(0, Piece.BISHOP)] | bb[Piece.make(1, Piece.BISHOP)]
				| bb[Piece.make(0, Piece.QUEEN)] | bb[Piece.make(1, Piece.QUEEN)];
		long rooks = bb[Piece.make(0, Piece.ROOK)] | bb[Piece.make(1, Piece.ROOK)]
				| bb[Piece.make(0, Piece.QUEEN)] | bb[Piece.make(1, Piece.QUEEN)];
		return (Attacks.PAWN[Piece.BLACK][sq] & bb[Piece.make(Piece.WHITE, Piece.PAWN)])
				| (Attacks.PAWN[Piece.WHITE][sq] & bb[Piece.make(Piece.BLACK, Piece.PAWN)])
				| (Attacks.KNIGHT[sq] & (bb[Piece.make(0, Piece.KNIGHT)] | bb[Piece.make(1, Piece.KNIGHT)]))
				| (Attacks.KING[sq] & (bb[Piece.make(0, Piece.KING)] | bb[Piece.make(1, Piece.KING)]))
				| (Attacks.bishop(sq, occupancy) & bishops)
				| (Attacks.rook(sq, occupancy) & rooks);
	}

	public boolean inCheck() {
		return isAttacked(kingSquare(side), side ^ 1);
	}

	/** After makeMove: true if the side that just moved left its king attacked. */
	public boolean leftKingInCheck() {
		return isAttacked(kingSquare(side ^ 1), side);
	}

	public boolean hasNonPawnMaterial(int color) {
		return (occ[color] & ~bb[Piece.make(color, Piece.PAWN)] & ~bb[Piece.make(color, Piece.KING)]) != 0;
	}

	// ------------------------------------------------------------------ move generation

	/**
	 * Writes pseudo-legal moves into {@code out} starting at {@code start}.
	 * With {@code tactical} only captures and promotions are produced.
	 * Returns the index after the last move written.
	 */
	public int generate(int[] out, int start, boolean tactical) {
		int n = start;
		int us = side, them = us ^ 1;
		long own = occ[us], enemy = occ[them];
		long targets = tactical ? enemy : ~own;

		// Pawns
		long pawns = bb[Piece.make(us, Piece.PAWN)];
		int forward = us == Piece.WHITE ? 8 : -8;
		int startRank = us == Piece.WHITE ? 1 : 6;
		int promoRank = us == Piece.WHITE ? 7 : 0;
		while (pawns != 0) {
			int from = Long.numberOfTrailingZeros(pawns);
			pawns &= pawns - 1;
			int one = from + forward;
			if (board[one] == Piece.NONE) {
				if ((one >> 3) == promoRank) {
					n = addPromotions(out, n, from, one, Move.PROMO);
				} else if (!tactical) {
					out[n++] = Move.of(from, one, Move.QUIET);
					int two = one + forward;
					if ((from >> 3) == startRank && board[two] == Piece.NONE) {
						out[n++] = Move.of(from, two, Move.DOUBLE_PUSH);
					}
				}
			}
			long caps = Attacks.PAWN[us][from] & enemy;
			while (caps != 0) {
				int to = Long.numberOfTrailingZeros(caps);
				caps &= caps - 1;
				if ((to >> 3) == promoRank) n = addPromotions(out, n, from, to, Move.PROMO_CAPTURE);
				else out[n++] = Move.of(from, to, Move.CAPTURE);
			}
			if (ep >= 0 && (Attacks.PAWN[us][from] & (1L << ep)) != 0) {
				out[n++] = Move.of(from, ep, Move.EN_PASSANT);
			}
		}

		// Knights
		long pieces = bb[Piece.make(us, Piece.KNIGHT)];
		while (pieces != 0) {
			int from = Long.numberOfTrailingZeros(pieces);
			pieces &= pieces - 1;
			n = addTargets(out, n, from, Attacks.KNIGHT[from] & targets, enemy);
		}
		// Bishops and queens (diagonal)
		pieces = bb[Piece.make(us, Piece.BISHOP)] | bb[Piece.make(us, Piece.QUEEN)];
		while (pieces != 0) {
			int from = Long.numberOfTrailingZeros(pieces);
			pieces &= pieces - 1;
			n = addTargets(out, n, from, Attacks.bishop(from, all) & targets, enemy);
		}
		// Rooks and queens (orthogonal)
		pieces = bb[Piece.make(us, Piece.ROOK)] | bb[Piece.make(us, Piece.QUEEN)];
		while (pieces != 0) {
			int from = Long.numberOfTrailingZeros(pieces);
			pieces &= pieces - 1;
			n = addTargets(out, n, from, Attacks.rook(from, all) & targets, enemy);
		}
		// King
		int king = kingSquare(us);
		n = addTargets(out, n, king, Attacks.KING[king] & targets, enemy);

		if (!tactical && castling != 0) {
			if (us == Piece.WHITE) {
				if ((castling & WK) != 0 && (all & 0x60L) == 0
						&& !isAttacked(4, them) && !isAttacked(5, them) && !isAttacked(6, them)) {
					out[n++] = Move.of(4, 6, Move.CASTLE_KING);
				}
				if ((castling & WQ) != 0 && (all & 0x0EL) == 0
						&& !isAttacked(4, them) && !isAttacked(3, them) && !isAttacked(2, them)) {
					out[n++] = Move.of(4, 2, Move.CASTLE_QUEEN);
				}
			} else {
				if ((castling & BK) != 0 && (all & (0x60L << 56)) == 0
						&& !isAttacked(60, them) && !isAttacked(61, them) && !isAttacked(62, them)) {
					out[n++] = Move.of(60, 62, Move.CASTLE_KING);
				}
				if ((castling & BQ) != 0 && (all & (0x0EL << 56)) == 0
						&& !isAttacked(60, them) && !isAttacked(59, them) && !isAttacked(58, them)) {
					out[n++] = Move.of(60, 58, Move.CASTLE_QUEEN);
				}
			}
		}
		return n;
	}

	private static int addPromotions(int[] out, int n, int from, int to, int base) {
		out[n++] = Move.of(from, to, base + 3);
		out[n++] = Move.of(from, to, base);
		out[n++] = Move.of(from, to, base + 2);
		out[n++] = Move.of(from, to, base + 1);
		return n;
	}

	private static int addTargets(int[] out, int n, int from, long targets, long enemy) {
		while (targets != 0) {
			int to = Long.numberOfTrailingZeros(targets);
			targets &= targets - 1;
			out[n++] = Move.of(from, to, (enemy & (1L << to)) != 0 ? Move.CAPTURE : Move.QUIET);
		}
		return n;
	}

	/** Fully legal moves of the side to move. */
	public int[] legalMoves() {
		int[] buf = new int[256];
		int n = generate(buf, 0, false);
		int legal = 0;
		for (int i = 0; i < n; i++) {
			makeMove(buf[i]);
			if (!leftKingInCheck()) buf[legal++] = buf[i];
			unmakeMove();
		}
		return Arrays.copyOf(buf, legal);
	}

	public boolean isLegal(int move) {
		for (int m : legalMoves()) {
			if (m == move) return true;
		}
		return false;
	}

	/** Finds the legal move matching from/to (and promotion type when promoting). */
	public int findMove(int from, int to, int promoType) {
		for (int m : legalMoves()) {
			if (Move.from(m) == from && Move.to(m) == to) {
				if (!Move.isPromotion(m) || Move.promoType(m) == promoType) return m;
			}
		}
		return Move.NONE;
	}

	public int parseUci(String uci) {
		if (uci.length() < 4) return Move.NONE;
		int from = Move.parseSquare(uci.substring(0, 2));
		int to = Move.parseSquare(uci.substring(2, 4));
		if (from < 0 || to < 0) return Move.NONE;
		int promo = Piece.QUEEN;
		if (uci.length() > 4) {
			int idx = "nbrq".indexOf(Character.toLowerCase(uci.charAt(4)));
			if (idx < 0) return Move.NONE;
			promo = Piece.KNIGHT + idx;
		}
		return findMove(from, to, promo);
	}

	public int pieceCount(int piece) {
		return Long.bitCount(bb[piece]);
	}

	public long perft(int depth) {
		if (depth == 0) return 1;
		int[] buf = new int[256];
		int n = generate(buf, 0, false);
		long total = 0;
		for (int i = 0; i < n; i++) {
			makeMove(buf[i]);
			if (!leftKingInCheck()) total += depth == 1 ? 1 : perft(depth - 1);
			unmakeMove();
		}
		return total;
	}
}
