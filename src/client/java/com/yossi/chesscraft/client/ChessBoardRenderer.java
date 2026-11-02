package com.yossi.chesscraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.yossi.chesscraft.block.ChessPieceBlock;
import com.yossi.chesscraft.block.entity.ChessBoardBlockEntity;
import com.yossi.chesscraft.chess.Move;
import com.yossi.chesscraft.chess.Piece;
import com.yossi.chesscraft.registry.ModBlocks;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Renders the pieces standing on a board, animating the latest move. */
public class ChessBoardRenderer implements BlockEntityRenderer<ChessBoardBlockEntity, ChessBoardRenderer.State> {
	/** Piece scale on the board: the widest base (rook, ~7.8px) still fits a 5px square. */
	private static final float PIECE_SCALE = 0.46f;
	private static final float SURFACE_Y = 2.5f;
	public static final long ANIM_MS = 380;

	public static class State extends BlockEntityRenderState {
		int count;
		final BlockState[] states = new BlockState[40];
		final float[] x = new float[40];
		final float[] y = new float[40];
		final float[] z = new float[40];
		final float[] scale = new float[40];
		float yaw;
	}

	private static final BlockState[] PIECE_STATES = new BlockState[Piece.COUNT];

	public ChessBoardRenderer(BlockEntityRendererProvider.Context context) {
	}

	static BlockState pieceState(int piece) {
		BlockState s = PIECE_STATES[piece];
		if (s == null) {
			s = ModBlocks.PIECES[piece].defaultBlockState()
					.setValue(ChessPieceBlock.ROTATION, Piece.color(piece) == Piece.WHITE ? 0 : 8);
			PIECE_STATES[piece] = s;
		}
		return s;
	}

	/** Square centre in board-model pixels (white at +z when facing north). */
	static float squareX(int sq) {
		return -9.5f + 5f * (sq & 7);
	}

	static float squareZ(int sq) {
		return 25.5f - 5f * (sq >> 3);
	}

	public static float facingYaw(Direction facing) {
		return switch (facing) {
			case EAST -> 90;
			case SOUTH -> 180;
			case WEST -> 270;
			default -> 0;
		};
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(ChessBoardBlockEntity be, State st, float partialTick, Vec3 camera,
			ModelFeatureRenderer.@Nullable CrumblingOverlay crumbling) {
		BlockEntityRenderer.super.extractRenderState(be, st, partialTick, camera, crumbling);
		st.yaw = facingYaw(be.facing());
		st.count = 0;
		int[] board = be.displayBoard();
		long elapsed = System.currentTimeMillis() - be.animStartMillis;
		int move = be.animMove;
		int[] prev = be.animPrevBoard;
		if (move == Move.NONE || prev == null || elapsed >= ANIM_MS || elapsed < 0) {
			for (int sq = 0; sq < 64; sq++) {
				if (board[sq] != Piece.NONE) add(st, board[sq], squareX(sq), 0, squareZ(sq), 1f);
			}
			return;
		}
		float t = elapsed / (float) ANIM_MS;
		float eased = t < 0.5f ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2;
		int from = Move.from(move), to = Move.to(move);
		int moving = prev[from];
		int rookFrom = -1, rookTo = -1;
		if (Move.flags(move) == Move.CASTLE_KING) {
			rookFrom = from + 3;
			rookTo = from + 1;
		} else if (Move.flags(move) == Move.CASTLE_QUEEN) {
			rookFrom = from - 4;
			rookTo = from - 1;
		}
		int captureSq = Move.isEnPassant(move) ? (to & 7) | (from & ~7) : (Move.isCapture(move) ? to : -1);
		for (int sq = 0; sq < 64; sq++) {
			int p = prev[sq];
			if (p == Piece.NONE || sq == from || sq == rookFrom) continue;
			if (sq == captureSq) {
				float s = Math.max(0, 1 - Math.max(0, (eased - 0.55f) / 0.45f));
				if (s > 0.02f) add(st, p, squareX(sq), 0, squareZ(sq), s);
				continue;
			}
			add(st, p, squareX(sq), 0, squareZ(sq), 1f);
		}
		if (moving != Piece.NONE) {
			boolean knight = Piece.type(moving) == Piece.KNIGHT;
			float hop = (float) Math.sin(Math.PI * eased) * (knight ? 4.5f : 1.5f);
			// Show the promoted piece only once it lands.
			int shown = Move.isPromotion(move) && eased > 0.85f ? board[to] : moving;
			add(st, shown, lerp(squareX(from), squareX(to), eased), hop, lerp(squareZ(from), squareZ(to), eased), 1f);
		}
		if (rookFrom >= 0 && prev[rookFrom] != Piece.NONE) {
			add(st, prev[rookFrom], lerp(squareX(rookFrom), squareX(rookTo), eased), (float) Math.sin(Math.PI * eased) * 2.5f,
					lerp(squareZ(rookFrom), squareZ(rookTo), eased), 1f);
		}
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	private static void add(State st, int piece, float x, float y, float z, float scale) {
		if (st.count >= st.states.length || piece < 0) return;
		int i = st.count++;
		st.states[i] = pieceState(piece);
		st.x[i] = x;
		st.y[i] = y;
		st.z[i] = z;
		st.scale[i] = scale;
	}

	@Override
	public void submit(State st, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		pose.pushPose();
		pose.translate(0.5f, 0, 0.5f);
		pose.mulPose(Axis.YP.rotationDegrees(-st.yaw));
		pose.translate(-0.5f, 0, -0.5f);
		for (int i = 0; i < st.count; i++) {
			float s = PIECE_SCALE * st.scale[i];
			pose.pushPose();
			pose.translate(st.x[i] / 16f, (SURFACE_Y + st.y[i]) / 16f, st.z[i] / 16f);
			pose.scale(s, s, s);
			pose.translate(-0.5f, 0, -0.5f);
			collector.submitBlock(pose, st.states[i], st.lightCoords, OverlayTexture.NO_OVERLAY, 0);
			pose.popPose();
		}
		pose.popPose();
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return true;
	}

	@Override
	public int getViewDistance() {
		return 96;
	}
}
