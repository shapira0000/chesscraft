package com.yossi.chesscraft.registry;

import com.yossi.chesscraft.ChessCraft;
import com.yossi.chesscraft.chess.Piece;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

public final class ModTab {
	public static final CreativeModeTab TAB = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, ChessCraft.id("chesscraft"),
			FabricItemGroup.builder()
					.title(Component.translatable("itemGroup.chesscraft"))
					.icon(() -> new ItemStack(ModBlocks.PIECE_ITEMS[Piece.make(Piece.WHITE, Piece.KING)]))
					.displayItems((params, output) -> {
						output.accept(ModBlocks.TABLE_BASE_ITEM);
						output.accept(ModBlocks.BOARD_ITEM);
						for (int color = 0; color < 2; color++) {
							for (int type = Piece.KING; type >= Piece.PAWN; type--) {
								output.accept(ModBlocks.PIECE_ITEMS[Piece.make(color, type)]);
							}
						}
					})
					.build());

	private ModTab() {
	}

	public static void init() {
	}
}
