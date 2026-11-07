package com.yossi.chesscraft;

import com.yossi.chesscraft.block.entity.ChessBoardBlockEntity;
import com.yossi.chesscraft.registry.ModBlockEntities;
import com.yossi.chesscraft.registry.ModBlocks;
import com.yossi.chesscraft.registry.ModTab;
import java.util.function.Function;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loader-independent mod setup. Each loader's entry point calls {@link #registerContent} once, while registries are open. */
public final class ChessCraft {
	public static final String MOD_ID = "chesscraft";
	public static final Logger LOGGER = LoggerFactory.getLogger("ChessCraft");

	private static boolean registered;

	/** Writes one entry into a registry. Forge swaps in its own, since it only opens one registry per event. */
	public interface Registrar {
		<T, V extends T> V register(Registry<T> registry, ResourceKey<T> key, V value);
	}

	public static Registrar registrar = new Registrar() {
		@Override
		public <T, V extends T> V register(Registry<T> registry, ResourceKey<T> key, V value) {
			return Registry.register(registry, key, value);
		}
	};

	private ChessCraft() {
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	/** Shows a short message above the hotbar. */
	public static void actionBar(Player player, Component message) {
//#if mc>=26.1
//$		player.sendOverlayMessage(message);
//#else
		player.displayClientMessage(message, true);
//#endif
	}

	/** Gives an item to a player, dropping it at their feet when the inventory is full (server side). */
	public static void give(Player player, ItemStack stack) {
		if (player.getInventory().add(stack)) return;
//#if mc>=26.3
//$		player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
//#else
		player.drop(stack, false);
//#endif
	}

	/**
	 * Registers the blocks, items, block entity type and creative tab.
	 *
	 * @param blockEntityType builds the board's block entity type for the board block (the constructor is loader specific)
	 * @param tabBuilder      a creative tab builder (the vanilla one needs a row and column, so each loader supplies its own)
	 */
	public static synchronized void registerContent(Function<Block, BlockEntityType<ChessBoardBlockEntity>> blockEntityType,
			Supplier<CreativeModeTab.Builder> tabBuilder) {
		if (registered) return;
		registered = true;
		registerBlocks();
		registerItems();
		registerBlockEntities(blockEntityType);
		registerTab(tabBuilder);
	}

	// The four registration phases, for loaders that open one registry at a time (in this order).

	public static void registerBlocks() {
		ModBlocks.initBlocks();
	}

	public static void registerItems() {
		ModBlocks.initItems();
	}

	public static void registerBlockEntities(Function<Block, BlockEntityType<ChessBoardBlockEntity>> blockEntityType) {
		ModBlockEntities.init(blockEntityType.apply(ModBlocks.BOARD));
	}

	public static void registerTab(Supplier<CreativeModeTab.Builder> tabBuilder) {
		ModTab.init(tabBuilder.get());
		LOGGER.info("ChessCraft loaded");
	}
}
