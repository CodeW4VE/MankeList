package xyz.w4ve.mankelist.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * OPTIONAL Mod Menu integration (the gear that opens {@link ConfigScreen}).
 * Mod Menu is modCompileOnly: when the player does not have it, Fabric
 * ignores this entrypoint and nothing happens.
 */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ConfigScreen::new;
	}
}
