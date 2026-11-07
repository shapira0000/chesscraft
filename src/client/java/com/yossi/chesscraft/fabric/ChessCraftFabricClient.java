package com.yossi.chesscraft.fabric;

import com.yossi.chesscraft.client.ChessBoardRenderer;
import com.yossi.chesscraft.client.ChessCraftClient;
import com.yossi.chesscraft.network.ChessActionPayload;
import com.yossi.chesscraft.registry.ModBlockEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public class ChessCraftFabricClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ChessCraftClient.init();
		ChessActionPayload.clientSender = ClientPlayNetworking::send;
		BlockEntityRenderers.register(ModBlockEntities.CHESS_BOARD, ChessBoardRenderer::new);
	}
}
