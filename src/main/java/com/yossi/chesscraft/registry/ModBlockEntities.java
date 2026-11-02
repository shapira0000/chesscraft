package com.yossi.chesscraft.registry;

import com.yossi.chesscraft.ChessCraft;
import com.yossi.chesscraft.block.entity.ChessBoardBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class ModBlockEntities {
	public static final BlockEntityType<ChessBoardBlockEntity> CHESS_BOARD = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,
			ChessCraft.id("chess_board"),
			FabricBlockEntityTypeBuilder.create(ChessBoardBlockEntity::new, ModBlocks.BOARD).build());

	private ModBlockEntities() {
	}

	public static void init() {
	}
}
