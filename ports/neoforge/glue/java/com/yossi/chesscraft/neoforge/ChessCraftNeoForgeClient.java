package com.yossi.chesscraft.neoforge;

import com.yossi.chesscraft.ChessCraft;
import com.yossi.chesscraft.client.ChessBoardRenderer;
import com.yossi.chesscraft.client.ChessCraftClient;
import com.yossi.chesscraft.network.ChessActionPayload;
import com.yossi.chesscraft.registry.ModBlockEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

@Mod(value = ChessCraft.MOD_ID, dist = Dist.CLIENT)
public final class ChessCraftNeoForgeClient {
	public ChessCraftNeoForgeClient(IEventBus modBus) {
		modBus.addListener(FMLClientSetupEvent.class, event -> {
			ChessCraftClient.init();
			ChessActionPayload.clientSender = ClientPacketDistributor::sendToServer;
		});
		modBus.addListener(EntityRenderersEvent.RegisterRenderers.class,
				event -> event.registerBlockEntityRenderer(ModBlockEntities.CHESS_BOARD, ChessBoardRenderer::new));
	}
}
