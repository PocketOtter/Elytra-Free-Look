package dev.pocketotter.elytrafreelook.client;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import dev.pocketotter.elytrafreelook.client.config.FreeLookConfig;
import dev.pocketotter.elytrafreelook.client.config.FreeLookConfig.ReturnTarget;

import static dev.pocketotter.elytrafreelook.ElytraFreeLook.LOGGER;

/**
 * Tracks Elytra Free Look's state: whether the local player is currently
 * gliding with an elytra equipped, whether free look is currently active,
 * the "locked" flight direction free look captures on activation, and the
 * SMOOTH return transition that can play out after deactivation — including
 * a fresh key press interrupting, skipping, or being ignored by it.
 *
 * <p>This is a static state holder (a simple singleton) rather than an
 * instance you construct — there's only ever one local player to track, so
 * static fields keep call sites simple ({@code FreeLookHandler.isGliding()}
 * from anywhere on the client).
 *
 * <p><b>One blend mechanic, not three separate modes.</b> Every
 * {@link ReturnTarget} reduces to the same thing: a single ratio between
 * {@link #lockedYaw}/{@link #lockedPitch} (the real rotation at
 * activation) and {@link #exitStartYaw}/{@link #exitStartPitch} (the
 * free-look angle at the moment of release), computed once at
 * {@link #deactivate} into a fixed {@link #blendTargetYaw}/
 * {@link #blendTargetPitch}. The camera always eases
 * {@code exitStart → blendTarget} every rendered frame (see
 * {@link #getCameraYaw}/{@link #getCameraPitch}); the flight direction
 * always eases {@code locked → blendTarget} once per tick, when it needs
 * to move at all (see {@link #advanceBodyBlend}). Both use the exact same
 * duration and easing curve, so they provably land on the same point —
 * not approximately, exactly, since they're both just evaluating the same
 * function at the same {@code t}.
 * <ul>
 *   <li>{@code CAMERA_TO_BODY} sets the ratio to 1.0, making
 *   {@code blendTarget == locked} — the flight direction's "eases toward
 *   blendTarget" computation becomes {@code locked → locked}, i.e.
 *   genuinely zero motion, not just a value that happens not to change.
 *   {@link #bodyNeedsMotion} is set {@code false} in exactly this case, so
 *   the flight direction isn't touched at all, matching the "don't turn"
 *   promise literally rather than incidentally.</li>
 *   <li>{@code BODY_FOLLOWS_CAMERA} sets the ratio to 0.0, making
 *   {@code blendTarget == exitStart} — the camera's "eases toward
 *   blendTarget" computation becomes {@code exitStart → exitStart}, a
 *   constant, so the camera genuinely never moves.</li>
 *   <li>{@code CUSTOM_BLEND} computes the ratio from
 *   {@link FreeLookConfig#getCameraAggression()}/
 *   {@link FreeLookConfig#getBodyAggression()} as
 *   {@code camera / (camera + body)}, falling back to 0.5 if both are 0 —
 *   see {@link #computeBlendRatio()}.</li>
 * </ul>
 *
 * <p><b>Why the render-vs-tick race that exists here is harmless.</b> The
 * camera's transition can finish itself (flip {@link #isExiting()} to
 * {@code false}) from any rendered frame, which can happen at any point
 * between two ticks — before {@code tick()} gets a chance to run
 * {@link #advanceBodyBlend} one more time. This doesn't need reconciling
 * mid-transition, because {@code advanceBodyBlend} performs no corrective
 * action of its own; it just stops being called once {@link #isExiting()}
 * is {@code false} (see {@code tick()}'s {@code if (exiting)} gate). What
 * *does* need reconciling is the very last, precise snap onto
 * {@code blendTarget}: since {@link FreeLookConfig#getTransitionDurationSeconds()}
 * is a real time budget (unlike the old exponential-decay "CHASE" design
 * this replaced), the flight direction is meant to land exactly on
 * {@code blendTarget}, not just "very close". {@link #bodyFinalizePending}
 * bridges exactly that: set whenever the flight direction needs motion at
 * all, and checked unconditionally at the top of every {@code tick()} call
 * — regardless of whether the transition finished on *this* tick, an
 * earlier tick, or a render frame in between — so the exact snap always
 * happens within one tick (~50ms) of the camera finishing, however that
 * finish was triggered.
 *
 * <p><b>Mouse input during the whole exit transition is deliberately
 * ignored</b> — this is an intentional simplification, not an oversight:
 * see {@code EntityTurnMixin}. Letting the mouse keep moving mid-transition
 * raises real design questions (retargeting an easing camera? moving the
 * blend target live? interaction with {@code ALLOW_INTERRUPT}?) left as a
 * possible future step rather than folded in here.
 *
 * <p>Every knob — hold vs. toggle, snap vs. smooth, what a key press does
 * mid-transition, the return target and its two aggression weights — is
 * read from {@link FreeLookConfig} on demand, so flipping any of them
 * takes effect immediately without this class needing to be told about it
 * separately. The exception is {@link #blendTargetYaw}/
 * {@link #blendTargetPitch}/{@link #bodyNeedsMotion}, all computed once
 * when a SMOOTH transition starts (see {@link #deactivate}) rather than
 * re-read live throughout it: deliberate, so a config change mid-flight
 * can't leave one continuous transition half-governed by old rules and
 * half by new ones.
 *
 * <p><b>Three mutually exclusive states.</b> At any moment, exactly one of
 * these is true: fully inactive, {@link #isFreeLookActive() fully active},
 * or {@link #isExiting() exiting} (mid SMOOTH return). {@code tick()}
 * handles the exiting case entirely separately (see
 * {@link #handleExitingTick}) and always returns before reaching the
 * normal activation logic — so a fresh key press mid-transition is
 * consumed exactly once, by exactly one of "resume", "skip to end", or
 * "wait out", never by the plain activation switch as well in the same
 * tick.
 *
 * <p>The actual camera/rotation decoupling lives in the {@code mixin}
 * package:
 * <ul>
 *   <li>{@code EntityTurnMixin} redirects mouse-look input into the
 *   free-look angles (via {@link FreeLookRotationHolder}) instead of the
 *   entity's real rotation whenever {@link #isControllingCamera()} is
 *   true — active or exiting — and otherwise just cancels the mouse input
 *   outright while exiting, per the note above.</li>
 *   <li>{@code LocalPlayerMixin} makes the camera render using
 *   {@link #getCameraYaw}/{@link #getCameraPitch} instead of the real
 *   rotation, called once per rendered frame — which is what lets the
 *   camera's return interpolate smoothly regardless of tick rate.</li>
 * </ul>
 * Neither mixin needed to change for any {@link ReturnTarget}: writes to
 * the entity's real rotation only ever happen from {@code tick()} (see
 * {@link #advanceBodyBlend}) — deliberately never from the render-frame
 * path, which stays purely about camera/timing math, in step with how
 * movement/physics normally advance.
 */
public final class FreeLookHandler {

    private static boolean gliding = false;
    private static boolean freeLookActive = false;

    /**
     * The player's real yaw/pitch at the moment free look activated —
     * where the flight direction's blend motion starts from. Only
     * meaningful while free look is (or just was) active or exiting.
     */
    private static float lockedYaw = 0.0F;
    private static float lockedPitch = 0.0F;

    /** True while a SMOOTH return transition is actively playing out. */
    private static boolean exiting = false;

    /**
     * The free-look angle at the instant the SMOOTH return began — i.e.
     * wherever the camera was looking the moment the key was
     * released/toggled off. This is where the camera's blend motion
     * starts from.
     */
    private static float exitStartYaw = 0.0F;
    private static float exitStartPitch = 0.0F;

    /** {@link System#nanoTime()} at the instant the SMOOTH return began. */
    private static long exitStartTimeNanos = 0L;

    /**
     * The single point both the camera and the flight direction ease
     * toward for the current transition — computed once at
     * {@link #deactivate} from {@link #computeBlendRatio()} and never
     * recomputed live, so a mid-transition config change can't retarget
     * an already-running return.
     */
    private static float blendTargetYaw = 0.0F;
    private static float blendTargetPitch = 0.0F;

    /**
     * Whether the flight direction has any distance at all to cover this
     * transition — {@code false} only for a pure {@code CAMERA_TO_BODY}
     * ratio of 1.0, where {@link #blendTargetYaw}/{@link #blendTargetPitch}
     * exactly equal {@link #lockedYaw}/{@link #lockedPitch} and the flight
     * direction's own motion is genuinely zero. When {@code false}, the
     * flight direction is never written to at all during the transition —
     * not computed-and-found-unchanged, simply skipped.
     */
    private static boolean bodyNeedsMotion = false;

    /**
     * True from the moment a transition needing body motion starts, until
     * the tick loop has snapped the flight direction exactly onto
     * {@link #blendTargetYaw}/{@link #blendTargetPitch} after it ends. See
     * the class javadoc's note on why this bridge is needed and how it's
     * used from {@code tick()}.
     */
    private static boolean bodyFinalizePending = false;

    private FreeLookHandler() {
    }

    /**
     * Whether the local player was gliding with an elytra as of the last
     * client tick.
     */
    public static boolean isGliding() {
        return gliding;
    }

    /**
     * Whether free look is <em>fully</em> active — mouse movement is
     * currently driving the free-look angles. This is {@code false} during
     * the SMOOTH return transition; see {@link #isExiting()} and
     * {@link #isControllingCamera()} for that case.
     *
     * <p>What makes this {@code true} depends on
     * {@link FreeLookConfig#getActivationMode()} — see {@link #tick} for
     * exactly how each mode decides.
     */
    public static boolean isFreeLookActive() {
        return freeLookActive;
    }

    /**
     * Whether a SMOOTH return transition is currently playing out. While
     * this is {@code true}, the free look key only does what
     * {@link FreeLookConfig#getMidTransitionKeyPressBehavior()} says (see
     * {@link #handleExitingTick}), and mouse input does nothing at all
     * (see the class javadoc).
     */
    public static boolean isExiting() {
        return exiting;
    }

    /**
     * Whether the camera should be reading free-look state <em>at all</em>
     * right now — fully active, or still mid-transition. Both mixins gate
     * on this (not {@link #isFreeLookActive()} alone) so the real rotation
     * stays untouched by mouse input for the entire transition, not just
     * while free look is fully active.
     */
    public static boolean isControllingCamera() {
        return freeLookActive || exiting;
    }

    /**
     * The yaw the camera should render this frame.
     *
     * <p>Only meaningful (and only ever called by {@code LocalPlayerMixin})
     * while {@link #isControllingCamera()} is true. While fully active,
     * this is just {@code liveFreeLookYaw} handed back unchanged. While
     * exiting, this eases {@link #exitStartYaw} toward
     * {@link #blendTargetYaw} based on real elapsed time — called once per
     * rendered frame, which is what makes this smooth regardless of tick
     * rate. This call finishes the transition (flips {@link #exiting} to
     * {@code false}) once it completes. Under a pure
     * {@code BODY_FOLLOWS_CAMERA} ratio, {@code blendTargetYaw ==
     * exitStartYaw}, so this naturally returns a constant without any
     * special-casing.
     *
     * @param liveFreeLookYaw the free-look yaw the mouse has been driving,
     *                        via {@link FreeLookRotationHolder}
     */
    public static float getCameraYaw(float liveFreeLookYaw) {
        if (!exiting) {
            return liveFreeLookYaw;
        }

        return lerpAngleDegrees(exitStartYaw, blendTargetYaw, advanceExitTransition());
    }

    /** Pitch counterpart to {@link #getCameraYaw}. */
    public static float getCameraPitch(float liveFreeLookPitch) {
        if (!exiting) {
            return liveFreeLookPitch;
        }

        return lerp(exitStartPitch, blendTargetPitch, advanceExitTransition());
    }

    /**
     * Recomputes state for the current tick. Safe to call every client
     * tick even when {@code player} is {@code null} (e.g. on the title
     * screen or while disconnected).
     *
     * @param player          the local player, or {@code null} if not in a world
     * @param freeLookKeyHeld whether the free look key is currently held down
     *                        (used in {@link FreeLookConfig.ActivationMode#HOLD})
     * @param freeLookKeyJustPressed whether the free look key was freshly
     *                        pressed this tick — a one-tick edge, not a
     *                        held state (used in
     *                        {@link FreeLookConfig.ActivationMode#TOGGLE},
     *                        and to detect mid-transition key presses)
     */
    static void tick(Player player, boolean freeLookKeyHeld, boolean freeLookKeyJustPressed) {
        setGliding(isGlidingWithElytra(player));

        // A render-frame call may have already finished the transition
        // (flipped `exiting` to false) before this tick even ran. If so,
        // this is where the exact finishing snap that would otherwise run
        // inside handleExitingTick actually happens — see
        // bodyFinalizePending's javadoc.
        if (bodyFinalizePending && !exiting && player != null) {
            player.setYRot(blendTargetYaw);
            player.setXRot(blendTargetPitch);
            bodyFinalizePending = false;
        }

        if (exiting) {
            handleExitingTick(player, freeLookKeyJustPressed);
            // Always return here: a key press consumed by handleExitingTick
            // (resume or skip-to-end) must not also be evaluated by the
            // plain activation switch below in this same tick — see the
            // class javadoc's note on consuming a press exactly once.
            return;
        }

        boolean shouldBeActive;

        switch (FreeLookConfig.getActivationMode()) {
            case TOGGLE:
                if (!gliding) {
                    // Free look can't be active without gliding, and this
                    // also clears any stale toggle-on state from a
                    // previous glide once you land.
                    shouldBeActive = false;
                } else if (freeLookKeyJustPressed) {
                    // Toggle only flips on a fresh press, never just from
                    // the key still being held down.
                    shouldBeActive = !freeLookActive;
                } else {
                    shouldBeActive = freeLookActive;
                }
                break;

            case HOLD:
            default:
                shouldBeActive = gliding && freeLookKeyHeld;
                break;
        }

        if (shouldBeActive != freeLookActive) {
            if (shouldBeActive) {
                activate(player);
            } else {
                deactivate(player);
            }
        }
    }

    /**
     * Everything that happens on a tick where free look was already
     * {@link #isExiting() exiting} when it started: handling a fresh key
     * press per {@link FreeLookConfig#getMidTransitionKeyPressBehavior()},
     * then — unless that press ended the transition outright — advancing
     * the blend.
     *
     * <p>The key press is checked <em>before</em> advancing anything, on
     * purpose: it's evaluated against the transition's progress as of the
     * start of this tick, so a press during the transition's very last
     * tick is still guaranteed to register as a mid-transition action —
     * rather than possibly racing against the timer completing the
     * transition first within the same tick and the press then falling
     * into neither bucket.
     */
    private static void handleExitingTick(Player player, boolean freeLookKeyJustPressed) {
        if (freeLookKeyJustPressed && player != null) {
            switch (FreeLookConfig.getMidTransitionKeyPressBehavior()) {
                case ALLOW_INTERRUPT:
                    resumeFromTransition(player, currentEasedProgress());
                    return; // exiting is now false; nothing left to do this tick

                case SKIP_TO_END:
                    skipToEnd(player);
                    return; // exiting is now false; nothing left to do this tick

                case WAIT_OUT:
                default:
                    // Deliberately falls through to the normal advancement
                    // below instead of returning here -- the press is
                    // completely ignored, not "handled by doing nothing
                    // and stopping". The transition proceeds exactly as if
                    // no key had been pressed this tick at all.
                    break;
            }
        }

        if (player == null) {
            advanceExitTransition(); // safety net; nothing to move anyway
            return;
        }

        // Ticks are also always a safety net for finishing the transition,
        // independent of whichever frame rate rendering happens to be
        // running at. Its return value is discarded here on purpose: the
        // flight direction's own motion below reads progress via
        // currentEasedProgress() (a pure read) instead, so it never risks
        // double-triggering this call's finishing side effect within the
        // same tick.
        advanceExitTransition();

        if (bodyNeedsMotion) {
            advanceBodyBlend(player);
        }
    }

    /**
     * Captures the entity's current real rotation as the locked flight
     * direction, then seeds the free-look camera angles with that same
     * direction so the view doesn't jump the instant free look turns on.
     */
    private static void activate(Player player) {
        freeLookActive = true;

        lockedYaw = player.getYRot();
        lockedPitch = player.getXRot();

        applyFreeLookAngles(player, lockedYaw, lockedPitch);

        LOGGER.info("Free look activated (flight direction locked at yaw={}, pitch={})", lockedYaw, lockedPitch);
    }

    /**
     * Either snaps straight back to the locked flight direction (SNAP), or
     * starts a SMOOTH transition: computes the blend target both the
     * camera and (if needed) the flight direction will ease toward — see
     * the class javadoc for the full mechanic.
     */
    private static void deactivate(Player player) {
        freeLookActive = false;

        if (FreeLookConfig.getReturnStyle() == FreeLookConfig.ReturnStyle.SNAP) {
            applyFreeLookAngles(player, lockedYaw, lockedPitch);
            LOGGER.info("Free look deactivated (snapped to flight direction)");
            return;
        }

        // SMOOTH: capture wherever the free-look angle currently is --
        // this is where the camera's own blend motion starts from.
        if (player instanceof FreeLookRotationHolder rotation) {
            exitStartYaw = rotation.elytraFreeLook$getFreeLookYaw();
            exitStartPitch = rotation.elytraFreeLook$getFreeLookPitch();
        } else {
            // Shouldn't happen for a real LocalPlayer, but fall back to
            // starting the transition already "at" the locked direction
            // rather than from stale/zeroed angles.
            exitStartYaw = lockedYaw;
            exitStartPitch = lockedPitch;
        }

        exiting = true;
        exitStartTimeNanos = System.nanoTime();

        // Computed once, here, rather than re-read live every tick of the
        // transition — see the class javadoc on why.
        float ratio = computeBlendRatio();
        blendTargetYaw = lerpAngleDegrees(exitStartYaw, lockedYaw, ratio);
        blendTargetPitch = lerp(exitStartPitch, lockedPitch, ratio);
        bodyNeedsMotion = ratio < 1.0F;
        bodyFinalizePending = bodyNeedsMotion;

        LOGGER.info(
                "Free look deactivated (smooth return starting, {}s, blend ratio={})",
                FreeLookConfig.getTransitionDurationSeconds(),
                ratio
        );
    }

    /**
     * The 0.0 (fully {@code BODY_FOLLOWS_CAMERA}) to 1.0 (fully
     * {@code CAMERA_TO_BODY}) ratio the current transition's blend target
     * is computed from — see the class javadoc for exactly how each
     * {@link ReturnTarget} maps to a ratio.
     */
    private static float computeBlendRatio() {
        switch (FreeLookConfig.getReturnTarget()) {
            case CAMERA_TO_BODY:
                return 1.0F;

            case BODY_FOLLOWS_CAMERA:
                return 0.0F;

            case CUSTOM_BLEND:
            default:
                float cameraAggression = FreeLookConfig.getCameraAggression();
                float bodyAggression = FreeLookConfig.getBodyAggression();
                float total = cameraAggression + bodyAggression;
                // "If both sliders are 0, treat as 50/50" -- avoids a 0/0
                // division rather than being an arbitrary special case.
                return total <= 0.0F ? 0.5F : cameraAggression / total;
        }
    }

    /**
     * {@link FreeLookConfig.MidTransitionKeyPressBehavior#ALLOW_INTERRUPT}:
     * cancels the transition right where the camera currently is and
     * re-enters full free look from there — no jump, and no waiting for
     * the transition to finish first.
     *
     * <p>Re-locks to the player's <em>current</em> real rotation rather
     * than reusing the old {@link #lockedYaw}/{@link #lockedPitch}: if the
     * flight direction has been moving at all this transition, it may
     * have already turned partway, and the new lock should reflect
     * wherever flight direction actually is now, exactly like a fresh
     * {@link #activate} would.
     *
     * @param easedProgress the transition's eased progress (0.0-1.0) at
     *                       the moment of interruption, from
     *                       {@link #currentEasedProgress()} — used to
     *                       compute exactly where the still-easing camera
     *                       currently is, so free look can resume from
     *                       there with no visible jump.
     */
    private static void resumeFromTransition(Player player, float easedProgress) {
        float resumeYaw = lerpAngleDegrees(exitStartYaw, blendTargetYaw, easedProgress);
        float resumePitch = lerp(exitStartPitch, blendTargetPitch, easedProgress);

        exiting = false;
        bodyFinalizePending = false;

        freeLookActive = true;
        lockedYaw = player.getYRot();
        lockedPitch = player.getXRot();

        // Seed the free-look angle to the camera's current position, NOT
        // to the newly captured lock — that's what avoids the jump.
        applyFreeLookAngles(player, resumeYaw, resumePitch);

        LOGGER.info("Free look resumed mid-transition (interrupted at yaw={}, pitch={})", resumeYaw, resumePitch);
    }

    /**
     * {@link FreeLookConfig.MidTransitionKeyPressBehavior#SKIP_TO_END}:
     * instantly finishes the transition instead of waiting out the
     * remaining duration — a "skip the animation" input, not a
     * re-activation one, so free look ends up fully off afterward.
     */
    private static void skipToEnd(Player player) {
        exiting = false;

        applyFreeLookAngles(player, blendTargetYaw, blendTargetPitch);

        if (bodyNeedsMotion) {
            player.setYRot(blendTargetYaw);
            player.setXRot(blendTargetPitch);
        }

        bodyFinalizePending = false;

        LOGGER.info("Free look return skipped to end");
    }

    /**
     * Turns the flight direction from {@link #lockedYaw}/
     * {@link #lockedPitch} toward {@link #blendTargetYaw}/
     * {@link #blendTargetPitch}, using the exact same real-elapsed-time,
     * smoothstep-eased progress the camera's own motion uses — see the
     * class javadoc for why both landing on the same point at the same
     * {@code t} is guaranteed, not coincidental.
     *
     * <p>Only ever called when {@link #bodyNeedsMotion} is {@code true}.
     * Runs from the tick loop deliberately: this is the one thing in the
     * whole return sequence that writes to the actual player entity
     * (which drives elytra velocity), so it stays in step with how
     * movement/physics normally advance, rather than on the render-frame
     * path the camera itself uses.
     */
    private static void advanceBodyBlend(Player player) {
        float t = currentEasedProgress(); // pure read -- see handleExitingTick

        float newYaw = lerpAngleDegrees(lockedYaw, blendTargetYaw, t);
        float newPitch = lerp(lockedPitch, blendTargetPitch, t);

        player.setYRot(newYaw);
        player.setXRot(newPitch);
    }

    /**
     * Computes how far through the current transition we are (0.0-1.0,
     * eased), and finishes the transition the moment it reaches 1.0. Safe
     * to call more than once per frame (e.g. once from the yaw hook and
     * once from the pitch hook), and once more per tick as a safety net —
     * finishing is idempotent.
     *
     * <p>Use {@link #currentEasedProgress()} instead when you need to read
     * the current progress <em>without</em> risking finishing the
     * transition as a side effect — see {@link #handleExitingTick} and
     * {@link #advanceBodyBlend} for why that distinction matters.
     */
    private static float advanceExitTransition() {
        float raw = rawProgress();

        if (raw >= 1.0F && exiting) {
            exiting = false;
            LOGGER.info("Free look return transition finished");
        }

        return smoothstep(raw);
    }

    /**
     * The transition's current eased progress, purely as a read — never
     * finishes the transition even if {@code raw} progress has technically
     * reached 1.0. See {@link #advanceExitTransition()} for the version
     * that does finish it.
     */
    private static float currentEasedProgress() {
        return smoothstep(rawProgress());
    }

    /** Raw (un-eased) 0.0-1.0 progress through the transition, or 1.0 if not exiting at all. */
    private static float rawProgress() {
        if (!exiting) {
            return 1.0F;
        }

        float durationSeconds = Math.max(FreeLookConfig.getTransitionDurationSeconds(), 0.0001F);
        float elapsedSeconds = (System.nanoTime() - exitStartTimeNanos) / 1_000_000_000.0F;
        return Math.min(elapsedSeconds / durationSeconds, 1.0F);
    }

    /** Classic smoothstep easing: eases in and out instead of linear. */
    private static float smoothstep(float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    /** Plain linear interpolation, fine for pitch since it never wraps. */
    private static float lerp(float start, float end, float t) {
        return start + (end - start) * t;
    }

    /**
     * Linearly interpolates an angle in degrees from {@code start} toward
     * {@code end} by fraction {@code t}, going whichever way around the
     * circle is shorter (e.g. 179° to -179° moves by +2°, not -358°).
     * Needed for yaw because Minecraft's yRot is an unbounded float — it
     * can accumulate past +/-180 or wrap negative after spinning around —
     * so a naive {@code lerp} could momentarily spin the camera (or the
     * flight direction) the long way around instead of easing directly to
     * the target.
     */
    private static float lerpAngleDegrees(float start, float end, float t) {
        float delta = end - start;
        // Normalize delta to the range from -180 up to and including 180
        // degrees, without depending on Mth's rotlerp/wrapDegrees, whose
        // exact behavior across versions this project hasn't independently
        // verified.
        delta = ((delta % 360.0F) + 540.0F) % 360.0F - 180.0F;
        return start + delta * t;
    }

    private static void applyFreeLookAngles(Player player, float yaw, float pitch) {
        if (player instanceof FreeLookRotationHolder rotation) {
            rotation.elytraFreeLook$setFreeLookYaw(yaw);
            rotation.elytraFreeLook$setFreeLookPitch(pitch);
        }
    }

    /**
     * True only if the player is both wearing an elytra in the chest slot
     * and currently in the fall-flying (gliding) state. Checking both
     * covers the (rare) case of something else granting fall-flying
     * without an elytra equipped.
     */
    private static boolean isGlidingWithElytra(Player player) {
        if (player == null) {
            return false;
        }

        if (!player.isFallFlying()) {
            return false;
        }

        ItemStack chestSlot = player.getItemBySlot(EquipmentSlot.CHEST);
        return chestSlot.is(Items.ELYTRA);
    }

    private static void setGliding(boolean nowGliding) {
        if (nowGliding == gliding) {
            return;
        }

        gliding = nowGliding;

        if (gliding) {
            LOGGER.info("Elytra gliding started");
        } else {
            LOGGER.info("Elytra gliding stopped");
        }
    }
}
