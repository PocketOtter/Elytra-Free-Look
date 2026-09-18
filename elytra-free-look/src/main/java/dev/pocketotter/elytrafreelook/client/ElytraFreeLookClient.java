package dev.pocketotter.elytrafreelook.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import org.lwjgl.glfw.GLFW;

import dev.pocketotter.elytrafreelook.ElytraFreeLook;
import dev.pocketotter.elytrafreelook.client.config.FreeLookConfig;

import static dev.pocketotter.elytrafreelook.ElytraFreeLook.LOGGER;

/**
 * Client-only entrypoint for Elytra Free Look.
 *
 * <p>Registers the free look keybind and drives {@link FreeLookHandler}
 * from the client tick loop every frame, feeding it both the key's held
 * state and its edge-triggered "just pressed" state — {@link
 * FreeLookHandler#tick} picks whichever one it needs based on the
 * currently configured {@link FreeLookConfig.ActivationMode}. The actual
 * camera/rotation decoupling lives in the {@code mixin} package
 * ({@code EntityTurnMixin}, {@code LocalPlayerMixin}).
 */
public class ElytraFreeLookClient implements ClientModInitializer {

    /** Shown as a group in Options > Controls > Key Binds. */
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(ElytraFreeLook.MOD_ID, "main")
    );

    /**
     * The free look keybind. Fully rebindable in the vanilla Controls
     * screen like any other key mapping; defaults to Left Alt.
     *
     * <p>Uses the raw LWJGL {@code GLFW} constant rather than
     * {@code InputConstants}: {@code InputConstants} only defines named
     * constants for the keys vanilla actually binds by default (WASD,
     * space, etc.) — not a complete mirror of every GLFW key — and Alt
     * isn't one of them. {@code GLFW_KEY_LEFT_ALT} is LWJGL's own stable
     * constant, unaffected by Minecraft's mappings either way.
     *
     * <p>The keybind itself doesn't know or care whether it's being used
     * as a hold or a toggle — {@link FreeLookHandler#tick} decides that
     * based on {@link FreeLookConfig#getActivationMode()}. This class just
     * reports both the raw held state and press events; see
     * {@link #onInitializeClient()}.
     */
    private static final KeyMapping FREE_LOOK_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                    "key.elytrafreelook.free_look",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_LEFT_ALT,
                    CATEGORY
            )
    );

    @Override
    public void onInitializeClient() {
        LOGGER.info("Elytra Free Look initialized (client)");

        // Load persisted settings (config/elytrafreelook.json) into the
        // live config instance before anything reads from it. Safe to
        // call even on a fresh install with no file yet — defaults apply.
        FreeLookConfig.load();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // consumeClick() is edge-triggered (one call per actual press),
            // unlike isDown()'s continuous "is it currently down" state.
            // Draining it every tick regardless of the active mode matters:
            // otherwise, switching from HOLD to TOGGLE later could replay a
            // backlog of stale presses as unexpected toggles.
            boolean justPressed = false;
            while (FREE_LOOK_KEY.consumeClick()) {
                justPressed = true;
            }

            FreeLookHandler.tick(client.player, FREE_LOOK_KEY.isDown(), justPressed);
        });
    }
}
