package com.yossi.chesscraft.chess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RulesTest {
	private static void perft(String fen, long... expected) {
		Position p = Position.fromFen(fen);
		for (int d = 1; d <= expected.length; d++) {
			assertEquals(expected[d - 1], p.perft(d), "perft(" + d + ") of " + fen);
		}
		assertEquals(Position.fromFen(fen).toFen(), p.toFen(), "make/unmake must restore the position");
		assertEquals(p.computeHash(), p.hash, "incremental hash must match");
	}

	@Test
	void perftStart() {
		perft(Position.START_FEN, 20, 400, 8902, 197281, 4865609);
	}

	@Test
	void perftKiwipete() {
		perft("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1", 48, 2039, 97862, 4085603);
	}

	@Test
	void perftPosition3() {
		perft("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", 14, 191, 2812, 43238, 674624);
	}

	@Test
	void perftPosition4() {
		perft("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1", 6, 264, 9467, 422333);
	}

	@Test
	void perftPosition5() {
		perft("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8", 44, 1486, 62379, 2103487);
	}

	@Test
	void fenRoundTrip() {
		String fen = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1";
		assertEquals(fen, Position.fromFen(fen).toFen());
	}

	@Test
	void foolsMateAndSan() {
		ChessGame g = ChessGame.replay(Position.START_FEN, List.of("f2f3", "e7e5", "g2g4", "d8h4"));
		assertEquals(ChessGame.Result.BLACK_WINS, g.result());
		assertEquals(ChessGame.Reason.CHECKMATE, g.reason());
		assertEquals(List.of("f3", "e5", "g4", "Qh4#"), g.sanMoves());
	}

	@Test
	void sanDisambiguationCastlingPromotion() {
		Position p = Position.fromFen("r3k2r/1P6/8/8/8/8/8/R3K2R w KQkq - 0 1");
		assertEquals("O-O", Notation.toSan(p, p.parseUci("e1g1")));
		assertEquals("O-O-O", Notation.toSan(p, p.parseUci("e1c1")));
		assertEquals("bxa8=Q+", Notation.toSan(p, p.parseUci("b7a8q")));
		assertEquals("b8=N", Notation.toSan(p, p.parseUci("b7b8n")));
		Position q = Position.fromFen("4k3/8/8/8/8/8/8/R4RK1 w - - 0 1");
		assertEquals("Rad1", Notation.toSan(q, q.parseUci("a1d1")));
		Position r = Position.fromFen("4k3/8/8/R7/8/8/8/R3K3 w - - 0 1");
		assertEquals("R1a3", Notation.toSan(r, r.parseUci("a1a3")));
	}

	@Test
	void enPassantAndHashOnlyWhenCapturable() {
		ChessGame g = ChessGame.replay(Position.START_FEN, List.of("e2e4", "a7a6", "e4e5", "d7d5"));
		Position p = g.position();
		assertEquals(Move.parseSquare("d6"), p.ep);
		int ep = p.parseUci("e5d6");
		assertTrue(Move.isEnPassant(ep));
		assertEquals("exd6", Notation.toSan(p, ep));
		Position noEp = Position.fromFen(Position.START_FEN);
		noEp.makeMove(noEp.parseUci("e2e4"));
		assertEquals(-1, noEp.ep, "no enemy pawn can capture, so no ep square");
	}

	@Test
	void stalemate() {
		ChessGame g = new ChessGame("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1");
		assertEquals(ChessGame.Result.DRAW, g.result());
		assertEquals(ChessGame.Reason.STALEMATE, g.reason());
	}

	@Test
	void threefoldRepetition() {
		ChessGame g = ChessGame.replay(Position.START_FEN,
				List.of("g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1", "f6g8"));
		assertEquals(ChessGame.Reason.REPETITION, g.reason());
		assertEquals(8, g.moves().size());
	}

	@Test
	void fiftyMoveRule() {
		ChessGame g = new ChessGame("4k3/8/8/8/8/8/8/R3K3 w - - 99 80");
		assertFalse(g.isOver());
		assertTrue(g.play(g.position().parseUci("a1a2")));
		assertEquals(ChessGame.Reason.FIFTY_MOVES, g.reason());
	}

	@Test
	void mateOnHundredthHalfmoveBeatsFiftyMoveRule() {
		ChessGame g = new ChessGame("7k/8/6K1/8/8/8/8/R7 w - - 99 80");
		assertTrue(g.play(g.position().parseUci("a1a8")));
		assertEquals(ChessGame.Reason.CHECKMATE, g.reason());
	}

	@Test
	void insufficientMaterial() {
		assertTrue(ChessGame.isInsufficientMaterial(Position.fromFen("8/8/4k3/8/8/2KB4/8/8 w - - 0 1")));
		assertTrue(ChessGame.isInsufficientMaterial(Position.fromFen("8/8/4k3/8/8/2KN4/8/8 w - - 0 1")));
		assertFalse(ChessGame.isInsufficientMaterial(Position.fromFen("8/2b5/4k3/8/8/2KB4/8/8 w - - 0 1")));
		assertTrue(ChessGame.isInsufficientMaterial(Position.fromFen("8/3b4/4k3/8/8/2KB4/8/8 w - - 0 1")));
		assertFalse(ChessGame.isInsufficientMaterial(Position.fromFen("8/8/4k3/8/8/2KNN3/8/8 w - - 0 1")));
	}

	@Test
	void castlingRightsAndThroughCheck() {
		Position p = Position.fromFen("4k3/8/8/8/8/8/5r2/R3K2R w KQ - 0 1");
		assertEquals(Move.NONE, p.parseUci("e1g1"), "cannot castle through f1 attacked? f2 rook attacks f1");
		assertTrue(p.parseUci("e1c1") != Move.NONE);
		p.makeMove(p.parseUci("h1h2"));
		assertEquals(Position.WQ, p.castling);
	}

	@Test
	void undoRestores() {
		ChessGame g = ChessGame.replay(Position.START_FEN, List.of("e2e4", "e7e5", "g1f3"));
		String fen = g.position().toFen();
		assertTrue(g.play(g.position().parseUci("b8c6")));
		assertTrue(g.undo());
		assertEquals(fen, g.position().toFen());
		assertEquals(3, g.sanMoves().size());
	}
}
