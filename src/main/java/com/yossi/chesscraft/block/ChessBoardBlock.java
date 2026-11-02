package com.yossi.chesscraft.block;

import com.mojang.serialization.MapCodec;
import com.yossi.chesscraft.block.entity.ChessBoardBlockEntity;
import com.yossi.chesscraft.registry.ModBlockEntities;
import com.yossi.chesscraft.registry.ModBlocks;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * The 3x3 chess board. The centre block (part 4) holds the block entity and renders the
 * board model; the eight surrounding parts only provide shape and forward interaction.
 * Part index = (dx + 1) + (dz + 1) * 3 using world offsets from the centre.
 */
public class ChessBoardBlock extends BaseEntityBlock {
	public static final MapCodec<ChessBoardBlock> CODEC = simpleCodec(ChessBoardBlock::new);
	public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
	public static final IntegerProperty PART = IntegerProperty.create("part", 0, 8);
	public static final int CENTER = 4;

	private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 3.25, 16);

	/** Set by the client entrypoint; opens the chess screen for a board centre. */
	public static Consumer<BlockPos> openScreen = pos -> {
	};

	public ChessBoardBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, CENTER));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, PART);
	}

	public static BlockPos centerOf(BlockPos pos, BlockState state) {
		int part = state.getValue(PART);
		return pos.offset(1 - part % 3, 0, 1 - part / 3);
	}

	private static BlockPos partPos(BlockPos center, int part) {
		return center.offset(part % 3 - 1, 0, part / 3 - 1);
	}

	@Override
	public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
		Level level = ctx.getLevel();
		BlockPos pos = ctx.getClickedPos();
		if (!level.getBlockState(pos.below()).is(ModBlocks.TABLE_BASE)) {
			if (level.isClientSide() && ctx.getPlayer() != null) {
				ctx.getPlayer().displayClientMessage(Component.literal("Place the chess board on top of a Chess Table Base."), true);
			}
			return null;
		}
		for (int part = 0; part < 9; part++) {
			if (part == CENTER) continue;
			BlockPos p = partPos(pos, part);
			if (!level.getBlockState(p).canBeReplaced(ctx) || !level.getWorldBorder().isWithinBounds(p)) {
				if (level.isClientSide() && ctx.getPlayer() != null) {
					ctx.getPlayer().displayClientMessage(Component.literal("The board needs a free 3x3 area."), true);
				}
				return null;
			}
		}
		return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection()).setValue(PART, CENTER);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level.isClientSide()) return;
		for (int part = 0; part < 9; part++) {
			if (part != CENTER) level.setBlock(partPos(pos, part), state.setValue(PART, part), Block.UPDATE_ALL);
		}
	}

	@Override
	protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		return state.getValue(PART) != CENTER || level.getBlockState(pos.below()).is(ModBlocks.TABLE_BASE);
	}

	@Override
	protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
			Direction dir, BlockPos neighborPos, BlockState neighbor, RandomSource random) {
		if (dir == Direction.DOWN && !canSurvive(state, level, pos)) return Blocks.AIR.defaultBlockState();
		return state;
	}

	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		if (!level.isClientSide() && state.getValue(PART) != CENTER) {
			BlockPos center = centerOf(pos, state);
			if (level.getBlockState(center).is(this)) level.destroyBlock(center, !player.isCreative(), player);
		}
		return super.playerWillDestroy(level, pos, state, player);
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		BlockPos center = centerOf(pos, state);
		for (int part = 0; part < 9; part++) {
			BlockPos p = partPos(center, part);
			BlockState other = level.getBlockState(p);
			if (!p.equals(pos) && other.is(this) && other.getValue(PART) == part) {
				if (part == CENTER) level.destroyBlock(p, true);
				else level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
			}
		}
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
			InteractionHand hand, BlockHitResult hit) {
		int piece = ModBlocks.pieceOf(Block.byItem(stack.getItem()));
		if (piece < 0) return InteractionResult.TRY_WITH_EMPTY_HAND;
		if (!level.isClientSide() && level.getBlockEntity(centerOf(pos, state)) instanceof ChessBoardBlockEntity be) {
			be.insertPieces((ServerPlayer) player, stack, piece);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (level.isClientSide()) openScreen.accept(centerOf(pos, state));
		return InteractionResult.SUCCESS;
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return state.getValue(PART) == CENTER ? RenderShape.MODEL : RenderShape.INVISIBLE;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return state.getValue(PART) == CENTER ? new ChessBoardBlockEntity(pos, state) : null;
	}

	@Override
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (level.isClientSide() || state.getValue(PART) != CENTER) return null;
		return createTickerHelper(type, ModBlockEntities.CHESS_BOARD, ChessBoardBlockEntity::serverTick);
	}
}
