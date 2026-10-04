package xyz.w4ve.mankelist.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import xyz.w4ve.mankelist.InventoryCounter;
import xyz.w4ve.mankelist.MaterialListPayload;

import java.util.HashMap;
import java.util.Map;

/**
 * Client state: the lists the server sent + the count of what the player
 * carries (inventory + shulker and bundle contents), cached for a couple of
 * seconds so the inventory is not walked every frame.
 */
public final class ClientListState {
	// All active lists from the server (v2); a 0.4.x server sends just one over
	// the v1 channel. The HUD shows the FOCUSED one and a key cycles them.
	private static java.util.List<MaterialListPayload> lists = java.util.List.of();
	private static int focus = 0;

	private static final Map<String, Integer> carried = new HashMap<>();
	private static long lastCountTime = 0;

	private ClientListState() {}

	/** v1 channel (0.4.x server): a single list. */
	public static void setList(MaterialListPayload payload) {
		setLists(payload == null || payload.listName().isEmpty()
				? java.util.List.of() : java.util.List.of(payload));
	}

	/** v2 channel: all active lists. Keeps the focus on the same list when it survives. */
	public static void setLists(java.util.List<MaterialListPayload> payloadLists) {
		String keep = getList() == null ? null : getList().listName();
		lists = payloadLists == null ? java.util.List.of()
				: payloadLists.stream().filter(l -> !l.listName().isEmpty()).toList();
		focus = 0;
		if (keep != null) {
			for (int i = 0; i < lists.size(); i++) {
				if (lists.get(i).listName().equals(keep)) {
					focus = i;
					break;
				}
			}
		}
		lastCountTime = 0; // force a recount next frame
	}

	/** The focused list; null when none is active (HUD hidden). */
	public static MaterialListPayload getList() {
		return lists.isEmpty() ? null : lists.get(Math.min(focus, lists.size() - 1));
	}

	public static int listCount() {
		return lists.size();
	}

	public static int focusIndex() {
		return Math.min(focus, Math.max(0, lists.size() - 1));
	}

	public static void cycleFocus() {
		if (lists.size() > 1) {
			focus = (focus + 1) % lists.size();
		}
	}

	/** How many units of {@code id} the player carries (2s cache). */
	public static int getCarried(Player player, String id) {
		long now = System.currentTimeMillis();
		if (now - lastCountTime > 2000) {
			recount(player);
			lastCountTime = now;
		}
		return carried.getOrDefault(id, 0);
	}

	private static void recount(Player player) {
		carried.clear();
		if (player == null || lists.isEmpty()) {
			return;
		}
		carried.putAll(InventoryCounter.countCarried(player));
	}

	/** ItemStack for the HUD icon; a barrier when the id resolves to no item. */
	public static ItemStack iconFor(String id) {
		try {
			Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
			if (item != Items.AIR) {
				return new ItemStack(item);
			}
		} catch (Exception ignored) {
		}
		return new ItemStack(Items.BARRIER);
	}
}
