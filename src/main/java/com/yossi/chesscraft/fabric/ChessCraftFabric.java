package com.yossi.chesscraft.fabric;

import com.yossi.chesscraft.ChessCraft;
import com.yossi.chesscraft.block.entity.ChessBoardBlockEntity;
import com.yossi.chesscraft.network.ChessActionPayload;
import net.fabricmc.api.ModInitializer;
//#if mc>=26.1
//$import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
//#else
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
//#endif
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;

public class ChessCraftFabric implements ModInitializer {
	@Override
	public void onInitialize() {
		ChessCraft.registerContent(board -> FabricBlockEntityTypeBuilder.create(ChessBoardBlockEntity::new, board).build(),
//#if mc>=26.1
//$				FabricCreativeModeTab::builder);
//$		PayloadTypeRegistry.serverboundPlay().register(ChessActionPayload.TYPE, ChessActionPayload.CODEC);
//#else
				FabricItemGroup::builder);
		PayloadTypeRegistry.playC2S().register(ChessActionPayload.TYPE, ChessActionPayload.CODEC);
//#endif
		ServerPlayNetworking.registerGlobalReceiver(ChessActionPayload.TYPE,
				(payload, context) -> ChessActionPayload.handle(payload, context.player()));
	}
}
