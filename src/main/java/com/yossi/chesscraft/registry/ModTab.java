package com.yossi.chesscraft.registry;

import com.yossi.chesscraft.ChessCraft;
import com.yossi.chesscraft.chess.Piece;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

public final class ModTab {
	public static CreativeModeTab TAB;

	private ModTab() {
	}

	public static void init(CreativeModeTab.Builder builder) {
		TAB = ChessCraft.registrar.register(BuiltInRegistries.CREATIVE_MODE_TAB,
				ResourceKey.create(Registries.CREATIVE_MODE_TAB, ChessCraft.id("chesscraft")), builder
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
	}
}
