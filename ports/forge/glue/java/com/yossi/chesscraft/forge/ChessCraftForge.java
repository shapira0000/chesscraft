package com.yossi.chesscraft.forge;

import com.yossi.chesscraft.ChessCraft;
import com.yossi.chesscraft.block.entity.ChessBoardBlockEntity;
import com.yossi.chesscraft.network.ChessActionPayload;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.registries.RegisterEvent;

@Mod(ChessCraft.MOD_ID)
public final class ChessCraftForge {
	/** The board actions sent from the chess screen; handled on the server thread. */
	public static final Channel<CustomPacketPayload> CHANNEL = ChannelBuilder.named(ChessCraft.id("main"))
			.networkProtocolVersion(1)
			.payloadChannel()
			.play()
			.serverbound()
			.addMain(ChessActionPayload.TYPE, ChessActionPayload.CODEC, (payload, context) -> {
				ServerPlayer player = context.getSender();
				if (player != null) ChessActionPayload.handle(payload, player);
			})
			.build();

	public ChessCraftForge(FMLJavaModLoadingContext context) {
		BusGroup modBus = context.getModBusGroup();
		// Forge only unlocks the registry its RegisterEvent is for, so each kind registers in its own event.
		RegisterEvent.getBus(modBus).addListener(event -> {
			ChessCraft.registrar = new ChessCraft.Registrar() {
				@Override
				public <T, V extends T> V register(Registry<T> registry, ResourceKey<T> key, V value) {
					event.register(registry.key(), key.identifier(), () -> value);
					return value;
				}
			};
			ResourceKey<? extends Registry<?>> key = event.getRegistryKey();
			if (key.equals(Registries.BLOCK)) {
				ChessCraft.registerBlocks();
			} else if (key.equals(Registries.ITEM)) {
				ChessCraft.registerItems();
			} else if (key.equals(Registries.BLOCK_ENTITY_TYPE)) {
				ChessCraft.registerBlockEntities(board -> new BlockEntityType<>(ChessBoardBlockEntity::new, Set.of(board)));
			} else if (key.equals(Registries.CREATIVE_MODE_TAB)) {
				ChessCraft.registerTab(CreativeModeTab::builder);
			}
		});
		if (FMLEnvironment.dist == Dist.CLIENT) ChessCraftForgeClient.init(modBus);
	}
}
