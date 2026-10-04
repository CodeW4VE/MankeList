package xyz.w4ve.mankelist.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

import xyz.w4ve.mankelist.MankeList;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * HUD configuration, persisted in {@code config/mankelist.json}.
 * No external dependencies: just Gson + Fabric Loader.
 */
public final class MankeListConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH =
			FabricLoader.getInstance().getConfigDir().resolve("mankelist.json");

	public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

	public enum SortMode { HIGH_TO_LOW, LOW_TO_HIGH, CUSTOM }

	/** HUD visible (the toggle key flips this very field). */
	public boolean hudEnabled = true;
	/** Screen corner the HUD is anchored to. */
	public Corner corner = Corner.TOP_RIGHT;
	/** HUD scale (0.5 - 1.5). */
	public double scale = 1.0;
	/** Maximum materials listed (the rest collapses into "+N more"). */
	public int maxLines = 10;
	/** Also show already-checked materials (with their green ✓). */
	public boolean showCompleted = true;
	/** Count what you carry (inventory + shulkers + bundles) per material. */
	public boolean inventoryCount = true;
	/** Pending on top, checked at the bottom (ignored in CUSTOM order). */
	public boolean sortByMissing = true;
	/** List order: by amount (desc/asc) or custom (ArrangeScreen). */
	public SortMode sortMode = SortMode.HIGH_TO_LOW;
	/** Custom order: material ids in the order the player chose. */
	public List<String> customOrder = new ArrayList<>();
	/** Semi-transparent dark background behind the HUD. */
	public boolean background = true;
	/** Item icons at the left of each line. */
	public boolean showIcons = true;

	private static MankeListConfig instance;

	public static MankeListConfig get() {
		if (instance == null) {
			load();
		}
		return instance;
	}

	public static void load() {
		try {
			if (Files.exists(PATH)) {
				try (Reader r = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
					MankeListConfig c = GSON.fromJson(r, MankeListConfig.class);
					instance = (c != null) ? c : new MankeListConfig();
				}
			} else {
				instance = new MankeListConfig();
				instance.save();
			}
		} catch (Exception e) {
			MankeList.LOGGER.error("[MankeList] could not read the config, using defaults", e);
			instance = new MankeListConfig();
		}
		if (instance.corner == null) instance.corner = Corner.TOP_RIGHT;
		if (instance.sortMode == null) instance.sortMode = SortMode.HIGH_TO_LOW;
		if (instance.customOrder == null) instance.customOrder = new ArrayList<>();
		instance.scale = Math.clamp(instance.scale, 0.5, 1.5);
		instance.maxLines = Math.clamp(instance.maxLines, 1, 999);
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer w = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(this, w);
			}
		} catch (Exception e) {
			MankeList.LOGGER.error("[MankeList] could not save the config", e);
		}
	}
}
