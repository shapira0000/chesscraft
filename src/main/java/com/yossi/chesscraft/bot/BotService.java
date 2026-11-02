package com.yossi.chesscraft.bot;

import com.yossi.chesscraft.ChessCraft;
import com.yossi.chesscraft.chess.engine.ChessBot;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Background threads for engine searches; each thread keeps its own engine (and hash table). */
public final class BotService {
	private static final int THREADS = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() - 1));
	private static final AtomicInteger COUNTER = new AtomicInteger();
	private static final ThreadLocal<ChessBot> ENGINE = ThreadLocal.withInitial(ChessBot::new);
	private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(THREADS, r -> {
		Thread t = new Thread(r, "ChessCraft-Engine-" + COUNTER.incrementAndGet());
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});

	private BotService() {
	}

	public static <T> Future<T> submit(Function<ChessBot, T> job) {
		Callable<T> task = () -> {
			try {
				return job.apply(ENGINE.get());
			} catch (Throwable t) {
				ChessCraft.LOGGER.error("Chess engine task failed", t);
				throw t;
			}
		};
		return EXECUTOR.submit(task);
	}
}
