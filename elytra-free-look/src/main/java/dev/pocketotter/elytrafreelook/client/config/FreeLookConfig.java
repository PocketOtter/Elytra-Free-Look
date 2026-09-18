package dev.pocketotter.elytrafreelook.client.config;

import dev.isxander.yacl3.config.v2.api.ConfigClassHandler;
import dev.isxander.yacl3.config.v2.api.SerialEntry;
import dev.isxander.yacl3.config.v2.api.serializer.GsonConfigSerializerBuilder;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

import dev.pocketotter.elytrafreelook.ElytraFreeLook;

/**
 * Configuration for Elytra Free Look, persisted to a JSON file in the
 * standard Fabric config folder ({@code config/elytrafreelook.json}) via
 * YACL's built-in config-class handling.
 *
 * <p>This class plays two roles at once, deliberately: it's both the
 * {@code @SerialEntry}-annotated data YACL reads/writes/serializes
 * directly, <em>and</em> the same static getter/setter facade earlier
 * steps of this mod were already written against
 * ({@code FreeLookHandler} and the {@code mixin} package call
 * {@code FreeLookConfig.getActivationMode()} and so on). Every getter
 * below reads through {@link #HANDLER}{@code .instance()} fresh each
 * call — never a cached reference — so a config reload
 * ({@link #load()}) is picked up immediately everywhere, with zero
 * changes needed to any of that existing call-site code.
 *
 * <p>{@link FreeLookConfigScreen} builds the actual YACL screen from
 * these fields; this class only owns the data and its persistence.
 *
 * <p>{@link MidTransitionKeyPressBehavior} and {@link ReturnTarget} only
 * ever matter when {@link #getReturnStyle()} is {@code SMOOTH} — with
 * {@code SNAP} there's no transition for either of them to act on.
 * {@link #getCameraAggression()}/{@link #getBodyAggression()}
 * additionally only matter when {@link #getReturnTarget()} is
 * {@code CUSTOM_BLEND}. They're otherwise independent of each other and
 * of {@link ActivationMode}: any combination is valid. The config screen
 * reflects this with conditional visibility; see
 * {@link FreeLookConfigScreen}.
 *
 * <p><b>Note on the JSON key rename:</b> this class used to have
 * {@code flightDirectionDuringTransition} (values {@code STAY_LOCKED} /
 * {@code GRADUALLY_FOLLOW} / {@code CHASE}) where {@link #getReturnTarget()}
 * now lives, and {@code midTransitionKeyPressBehavior} used to only have
 * {@code ALLOW_INTERRUPT} / {@code FORCE_COMPLETE}. An existing
 * {@code config/elytrafreelook.json} from before this rename will simply
 * have those old keys/values ignored by Gson and fall back to this
 * class's current defaults — nothing crashes, but old choices for those
 * two settings specifically won't carry over.
 */
public final class FreeLookConfig {

    /** How the free look key is interpreted. */
    public enum ActivationMode {
        /** Free look is active only while the key is physically held down. */
        HOLD,
        /** Press once to enter free look, press again to exit. */
        TOGGLE
    }

    /** How the camera returns to the locked flight direction on exit. */
    public enum ReturnStyle {
        /** The camera instantly jumps back to the locked flight direction. */
        SNAP,
        /**
         * Eases back over {@link #getTransitionDurationSeconds()} instead
         * of jumping — see {@link ReturnTarget} for what "eases back"
         * means exactly, since it isn't always just the camera that moves.
         */
        SMOOTH
    }

    /**
     * What a fresh free-look key press does while a SMOOTH return
     * transition is playing out. Irrelevant when {@link #getReturnStyle()}
     * is {@code SNAP}, since there's no transition to press into.
     */
    public enum MidTransitionKeyPressBehavior {
        /**
         * Cancels the transition right where the camera currently is and
         * immediately re-enters free look from there — no jump, and no
         * waiting for the transition to finish first.
         */
        ALLOW_INTERRUPT,
        /**
         * Instantly finishes the transition (as if it had reached its full
         * duration) instead of resuming free look. A "skip the animation"
         * input, not a re-activation one.
         */
        SKIP_TO_END,
        /**
         * Does nothing at all. The press is ignored outright — not
         * interrupted, not skipped — and the transition continues exactly
         * as if the key had never been touched, right up until it finishes
         * on its own.
         */
        WAIT_OUT
    }

    /**
     * Where the camera and the flight direction end up when a SMOOTH
     * return finishes. Irrelevant when {@link #getReturnStyle()} is
     * {@code SNAP} — there's no transition for this to act on.
     *
     * <p>All three values are really one mechanic with a single dial: a
     * blend ratio between "the flight direction's old heading" and
     * "wherever the camera was looking when free look ended". {@code
     * CAMERA_TO_BODY} and {@code BODY_FOLLOWS_CAMERA} are just that ratio
     * pinned to its two extremes; {@code CUSTOM_BLEND} exposes the ratio
     * directly via {@link #getCameraAggression()}/
     * {@link #getBodyAggression()}. See {@code FreeLookHandler}'s class
     * javadoc for exactly how the ratio is computed and used.
     */
    public enum ReturnTarget {
        /**
         * "I was only looking — don't turn." The flight direction stays
         * exactly where it was; the camera is the only thing that moves,
         * easing back to face it.
         */
        CAMERA_TO_BODY,
        /**
         * "I looked that way — fly that way now." The camera stays
         * exactly where it was looking; the flight direction is the only
         * thing that moves, easing to turn and match it.
         */
        BODY_FOLLOWS_CAMERA,
        /**
         * Both move, meeting at a weighted point between "where you
         * looked" and "where you were flying" — set with
         * {@link #getCameraAggression()} and {@link #getBodyAggression()}.
         * Always lands exactly on that computed point; the two sliders
         * control <em>where</em> the point is, not how precisely it's
         * reached.
         */
        CUSTOM_BLEND
    }

    // -----------------------------------------------------------------
    // Defaults and clamp ranges. Exposed as constants (rather than
    // inlined) so FreeLookConfigScreen's YACL bindings and sliders can
    // reference the exact same values as the field initializers below,
    // instead of two hand-copied numbers slowly drifting apart.
    // -----------------------------------------------------------------

    public static final ActivationMode DEFAULT_ACTIVATION_MODE = ActivationMode.HOLD;
    public static final ReturnStyle DEFAULT_RETURN_STYLE = ReturnStyle.SMOOTH;
    public static final float DEFAULT_TRANSITION_DURATION_SECONDS = 0.35F;
    public static final MidTransitionKeyPressBehavior DEFAULT_MID_TRANSITION_KEY_PRESS_BEHAVIOR =
            MidTransitionKeyPressBehavior.ALLOW_INTERRUPT;
    public static final ReturnTarget DEFAULT_RETURN_TARGET = ReturnTarget.CAMERA_TO_BODY;
    public static final float DEFAULT_CAMERA_AGGRESSION = 0.5F;
    public static final float DEFAULT_BODY_AGGRESSION = 0.5F;

    /** Lower bound accepted by {@link #setTransitionDurationSeconds(float)}. */
    public static final float MIN_TRANSITION_DURATION_SECONDS = 0.1F;

    /** Upper bound accepted by {@link #setTransitionDurationSeconds(float)}. */
    public static final float MAX_TRANSITION_DURATION_SECONDS = 2.0F;

    /**
     * Bounds accepted by {@link #setCameraAggression(float)}/
     * {@link #setBodyAggression(float)}. These are relative weights, not
     * absolute distances — see {@code FreeLookHandler} for exactly how
     * they combine into a blend ratio — so 0.0 (this weight contributes
     * nothing) is a meaningful, explicitly supported value, unlike the
     * old speed-based "Follow Aggression" this replaced.
     */
    public static final float MIN_AGGRESSION = 0.0F;
    public static final float MAX_AGGRESSION = 1.0F;

    /**
     * YACL's config-class handler: owns loading, saving, and holding the
     * single live instance of this class. {@code config/elytrafreelook.json}
     * lives in the standard per-instance Fabric config folder, resolved via
     * {@link FabricLoader}, not hardcoded — matches every other mod's
     * config location.
     */
    public static final ConfigClassHandler<FreeLookConfig> HANDLER =
            ConfigClassHandler.createBuilder(FreeLookConfig.class)
                    .id(Identifier.fromNamespaceAndPath(ElytraFreeLook.MOD_ID, "config"))
                    .serializer(config -> GsonConfigSerializerBuilder.create(config)
                            .setPath(FabricLoader.getInstance().getConfigDir().resolve(ElytraFreeLook.MOD_ID + ".json"))
                            .build())
                    .build();

    // -----------------------------------------------------------------
    // The actual persisted fields. Note there's deliberately no private
    // constructor here (unlike this mod's other static-holder classes) —
    // YACL constructs instances of this class via reflection (once for
    // the live instance, once more for an internal "defaults" instance
    // used for reset-to-default comparisons), which needs an accessible
    // no-arg constructor. Java's implicit default one is exactly that.
    // -----------------------------------------------------------------

    @SerialEntry
    private ActivationMode activationMode = DEFAULT_ACTIVATION_MODE;

    @SerialEntry
    private ReturnStyle returnStyle = DEFAULT_RETURN_STYLE;

    @SerialEntry
    private float transitionDurationSeconds = DEFAULT_TRANSITION_DURATION_SECONDS;

    @SerialEntry
    private MidTransitionKeyPressBehavior midTransitionKeyPressBehavior = DEFAULT_MID_TRANSITION_KEY_PRESS_BEHAVIOR;

    @SerialEntry
    private ReturnTarget returnTarget = DEFAULT_RETURN_TARGET;

    @SerialEntry
    private float cameraAggression = DEFAULT_CAMERA_AGGRESSION;

    @SerialEntry
    private float bodyAggression = DEFAULT_BODY_AGGRESSION;

    /** Loads persisted settings from disk into the live instance, if a config file exists. */
    public static void load() {
        HANDLER.load();
    }

    /** Writes the live instance's current settings to disk. Called by the YACL screen's Save button. */
    public static void save() {
        HANDLER.save();
    }

    public static ActivationMode getActivationMode() {
        return HANDLER.instance().activationMode;
    }

    public static void setActivationMode(ActivationMode mode) {
        HANDLER.instance().activationMode = mode;
    }

    public static ReturnStyle getReturnStyle() {
        return HANDLER.instance().returnStyle;
    }

    public static void setReturnStyle(ReturnStyle style) {
        HANDLER.instance().returnStyle = style;
    }

    /** How long, in seconds, a SMOOTH exit transition takes — the only time control, regardless of {@link ReturnTarget}. */
    public static float getTransitionDurationSeconds() {
        return HANDLER.instance().transitionDurationSeconds;
    }

    /**
     * Sets the SMOOTH transition duration, clamped to the reasonable range
     * [{@value #MIN_TRANSITION_DURATION_SECONDS},
     * {@value #MAX_TRANSITION_DURATION_SECONDS}] seconds so the config
     * screen's slider (or a malformed hand-edited JSON file) can't push
     * this into a value that feels broken (instant or absurdly slow).
     */
    public static void setTransitionDurationSeconds(float seconds) {
        HANDLER.instance().transitionDurationSeconds = Math.max(
                MIN_TRANSITION_DURATION_SECONDS,
                Math.min(MAX_TRANSITION_DURATION_SECONDS, seconds)
        );
    }

    public static MidTransitionKeyPressBehavior getMidTransitionKeyPressBehavior() {
        return HANDLER.instance().midTransitionKeyPressBehavior;
    }

    public static void setMidTransitionKeyPressBehavior(MidTransitionKeyPressBehavior behavior) {
        HANDLER.instance().midTransitionKeyPressBehavior = behavior;
    }

    public static ReturnTarget getReturnTarget() {
        return HANDLER.instance().returnTarget;
    }

    public static void setReturnTarget(ReturnTarget target) {
        HANDLER.instance().returnTarget = target;
    }

    /** Relative weight for how far the camera contributes to {@code CUSTOM_BLEND}'s meeting point. Only used when {@link #getReturnTarget()} is {@code CUSTOM_BLEND}. */
    public static float getCameraAggression() {
        return HANDLER.instance().cameraAggression;
    }

    /**
     * Sets the camera's relative weight for {@code CUSTOM_BLEND}, clamped
     * to [{@value #MIN_AGGRESSION}, {@value #MAX_AGGRESSION}]. See
     * {@code FreeLookHandler} for exactly how this combines with
     * {@link #getBodyAggression()} into a blend ratio — in short,
     * {@code camera / (camera + body)}, falling back to 0.5 if both are 0.
     */
    public static void setCameraAggression(float aggression) {
        HANDLER.instance().cameraAggression = Math.max(MIN_AGGRESSION, Math.min(MAX_AGGRESSION, aggression));
    }

    /** Relative weight for how far the flight direction contributes to {@code CUSTOM_BLEND}'s meeting point. Only used when {@link #getReturnTarget()} is {@code CUSTOM_BLEND}. */
    public static float getBodyAggression() {
        return HANDLER.instance().bodyAggression;
    }

    /** Body counterpart to {@link #setCameraAggression(float)}; same clamp range and combination rule. */
    public static void setBodyAggression(float aggression) {
        HANDLER.instance().bodyAggression = Math.max(MIN_AGGRESSION, Math.min(MAX_AGGRESSION, aggression));
    }
}
