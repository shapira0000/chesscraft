package com.yossi.chesscraft.smoketest;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Development-only: drives {@link ChessSmokeTest} from the client tick. */
@Mod.EventBusSubscriber(modid = "chesscraft", value = Dist.CLIENT)
public final class ForgeSmokeHook {
	private ForgeSmokeHook() {
	}

	@SubscribeEvent
	public static void onTick(TickEvent.ClientTickEvent.Post event) {
		ChessSmokeTest.tick();
	}
}
