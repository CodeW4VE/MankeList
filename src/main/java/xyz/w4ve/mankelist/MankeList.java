package xyz.w4ve.mankelist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Common/server side: publishes the active material lists to clients that run
 * the mod. The server re-reads the state file when its mtime changes (cheap
 * poll every pollSeconds) and broadcasts; every joining player gets the
 * current state. In singleplayer there is no state file and the mod stays
 * dormant.
 */
public class MankeList implements ModInitializer {
	public static final String MOD_ID = "mankelist";
	public static final Logger LOGGER = LoggerFactory.getLogger("MankeList");

	public static MankeList INSTANCE;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// Server-side config (config/mankelist-server.json)
	private Path statePath;
	private Path importDir;
	private String webhookUrl;
	private int pollTicks;
	private boolean promptsEnabled;
	private int scanTicks;
	private long promptCooldownMs;

	// Watcher state
	private long lastMtime = Long.MIN_VALUE;
	private int tickCounter = 0;
	private java.util.List<MaterialListPayload> current = java.util.List.of();

	// Manke's prompt: last nudge per player+material, so it does not repeat
	private int scanTickCounter = 0;
	private final Map<UUID, Map<String, Long>> lastPrompt = new HashMap<>();

	// Manke's followers (config/mankelist-followers.json): the prompt is
	// OPT-IN, it only talks to players who ran /ml follow. On a big server
	// most people are not farming the list and the nudge is just noise.
	private Path followersPath;
	private final Map<UUID, String> followers = new HashMap<>();

	// Stocking areas: periodic container scan → "stocked" field
	private StockAreas stockAreas;
	private int stockScanTicks;
	private int stockTickCounter = 0;

	// Built-in Discord board (webhook); inert without a URL in the config
	private DiscordWebhook webhook;

	@Override
	public void onInitialize() {
		INSTANCE = this;
		PayloadTypeRegistry.playS2C().register(MaterialListPayload.TYPE, MaterialListPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(MaterialListsPayload.TYPE, MaterialListsPayload.CODEC);

		loadServerConfig();
		loadFollowers();
		this.stockAreas = new StockAreas(FabricLoader.getInstance().getConfigDir().resolve("mankelist-stockareas.json"));
		this.webhook = new DiscordWebhook(this.webhookUrl,
				FabricLoader.getInstance().getConfigDir().resolve("mankelist-webhook.json"));
		if (this.webhook.enabled()) {
			LOGGER.info("[MankeList] Discord webhook board enabled");
		}

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				MlCommand.register(dispatcher));

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (!this.current.isEmpty()) {
				sendListsTo(handler.getPlayer());
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(this::pollAndBroadcast);
		ServerTickEvents.END_SERVER_TICK.register(this::scanInventories);
		ServerTickEvents.END_SERVER_TICK.register(this::scanStockAreas);
		ServerTickEvents.END_SERVER_TICK.register(server -> this.webhook.tick());
	}

	/**
	 * Manke's prompt: a light inventory scan of online followers. When someone
	 * carries the requested amount (or more) of an unchecked material, they get
	 * a PRIVATE nudge with a [Yes] button that runs /ml check. Cooldown per
	 * player+material keeps it polite; the manual confirmation kills false
	 * positives.
	 */
	private void scanInventories(MinecraftServer server) {
		if (!this.promptsEnabled || this.current.isEmpty()) {
			return;
		}
		if (++this.scanTickCounter < this.scanTicks) {
			return;
		}
		this.scanTickCounter = 0;

		long now = System.currentTimeMillis();
		for (ServerPlayer player : PlayerLookup.all(server)) {
			if (!this.followers.containsKey(player.getUUID())) {
				continue;
			}
			Map<String, Integer> carried = null;
			for (MaterialListPayload list : this.current) {
				for (MaterialListPayload.Entry e : list.entries()) {
					if (e.done() || e.count() <= 0) {
						continue;
					}
					if (carried == null) {
						carried = InventoryCounter.countCarried(player);
					}
					int have = carried.getOrDefault(e.id(), 0);
					if (have < e.count()) {
						continue;
					}
					Map<String, Long> per = this.lastPrompt.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
					Long last = per.get(e.id());
					if (last != null && now - last < this.promptCooldownMs) {
						continue;
					}
					per.put(e.id(), now);
					sendPrompt(player, e, have, this.current.size() > 1 ? list.listName() : null);
				}
			}
		}
	}

	/**
	 * Clears Manke's cooldown for one material: called on UN-check so the
	 * prompt can immediately ask again whoever carries it.
	 */
	public void clearPromptCooldown(String materialId) {
		for (Map<String, Long> per : this.lastPrompt.values()) {
			per.remove(materialId);
		}
	}

	public StockAreas stockAreas() {
		return this.stockAreas;
	}

	/**
	 * Stocking-area scan: counts what the containers hold and updates the
	 * "stocked" field of the active materials in the state file, ONLY when
	 * something changed (no pointless rewrites every minute). If some chunk of
	 * an area is unloaded, the previous values are kept.
	 */
	private void scanStockAreas(MinecraftServer server) {
		if (this.current.isEmpty()) {
			return;
		}
		if (++this.stockTickCounter < this.stockScanTicks) {
			return;
		}
		this.stockTickCounter = 0;

		Map<String, Integer> counts = this.stockAreas.scan(server);
		if (counts == null) {
			return; // unloaded area: touch nothing
		}

		// Anything different from what the active lists already know?
		boolean changed = false;
		for (MaterialListPayload list : this.current) {
			for (MaterialListPayload.Entry e : list.entries()) {
				if (e.stocked() != counts.getOrDefault(e.id(), 0)) {
					changed = true;
					break;
				}
			}
		}
		if (!changed) {
			return;
		}

		try {
			com.google.gson.JsonObject root = StateStore.readRoot(this.statePath);
			com.google.gson.JsonObject lists = root.getAsJsonObject("lists");
			for (String name : StateStore.activeNames(root)) {
				if (lists == null || !lists.has(name)) {
					continue;
				}
				com.google.gson.JsonArray blocks = lists.getAsJsonObject(name).getAsJsonArray("blocks");
				if (blocks == null) {
					continue;
				}
				for (com.google.gson.JsonElement el : blocks) {
					com.google.gson.JsonObject b = el.getAsJsonObject();
					int stocked = counts.getOrDefault(b.get("id").getAsString(), 0);
					if (stocked > 0) {
						b.add("stocked", new com.google.gson.JsonPrimitive(stocked));
					} else {
						b.remove("stocked");
					}
				}
			}
			StateStore.writeRoot(this.statePath, root);
			refreshAndBroadcast(server);
		} catch (Exception e) {
			LOGGER.warn("[MankeList] could not update stocked amounts in the state file", e);
		}
	}

	public boolean isFollowing(UUID id) {
		return this.followers.containsKey(id);
	}

	/**
	 * Follow/unfollow for one player. Returns false when they already were in
	 * that state (so the command replies "already ..." instead of re-saving).
	 */
	public boolean setFollowing(ServerPlayer player, boolean follow) {
		UUID id = player.getUUID();
		if (follow == this.followers.containsKey(id)) {
			return false;
		}
		if (follow) {
			this.followers.put(id, player.getGameProfile().getName());
		} else {
			this.followers.remove(id);
		}
		saveFollowers();
		return true;
	}

	private void loadFollowers() {
		this.followersPath = FabricLoader.getInstance().getConfigDir().resolve("mankelist-followers.json");
		if (!Files.exists(this.followersPath)) {
			return;
		}
		try (Reader r = Files.newBufferedReader(this.followersPath, StandardCharsets.UTF_8)) {
			Map<String, String> raw = GSON.fromJson(r, new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType());
			if (raw != null) {
				for (Map.Entry<String, String> en : raw.entrySet()) {
					try {
						this.followers.put(UUID.fromString(en.getKey()), en.getValue());
					} catch (IllegalArgumentException ignored) {
					}
				}
			}
			LOGGER.info("[MankeList] {} follower(s) loaded", this.followers.size());
		} catch (Exception e) {
			LOGGER.warn("[MankeList] could not read mankelist-followers.json", e);
		}
	}

	private void saveFollowers() {
		try {
			Map<String, String> raw = new HashMap<>();
			for (Map.Entry<UUID, String> en : this.followers.entrySet()) {
				raw.put(en.getKey().toString(), en.getValue());
			}
			Files.createDirectories(this.followersPath.getParent());
			try (Writer w = Files.newBufferedWriter(this.followersPath, StandardCharsets.UTF_8)) {
				GSON.toJson(raw, w);
			}
		} catch (Exception e) {
			LOGGER.warn("[MankeList] could not save mankelist-followers.json", e);
		}
	}

	private static void sendPrompt(ServerPlayer player, MaterialListPayload.Entry e, int have, String listName) {
		String shortId = e.id().startsWith("minecraft:") ? e.id().substring("minecraft:".length()) : e.id();
		// With several active lists the check goes disambiguated as list/material
		String arg = listName != null ? listName + "/" + shortId : shortId;
		String where = listName != null ? " for '" + listName + "'" : "";
		String counts = String.format(Locale.US, "%,d / %,d", have, e.count());
		Component msg = Component.literal("Ooh ooh! Manke sees you carry enough " + e.display()
						+ " (" + counts + ")" + where + ". Check it off? ")
				.withStyle(ChatFormatting.YELLOW)
				.append(Component.literal("[✓ Yes]").withStyle(style -> style
						.withColor(ChatFormatting.GREEN)
						.withBold(true)
						.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ml check " + arg))
						.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
								Component.literal("Check off " + e.display() + " for everyone")))));
		player.sendSystemMessage(msg);
	}

	private void pollAndBroadcast(MinecraftServer server) {
		if (++this.tickCounter < this.pollTicks) {
			return;
		}
		this.tickCounter = 0;

		long mtime;
		try {
			mtime = Files.exists(this.statePath) ? Files.getLastModifiedTime(this.statePath).toMillis() : -1L;
		} catch (Exception e) {
			return;
		}
		if (mtime == this.lastMtime) {
			return;
		}
		this.lastMtime = mtime;

		java.util.List<MaterialListPayload> fresh = SchemlistState.readActiveLists(this.statePath);
		if (Objects.equals(fresh, this.current)) {
			return;
		}
		this.current = fresh;
		LOGGER.info("[MankeList] state changed, broadcasting {} list(s): {}",
				fresh.size(), fresh.stream().map(MaterialListPayload::listName).toList());

		for (ServerPlayer player : PlayerLookup.all(server)) {
			sendListsTo(player);
		}
		this.webhook.push(this.current);
	}

	/**
	 * Sends the state to one player over the newest channel they register: v2
	 * (all lists) or v1 (first list only, 0.4.x clients). Without the mod they
	 * register neither and nothing is sent.
	 */
	private void sendListsTo(ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, MaterialListsPayload.TYPE)) {
			ServerPlayNetworking.send(player, new MaterialListsPayload(this.current));
		} else if (ServerPlayNetworking.canSend(player, MaterialListPayload.TYPE)) {
			ServerPlayNetworking.send(player, this.current.isEmpty() ? SchemlistState.empty() : this.current.get(0));
		}
	}

	/** The active lists as the server knows them right now. */
	public java.util.List<MaterialListPayload> currentLists() {
		return this.current;
	}

	public Path statePath() {
		return this.statePath;
	}

	/** Folder holding .litematic files for /ml import (default: the server's syncmatics/). */
	public Path importDir() {
		return this.importDir;
	}

	/**
	 * Re-read the state file NOW (after an /ml command changed it) and send it
	 * to every modded client, without waiting for the mtime poll.
	 */
	public void refreshAndBroadcast(MinecraftServer server) {
		try {
			this.lastMtime = Files.exists(this.statePath) ? Files.getLastModifiedTime(this.statePath).toMillis() : -1L;
		} catch (Exception ignored) {
		}
		this.current = SchemlistState.readActiveLists(this.statePath);
		for (ServerPlayer player : PlayerLookup.all(server)) {
			sendListsTo(player);
		}
		this.webhook.push(this.current);
	}

	/**
	 * config/mankelist-server.json: statePath + pollSeconds (watcher),
	 * promptsEnabled/scanSeconds/promptCooldownSeconds (Manke's prompt),
	 * importDir (/ml import), stockScanSeconds (stocking areas) and
	 * discordWebhookUrl (built-in board). Missing fields = defaults (nullable
	 * wrappers keep old configs valid). The default statePath assumes an MCDR
	 * layout (the server runs in server/ and MCDR config lives one level up);
	 * on a plain Fabric server it simply starts empty until /ml load.
	 */
	private void loadServerConfig() {
		Path cfgPath = FabricLoader.getInstance().getConfigDir().resolve("mankelist-server.json");
		String statePathStr = "../config/schemlist/state.json";
		String importDirStr = "syncmatics";
		String webhookUrlStr = "";
		int pollSeconds = 5;
		boolean prompts = true;
		int scanSeconds = 30;
		int cooldownSeconds = 600;
		int stockScanSeconds = 60;
		try {
			if (Files.exists(cfgPath)) {
				try (Reader r = Files.newBufferedReader(cfgPath, StandardCharsets.UTF_8)) {
					ServerConfigJson c = GSON.fromJson(r, ServerConfigJson.class);
					if (c != null) {
						if (c.statePath != null && !c.statePath.isBlank()) statePathStr = c.statePath;
						if (c.importDir != null && !c.importDir.isBlank()) importDirStr = c.importDir;
						if (c.discordWebhookUrl != null) webhookUrlStr = c.discordWebhookUrl;
						if (c.pollSeconds != null && c.pollSeconds > 0) pollSeconds = c.pollSeconds;
						if (c.promptsEnabled != null) prompts = c.promptsEnabled;
						if (c.scanSeconds != null && c.scanSeconds > 0) scanSeconds = c.scanSeconds;
						if (c.promptCooldownSeconds != null && c.promptCooldownSeconds > 0) cooldownSeconds = c.promptCooldownSeconds;
						if (c.stockScanSeconds != null && c.stockScanSeconds > 0) stockScanSeconds = c.stockScanSeconds;
					}
				}
			} else {
				ServerConfigJson c = new ServerConfigJson();
				c.statePath = statePathStr;
				c.importDir = importDirStr;
				c.discordWebhookUrl = webhookUrlStr;
				c.pollSeconds = pollSeconds;
				c.promptsEnabled = prompts;
				c.scanSeconds = scanSeconds;
				c.promptCooldownSeconds = cooldownSeconds;
				c.stockScanSeconds = stockScanSeconds;
				Files.createDirectories(cfgPath.getParent());
				try (Writer w = Files.newBufferedWriter(cfgPath, StandardCharsets.UTF_8)) {
					GSON.toJson(c, w);
				}
			}
		} catch (Exception e) {
			LOGGER.warn("[MankeList] could not read/create mankelist-server.json, using defaults", e);
		}
		this.statePath = Path.of(statePathStr).toAbsolutePath().normalize();
		this.importDir = Path.of(importDirStr).toAbsolutePath().normalize();
		this.webhookUrl = webhookUrlStr;
		this.pollTicks = pollSeconds * 20;
		this.promptsEnabled = prompts;
		this.scanTicks = scanSeconds * 20;
		this.promptCooldownMs = cooldownSeconds * 1000L;
		this.stockScanTicks = stockScanSeconds * 20;
		LOGGER.info("[MankeList] state: {} (poll {}s, prompts {} every {}s, cooldown {}s)",
				this.statePath, pollSeconds, prompts ? "ON" : "OFF", scanSeconds, cooldownSeconds);
	}

	private static class ServerConfigJson {
		String statePath;
		String importDir;
		String discordWebhookUrl;
		Integer pollSeconds;
		Boolean promptsEnabled;
		Integer scanSeconds;
		Integer promptCooldownSeconds;
		Integer stockScanSeconds;
	}
}
