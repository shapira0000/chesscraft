package com.yossi.chesscraft.chess.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yossi.chesscraft.chess.ChessGame;
import com.yossi.chesscraft.chess.Move;
import com.yossi.chesscraft.chess.Position;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class EngineTest {
	private static Searcher.Result think(String fen, long ms) {
		Searcher s = new Searcher(18);
		Searcher.Limits l = new Searcher.Limits();
		l.softTimeMs = ms;
		l.hardTimeMs = ms * 2;
		return s.search(Position.fromFen(fen), l, null);
	}

	@Test
	void bookLinesAreLegal() {
		for (String line : OpeningBook.LINES) {
			ChessGame g = new ChessGame();
			for (String uci : line.split(" ")) {
				int m = g.position().parseUci(uci);
				assertTrue(m != Move.NONE && g.play(m), "illegal book move " + uci + " in: " + line);
			}
		}
	}

	@Test
	void findsMateInOne() {
		Searcher.Result r = think("r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4", 300);
		assertEquals("h5f7", Move.toUci(r.bestMove));
		assertEquals(Searcher.MATE - 1, r.score);
	}

	@Test
	void findsBackRankMate() {
		Searcher.Result r = think("6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1", 300);
		assertEquals("d1d8", Move.toUci(r.bestMove));
	}

	@Test
	void tacticalSuite() {
		String[][] wac = {
				{"2rr3k/pp3pp1/1nnqbN1p/3pN3/2pP4/2P3Q1/PPB4P/R4RK1 w - - 0 1", "g3g6"},
				{"8/7p/5k2/5p2/p1p2P2/Pr1pPK2/1P1R3P/8 b - - 0 1", "b3b2"},
				{"5rk1/1ppb3p/p1pb4/6q1/3P1p1r/2P1R2P/PP1BQ1P1/5RKN w - - 0 1", "e3g3"},
				{"r1bq2rk/pp3pbp/2p1p1pQ/7P/3P4/2PB1N2/PP3PPR/2KR4 w - - 0 1", "h6h7"},
				{"5k2/6pp/p1qN4/1p1p4/3P4/2PKP2Q/PP3r2/3R4 b - - 0 1", "c6c4"},
		};
		int solved = 0;
		for (String[] t : wac) {
			Searcher.Result r = think(t[0], 1000);
			boolean ok = Move.toUci(r.bestMove).equals(t[1]);
			if (ok) solved++;
			System.out.printf("WAC %s -> %s (expected %s) depth %d score %d nodes %d%n",
					ok ? "OK  " : "MISS", Move.toUci(r.bestMove), t[1], r.depth, r.score, r.nodes);
		}
		assertTrue(solved >= 4, "solved only " + solved + "/5");
	}

	private static void assertConverts(String fen, int maxMoves) {
		ChessGame g = new ChessGame(fen);
		ChessBot bot = new ChessBot();
		Random rnd = new Random(1);
		while (!g.isOver() && g.moves().size() < maxMoves * 2) {
			ChessBot.Choice c = bot.choose(g, 3200, 200, rnd, null);
			assertTrue(g.play(c.move()));
		}
		assertEquals(ChessGame.Reason.CHECKMATE, g.reason(), "did not mate: " + g.sanMoves());
	}

	@Test
	void convertsQueenVsKing() {
		assertConverts("8/8/8/4k3/8/8/8/4K2Q w - - 0 1", 25);
	}

	@Test
	void convertsRookVsKing() {
		assertConverts("8/8/8/4k3/8/8/8/R3K3 w - - 0 1", 40);
	}

	@Test
	void levelsAreMonotonic() {
		ChessBot.Level prev = ChessBot.level(100);
		for (int elo = 150; elo <= 3200; elo += 50) {
			ChessBot.Level lv = ChessBot.level(elo);
			if (prev.usesRootScoring() && lv.usesRootScoring()) {
				assertTrue(lv.depth() >= prev.depth() && lv.noise() <= prev.noise() && lv.randomMove() <= prev.randomMove(), "elo " + elo);
			} else if (!prev.usesRootScoring()) {
				assertTrue(lv.maxNodes() >= prev.maxNodes() && lv.timeMs() >= prev.timeMs(), "elo " + elo);
			}
			prev = lv;
		}
	}

	@Test
	void strongBeatsWeak() {
		// A 2400 bot should comfortably beat a 400 bot.
		ChessBot strong = new ChessBot(), weak = new ChessBot();
		Random rnd = new Random(7);
		int strongPoints = 0;
		for (int game = 0; game < 2; game++) {
			ChessGame g = new ChessGame();
			int strongColor = game % 2;
			while (!g.isOver() && g.moves().size() < 300) {
				boolean strongTurn = g.sideToMove() == strongColor;
				ChessBot.Choice c = (strongTurn ? strong : weak).choose(g, strongTurn ? 2400 : 400, 300, rnd, null);
				assertTrue(g.play(c.move()));
			}
			ChessGame.Result want = strongColor == 0 ? ChessGame.Result.WHITE_WINS : ChessGame.Result.BLACK_WINS;
			System.out.println("game " + game + ": " + g.result() + " " + g.reason() + " in " + g.moves().size() + " plies");
			if (g.result() == want) strongPoints++;
		}
		assertEquals(2, strongPoints);
	}

	@Test
	void benchmark() {
		String[] fens = {
				Position.START_FEN,
				"r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
				"r1bq1rk1/pp2bppp/2n1pn2/3p4/2PP4/2N1PN2/PP1B1PPP/R2QKB1R w KQ - 0 8",
		};
		List<String> out = new ArrayList<>();
		for (String fen : fens) {
			Searcher.Result r = think(fen, 1500);
			out.add(String.format("depth %d, %d nodes in %d ms (%d knps), best %s score %d",
					r.depth, r.nodes, r.timeMs, r.nodes / Math.max(1, r.timeMs), Move.toUci(r.bestMove), r.score));
		}
		out.forEach(System.out::println);
	}
}
