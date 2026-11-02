package com.yossi.chesscraft.client;

import com.yossi.chesscraft.block.ChessBoardBlock;
import com.yossi.chesscraft.registry.ModBlockEntities;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public class ChessCraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BlockEntityRenderers.register(ModBlockEntities.CHESS_BOARD, ChessBoardRenderer::new);
		ChessBoardBlock.openScreen = pos -> Minecraft.getInstance().setScreen(new ChessScreen(pos));
	}
}
