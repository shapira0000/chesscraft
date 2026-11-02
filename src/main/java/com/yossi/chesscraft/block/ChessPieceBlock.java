package com.yossi.chesscraft.block;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yossi.chesscraft.chess.Piece;
import com.yossi.chesscraft.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** A decorative chess piece that can stand in the world facing any of 16 directions. */
public class ChessPieceBlock extends Block {
	public static final MapCodec<ChessPieceBlock> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Codec.INT.fieldOf("piece").forGetter(ChessPieceBlock::piece),
			propertiesCodec()
	).apply(i, ChessPieceBlock::new));

	public static final IntegerProperty ROTATION = BlockStateProperties.ROTATION_16;

	/** Rough body radius and height (in pixels) per piece type, for the outline/collision shape. */
	private static final double[][] SIZE = {{2.75, 9.25}, {3.5, 14}, {2.9, 14}, {3.9, 13}, {3.1, 15.5}, {3.3, 16}};

	private final int piece;
	private final VoxelShape shape;

	public ChessPieceBlock(int piece, Properties properties) {
		super(properties);
		this.piece = piece;
		double[] s = SIZE[Piece.type(piece)];
		this.shape = Block.box(8 - s[0], 0, 8 - s[0], 8 + s[0], s[1], 8 + s[0]);
		registerDefaultState(stateDefinition.any().setValue(ROTATION, 0));
	}

	public int piece() {
		return piece;
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(ROTATION);
	}

	@Override
	public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
		// Pieces on a board live inside it; never stand a decorative piece on top of one.
		if (context.getLevel().getBlockState(context.getClickedPos().below()).is(ModBlocks.BOARD)) return null;
		return defaultBlockState().setValue(ROTATION, RotationSegment.convertToSegment(context.getRotation()));
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shape;
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(ROTATION, rotation.rotate(state.getValue(ROTATION), 16));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.setValue(ROTATION, mirror.mirror(state.getValue(ROTATION), 16));
	}
}
