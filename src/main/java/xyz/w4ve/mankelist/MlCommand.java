package xyz.w4ve.mankelist;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Native /ml command (Brigadier): real autocomplete, no red "Unknown command".
 * check/uncheck/follow/claim/unclaim are for everyone (it is a collaborative
 * list); load/unload/switch/reset/import/stockarea require permission level 2
 * (OP). Since 0.5 SEVERAL lists can be active at once: material arguments
 * accept a bare "material" when unambiguous or "list/material" to
 * disambiguate ("/" never appears in an id).
 */
public final class MlCommand {

	private MlCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("ml")
				.executes(MlCommand::status)
				.then(Commands.literal("status").executes(MlCommand::status))
				.then(Commands.literal("lists").executes(MlCommand::lists))
				.then(Commands.literal("follow").executes(ctx -> setFollow(ctx, true)))
				.then(Commands.literal("unfollow").executes(ctx -> setFollow(ctx, false)))
				.then(Commands.literal("check")
						.then(Commands.argument("material", StringArgumentType.greedyString())
								.suggests((ctx, b) -> suggestMaterials(b, e -> !e.done()))
								.executes(ctx -> setDone(ctx, true))))
				.then(Commands.literal("uncheck")
						.then(Commands.argument("material", StringArgumentType.greedyString())
								.suggests((ctx, b) -> suggestMaterials(b, MaterialListPayload.Entry::done))
								.executes(ctx -> setDone(ctx, false))))
				.then(Commands.literal("have")
						.then(Commands.argument("amount", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
								.then(Commands.argument("material", StringArgumentType.greedyString())
										.suggests((ctx, b) -> suggestMaterials(b, e -> !e.done()))
										.executes(MlCommand::have))))
				.then(Commands.literal("claim")
						.then(Commands.argument("material", StringArgumentType.greedyString())
								.suggests((ctx, b) -> suggestMaterials(b, e -> !e.done() && e.claim().isEmpty()))
								.executes(MlCommand::claim)))
				.then(Commands.literal("unclaim")
						.then(Commands.argument("material", StringArgumentType.greedyString())
								.suggests((ctx, b) -> suggestMaterials(b, e -> !e.claim().isEmpty()))
								.executes(MlCommand::unclaim)))
				.then(Commands.literal("load")
						.requires(src -> src.hasPermission(2))
						.then(Commands.argument("list", StringArgumentType.word())
								.suggests((ctx, b) -> suggestListFiles(b))
								.executes(MlCommand::load)))
				.then(Commands.literal("import")
						.requires(src -> src.hasPermission(2))
						.then(Commands.argument("file", StringArgumentType.greedyString())
								.suggests((ctx, b) -> suggestLitematics(b))
								.executes(MlCommand::importLitematic)))
				.then(Commands.literal("unload")
						.requires(src -> src.hasPermission(2))
						.then(Commands.argument("list", StringArgumentType.word())
								.suggests((ctx, b) -> suggestNames(b, true))
								.executes(MlCommand::unload)))
				.then(Commands.literal("switch")
						.requires(src -> src.hasPermission(2))
						.then(Commands.argument("list", StringArgumentType.word())
								.suggests((ctx, b) -> suggestNames(b, false))
								.executes(MlCommand::switchTo)))
				.then(Commands.literal("stock").executes(MlCommand::stock))
				.then(Commands.literal("stockarea")
						.requires(src -> src.hasPermission(2))
						.then(Commands.literal("add")
								.then(Commands.argument("from", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
										.then(Commands.argument("to", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
												.executes(MlCommand::stockAreaAdd))))
						.then(Commands.literal("list").executes(MlCommand::stockAreaList))
						.then(Commands.literal("clear").executes(MlCommand::stockAreaClear)))
				.then(Commands.literal("reset")
						.requires(src -> src.hasPermission(2))
						.executes(ctx -> reset(ctx, null))
						.then(Commands.argument("list", StringArgumentType.word())
								.suggests((ctx, b) -> suggestNames(b, true))
								.executes(ctx -> reset(ctx, StringArgumentType.getString(ctx, "list"))))));
	}

	// --- subcommands ---

	private static int status(CommandContext<CommandSourceStack> ctx) {
		List<MaterialListPayload> lists = MankeList.INSTANCE.currentLists();
		if (lists.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("No material list active. Use /ml load <list>"));
			return 0;
		}
		for (MaterialListPayload list : lists) {
			long done = list.entries().stream().filter(MaterialListPayload.Entry::done).count();
			int total = list.entries().size();
			int pct = total == 0 ? 0 : (int) (done * 100 / total);
			ctx.getSource().sendSuccess(() -> Component.literal("=== " + list.listName() + ": " + done + "/" + total + " (" + pct + "%) ===")
					.withStyle(ChatFormatting.GOLD), false);
			for (MaterialListPayload.Entry e : list.entries()) {
				// Pending: @X is farming it (maybe with partial progress); done: @X got it
				String farmed = e.gathered() > 0 && !e.done()
						? " (" + String.format(Locale.US, "%,d", Math.min(e.gathered(), e.count())) + " farmed)" : "";
				String claim = (e.claim().isEmpty() ? "" : "  @" + e.claim()) + farmed;
				String stocked = e.stocked() > 0 && !e.done()
						? " (stocked " + String.format(Locale.US, "%,d", Math.min(e.stocked(), e.count())) + ")" : "";
				Component line = e.done()
						? Component.literal("  ✓ " + e.display() + claim).withStyle(ChatFormatting.GREEN)
						: Component.literal("  ✘ " + e.display() + ": " + String.format(Locale.US, "%,d", e.count()) + stocked + claim)
								.withStyle(ChatFormatting.WHITE);
				ctx.getSource().sendSuccess(() -> line, false);
			}
		}
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player != null) {
			boolean following = MankeList.INSTANCE.isFollowing(player.getUUID());
			Component tail = Component.literal(following
					? "Manke prompts: ON (/ml unfollow to stop)"
					: "Manke prompts: OFF (/ml follow to get nudged when you carry enough)")
					.withStyle(ChatFormatting.GRAY);
			ctx.getSource().sendSuccess(() -> tail, false);
		}
		return 1;
	}

	private static int setFollow(CommandContext<CommandSourceStack> ctx, boolean follow) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			ctx.getSource().sendFailure(Component.literal("Only players can " + (follow ? "follow" : "unfollow") + " the list"));
			return 0;
		}
		if (!MankeList.INSTANCE.setFollowing(player, follow)) {
			ctx.getSource().sendFailure(Component.literal(follow
					? "You are already following the material list"
					: "You were not following the material list"));
			return 0;
		}
		Component msg = follow
				? Component.literal("[ML] Following! Manke will nudge you when you carry enough of a pending material. /ml unfollow to stop.")
						.withStyle(ChatFormatting.GREEN)
				: Component.literal("[ML] Unfollowed, Manke will leave you alone. /ml follow to opt back in.")
						.withStyle(ChatFormatting.YELLOW);
		ctx.getSource().sendSuccess(() -> msg, false);
		return 1;
	}

	private static int lists(CommandContext<CommandSourceStack> ctx) {
		JsonObject root = StateStore.readRoot(MankeList.INSTANCE.statePath());
		List<String> active = StateStore.activeNames(root);
		List<String> saved = new ArrayList<>();
		JsonObject listsObj = root.getAsJsonObject("lists");
		if (listsObj != null) {
			for (String name : listsObj.keySet()) {
				if (!active.contains(name)) {
					saved.add(name);
				}
			}
		}
		List<String> files = availableListFiles();
		if (active.isEmpty() && saved.isEmpty() && files.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("No list files found in " + MankeList.INSTANCE.statePath().getParent()));
			return 0;
		}
		if (!active.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.literal("Active: " + String.join(", ", active))
					.withStyle(ChatFormatting.GOLD), false);
		}
		if (!saved.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.literal("Saved (progress kept, /ml switch to activate): " + String.join(", ", saved))
					.withStyle(ChatFormatting.YELLOW), false);
		}
		if (!files.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.literal("Files (/ml load starts fresh): " + String.join(", ", files))
					.withStyle(ChatFormatting.GRAY), false);
		}
		return 1;
	}

	/** Result of resolving a "material" or "list/material" argument in the state. */
	private record Found(JsonObject root, String list, JsonObject entry) {}

	/**
	 * Resolves the material argument against the active lists, with the
	 * "list/material" disambiguation. Sends the failure and returns null when
	 * there is no active list, the material does not exist, or it appears in
	 * several lists without disambiguation.
	 */
	private static Found resolve(CommandContext<CommandSourceStack> ctx, String verb) {
		String raw = StringArgumentType.getString(ctx, "material").trim().toLowerCase(Locale.ROOT);
		String wantedList = null;
		if (raw.contains("/")) {
			wantedList = raw.substring(0, raw.indexOf('/'));
			raw = raw.substring(raw.indexOf('/') + 1);
		}
		JsonObject root = StateStore.readRoot(MankeList.INSTANCE.statePath());
		Map<String, JsonObject> active = activeLists(root);
		if (active.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("No material list active. Use /ml load <list>"));
			return null;
		}
		if (wantedList != null && !active.containsKey(wantedList)) {
			ctx.getSource().sendFailure(Component.literal("No active list named '" + wantedList + "' (see /ml lists)"));
			return null;
		}

		// Look the material up in the active lists (or only the requested one)
		List<String> foundIn = new ArrayList<>();
		JsonObject match = null;
		String matchList = null;
		for (Map.Entry<String, JsonObject> en : active.entrySet()) {
			if (wantedList != null && !en.getKey().equals(wantedList)) {
				continue;
			}
			for (JsonElement el : en.getValue().getAsJsonArray("blocks")) {
				JsonObject b = el.getAsJsonObject();
				if (matches(raw, b)) {
					foundIn.add(en.getKey());
					match = b;
					matchList = en.getKey();
					break;
				}
			}
		}
		if (match == null) {
			ctx.getSource().sendFailure(Component.literal("No material named '" + raw + "' in the active list(s)"));
			return null;
		}
		if (foundIn.size() > 1) {
			ctx.getSource().sendFailure(Component.literal("'" + raw + "' is in several lists: "
					+ String.join(", ", foundIn) + ". Use /ml " + verb + " "
					+ foundIn.get(0) + "/" + raw + " (list/material)"));
			return null;
		}
		return new Found(root, matchList, match);
	}

	/** Saves the root and rebroadcasts; false (failure already sent) when it could not. */
	private static boolean saveAndBroadcast(CommandContext<CommandSourceStack> ctx, JsonObject root, String what) {
		try {
			StateStore.writeRoot(MankeList.INSTANCE.statePath(), root);
		} catch (Exception e) {
			MankeList.LOGGER.error("[MankeList] {} failed", what, e);
			ctx.getSource().sendFailure(Component.literal("Could not save the list, check server log"));
			return false;
		}
		MankeList.INSTANCE.refreshAndBroadcast(ctx.getSource().getServer());
		return true;
	}

	private static int setDone(CommandContext<CommandSourceStack> ctx, boolean done) {
		Found f = resolve(ctx, done ? "check" : "uncheck");
		if (f == null) {
			return 0;
		}
		JsonObject match = f.entry();
		if (match.has("done") && match.get("done").getAsBoolean() == done) {
			String d = match.get("display").getAsString();
			ctx.getSource().sendFailure(Component.literal(d + " is already " + (done ? "checked" : "unchecked")));
			return 0;
		}
		match.add("done", new JsonPrimitive(done));
		if (done) {
			// The claim flips from "who farms it" to "who got it": a player
			// checker takes the credit; console/RCON never overwrite the claim
			// of whoever was farming it
			ServerPlayer checker = ctx.getSource().getPlayer();
			if (checker != null) {
				match.add("claim", new JsonPrimitive(checker.getGameProfile().getName()));
			}
		} else {
			// Back to pending, unowned
			match.remove("claim");
		}
		// Either way the partial report is stale now
		match.remove("gathered");
		if (!saveAndBroadcast(ctx, f.root(), "/ml check")) {
			return 0;
		}
		if (!done) {
			// Unchecked: the prompt may ask again without waiting out the cooldown
			MankeList.INSTANCE.clearPromptCooldown(match.get("id").getAsString());
		}

		// Announce to the whole server (and to any chat bridge that relays it)
		MaterialListPayload list = findList(f.list());
		long doneCount = list == null ? 0 : list.entries().stream().filter(MaterialListPayload.Entry::done).count();
		int totalCount = list == null ? 0 : list.entries().size();
		String where = MankeList.INSTANCE.currentLists().size() > 1 ? f.list() + " " : "";
		String display = match.get("display").getAsString();
		String who = ctx.getSource().getTextName();
		Component msg = done
				? Component.literal("[ML] " + who + " checked off " + display + " ✓ (" + where + doneCount + "/" + totalCount + " done)")
						.withStyle(ChatFormatting.GREEN)
				: Component.literal("[ML] " + who + " unchecked " + display + " (" + where + doneCount + "/" + totalCount + " done)")
						.withStyle(ChatFormatting.YELLOW);
		ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(msg, false);
		return 1;
	}

	/**
	 * /ml have <material> <amount>: report partial farming progress ("I only
	 * have 5,000 of the 20,000 azaleas so far"). Claims the material for you
	 * when it was unclaimed; counts as fine progress everywhere. Cleared on
	 * check/uncheck. Amount 0 clears the report.
	 */
	private static int have(CommandContext<CommandSourceStack> ctx) {
		int amount = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "amount");
		Found f = resolve(ctx, "have");
		if (f == null) {
			return 0;
		}
		JsonObject match = f.entry();
		String display = match.get("display").getAsString();
		if (match.has("done") && match.get("done").getAsBoolean()) {
			ctx.getSource().sendFailure(Component.literal(display + " is already checked off"));
			return 0;
		}
		String owner = match.has("claim") && !match.get("claim").isJsonNull() ? match.get("claim").getAsString() : "";
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player != null) {
			String me = player.getGameProfile().getName();
			if (owner.isEmpty()) {
				// Reporting progress implies you are farming it
				match.add("claim", new JsonPrimitive(me));
			} else if (!owner.equals(me) && !ctx.getSource().hasPermission(2)) {
				ctx.getSource().sendFailure(Component.literal(display + " is claimed by " + owner
						+ ", their progress is theirs to report (or an op can override)"));
				return 0;
			}
		}
		if (amount > 0) {
			match.add("gathered", new JsonPrimitive(amount));
		} else {
			match.remove("gathered");
		}
		if (!saveAndBroadcast(ctx, f.root(), "/ml have")) {
			return 0;
		}
		String who = ctx.getSource().getTextName();
		int need = match.get("count").getAsInt();
		if (amount > 0) {
			ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(
					Component.literal("[ML] " + who + " has " + String.format(Locale.US, "%,d", amount)
							+ " / " + String.format(Locale.US, "%,d", need) + " " + display + " so far ⛏")
							.withStyle(ChatFormatting.AQUA), false);
		} else {
			ctx.getSource().sendSuccess(() -> Component.literal("[ML] Progress report for " + display + " cleared")
					.withStyle(ChatFormatting.YELLOW), false);
		}
		return 1;
	}

	private static int claim(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			ctx.getSource().sendFailure(Component.literal("Only players can claim a material"));
			return 0;
		}
		Found f = resolve(ctx, "claim");
		if (f == null) {
			return 0;
		}
		JsonObject match = f.entry();
		String display = match.get("display").getAsString();
		if (match.has("done") && match.get("done").getAsBoolean()) {
			ctx.getSource().sendFailure(Component.literal(display + " is already checked off"));
			return 0;
		}
		String me = player.getGameProfile().getName();
		String owner = match.has("claim") && !match.get("claim").isJsonNull() ? match.get("claim").getAsString() : "";
		if (me.equals(owner)) {
			ctx.getSource().sendFailure(Component.literal("You already claimed " + display));
			return 0;
		}
		if (!owner.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal(display + " is already claimed by " + owner
					+ " (ask them, or an op can /ml unclaim it)"));
			return 0;
		}
		match.add("claim", new JsonPrimitive(me));
		if (!saveAndBroadcast(ctx, f.root(), "/ml claim")) {
			return 0;
		}
		String where = MankeList.INSTANCE.currentLists().size() > 1 ? " (" + f.list() + ")" : "";
		ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(
				Component.literal("[ML] " + me + " is farming " + display + where + " ⛏").withStyle(ChatFormatting.AQUA), false);
		return 1;
	}

	private static int unclaim(CommandContext<CommandSourceStack> ctx) {
		Found f = resolve(ctx, "unclaim");
		if (f == null) {
			return 0;
		}
		JsonObject match = f.entry();
		String display = match.get("display").getAsString();
		String owner = match.has("claim") && !match.get("claim").isJsonNull() ? match.get("claim").getAsString() : "";
		if (owner.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal(display + " is not claimed by anyone"));
			return 0;
		}
		ServerPlayer player = ctx.getSource().getPlayer();
		String me = player != null ? player.getGameProfile().getName() : ctx.getSource().getTextName();
		if (!me.equals(owner) && !ctx.getSource().hasPermission(2)) {
			ctx.getSource().sendFailure(Component.literal(display + " is claimed by " + owner
					+ ", only they (or an op) can unclaim it"));
			return 0;
		}
		match.remove("claim");
		// The partial report leaves with the farmer
		match.remove("gathered");
		if (!saveAndBroadcast(ctx, f.root(), "/ml unclaim")) {
			return 0;
		}
		String byWhom = me.equals(owner) ? owner + " unclaimed it" : me + " unclaimed it from " + owner;
		ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(
				Component.literal("[ML] " + display + " is up for grabs again (" + byWhom + ")").withStyle(ChatFormatting.YELLOW), false);
		return 1;
	}

	private static int load(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "list");
		Path statePath = MankeList.INSTANCE.statePath();
		Path listFile = statePath.getParent().resolve(name + ".json");
		if (!Files.isRegularFile(listFile)) {
			ctx.getSource().sendFailure(Component.literal("No list file " + listFile.getFileName() + " (see /ml lists)"));
			return 0;
		}
		try {
			// A list file is a flat {name, source, blocks} object (schemlist
			// format). load ALWAYS re-reads the file (resets the checks); to
			// reactivate a saved list with its progress there is /ml switch.
			JsonObject listObj = com.google.gson.JsonParser.parseString(Files.readString(listFile)).getAsJsonObject();
			if (!listObj.has("blocks")) {
				ctx.getSource().sendFailure(Component.literal(listFile.getFileName() + " has no 'blocks' array"));
				return 0;
			}
			JsonObject root = StateStore.readRoot(statePath);
			root.getAsJsonObject("lists").add(name, listObj);
			List<String> active = StateStore.activeNames(root);
			if (!active.contains(name)) {
				active.add(name);
			}
			StateStore.setActiveNames(root, active);
			StateStore.writeRoot(statePath, root);
		} catch (Exception e) {
			MankeList.LOGGER.error("[MankeList] /ml load failed", e);
			ctx.getSource().sendFailure(Component.literal("Could not load the list, check server log"));
			return 0;
		}
		MankeList.INSTANCE.refreshAndBroadcast(ctx.getSource().getServer());
		MaterialListPayload loaded = findList(name);
		int total = loaded == null ? 0 : loaded.entries().size();
		int nActive = MankeList.INSTANCE.currentLists().size();
		String tail = nActive > 1 ? " (" + nActive + " lists active)" : "";
		ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(
				Component.literal("[ML] Material list '" + name + "' loaded (" + total + " materials)" + tail
						+ " · /ml follow for pickup prompts").withStyle(ChatFormatting.GOLD), false);
		return 1;
	}

	/**
	 * /ml import <file.litematic>: parses the NBT in Java, writes <name>.json
	 * (schemlist format, reusable by /ml load) and activates it. Parsing a big
	 * litematic (~80M positions) blocks the tick for a moment: it is an OP
	 * command, that is assumed.
	 */
	private static int importLitematic(CommandContext<CommandSourceStack> ctx) {
		String raw = StringArgumentType.getString(ctx, "file").trim();
		if (!raw.toLowerCase(Locale.ROOT).endsWith(".litematic")) {
			raw = raw + ".litematic";
		}
		Path dir = MankeList.INSTANCE.importDir();
		Path file = dir.resolve(raw).toAbsolutePath().normalize();
		if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
			ctx.getSource().sendFailure(Component.literal("No litematic '" + raw + "' in " + dir));
			return 0;
		}
		String name = file.getFileName().toString();
		name = name.substring(0, name.length() - ".litematic".length())
				.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
		if (name.isEmpty()) {
			name = "imported";
		}

		JsonObject listObj;
		try {
			listObj = LitematicImporter.parse(file, name);
		} catch (Exception e) {
			MankeList.LOGGER.error("[MankeList] could not parse {}", file, e);
			ctx.getSource().sendFailure(Component.literal("Could not parse " + file.getFileName() + ": " + e.getMessage()));
			return 0;
		}
		if (listObj.getAsJsonArray("blocks").isEmpty()) {
			ctx.getSource().sendFailure(Component.literal(file.getFileName() + " has no countable blocks"));
			return 0;
		}

		Path statePath = MankeList.INSTANCE.statePath();
		try {
			// The <name>.json becomes a regular list file (/ml load can re-read it)
			Files.createDirectories(statePath.getParent());
			try (java.io.Writer w = Files.newBufferedWriter(statePath.getParent().resolve(name + ".json"),
					java.nio.charset.StandardCharsets.UTF_8)) {
				new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(listObj, w);
			}
			JsonObject root = StateStore.readRoot(statePath);
			root.getAsJsonObject("lists").add(name, listObj);
			List<String> active = StateStore.activeNames(root);
			if (!active.contains(name)) {
				active.add(name);
			}
			StateStore.setActiveNames(root, active);
			StateStore.writeRoot(statePath, root);
		} catch (Exception e) {
			MankeList.LOGGER.error("[MankeList] /ml import failed", e);
			ctx.getSource().sendFailure(Component.literal("Could not save the imported list, check server log"));
			return 0;
		}
		MankeList.INSTANCE.refreshAndBroadcast(ctx.getSource().getServer());
		int materials = listObj.getAsJsonArray("blocks").size();
		long blocks = 0;
		for (JsonElement el : listObj.getAsJsonArray("blocks")) {
			blocks += el.getAsJsonObject().get("count").getAsLong();
		}
		String msg = "[ML] Imported '" + name + "' from " + file.getFileName()
				+ " (" + materials + " materials, " + String.format(Locale.US, "%,d", blocks)
				+ " blocks) · /ml follow for pickup prompts";
		ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(
				Component.literal(msg).withStyle(ChatFormatting.GOLD), false);
		return 1;
	}

	private static int unload(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "list");
		Path statePath = MankeList.INSTANCE.statePath();
		JsonObject root = StateStore.readRoot(statePath);
		List<String> active = StateStore.activeNames(root);
		if (!active.remove(name)) {
			ctx.getSource().sendFailure(Component.literal("'" + name + "' is not an active list (see /ml lists)"));
			return 0;
		}
		StateStore.setActiveNames(root, active);
		try {
			StateStore.writeRoot(statePath, root);
		} catch (Exception e) {
			MankeList.LOGGER.error("[MankeList] /ml unload failed", e);
			ctx.getSource().sendFailure(Component.literal("Could not save the list, check server log"));
			return 0;
		}
		MankeList.INSTANCE.refreshAndBroadcast(ctx.getSource().getServer());
		ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(
				Component.literal("[ML] Material list '" + name + "' unloaded (progress kept, /ml switch " + name + " brings it back)")
						.withStyle(ChatFormatting.GOLD), false);
		return 1;
	}

	private static int switchTo(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "list");
		Path statePath = MankeList.INSTANCE.statePath();
		JsonObject root = StateStore.readRoot(statePath);
		JsonObject listsObj = root.getAsJsonObject("lists");
		if (listsObj == null || !listsObj.has(name)) {
			ctx.getSource().sendFailure(Component.literal("No saved list '" + name + "' in the state (see /ml lists; /ml load reads a file)"));
			return 0;
		}
		List<String> active = StateStore.activeNames(root);
		if (active.contains(name)) {
			ctx.getSource().sendFailure(Component.literal("'" + name + "' is already active"));
			return 0;
		}
		active.add(name);
		StateStore.setActiveNames(root, active);
		try {
			StateStore.writeRoot(statePath, root);
		} catch (Exception e) {
			MankeList.LOGGER.error("[MankeList] /ml switch failed", e);
			ctx.getSource().sendFailure(Component.literal("Could not save the list, check server log"));
			return 0;
		}
		MankeList.INSTANCE.refreshAndBroadcast(ctx.getSource().getServer());
		MaterialListPayload list = findList(name);
		long done = list == null ? 0 : list.entries().stream().filter(MaterialListPayload.Entry::done).count();
		int total = list == null ? 0 : list.entries().size();
		ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(
				Component.literal("[ML] Material list '" + name + "' is active again (" + done + "/" + total + " done)")
						.withStyle(ChatFormatting.GOLD), false);
		return 1;
	}

	/**
	 * /ml stock: compact stock report — only pending materials that have
	 * something in the stocking areas, so a 293-material list does not flood
	 * the chat like /ml status does.
	 */
	private static int stock(CommandContext<CommandSourceStack> ctx) {
		if (MankeList.INSTANCE.stockAreas().list().isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("No stocking areas set. An op can add one: /ml stockarea add <x1 y1 z1> <x2 y2 z2>"));
			return 0;
		}
		List<MaterialListPayload> lists = MankeList.INSTANCE.currentLists();
		if (lists.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("No material list active. Use /ml load <list>"));
			return 0;
		}
		boolean any = false;
		for (MaterialListPayload list : lists) {
			List<Component> lines = new ArrayList<>();
			for (MaterialListPayload.Entry e : list.entries()) {
				if (e.done() || e.stocked() <= 0) {
					continue;
				}
				boolean covered = e.stocked() >= e.count();
				String counts = String.format(Locale.US, "%,d / %,d", Math.min(e.stocked(), e.count()), e.count());
				lines.add(Component.literal("  " + (covered ? "✓ " : "▪ ") + e.display() + ": " + counts
								+ (covered ? " (covered!)" : ""))
						.withStyle(covered ? ChatFormatting.GREEN : ChatFormatting.WHITE));
			}
			if (lines.isEmpty()) {
				continue;
			}
			any = true;
			ctx.getSource().sendSuccess(() -> Component.literal("=== Stocked for '" + list.listName() + "' ===")
					.withStyle(ChatFormatting.GOLD), false);
			for (Component line : lines) {
				ctx.getSource().sendSuccess(() -> line, false);
			}
		}
		if (!any) {
			ctx.getSource().sendSuccess(() -> Component.literal("Nothing stocked yet: drop materials in the stocking area chests")
					.withStyle(ChatFormatting.YELLOW), false);
		}
		return 1;
	}

	private static int stockAreaAdd(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		net.minecraft.core.BlockPos from = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "from");
		net.minecraft.core.BlockPos to = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "to");
		String dimension = ctx.getSource().getLevel().dimension().location().toString();
		StockAreas.Area area = StockAreas.Area.of(dimension, from, to);
		MankeList.INSTANCE.stockAreas().add(area);
		ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(
				Component.literal("[ML] Stocking area added: " + area.describe()
						+ ": containers there now count as 'stocked'").withStyle(ChatFormatting.GOLD), false);
		return 1;
	}

	private static int stockAreaList(CommandContext<CommandSourceStack> ctx) {
		List<StockAreas.Area> areas = MankeList.INSTANCE.stockAreas().list();
		if (areas.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("No stocking areas set. /ml stockarea add <x1 y1 z1> <x2 y2 z2>"));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Stocking areas (" + areas.size() + "):").withStyle(ChatFormatting.GOLD), false);
		for (StockAreas.Area a : areas) {
			ctx.getSource().sendSuccess(() -> Component.literal("  " + a.describe()).withStyle(ChatFormatting.WHITE), false);
		}
		return 1;
	}

	private static int stockAreaClear(CommandContext<CommandSourceStack> ctx) {
		int n = MankeList.INSTANCE.stockAreas().clear();
		ctx.getSource().sendSuccess(() -> Component.literal("[ML] " + n + " stocking area(s) removed").withStyle(ChatFormatting.YELLOW), true);
		return 1;
	}

	private static int reset(CommandContext<CommandSourceStack> ctx, String name) {
		Path statePath = MankeList.INSTANCE.statePath();
		JsonObject root = StateStore.readRoot(statePath);
		Map<String, JsonObject> active = activeLists(root);
		if (active.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("No material list active"));
			return 0;
		}
		if (name == null) {
			if (active.size() > 1) {
				ctx.getSource().sendFailure(Component.literal("Several lists active, say which one: /ml reset <list>"));
				return 0;
			}
			name = active.keySet().iterator().next();
		}
		JsonObject listObj = active.get(name);
		if (listObj == null) {
			ctx.getSource().sendFailure(Component.literal("'" + name + "' is not an active list (see /ml lists)"));
			return 0;
		}
		for (JsonElement el : listObj.getAsJsonArray("blocks")) {
			JsonObject b = el.getAsJsonObject();
			b.add("done", new JsonPrimitive(false));
			b.remove("claim");
			b.remove("gathered");
		}
		try {
			StateStore.writeRoot(statePath, root);
		} catch (Exception e) {
			MankeList.LOGGER.error("[MankeList] /ml reset failed", e);
			ctx.getSource().sendFailure(Component.literal("Could not save the list, check server log"));
			return 0;
		}
		MankeList.INSTANCE.refreshAndBroadcast(ctx.getSource().getServer());
		String finalName = name;
		ctx.getSource().sendSuccess(() -> Component.literal("[ML] All checks of '" + finalName + "' reset").withStyle(ChatFormatting.YELLOW), true);
		return 1;
	}

	// --- helpers ---

	/** The root's active lists (name → object), in order. */
	private static Map<String, JsonObject> activeLists(JsonObject root) {
		Map<String, JsonObject> out = new LinkedHashMap<>();
		JsonObject lists = root.getAsJsonObject("lists");
		if (lists == null) {
			return out;
		}
		for (String name : StateStore.activeNames(root)) {
			if (lists.has(name)) {
				JsonObject listObj = lists.getAsJsonObject(name);
				if (listObj.has("blocks") && listObj.get("blocks").isJsonArray()) {
					out.put(name, listObj);
				}
			}
		}
		return out;
	}

	/** The active list {@code name} in the server's in-memory snapshot, or null. */
	private static MaterialListPayload findList(String name) {
		for (MaterialListPayload list : MankeList.INSTANCE.currentLists()) {
			if (list.listName().equals(name)) {
				return list;
			}
		}
		return null;
	}

	/** Matches by short id ("emerald_block"), full id, or display name. */
	private static boolean matches(String raw, JsonObject block) {
		String id = block.get("id").getAsString().toLowerCase(Locale.ROOT);
		String shortId = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		String display = block.has("display") ? block.get("display").getAsString().toLowerCase(Locale.ROOT) : "";
		return raw.equals(id) || raw.equals(shortId) || raw.equals(display);
	}

	/**
	 * Suggestions for check/uncheck/claim/unclaim: a bare short id when it is
	 * unambiguous across the active lists, "list/id" for the repeated ones.
	 */
	private static CompletableFuture<Suggestions> suggestMaterials(SuggestionsBuilder builder,
			java.util.function.Predicate<MaterialListPayload.Entry> filter) {
		Map<String, List<String>> byId = new LinkedHashMap<>();
		for (MaterialListPayload list : MankeList.INSTANCE.currentLists()) {
			for (MaterialListPayload.Entry e : list.entries()) {
				if (!filter.test(e)) {
					continue;
				}
				String id = e.id();
				String shortId = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
				byId.computeIfAbsent(shortId, k -> new ArrayList<>()).add(list.listName());
			}
		}
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, List<String>> en : byId.entrySet()) {
			if (en.getValue().size() == 1) {
				out.add(en.getKey());
			} else {
				for (String listName : en.getValue()) {
					out.add(listName + "/" + en.getKey());
				}
			}
		}
		return SharedSuggestionProvider.suggest(out.stream(), builder);
	}

	/** List names: active (unload/reset) or saved-but-inactive (switch). */
	private static CompletableFuture<Suggestions> suggestNames(SuggestionsBuilder builder, boolean activeOnes) {
		JsonObject root = StateStore.readRoot(MankeList.INSTANCE.statePath());
		List<String> active = StateStore.activeNames(root);
		Stream<String> names;
		if (activeOnes) {
			names = active.stream();
		} else {
			JsonObject lists = root.getAsJsonObject("lists");
			names = lists == null ? Stream.of() : lists.keySet().stream().filter(n -> !active.contains(n));
		}
		return SharedSuggestionProvider.suggest(names, builder);
	}

	private static CompletableFuture<Suggestions> suggestLitematics(SuggestionsBuilder builder) {
		List<String> names = new ArrayList<>();
		try (Stream<Path> files = Files.list(MankeList.INSTANCE.importDir())) {
			files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic"))
					.forEach(p -> names.add(p.getFileName().toString()));
		} catch (Exception ignored) {
		}
		return SharedSuggestionProvider.suggest(names.stream(), builder);
	}

	private static CompletableFuture<Suggestions> suggestListFiles(SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(availableListFiles().stream(), builder);
	}

	private static List<String> availableListFiles() {
		List<String> names = new ArrayList<>();
		try (Stream<Path> files = Files.list(MankeList.INSTANCE.statePath().getParent())) {
			files.filter(p -> p.getFileName().toString().endsWith(".json"))
					.filter(p -> !p.getFileName().toString().equals("state.json"))
					.forEach(p -> {
						String n = p.getFileName().toString();
						names.add(n.substring(0, n.length() - ".json".length()));
					});
		} catch (Exception ignored) {
		}
		return names;
	}
}
