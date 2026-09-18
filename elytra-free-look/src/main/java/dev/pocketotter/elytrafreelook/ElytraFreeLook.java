package dev.pocketotter.elytrafreelook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared constants for the Elytra Free Look mod.
 *
 * <p>This mod is client-side only and has no {@code ModInitializer}; see
 * {@link dev.pocketotter.elytrafreelook.client.ElytraFreeLookClient} for the
 * actual entrypoint and mod logic.
 */
public final class ElytraFreeLook {

    /** Must match the {@code id} field in fabric.mod.json. */
    public static final String MOD_ID = "elytrafreelook";

    public static final Logger LOGGER = LoggerFactory.getLogger("Elytra Free Look");

    private ElytraFreeLook() {
    }
}
