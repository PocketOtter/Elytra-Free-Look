package dev.pocketotter.elytrafreelook.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Registered as the {@code "modmenu"} entrypoint in fabric.mod.json, so Mod
 * Menu's config button for this mod opens {@link FreeLookConfigScreen}.
 *
 * <p>Mod Menu is a soft dependency ({@code "suggests"} in fabric.mod.json,
 * not {@code "depends"}): this class only ever gets loaded if Mod Menu
 * itself is present to go looking for a {@code "modmenu"} entrypoint in the
 * first place, so its absence can't crash the mod. The mod's actual
 * settings live entirely in {@link FreeLookConfig}, independent of whether
 * this integration — or Mod Menu at all — is present; this class is purely
 * a discoverability convenience.
 */
public class ElytraFreeLookModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return FreeLookConfigScreen::createScreen;
    }
}
