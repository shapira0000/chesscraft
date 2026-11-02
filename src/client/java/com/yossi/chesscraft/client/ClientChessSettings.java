package com.yossi.chesscraft.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.yossi.chesscraft.ChessCraft;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Per-player chess preferences and last-used game options, saved in config/chesscraft-client.json. */
public final class ClientChessSettings {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ClientChessSettings instance;

	// Preferences
	public boolean premoves = true;
	public boolean showLegalMoves = true;
	public boolean highlightLastMove = true;

	// New game options
	/** "BOT" or "FRIEND". */
	public String opponent = "BOT";
	public boolean sameScreen = false;
	public int color = 0;
	public int eloWhite = 1200;
	public int eloBlack = 1500;
	public boolean botTakebacks = true;
	public boolean botHints = true;
	public int clockBase = 600;
	public int clockInc = 0;
	public int timeTab = 0;

	public static ClientChessSettings get() {
		if (instance == null) instance = load();
		return instance;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("chesscraft-client.json");
	}

	private static ClientChessSettings load() {
		try {
			Path f = file();
			if (Files.exists(f)) {
				ClientChessSettings s = GSON.fromJson(Files.readString(f), ClientChessSettings.class);
				if (s != null) return s;
			}
		} catch (IOException | RuntimeException e) {
			ChessCraft.LOGGER.warn("Could not read chess settings, using defaults", e);
		}
		return new ClientChessSettings();
	}

	public void save() {
		try {
			Files.writeString(file(), GSON.toJson(this));
		} catch (IOException e) {
			ChessCraft.LOGGER.warn("Could not save chess settings", e);
		}
	}

	/** Options string understood by the board's START action. */
	public String startOptions() {
		String opp = "BOT".equals(opponent) ? "BOT" : (sameScreen ? "LOCAL" : "FRIEND");
		boolean vsBot = "BOT".equals(opponent);
		return "opp=" + opp + ";color=" + color + ";eloW=" + eloWhite + ";eloB=" + eloBlack
				+ ";base=" + clockBase + ";inc=" + clockInc
				+ ";undo=" + (vsBot && botTakebacks ? 1 : 0) + ";hints=" + (vsBot && botHints ? 1 : 0);
	}

	public String timeLabel() {
		return timeLabel(clockBase, clockInc);
	}

	public static String timeLabel(int base, int inc) {
		if (base <= 0) return "No limit";
		String m = base >= 3600 && base % 3600 == 0 ? (base / 3600) + " hour" : (base / 60) + " min";
		return inc > 0 ? (base / 60) + " | " + inc : m;
	}
}
