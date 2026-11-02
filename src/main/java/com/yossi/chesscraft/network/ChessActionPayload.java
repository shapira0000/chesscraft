package com.yossi.chesscraft.network;

import com.yossi.chesscraft.ChessCraft;
import com.yossi.chesscraft.block.entity.ChessBoardBlockEntity;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: one action on the board at {@code pos}. Arguments depend on the action. */
public record ChessActionPayload(BlockPos pos, int action, int a, int b, int c, String text) implements CustomPacketPayload {
	public static final int MOVE = 0;
	public static final int SET_BOARD_MODE = 1;
	public static final int CUSTOM_PLACE = 2;
	public static final int CUSTOM_MOVE = 3;
	public static final int CUSTOM_CLEAR = 4;
	public static final int CUSTOM_RESET = 16;
	public static final int SETUP_SIDE = 17;
	/** Take moves back until the game has a() moves (against the bot, when take-backs are on). */
	public static final int UNDO_TO = 18;
	public static final int START = 5;
	public static final int JOIN = 6;
	public static final int RESIGN = 7;
	public static final int OFFER_DRAW = 8;
	public static final int ANSWER_DRAW = 9;
	public static final int UNDO = 10;
	public static final int HINT = 11;
	public static final int NEW_GAME = 12;
	public static final int REMATCH = 13;
	public static final int WITHDRAW = 14;
	public static final int SET_ELO = 15;

	public static final Type<ChessActionPayload> TYPE = new Type<>(ChessCraft.id("action"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ChessActionPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, ChessActionPayload::pos,
			ByteBufCodecs.VAR_INT, ChessActionPayload::action,
			ByteBufCodecs.INT, ChessActionPayload::a,
			ByteBufCodecs.INT, ChessActionPayload::b,
			ByteBufCodecs.INT, ChessActionPayload::c,
			ByteBufCodecs.stringUtf8(256), ChessActionPayload::text,
			ChessActionPayload::new);

	public ChessActionPayload(BlockPos pos, int action, int a, int b, int c) {
		this(pos, action, a, b, c, "");
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void registerServer() {
		PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
		ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) -> {
			var player = context.player();
			var level = player.level();
			if (!level.isLoaded(payload.pos())) return;
			if (player.distanceToSqr(payload.pos().getCenter()) > 12 * 12) return;
			if (level.getBlockEntity(payload.pos()) instanceof ChessBoardBlockEntity be) {
				be.handleAction(player, payload);
			}
		});
	}
}
