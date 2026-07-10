package xyz.w4ve.mankelist;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.HashMap;
import java.util.Map;

/**
 * Counts what a player carries (inventory + offhand + shulker and bundle
 * contents), by item id. Used by the client HUD (own inventory), the
 * server-side scan that triggers Manke's prompt, and the stocking-area scan.
 */
public final class InventoryCounter {

	private InventoryCounter() {}

	public static Map<String, Integer> countCarried(Player player) {
		Map<String, Integer> out = new HashMap<>();
		for (ItemStack stack : player.getInventory().items) {
			countStack(stack, out);
		}
		for (ItemStack stack : player.getInventory().offhand) {
			countStack(stack, out);
		}
		return out;
	}

	/** Accumulates one stack (and whatever is inside it: shulker/bundle) into the id→count map. */
	public static void countStack(ItemStack stack, Map<String, Integer> out) {
		if (stack.isEmpty()) {
			return;
		}
		add(stack, stack.getCount(), out);

		// Shulker box contents (1.21: components -> minecraft:container)
		ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
		if (contents != null) {
			for (ItemStack inner : contents.nonEmptyItems()) {
				add(inner, inner.getCount(), out);
			}
		}

		// Bundle contents
		BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
		if (bundle != null) {
			for (ItemStack inner : bundle.items()) {
				add(inner, inner.getCount(), out);
			}
		}
	}

	private static void add(ItemStack stack, int count, Map<String, Integer> out) {
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		out.merge(id, count, Integer::sum);
	}
}
