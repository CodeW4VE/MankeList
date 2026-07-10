package xyz.w4ve.mankelist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Built-in Discord board: posts the active material lists to a Discord channel
 * through a plain webhook and keeps editing the same messages afterwards, so
 * the channel always shows one live board instead of a scrolling log.
 *
 * No bot account, no extra process: set {@code discordWebhookUrl} in
 * mankelist-server.json and the mod does the rest. Message ids are remembered
 * in config/mankelist-webhook.json so the board survives restarts. All HTTP
 * runs on a daemon worker thread; the server thread only hands over snapshots.
 */
public final class DiscordWebhook {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Discord embeds cap description at 4096 chars; stay under with margin. */
	private static final int MAX_DESC = 3900;
	/** Board color (a friendly green). */
	private static final int COLOR = 0x57F287;
	/** Do not hit Discord more often than this, even if checks come in bursts. */
	private static final long MIN_INTERVAL_MS = 10_000;

	private final String url;
	private final Path idsPath;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "MankeList-Webhook");
		t.setDaemon(true);
		return t;
	});

	/** Latest snapshot waiting to be pushed; null when the board is up to date. */
	private final AtomicReference<List<MaterialListPayload>> pending = new AtomicReference<>(null);
	private volatile long lastPush = 0;
	/** Only touched from the worker thread. */
	private List<String> messageIds = new ArrayList<>();

	public DiscordWebhook(String url, Path idsPath) {
		this.url = url == null ? "" : url.trim();
		this.idsPath = idsPath;
		loadIds();
	}

	public boolean enabled() {
		return !this.url.isBlank();
	}

	/** Queue a fresh snapshot (server thread). The tick decides when to send it. */
	public void push(List<MaterialListPayload> lists) {
		if (enabled()) {
			this.pending.set(List.copyOf(lists));
		}
	}

	/** Called every server tick: flush the pending snapshot if the cooldown allows. */
	public void tick() {
		if (!enabled()) {
			return;
		}
		List<MaterialListPayload> lists = this.pending.get();
		if (lists == null || System.currentTimeMillis() - this.lastPush < MIN_INTERVAL_MS) {
			return;
		}
		if (this.pending.compareAndSet(lists, null)) {
			this.lastPush = System.currentTimeMillis();
			this.worker.submit(() -> sync(lists));
		}
	}

	// --- worker thread ---

	/** Bring the Discord messages in line with the snapshot: edit, add, delete. */
	private void sync(List<MaterialListPayload> lists) {
		try {
			List<String> bodies = new ArrayList<>();
			for (JsonObject embed : buildEmbeds(lists)) {
				JsonObject payload = new JsonObject();
				JsonArray embeds = new JsonArray();
				embeds.add(embed);
				payload.add("embeds", embeds);
				bodies.add(payload.toString());
			}

			boolean idsChanged = false;
			for (int i = 0; i < bodies.size(); i++) {
				if (i < this.messageIds.size()) {
					if (!edit(this.messageIds.get(i), bodies.get(i))) {
						// Message gone (someone deleted it): post a replacement
						String id = post(bodies.get(i));
						if (id != null) {
							this.messageIds.set(i, id);
							idsChanged = true;
						}
					}
				} else {
					String id = post(bodies.get(i));
					if (id == null) {
						break; // webhook unreachable; retry on the next change
					}
					this.messageIds.add(id);
					idsChanged = true;
				}
			}
			// The list shrank: drop the leftover messages
			while (this.messageIds.size() > bodies.size()) {
				delete(this.messageIds.remove(this.messageIds.size() - 1));
				idsChanged = true;
			}
			if (idsChanged) {
				saveIds();
			}
		} catch (Exception e) {
			MankeList.LOGGER.warn("[MankeList] webhook sync failed: {}", e.toString());
		}
	}

	private String post(String body) throws Exception {
		HttpResponse<String> resp = send("POST", this.url + "?wait=true", body);
		if (resp.statusCode() / 100 != 2) {
			MankeList.LOGGER.warn("[MankeList] webhook POST -> {}", resp.statusCode());
			return null;
		}
		return JsonParser.parseString(resp.body()).getAsJsonObject().get("id").getAsString();
	}

	private boolean edit(String messageId, String body) throws Exception {
		HttpResponse<String> resp = send("PATCH", this.url + "/messages/" + messageId, body);
		if (resp.statusCode() == 404) {
			return false;
		}
		if (resp.statusCode() / 100 != 2) {
			MankeList.LOGGER.warn("[MankeList] webhook PATCH -> {}", resp.statusCode());
		}
		return true;
	}

	private void delete(String messageId) throws Exception {
		send("DELETE", this.url + "/messages/" + messageId, null);
	}

	private HttpResponse<String> send(String method, String uri, String body) throws Exception {
		HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(uri))
				.timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json");
		req.method(method, body == null
				? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		return this.http.send(req.build(), HttpResponse.BodyHandlers.ofString());
	}

	// --- board rendering ---

	/**
	 * One embed per chunk, one or more chunks per active list (a big list does
	 * not fit in a single embed). Progress is weighted by BLOCKS and counts the
	 * stocked amount of pending materials as partial progress.
	 */
	private static List<JsonObject> buildEmbeds(List<MaterialListPayload> lists) {
		List<JsonObject> out = new ArrayList<>();
		if (lists.isEmpty()) {
			JsonObject embed = new JsonObject();
			embed.add("title", new JsonPrimitive("🧱 Build Materials"));
			embed.add("description", new JsonPrimitive("_No material list active right now._"));
			embed.add("color", new JsonPrimitive(COLOR));
			out.add(embed);
			return out;
		}
		for (MaterialListPayload list : lists) {
			long totalBlocks = 0;
			long doneBlocks = 0;
			long doneItems = 0;
			for (MaterialListPayload.Entry e : list.entries()) {
				totalBlocks += e.count();
				if (e.done()) {
					doneBlocks += e.count();
					doneItems++;
				} else {
					doneBlocks += Math.min(e.stocked() + e.gathered(), e.count());
				}
			}
			int pct = totalBlocks == 0 ? 0 : (int) (doneBlocks * 100 / totalBlocks);
			int filled = Math.round(pct / 10f);
			String header = "▰".repeat(filled) + "▱".repeat(10 - filled)
					+ " **" + pct + "%**  (" + fmt(doneBlocks) + "/" + fmt(totalBlocks) + " blocks · "
					+ doneItems + "/" + list.entries().size() + " materials done)";

			List<String> lines = new ArrayList<>();
			for (MaterialListPayload.Entry e : list.entries()) {
				if (!e.done()) {
					lines.add(line(e));
				}
			}
			for (MaterialListPayload.Entry e : list.entries()) {
				if (e.done()) {
					lines.add(line(e));
				}
			}

			// Split into <= MAX_DESC chunks; only the first carries the title
			StringBuilder cur = new StringBuilder(header).append('\n');
			boolean first = true;
			for (String ln : lines) {
				if (cur.length() + ln.length() + 1 > MAX_DESC) {
					out.add(embed(first ? list.listName() : null, cur.toString()));
					first = false;
					cur = new StringBuilder();
				}
				cur.append('\n').append(ln);
			}
			out.add(embed(first ? list.listName() : null, cur.toString()));
		}
		JsonObject footer = new JsonObject();
		footer.add("text", new JsonPrimitive("Live from the server · check items in-game with /ml check"));
		out.get(out.size() - 1).add("footer", footer);
		return out;
	}

	private static String line(MaterialListPayload.Entry e) {
		String name = e.display().isEmpty() ? e.id() : e.display();
		if (e.done()) {
			return "~~" + name + "~~  ✓" + (e.claim().isEmpty() ? "" : " ⛏ " + e.claim());
		}
		String extra = "";
		if (e.stocked() > 0) {
			extra += " · 📦 " + fmt(Math.min(e.stocked(), e.count()));
		}
		if (!e.claim().isEmpty()) {
			extra += " · ⛏ " + e.claim();
			if (e.gathered() > 0) {
				extra += " (" + fmt(Math.min(e.gathered(), e.count())) + ")";
			}
		}
		return "**" + name + "**  `" + fmt(e.count()) + "`" + extra;
	}

	private static JsonObject embed(String listName, String description) {
		JsonObject embed = new JsonObject();
		if (listName != null) {
			embed.add("title", new JsonPrimitive("🧱 Build Materials: " + listName));
		}
		embed.add("description", new JsonPrimitive(description));
		embed.add("color", new JsonPrimitive(COLOR));
		return embed;
	}

	private static String fmt(long n) {
		return String.format(Locale.US, "%,d", n);
	}

	// --- message id persistence ---

	private static class IdsJson {
		List<String> messageIds;
	}

	private void loadIds() {
		if (!Files.exists(this.idsPath)) {
			return;
		}
		try (Reader r = Files.newBufferedReader(this.idsPath, StandardCharsets.UTF_8)) {
			IdsJson f = GSON.fromJson(r, IdsJson.class);
			if (f != null && f.messageIds != null) {
				this.messageIds = new ArrayList<>(f.messageIds);
			}
		} catch (Exception e) {
			MankeList.LOGGER.warn("[MankeList] could not read mankelist-webhook.json", e);
		}
	}

	private void saveIds() {
		try {
			IdsJson f = new IdsJson();
			f.messageIds = this.messageIds;
			Files.createDirectories(this.idsPath.getParent());
			try (Writer w = Files.newBufferedWriter(this.idsPath, StandardCharsets.UTF_8)) {
				GSON.toJson(f, w);
			}
		} catch (Exception e) {
			MankeList.LOGGER.warn("[MankeList] could not save mankelist-webhook.json", e);
		}
	}
}
