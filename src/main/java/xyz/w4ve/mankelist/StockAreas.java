package xyz.w4ve.mankelist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stocking areas: 3D boxes (per dimension) where the community drops off the
 * materials. The server scans the containers inside them (chests, barrels,
 * placed shulkers... any BlockEntity that is a Container, nested
 * shulker/bundle contents included) and derives how much of each material is
 * "stocked". Persisted in config/mankelist-stockareas.json.
 */
public final class StockAreas {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Normalized box (min/max per axis) in one specific dimension. */
	public record Area(String dimension, int x1, int y1, int z1, int x2, int y2, int z2) {
		public static Area of(String dimension, BlockPos a, BlockPos b) {
			return new Area(dimension,
					Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
					Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
		}

		public boolean contains(BlockPos pos) {
			return pos.getX() >= this.x1 && pos.getX() <= this.x2
					&& pos.getY() >= this.y1 && pos.getY() <= this.y2
					&& pos.getZ() >= this.z1 && pos.getZ() <= this.z2;
		}

		public String describe() {
			return String.format(Locale.US, "%s (%d %d %d) -> (%d %d %d)",
					this.dimension, this.x1, this.y1, this.z1, this.x2, this.y2, this.z2);
		}
	}

	private final Path file;
	private final List<Area> areas = new ArrayList<>();

	public StockAreas(Path file) {
		this.file = file;
		load();
	}

	public List<Area> list() {
		return List.copyOf(this.areas);
	}

	public void add(Area area) {
		this.areas.add(area);
		save();
	}

	public int clear() {
		int n = this.areas.size();
		this.areas.clear();
		save();
		return n;
	}

	/**
	 * id→amount count of EVERYTHING stored inside the areas, or null when some
	 * chunk of some area is not loaded (better to keep the previous stocked
	 * values than to under-report just because nobody is nearby).
	 */
	public Map<String, Integer> scan(MinecraftServer server) {
		if (this.areas.isEmpty()) {
			// No areas means nothing is stocked: an empty result lets the
			// caller zero out stale "stocked" values after /ml stockarea clear.
			return Map.of();
		}
		Map<String, Integer> out = new HashMap<>();
		for (Area area : this.areas) {
			ServerLevel level = findLevel(server, area.dimension());
			if (level == null) {
				return null;
			}
			int minCx = area.x1() >> 4;
			int maxCx = area.x2() >> 4;
			int minCz = area.z1() >> 4;
			int maxCz = area.z2() >> 4;
			for (int cx = minCx; cx <= maxCx; cx++) {
				for (int cz = minCz; cz <= maxCz; cz++) {
					if (!level.hasChunk(cx, cz)) {
						return null;
					}
					LevelChunk chunk = level.getChunk(cx, cz);
					for (Map.Entry<BlockPos, BlockEntity> en : chunk.getBlockEntities().entrySet()) {
						if (!area.contains(en.getKey()) || !(en.getValue() instanceof Container container)) {
							continue;
						}
						for (int slot = 0; slot < container.getContainerSize(); slot++) {
							InventoryCounter.countStack(container.getItem(slot), out);
						}
					}
				}
			}
		}
		return out;
	}

	private static ServerLevel findLevel(MinecraftServer server, String dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().location().toString().equals(dimension)) {
				return level;
			}
		}
		return null;
	}

	// --- persistence ---

	private static class AreaJson {
		String dimension;
		int[] from;
		int[] to;
	}

	private static class FileJson {
		List<AreaJson> areas;
	}

	private void load() {
		if (!Files.exists(this.file)) {
			return;
		}
		try (Reader r = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
			FileJson f = GSON.fromJson(r, FileJson.class);
			if (f != null && f.areas != null) {
				for (AreaJson a : f.areas) {
					if (a.dimension != null && a.from != null && a.to != null
							&& a.from.length == 3 && a.to.length == 3) {
						this.areas.add(Area.of(a.dimension,
								new BlockPos(a.from[0], a.from[1], a.from[2]),
								new BlockPos(a.to[0], a.to[1], a.to[2])));
					}
				}
			}
			MankeList.LOGGER.info("[MankeList] {} stocking area(s) loaded", this.areas.size());
		} catch (Exception e) {
			MankeList.LOGGER.warn("[MankeList] could not read mankelist-stockareas.json", e);
		}
	}

	private void save() {
		try {
			FileJson f = new FileJson();
			f.areas = new ArrayList<>();
			for (Area a : this.areas) {
				AreaJson j = new AreaJson();
				j.dimension = a.dimension();
				j.from = new int[]{a.x1(), a.y1(), a.z1()};
				j.to = new int[]{a.x2(), a.y2(), a.z2()};
				f.areas.add(j);
			}
			Files.createDirectories(this.file.getParent());
			try (Writer w = Files.newBufferedWriter(this.file, StandardCharsets.UTF_8)) {
				GSON.toJson(f, w);
			}
		} catch (Exception e) {
			MankeList.LOGGER.warn("[MankeList] could not save mankelist-stockareas.json", e);
		}
	}
}
