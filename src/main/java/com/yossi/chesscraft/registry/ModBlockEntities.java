package com.yossi.chesscraft.registry;

import com.yossi.chesscraft.ChessCraft;
import com.yossi.chesscraft.block.entity.ChessBoardBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class ModBlockEntities {
	public static BlockEntityType<ChessBoardBlockEntity> CHESS_BOARD;

	private ModBlockEntities() {
	}

	public static void init(BlockEntityType<ChessBoardBlockEntity> type) {
		CHESS_BOARD = ChessCraft.registrar.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
				ResourceKey.create(Registries.BLOCK_ENTITY_TYPE, ChessCraft.id("chess_board")), type);
	}
}
