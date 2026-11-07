package com.yossi.chesscraft.smoketest;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Development-only: drives {@link ChessSmokeTest} from the client tick. */
@EventBusSubscriber(modid = "chesscraft", value = Dist.CLIENT)
public final class NeoForgeSmokeHook {
	private NeoForgeSmokeHook() {
	}

	@SubscribeEvent
	public static void onTick(ClientTickEvent.Post event) {
		ChessSmokeTest.tick();
	}
}
