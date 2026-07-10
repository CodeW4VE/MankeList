package xyz.w4ve.mankelist;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java .litematic parser (using Minecraft's own NBT classes), so
 * /ml import needs no external scripts. Counts blocks per id from
 * Regions → BlockStatePalette + BlockStates and builds a schemlist-format
 * list object {name, source, blocks:[{id, display, count, done}]}.
 *
 * Format gotcha: Litematica's BlockStates is a TIGHTLY packed bit array where
 * an index may SPAN from one long into the next (like vanilla pre-1.16), NOT
 * the padded packing of the modern PackedBitStorage.
 */
public final class LitematicImporter {

	/** No air, no technical blocks, no fluids. */
	private static final Set<String> EXCLUDE = Set.of(
			"minecraft:air", "minecraft:cave_air", "minecraft:void_air",
			"minecraft:structure_void", "minecraft:barrier", "minecraft:light",
			"minecraft:moving_piston", "minecraft:water", "minecraft:lava");

	private LitematicImporter() {}

	/** Reads the litematic and returns the schemlist list-object, blocks sorted by count desc. */
	public static JsonObject parse(Path file, String listName) throws Exception {
		CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		CompoundTag regions = root.getCompound("Regions");
		if (regions.isEmpty()) {
			throw new IllegalArgumentException("no Regions in " + file.getFileName());
		}

		Map<String, Long> countById = new HashMap<>();
		for (String regionName : regions.getAllKeys()) {
			CompoundTag region = regions.getCompound(regionName);
			ListTag palette = region.getList("BlockStatePalette", Tag.TAG_COMPOUND);
			long[] states = region.getLongArray("BlockStates");
			CompoundTag size = region.getCompound("Size");
			long volume = Math.abs((long) size.getInt("x"))
					* Math.abs((long) size.getInt("y"))
					* Math.abs((long) size.getInt("z"));
			if (palette.isEmpty() || states.length == 0 || volume == 0) {
				continue;
			}
			int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
			long mask = (1L << bits) - 1L;
			long[] perIndex = new long[palette.size()];
			for (long i = 0; i < volume; i++) {
				long bitOffset = i * bits;
				int startLong = (int) (bitOffset >> 6);
				int startBit = (int) (bitOffset & 63);
				long idx;
				if (startBit + bits <= 64) {
					idx = (states[startLong] >>> startBit) & mask;
				} else {
					idx = ((states[startLong] >>> startBit)
							| (states[startLong + 1] << (64 - startBit))) & mask;
				}
				if (idx < perIndex.length) {
					perIndex[(int) idx]++;
				}
			}
			for (int p = 0; p < palette.size(); p++) {
				if (perIndex[p] == 0) {
					continue;
				}
				String id = palette.getCompound(p).getString("Name");
				if (!EXCLUDE.contains(id)) {
					countById.merge(id, perIndex[p], Long::sum);
				}
			}
		}

		JsonArray blocks = new JsonArray();
		countById.entrySet().stream()
				.sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
				.forEach(en -> {
					JsonObject b = new JsonObject();
					b.add("id", new JsonPrimitive(en.getKey()));
					b.add("display", new JsonPrimitive(displayName(en.getKey())));
					b.add("count", new JsonPrimitive(en.getValue()));
					b.add("done", new JsonPrimitive(false));
					blocks.add(b);
				});

		JsonObject listObj = new JsonObject();
		listObj.add("name", new JsonPrimitive(listName));
		listObj.add("source", new JsonPrimitive(file.getFileName().toString()));
		listObj.add("blocks", blocks);
		return listObj;
	}

	/** Readable name: the block's registry name, or the prettified id. */
	private static String displayName(String id) {
		try {
			Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
			if (block != Blocks.AIR) {
				return block.getName().getString();
			}
		} catch (Exception ignored) {
		}
		String shortId = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		String[] words = shortId.split("_");
		StringBuilder sb = new StringBuilder();
		for (String w : words) {
			if (!w.isEmpty()) {
				if (sb.length() > 0) {
					sb.append(' ');
				}
				sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase(Locale.ROOT));
			}
		}
		return sb.toString();
	}
}
