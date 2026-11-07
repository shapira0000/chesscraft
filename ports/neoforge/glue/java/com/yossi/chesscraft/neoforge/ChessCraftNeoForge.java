package com.yossi.chesscraft.neoforge;

import com.yossi.chesscraft.ChessCraft;
import com.yossi.chesscraft.block.entity.ChessBoardBlockEntity;
import com.yossi.chesscraft.network.ChessActionPayload;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

@Mod(ChessCraft.MOD_ID)
public final class ChessCraftNeoForge {
	public ChessCraftNeoForge(IEventBus modBus) {
		// All registries are open during the RegisterEvent sequence; blocks come first.
		modBus.addListener(RegisterEvent.class, event -> {
			if (event.getRegistryKey().equals(Registries.BLOCK)) {
				ChessCraft.registerContent(board -> new BlockEntityType<>(ChessBoardBlockEntity::new, Set.of(board)),
						CreativeModeTab::builder);
			}
		});
		modBus.addListener(RegisterPayloadHandlersEvent.class, event -> event.registrar("1")
				.playToServer(ChessActionPayload.TYPE, ChessActionPayload.CODEC, (payload, context) -> {
					if (context.player() instanceof ServerPlayer player) ChessActionPayload.handle(payload, player);
				}));
	}
}
