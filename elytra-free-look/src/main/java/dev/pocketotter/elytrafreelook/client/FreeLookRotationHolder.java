package dev.pocketotter.elytrafreelook.client;

/**
 * Duck interface implemented via mixin on {@code LocalPlayer}
 * ({@code dev.pocketotter.elytrafreelook.mixin.LocalPlayerMixin}) to store the
 * free-look camera's own yaw/pitch, separately from the entity's real
 * {@code getYRot()}/{@code getXRot()} — which keep driving elytra velocity
 * and model orientation, completely untouched, while free look is active.
 *
 * <p>Method names are prefixed with {@code elytraFreeLook$}, Fabric's usual
 * convention for mixin-injected interface methods, so they can never
 * collide with a real vanilla or other-mod method of the same name.
 */
public interface FreeLookRotationHolder {

    float elytraFreeLook$getFreeLookYaw();

    float elytraFreeLook$getFreeLookPitch();

    void elytraFreeLook$setFreeLookYaw(float yaw);

    void elytraFreeLook$setFreeLookPitch(float pitch);
}
