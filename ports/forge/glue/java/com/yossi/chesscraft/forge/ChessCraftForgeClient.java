package com.yossi.chesscraft.forge;

import com.yossi.chesscraft.client.ChessBoardRenderer;
import com.yossi.chesscraft.client.ChessCraftClient;
import com.yossi.chesscraft.network.ChessActionPayload;
import com.yossi.chesscraft.registry.ModBlockEntities;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.network.PacketDistributor;

/** Client-only setup; only loaded on the physical client. */
final class ChessCraftForgeClient {
	private ChessCraftForgeClient() {
	}

	static void init(BusGroup modBus) {
		FMLClientSetupEvent.getBus(modBus).addListener(event -> {
			ChessCraftClient.init();
			ChessActionPayload.clientSender = payload -> ChessCraftForge.CHANNEL.send(payload, PacketDistributor.SERVER.noArg());
		});
//#if mc>=26.1
//$		EntityRenderersEvent.RegisterRenderers.BUS.addListener(
//#else
		EntityRenderersEvent.RegisterRenderers.getBus(modBus).addListener(
//#endif
				event -> event.registerBlockEntityRenderer(ModBlockEntities.CHESS_BOARD, ChessBoardRenderer::new));
	}
}
