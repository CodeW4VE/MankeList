package xyz.w4ve.mankelist;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reader for the state file (extended schemlist format): {"lists": {name:
 * {name, source, blocks: [{id, display, count, done, claim?, stocked?}]}},
 * "active": name|[names]|null}. A string "active" is the legacy single-list
 * format (schemlist / mod 0.4), an array is 0.5+ multi-list; both stay
 * accepted forever.
 */
public final class SchemlistState {
	private static final Gson GSON = new Gson();

	private SchemlistState() {}

	/** The ACTIVE lists of the state file, in order; empty when none or the file is missing. */
	public static List<MaterialListPayload> readActiveLists(Path statePath) {
		try {
			if (!Files.isRegularFile(statePath)) {
				return List.of();
			}
			JsonObject root;
			try (Reader r = Files.newBufferedReader(statePath, StandardCharsets.UTF_8)) {
				root = GSON.fromJson(r, JsonObject.class);
			}
			if (root == null) {
				return List.of();
			}
			JsonObject lists = root.getAsJsonObject("lists");
			if (lists == null) {
				return List.of();
			}
			List<MaterialListPayload> out = new ArrayList<>();
			for (String name : StateStore.activeNames(root)) {
				if (!lists.has(name)) {
					continue;
				}
				JsonArray blocks = lists.getAsJsonObject(name).getAsJsonArray("blocks");
				List<MaterialListPayload.Entry> entries = new ArrayList<>();
				if (blocks != null) {
					for (JsonElement el : blocks) {
						JsonObject b = el.getAsJsonObject();
						entries.add(new MaterialListPayload.Entry(
								b.get("id").getAsString(),
								b.has("display") ? b.get("display").getAsString() : b.get("id").getAsString(),
								b.get("count").getAsInt(),
								b.has("done") && b.get("done").getAsBoolean(),
								b.has("claim") && !b.get("claim").isJsonNull() ? b.get("claim").getAsString() : "",
								b.has("stocked") ? b.get("stocked").getAsInt() : 0,
								b.has("gathered") ? b.get("gathered").getAsInt() : 0));
					}
				}
				out.add(new MaterialListPayload(name, entries));
			}
			return out;
		} catch (Exception e) {
			MankeList.LOGGER.warn("[MankeList] unreadable state file ({}), sending an empty list", e.toString());
			return List.of();
		}
	}

	public static MaterialListPayload empty() {
		return new MaterialListPayload("", List.of());
	}
}
