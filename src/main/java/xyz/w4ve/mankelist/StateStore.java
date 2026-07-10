package xyz.w4ve.mankelist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Raw read/write of the state file. The format stays byte-compatible with the
 * schemlist MCDR plugin so any external pipeline reading this file keeps
 * working: {"lists": {name: {name, source, blocks: [...]}}, "active": ...}.
 */
public final class StateStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private StateStore() {}

	/** Root object of the state file; a blank template when missing or broken. */
	public static JsonObject readRoot(Path statePath) {
		try {
			if (Files.isRegularFile(statePath)) {
				try (Reader r = Files.newBufferedReader(statePath, StandardCharsets.UTF_8)) {
					JsonObject root = GSON.fromJson(r, JsonObject.class);
					if (root != null) {
						if (!root.has("lists")) root.add("lists", new JsonObject());
						if (!root.has("active")) root.add("active", JsonNull.INSTANCE);
						return root;
					}
				}
			}
		} catch (Exception e) {
			MankeList.LOGGER.warn("[MankeList] unreadable state file, starting fresh: {}", e.toString());
		}
		JsonObject root = new JsonObject();
		root.add("lists", new JsonObject());
		root.add("active", JsonNull.INSTANCE);
		return root;
	}

	/**
	 * Active list names: "active" may be a string (legacy schemlist / mod 0.4
	 * state) or an array (0.5+). Always in stored order.
	 */
	public static java.util.List<String> activeNames(JsonObject root) {
		java.util.List<String> names = new java.util.ArrayList<>();
		if (root == null || !root.has("active") || root.get("active").isJsonNull()) {
			return names;
		}
		com.google.gson.JsonElement active = root.get("active");
		if (active.isJsonArray()) {
			for (com.google.gson.JsonElement el : active.getAsJsonArray()) {
				if (el.isJsonPrimitive()) {
					names.add(el.getAsString());
				}
			}
		} else if (active.isJsonPrimitive()) {
			names.add(active.getAsString());
		}
		return names;
	}

	/** Always writes "active" as an array (0.5 format; readers accept both). */
	public static void setActiveNames(JsonObject root, java.util.List<String> names) {
		com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
		for (String n : names) {
			arr.add(n);
		}
		root.add("active", arr);
	}

	public static void writeRoot(Path statePath, JsonObject root) throws Exception {
		Files.createDirectories(statePath.getParent());
		try (Writer w = Files.newBufferedWriter(statePath, StandardCharsets.UTF_8)) {
			GSON.toJson(root, w);
		}
	}
}
