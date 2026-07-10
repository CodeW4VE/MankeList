package xyz.w4ve.mankelist.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;

import org.lwjgl.glfw.GLFW;

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
				ClientListState.setList(null));

		KeyMapping toggleHud = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.mankelist.toggle_hud", InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_J, "key.categories.mankelist"));
		KeyMapping cycleList = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.mankelist.cycle_list", InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_K, "key.categories.mankelist"));
		KeyMapping openConfig = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.mankelist.open_config", InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN, "key.categories.mankelist"));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleHud.consumeClick()) {
				if (Screen.hasShiftDown()) {
					// Shift+J opens the settings (no Mod Menu needed);
					// plain J just toggles the HUD.
					if (client.screen == null) {
						client.setScreen(new ConfigScreen(null));
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
				if (client.screen == null) {
					client.setScreen(new ConfigScreen(null));
				}
			}
		});

		HudRenderCallback.EVENT.register((guiGraphics, deltaTracker) -> HudRenderer.render(guiGraphics));
	}
}
