package xyz.w4ve.mankelist.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;



import xyz.w4ve.mankelist.MaterialListPayload;

/**
 * Client side: receives the lists from the server, draws the HUD and registers
 * the keys (J = toggle HUD, Shift+J = open the config, K = cycle the focused
 * list; the config also opens from Mod Menu or a custom-bound key).
 */
public class MankeListClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		MankeListConfig.load();

		ClientPlayNetworking.registerGlobalReceiver(MaterialListPayload.TYPE, (payload, context) ->
				context.client().execute(() -> ClientListState.setList(payload)));
		// v2 (server 0.5+): all active lists; the server picks one of the two
		// channels based on what the client registers
		ClientPlayNetworking.registerGlobalReceiver(xyz.w4ve.mankelist.MaterialListsPayload.TYPE, (payload, context) ->
				context.client().execute(() -> ClientListState.setLists(payload.lists())));

		// No stale list left behind after disconnecting
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
				client.execute(() -> ClientListState.setList(null)));

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("mankelist", "mankelist"));
		KeyMapping toggleHud = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.mankelist.toggle_hud", InputConstants.Type.KEYBOARD,
				InputConstants.KEY_J, category));
		KeyMapping cycleList = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.mankelist.cycle_list", InputConstants.Type.KEYBOARD,
				InputConstants.KEY_K, category));
		KeyMapping openConfig = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.mankelist.open_config", InputConstants.Type.KEYBOARD,
				InputConstants.UNKNOWN.getValue(), category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleHud.consumeClick()) {
				if (net.minecraft.client.Minecraft.getInstance().hasShiftDown()) {
					// Shift+J opens the settings (no Mod Menu needed);
					// plain J just toggles the HUD.
					if (client.gui.screen() == null) {
						client.setScreenAndShow(new ConfigScreen(null));
					}
					continue;
				}
				MankeListConfig cfg = MankeListConfig.get();
				cfg.hudEnabled = !cfg.hudEnabled;
				cfg.save();
			}
			while (cycleList.consumeClick()) {
				ClientListState.cycleFocus();
			}
			while (openConfig.consumeClick()) {
				if (client.gui.screen() == null) {
					client.setScreenAndShow(new ConfigScreen(null));
				}
			}
		});

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("mankelist", "materials"), (guiGraphics, deltaTracker) -> HudRenderer.render(guiGraphics));
	}
}
