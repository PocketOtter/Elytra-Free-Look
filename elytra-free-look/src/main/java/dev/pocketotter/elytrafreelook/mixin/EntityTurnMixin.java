package dev.pocketotter.elytrafreelook.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

import dev.pocketotter.elytrafreelook.client.FreeLookHandler;
import dev.pocketotter.elytrafreelook.client.FreeLookRotationHolder;

/**
 * Redirects mouse-look input away from the entity's real rotation while
 * free look is active, and keeps that rotation frozen for the SMOOTH
 * return transition too.
 *
 * <p>{@link Entity#turn} is exactly what {@code MouseHandler#turnPlayer()}
 * calls every frame to apply accumulated mouse movement — the single choke
 * point all look-around mouse input passes through, for every entity that
 * can be turned (the player, a vehicle passenger, etc). Cancelling it for
 * the local player specifically, whenever
 * {@link FreeLookHandler#isControllingCamera()} is true, means
 * {@code getYRot()}/{@code getXRot()} (and therefore elytra velocity and
 * the rendered model's facing) simply never receive any more input — there
 * is nothing left to separately "lock", the values just stay put, for the
 * entire active-plus-exiting window.
 *
 * <p><b>Mouse input is deliberately ignored for the entire exit
 * transition</b> — not applied to anything, in either
 * {@code FlightDirectionDuringTransition} sub-mode. This is a considered
 * simplification, not an oversight: letting the mouse keep moving during
 * the transition raises real questions (does it retarget a still-easing
 * camera in STAY_LOCKED? does it move GRADUALLY_FOLLOW's fixed catch-up
 * point, effectively turning "fly toward where you looked" into "keep
 * flying toward wherever you're currently looking"? how does that interact
 * with {@code ALLOW_INTERRUPT}, which already lets a fresh key press
 * reclaim control?) that are a genuinely separate feature to design, not a
 * one-line fix. Freezing input during the transition sidesteps all of that
 * cleanly, at the cost of a fraction of a second of unresponsive mouse
 * right after releasing the key.
 */
@Mixin(Entity.class)
public class EntityTurnMixin {

    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void elytraFreeLook$redirectWhileFreeLooking(double yaw, double pitch, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();

        // Only the client's own player, and only while free look is
        // controlling the camera at all (active or exiting). Every other
        // case (other entities, riding a vehicle, free look fully off)
        // falls straight through to vanilla's own turn() below, unmodified.
        if (!FreeLookHandler.isControllingCamera() || (Object) this != client.player) {
            return;
        }

        // Cancelled either way: the real getYRot()/getXRot()/yRotO/xRotO
        // stay frozen on the locked flight direction for as long as free
        // look is active OR still returning from it.
        ci.cancel();

        if (!FreeLookHandler.isFreeLookActive()) {
            // Mid-transition: intentionally drop the input rather than
            // apply it anywhere — see the class javadoc for why this is a
            // deliberate choice, not a gap. (Whether the camera is easing
            // by itself, or holding fixed while the body catches up to it,
            // depends on which FlightDirectionDuringTransition sub-mode is
            // active — see FreeLookHandler.getCameraYaw/Pitch — but mouse
            // input is ignored identically either way.)
            return;
        }

        FreeLookRotationHolder rotation = (FreeLookRotationHolder) this;

        // Mirrors the yaw/pitch-per-mouse-unit conversion vanilla's own
        // turn() applies internally, just aimed at the free-look angles
        // instead of the entity's real rotation, with the same +/-90
        // pitch clamp so looking straight up/down still feels normal.
        float pitchDelta = (float) pitch * 0.15F;
        float yawDelta = (float) yaw * 0.15F;

        float newPitch = Mth.clamp(rotation.elytraFreeLook$getFreeLookPitch() + pitchDelta, -90.0F, 90.0F);
        float newYaw = rotation.elytraFreeLook$getFreeLookYaw() + yawDelta;

        rotation.elytraFreeLook$setFreeLookPitch(newPitch);
        rotation.elytraFreeLook$setFreeLookYaw(newYaw);
    }
}
