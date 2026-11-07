package com.yossi.chesscraft.client;

import com.yossi.chesscraft.block.ChessBoardBlock;
import net.minecraft.client.Minecraft;

/** Loader-independent client setup; the renderer and the network sender are registered by each loader. */
public final class ChessCraftClient {
	private ChessCraftClient() {
	}

	public static void init() {
//#if mc>=26.2
//$		ChessBoardBlock.openScreen = pos -> Minecraft.getInstance().gui.setScreen(new ChessScreen(pos));
//#else
		ChessBoardBlock.openScreen = pos -> Minecraft.getInstance().setScreen(new ChessScreen(pos));
//#endif
	}
}
