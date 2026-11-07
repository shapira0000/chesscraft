package com.yossi.chesscraft.block;

//#if mc<26.3
import com.mojang.serialization.MapCodec;
//#endif
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Pedestal that carries a chess board. */
public class ChessTableBaseBlock extends Block {
//#if mc<26.3
	public static final MapCodec<ChessTableBaseBlock> CODEC = simpleCodec(ChessTableBaseBlock::new);
//#endif

	private static final VoxelShape SHAPE = Shapes.or(
			Block.box(1, 0, 1, 15, 2, 15),
			Block.box(5, 2, 5, 11, 13, 11),
			Block.box(4, 13, 4, 12, 14, 12),
			Block.box(2, 14, 2, 14, 16, 14));

	public ChessTableBaseBlock(Properties properties) {
		super(properties);
	}

//#if mc<26.3
	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}
//#endif

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}
}
