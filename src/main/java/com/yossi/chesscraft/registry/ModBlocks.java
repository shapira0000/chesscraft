package com.yossi.chesscraft.registry;

import com.yossi.chesscraft.ChessCraft;
import com.yossi.chesscraft.block.ChessBoardBlock;
import com.yossi.chesscraft.block.ChessPieceBlock;
import com.yossi.chesscraft.block.ChessTableBaseBlock;
import com.yossi.chesscraft.chess.Piece;
import java.util.function.Function;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

public final class ModBlocks {
	public static final Block TABLE_BASE = register("chess_table_base", ChessTableBaseBlock::new,
			BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.0f, 3.0f).sound(SoundType.WOOD).noOcclusion());

	public static final Block BOARD = register("chess_board", ChessBoardBlock::new,
			BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(1.5f, 3.0f).sound(SoundType.WOOD)
					.noOcclusion().pushReaction(PushReaction.BLOCK));

	/** Indexed by {@link Piece#make(int, int)}. */
	public static final Block[] PIECES = new Block[Piece.COUNT];

	public static final Item TABLE_BASE_ITEM = Items.registerBlock(TABLE_BASE);
	public static final Item BOARD_ITEM = Items.registerBlock(BOARD, p -> p.stacksTo(16));
	public static final Item[] PIECE_ITEMS = new Item[Piece.COUNT];

	static {
		for (int piece = 0; piece < Piece.COUNT; piece++) {
			final int pc = piece;
			MapColor color = Piece.color(piece) == Piece.WHITE ? MapColor.QUARTZ : MapColor.COLOR_BLACK;
			PIECES[piece] = register(Piece.id(piece), props -> new ChessPieceBlock(pc, props),
					BlockBehaviour.Properties.of().mapColor(color).strength(0.8f, 2.0f).sound(SoundType.WOOD)
							.noOcclusion().pushReaction(PushReaction.DESTROY));
			PIECE_ITEMS[piece] = Items.registerBlock(PIECES[piece]);
		}
	}

	private ModBlocks() {
	}

	private static Block register(String name, Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties props) {
		return Blocks.register(ResourceKey.create(Registries.BLOCK, ChessCraft.id(name)), factory, props);
	}

	public static Block piece(int piece) {
		return PIECES[piece];
	}

	/** The piece index for a block, or {@link Piece#NONE}. */
	public static int pieceOf(Block block) {
		return block instanceof ChessPieceBlock p ? p.piece() : Piece.NONE;
	}

	public static void init() {
	}
}
