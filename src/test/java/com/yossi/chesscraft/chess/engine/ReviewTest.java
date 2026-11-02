package com.yossi.chesscraft.chess.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yossi.chesscraft.chess.ChessGame;
import com.yossi.chesscraft.chess.Position;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewTest {
	private static GameReview.Result review(String fen, List<String> moves) {
		ChessGame g = ChessGame.replay(fen, moves);
		return GameReview.analyze(g, new Searcher(18), 150, null, null);
	}

	private static void print(GameReview.Result r) {
		for (GameReview.MoveInfo m : r.moves) {
			System.out.printf("%2d %-7s %-10s loss %5.1f best %s%n", m.ply, m.san, m.kind, m.winLoss, m.bestSan);
		}
		System.out.printf("accuracy white %.1f black %.1f%n", r.accuracy[0], r.accuracy[1]);
	}

	@Test
	void foolsMate() {
		GameReview.Result r = review(Position.START_FEN, List.of("f2f3", "e7e5", "g2g4", "d8h4"));
		print(r);
		assertEquals(GameReview.Kind.BLUNDER, r.moves.get(2).kind, "g4 allows mate in one");
		assertEquals(GameReview.Kind.BEST, r.moves.get(3).kind, "Qh4# is the best move");
		assertTrue(r.accuracy[1] > r.accuracy[0]);
		assertTrue(GameReview.isMate(r.whiteEval[3]) && r.whiteEval[3] < 0, "after g4 black mates: " + r.whiteEval[3]);
	}

	@Test
	void hangingQueenIsBlunderAndBookIsBook() {
		// 1.e4 e5 2.Nf3 Nc6 3.Bc4 Qh4?? (hangs the queen to Nxh4)
		GameReview.Result r = review(Position.START_FEN, List.of("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "d8h4", "f3h4"));
		print(r);
		assertEquals(GameReview.Kind.BOOK, r.moves.get(0).kind);
		assertEquals(GameReview.Kind.BLUNDER, r.moves.get(5).kind);
		assertTrue(r.moves.get(6).kind == GameReview.Kind.BEST || r.moves.get(6).kind == GameReview.Kind.GREAT);
		assertTrue(r.whiteEval[7] > 500, "white is a queen up: " + r.whiteEval[7]);
	}

	@Test
	void queenSacrificeForMateIsBrilliant() {
		// Smothered mate: 1.Qg8+! Rxg8 2.Nf7#
		GameReview.Result r2 = review("4r2k/6pp/7N/3Q4/8/8/6PP/6K1 w - - 0 1", List.of("d5g8", "e8g8", "h6f7"));
		print(r2);
		assertEquals(GameReview.Kind.BRILLIANT, r2.moves.get(0).kind, "Qg8+ sacrifices the queen for mate");
	}

	@Test
	void formatting() {
		assertEquals("+1.5", GameReview.format(150));
		assertEquals("-0.3", GameReview.format(-30));
		assertEquals("M2", GameReview.format(GameReview.MATE_CP - 3));
		assertEquals("-M1", GameReview.format(-(GameReview.MATE_CP - 1)));
	}
}
