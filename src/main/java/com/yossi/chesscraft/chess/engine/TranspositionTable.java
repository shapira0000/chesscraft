package com.yossi.chesscraft.chess.engine;

/**
 * Single-threaded transposition table. Each slot stores the full key plus packed data:
 * move (16) | score (16) | static eval (16) | depth (8) | bound (2) | age (6).
 */
final class TranspositionTable {
	static final int EXACT = 1, LOWER = 2, UPPER = 3;

	private final long[] keys;
	private final long[] data;
	private final int mask;
	private int age;

	// Results of the last probe.
	int move, score, eval, depth, bound;

	TranspositionTable(int log2Entries) {
		keys = new long[1 << log2Entries];
		data = new long[1 << log2Entries];
		mask = (1 << log2Entries) - 1;
	}

	void newSearch() {
		age = (age + 1) & 63;
	}

	void clear() {
		java.util.Arrays.fill(keys, 0);
		java.util.Arrays.fill(data, 0);
	}

	boolean probe(long key) {
		int i = (int) key & mask;
		if (keys[i] != key || data[i] == 0) return false;
		long d = data[i];
		move = (int) (d & 0xFFFF);
		score = (short) (d >>> 16);
		eval = (short) (d >>> 32);
		depth = (int) ((d >>> 48) & 0xFF);
		bound = (int) ((d >>> 56) & 3);
		return true;
	}

	void store(long key, int move, int score, int eval, int depth, int bound) {
		int i = (int) key & mask;
		long old = data[i];
		if (keys[i] == key || old == 0) {
			// Same position: keep the old best move if the new search has none.
			if (move == 0 && keys[i] == key) move = (int) (old & 0xFFFF);
		} else {
			int oldDepth = (int) ((old >>> 48) & 0xFF);
			int oldAge = (int) (old >>> 58);
			if (oldAge == age && oldDepth > depth + 2 && bound != EXACT) return;
		}
		keys[i] = key;
		data[i] = (move & 0xFFFFL)
				| ((score & 0xFFFFL) << 16)
				| ((eval & 0xFFFFL) << 32)
				| ((long) Math.max(0, Math.min(255, depth)) << 48)
				| ((long) bound << 56)
				| ((long) age << 58);
	}
}
