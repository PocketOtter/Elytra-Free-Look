package dev.pocketotter.elytrafreelook.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.player.LocalPlayer;

import dev.pocketotter.elytrafreelook.client.FreeLookHandler;
import dev.pocketotter.elytrafreelook.client.FreeLookRotationHolder;

/**
 * Gives the local player somewhere to store free-look camera angles, and
 * makes the camera actually use them — the live angle while fully active,
 * or whatever {@link FreeLookHandler#getCameraYaw}/{@code getCameraPitch}
 * says while exiting (which, depending on
 * {@code FlightDirectionDuringTransition}, is either an easing return to
 * the locked flight direction, or a fixed point the flight direction is
 * the one catching up to — see that class's javadoc for the full
 * distinction).
 *
 * <p>{@code net.minecraft.client.Camera#setup} orients the camera every
 * frame by calling {@link LocalPlayer#getViewYRot} and {@code getViewXRot}
 * — for both first- <em>and</em> third-person, since third person just
 * offsets the camera position backwards using those same two angles.
 * {@code LocalPlayer} already overrides both methods to return the live,
 * uninterpolated rotation specifically for camera purposes, separate from
 * whatever drives movement — which is exactly the seam free look needs.
 *
 * <p>By overriding only these two "view" getters and never touching
 * {@code getYRot()}/{@code getXRot()} themselves, the camera can roam
 * freely (or hold fixed, or ease home, depending on mode) while elytra
 * velocity and the rendered player model are driven entirely separately —
 * see {@code FreeLookHandler} and {@code EntityTurnMixin}.
 */
@Mixin(LocalPlayer.class)
public class LocalPlayerMixin implements FreeLookRotationHolder {

    @Unique
    private float elytraFreeLook$freeLookYaw;

    @Unique
    private float elytraFreeLook$freeLookPitch;

    @Override
    public float elytraFreeLook$getFreeLookYaw() {
        return this.elytraFreeLook$freeLookYaw;
    }

    @Override
    public float elytraFreeLook$getFreeLookPitch() {
        return this.elytraFreeLook$freeLookPitch;
    }

    @Override
    public void elytraFreeLook$setFreeLookYaw(float yaw) {
        this.elytraFreeLook$freeLookYaw = yaw;
    }

    @Override
    public void elytraFreeLook$setFreeLookPitch(float pitch) {
        this.elytraFreeLook$freeLookPitch = pitch;
    }

    /**
     * Feeds the camera the free-look yaw instead of the real one, whenever
     * {@link FreeLookHandler#isControllingCamera()} is true — fully active,
     * or still exiting (either sub-mode). {@code getYRot()} itself is
     * never called here, so nothing about the entity's actual rotation
     * changes either way.
     *
     * <p>This runs once per rendered frame (not once per tick), which
     * matters specifically for the STAY_LOCKED sub-mode: it's what lets
     * {@link FreeLookHandler#getCameraYaw} ease the camera home smoothly
     * regardless of framerate. Under GRADUALLY_FOLLOW, {@code getCameraYaw}
     * just returns a fixed value here every time — the flight direction is
     * what's animating in that mode, from the tick loop, not this method.
     */
    @Inject(method = "getViewYRot", at = @At("RETURN"), cancellable = true)
    private void elytraFreeLook$useFreeLookYaw(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (FreeLookHandler.isControllingCamera()) {
            cir.setReturnValue(FreeLookHandler.getCameraYaw(this.elytraFreeLook$freeLookYaw));
        }
    }

    /** Pitch counterpart of {@link #elytraFreeLook$useFreeLookYaw}. */
    @Inject(method = "getViewXRot", at = @At("RETURN"), cancellable = true)
    private void elytraFreeLook$useFreeLookPitch(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (FreeLookHandler.isControllingCamera()) {
            cir.setReturnValue(FreeLookHandler.getCameraPitch(this.elytraFreeLook$freeLookPitch));
        }
    }
}
