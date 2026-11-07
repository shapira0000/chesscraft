package com.yossi.chesscraft.registry;

import com.yossi.chesscraft.ChessCraft;
import com.yossi.chesscraft.block.ChessBoardBlock;
import com.yossi.chesscraft.block.ChessPieceBlock;
import com.yossi.chesscraft.block.ChessTableBaseBlock;
import com.yossi.chesscraft.chess.Piece;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

public final class ModBlocks {
	public static Block TABLE_BASE;
	public static Block BOARD;
	/** Indexed by {@link Piece#make(int, int)}. */
	public static final Block[] PIECES = new Block[Piece.COUNT];

	public static Item TABLE_BASE_ITEM;
	public static Item BOARD_ITEM;
	public static final Item[] PIECE_ITEMS = new Item[Piece.COUNT];

	private ModBlocks() {
	}

//#if mc>=26.3
//$	private static final PushReaction PUSH_BLOCK = PushReaction.IMMOVEABLE;
//$	private static final PushReaction PUSH_DESTROY = PushReaction.POPPED;
//#else
	private static final PushReaction PUSH_BLOCK = PushReaction.BLOCK;
	private static final PushReaction PUSH_DESTROY = PushReaction.DESTROY;
//#endif

	private static Item blockItem(Block block, UnaryOperator<Item.Properties> props) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, BuiltInRegistries.BLOCK.getKey(block));
		Item.Properties properties = props.apply(new Item.Properties().setId(key).useBlockDescriptionPrefix());
		BlockItem item = new BlockItem(block, properties);
		item.registerBlocks(Item.BY_BLOCK, item);
		return ChessCraft.registrar.register(BuiltInRegistries.ITEM, key, item);
	}

	private static Block register(String name, Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties props) {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, ChessCraft.id(name));
		return ChessCraft.registrar.register(BuiltInRegistries.BLOCK, key, factory.apply(props.setId(key)));
	}

	public static Block piece(int piece) {
		return PIECES[piece];
	}

	/** The piece index for a block, or {@link Piece#NONE}. */
	public static int pieceOf(Block block) {
		return block instanceof ChessPieceBlock p ? p.piece() : Piece.NONE;
	}

	public static void initBlocks() {
		TABLE_BASE = register("chess_table_base", ChessTableBaseBlock::new,
				BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.0f, 3.0f).sound(SoundType.WOOD).noOcclusion());
		BOARD = register("chess_board", ChessBoardBlock::new,
				BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(1.5f, 3.0f).sound(SoundType.WOOD)
						.noOcclusion().pushReaction(PUSH_BLOCK));
		for (int piece = 0; piece < Piece.COUNT; piece++) {
			final int pc = piece;
			MapColor color = Piece.color(piece) == Piece.WHITE ? MapColor.QUARTZ : MapColor.COLOR_BLACK;
			PIECES[piece] = register(Piece.id(piece), props -> new ChessPieceBlock(pc, props),
					BlockBehaviour.Properties.of().mapColor(color).strength(0.8f, 2.0f).sound(SoundType.WOOD)
							.noOcclusion().pushReaction(PUSH_DESTROY));
		}
	}

	public static void initItems() {
		TABLE_BASE_ITEM = blockItem(TABLE_BASE, p -> p);
		BOARD_ITEM = blockItem(BOARD, p -> p.stacksTo(16));
		for (int piece = 0; piece < Piece.COUNT; piece++) {
			PIECE_ITEMS[piece] = blockItem(PIECES[piece], p -> p);
		}
	}
}
