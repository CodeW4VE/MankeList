package xyz.w4ve.mankelist.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import xyz.w4ve.mankelist.MaterialListPayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Floating HUD with the material list, layout inspired by Litematica's (dark
 * background, icon + name + right-aligned count) but written from scratch:
 * title with global % and a progress bar, green ✓ for checked materials and a
 * "carrying / needed" count against your own inventory.
 */
public final class HudRenderer {
	private static final int BG_COLOR = 0xA0000000;
	private static final int TITLE_COLOR = 0xFFFFFFFF;
	private static final int NAME_COLOR = 0xFFFFFFFF;
	private static final int DONE_COLOR = 0xFF55FF55;
	private static final int DONE_NAME_COLOR = 0xFF999999;
	private static final int PARTIAL_COLOR = 0xFFFFFF55;
	private static final int MISSING_COLOR = 0xFFFF5555;
	private static final int MORE_COLOR = 0xFFAAAAAA;
	private static final int BAR_BG = 0xFF333333;
	private static final int BAR_FILL = 0xFF55FF55;
	private static final int MARGIN = 4;
	private static final int PAD = 3;

	private HudRenderer() {}

	public static void render(GuiGraphics g) {
		MankeListConfig cfg = MankeListConfig.get();
		MaterialListPayload list = ClientListState.getList();
		Minecraft mc = Minecraft.getInstance();

		if (!cfg.hudEnabled || list == null || mc.player == null || mc.options.hideGui) {
			return;
		}

		Font font = mc.font;
		double scale = cfg.scale;
		int lineHeight = cfg.showIcons ? 16 : 11;
		int iconSpace = cfg.showIcons ? 18 : 0;

		// Filter + order
		List<MaterialListPayload.Entry> entries = new ArrayList<>(list.entries());
		int total = entries.size();
		long doneCount = entries.stream().filter(MaterialListPayload.Entry::done).count();
		// Progress weighted by blocks: checking obsidian (190k) weighs what it
		// is, not the same as an 8-block material. The "stocked" amount (from
		// stocking-area containers) counts as fine progress of pending items.
		long totalBlocks = 0;
		long doneBlocks = 0;
		for (MaterialListPayload.Entry e : entries) {
			totalBlocks += e.count();
			doneBlocks += e.done() ? e.count() : Math.min(e.stocked() + e.gathered(), e.count());
		}
		if (!cfg.showCompleted) {
			entries.removeIf(MaterialListPayload.Entry::done);
		}
		if (cfg.sortMode == MankeListConfig.SortMode.CUSTOM && !cfg.customOrder.isEmpty()) {
			Map<String, Integer> idx = new HashMap<>();
			for (int i = 0; i < cfg.customOrder.size(); i++) {
				idx.put(cfg.customOrder.get(i), i);
			}
			entries.sort(Comparator.comparingInt(e -> idx.getOrDefault(e.id(), Integer.MAX_VALUE)));
		} else {
			Comparator<MaterialListPayload.Entry> byCount = Comparator.comparingInt(MaterialListPayload.Entry::count);
			if (cfg.sortMode != MankeListConfig.SortMode.LOW_TO_HIGH) {
				byCount = byCount.reversed();
			}
			entries.sort(cfg.sortByMissing
					? Comparator.comparing(MaterialListPayload.Entry::done).thenComparing(byCount)
					: byCount);
		}
		int shown = Math.min(entries.size(), cfg.maxLines);
		int hidden = entries.size() - shown;

		int pct = totalBlocks == 0 ? 0 : (int) (doneBlocks * 100 / totalBlocks);
		// With several active lists: "2/3" indicator (the cycle key moves the focus)
		int nLists = ClientListState.listCount();
		String multi = nLists > 1 ? " §7" + (ClientListState.focusIndex() + 1) + "/" + nLists + "§r" : "";
		String title = "§l" + list.listName() + "§r" + multi + "  " + doneCount + "/" + total + " (" + pct + "%)";

		// Measurements
		int maxName = font.width(title);
		int maxCount = 0;
		String[] countStrs = new String[shown];
		int[] countColors = new int[shown];
		int[] nameColors = new int[shown];
		for (int i = 0; i < shown; i++) {
			MaterialListPayload.Entry e = entries.get(i);
			if (e.done()) {
				countStrs[i] = "✓";
				countColors[i] = DONE_COLOR;
				nameColors[i] = DONE_NAME_COLOR;
			} else {
				// "carrying + stocked / needed": stocked = what already sits in
				// the stocking areas; green kicks in when both together cover it
				int stocked = Math.min(e.stocked(), e.count());
				int have = cfg.inventoryCount ? ClientListState.getCarried(mc.player, e.id()) : 0;
				if (cfg.inventoryCount && stocked > 0) {
					countStrs[i] = fmt(Math.min(have, e.count())) + " + " + fmt(stocked) + " / " + fmt(e.count());
				} else if (cfg.inventoryCount) {
					countStrs[i] = fmt(Math.min(have, e.count())) + " / " + fmt(e.count());
				} else if (stocked > 0) {
					countStrs[i] = fmt(stocked) + " / " + fmt(e.count());
				} else {
					countStrs[i] = fmt(e.count());
				}
				int progress = have + stocked;
				if (!cfg.inventoryCount && stocked == 0) {
					countColors[i] = PARTIAL_COLOR;
				} else {
					countColors[i] = progress >= e.count() ? DONE_COLOR : (progress > 0 ? PARTIAL_COLOR : MISSING_COLOR);
				}
				nameColors[i] = NAME_COLOR;
			}
			maxName = Math.max(maxName, iconSpace + font.width(label(e)));
			maxCount = Math.max(maxCount, font.width(countStrs[i]));
		}

		int width = maxName + maxCount + 12;
		int height = 10 /* titulo */ + 5 /* barra */ + shown * lineHeight + (hidden > 0 ? 10 : 0) + PAD * 2;

		// Position by corner (in already-scaled coords)
		int sw = (int) (g.guiWidth() / scale);
		int sh = (int) (g.guiHeight() / scale);
		int x = switch (cfg.corner) {
			case TOP_LEFT, BOTTOM_LEFT -> MARGIN;
			case TOP_RIGHT, BOTTOM_RIGHT -> sw - width - MARGIN;
		};
		int y = switch (cfg.corner) {
			case TOP_LEFT, TOP_RIGHT -> MARGIN;
			case BOTTOM_LEFT, BOTTOM_RIGHT -> sh - height - MARGIN;
		};

		g.pose().pushPose();
		g.pose().scale((float) scale, (float) scale, 1f);

		if (cfg.background) {
			g.fill(x, y, x + width, y + height, BG_COLOR);
		}

		int tx = x + PAD;
		int ty = y + PAD;
		g.drawString(font, title, tx, ty, TITLE_COLOR, false);
		ty += 10;

		// Global progress bar
		int barW = width - PAD * 2;
		g.fill(tx, ty, tx + barW, ty + 3, BAR_BG);
		if (doneBlocks > 0) {
			g.fill(tx, ty, tx + (int) (barW * doneBlocks / Math.max(1, totalBlocks)), ty + 3, BAR_FILL);
		}
		ty += 5;

		for (int i = 0; i < shown; i++) {
			MaterialListPayload.Entry e = entries.get(i);
			int textY = ty + (cfg.showIcons ? 4 : 1);
			if (cfg.showIcons) {
				g.renderItem(ClientListState.iconFor(e.id()), tx, ty);
			}
			g.drawString(font, label(e), tx + iconSpace, textY, nameColors[i], false);
			g.drawString(font, countStrs[i], x + width - PAD - font.width(countStrs[i]), textY, countColors[i], false);
			ty += lineHeight;
		}

		if (hidden > 0) {
			g.drawString(font, "+" + hidden + " more...", tx + iconSpace, ty + 1, MORE_COLOR, false);
		}

		g.pose().popPose();
	}

	private static String name(MaterialListPayload.Entry e) {
		String display = ClientListState.iconFor(e.id()).getHoverName().getString();
		// An unresolved id (barrier icon) falls back to the stored display name
		return "Barrier".equals(display) && !e.display().isEmpty() ? e.display() : display;
	}

	/** Name + who works on it (claim), plus their reported progress (/ml have). */
	private static String label(MaterialListPayload.Entry e) {
		String out = name(e);
		if (!e.claim().isEmpty()) {
			out += " §b@" + e.claim();
		}
		if (e.gathered() > 0 && !e.done()) {
			out += " §7(" + fmt(Math.min(e.gathered(), e.count())) + ")";
		}
		return out;
	}

	private static String fmt(int n) {
		return String.format(Locale.US, "%,d", n);
	}
}
