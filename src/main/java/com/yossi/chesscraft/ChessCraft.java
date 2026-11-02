package com.yossi.chesscraft;

import com.yossi.chesscraft.network.ChessActionPayload;
import com.yossi.chesscraft.registry.ModBlockEntities;
import com.yossi.chesscraft.registry.ModBlocks;
import com.yossi.chesscraft.registry.ModTab;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChessCraft implements ModInitializer {
	public static final String MOD_ID = "chesscraft";
	public static final Logger LOGGER = LoggerFactory.getLogger("ChessCraft");

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		ModBlocks.init();
		ModBlockEntities.init();
		ModTab.init();
		ChessActionPayload.registerServer();
		LOGGER.info("ChessCraft loaded");
	}
}
