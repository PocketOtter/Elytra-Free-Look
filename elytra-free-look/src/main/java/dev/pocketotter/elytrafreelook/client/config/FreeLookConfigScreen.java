package dev.pocketotter.elytrafreelook.client.config;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.FloatSliderControllerBuilder;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.pocketotter.elytrafreelook.client.config.FreeLookConfig.ActivationMode;
import dev.pocketotter.elytrafreelook.client.config.FreeLookConfig.MidTransitionKeyPressBehavior;
import dev.pocketotter.elytrafreelook.client.config.FreeLookConfig.ReturnStyle;
import dev.pocketotter.elytrafreelook.client.config.FreeLookConfig.ReturnTarget;

/**
 * Builds the YACL screen for {@link FreeLookConfig}.
 *
 * <p>This is the one place a future keybind (in addition to the Mod Menu
 * integration in {@link ElytraFreeLookModMenuIntegration}) should call
 * into: {@link #createScreen(Screen)} has no Mod Menu dependency at all,
 * so {@code Minecraft.getInstance().setScreen(FreeLookConfigScreen.createScreen(previousScreen))}
 * would work equally well from a key press as it does from Mod Menu's
 * config button.
 *
 * <p><b>Conditional visibility.</b> {@code TransitionDuration},
 * {@code MidTransitionKeyPressBehavior}, and {@code ReturnTarget} only
 * make sense when {@code ReturnStyle} is {@code SMOOTH};
 * {@code CameraAggression}/{@code BodyAggression} additionally only make
 * sense when {@code ReturnTarget} is {@code CUSTOM_BLEND}. Every affected
 * option is built as a local variable first, then wired to a listener via
 * {@code Option#addListener} — which YACL fires every time the user
 * changes a value in the screen (before Save is clicked) — reading the
 * live in-screen selection via {@code Option#pendingValue()} and calling
 * {@code Option#setAvailable(...)} on whichever options depend on it,
 * updating the screen immediately.
 */
public final class FreeLookConfigScreen {

    private FreeLookConfigScreen() {
    }

    public static Screen createScreen(Screen parent) {
        return YetAnotherConfigLib.createBuilder()
                .title(Component.literal("Elytra Free Look"))
                .category(ConfigCategory.createBuilder()
                        .name(Component.literal("Elytra Free Look"))
                        .group(activationGroup())
                        .group(returnGroup())
                        .build())
                .save(FreeLookConfig::save)
                .build()
                .generateScreen(parent);
    }

    // -----------------------------------------------------------------
    // Activation
    // -----------------------------------------------------------------

    private static OptionGroup activationGroup() {
        return OptionGroup.createBuilder()
                .name(Component.literal("Activation"))
                .description(OptionDescription.of(Component.literal(
                        "The free look key itself is a normal Minecraft keybind, "
                                + "rebindable from Options > Controls > Key Binds under "
                                + "\"Elytra Free Look\" — default: Left Alt. This only "
                                + "controls how that key behaves while gliding."
                )))
                .option(activationModeOption())
                .build();
    }

    private static Option<ActivationMode> activationModeOption() {
        return Option.<ActivationMode>createBuilder()
                .name(Component.literal("Activation Mode"))
                .description(OptionDescription.of(Component.literal(
                        "Hold: free look is active only while the key is held "
                                + "down.\n\n"
                                + "Toggle: press once to enter free look, press again "
                                + "to exit."
                )))
                .binding(
                        FreeLookConfig.DEFAULT_ACTIVATION_MODE,
                        FreeLookConfig::getActivationMode,
                        FreeLookConfig::setActivationMode
                )
                .controller(opt -> EnumControllerBuilder.create(opt)
                        .enumClass(ActivationMode.class)
                        .valueFormatter(FreeLookConfigScreen::formatActivationMode))
                .build();
    }

    // -----------------------------------------------------------------
    // Return (SMOOTH-only options, with CUSTOM_BLEND-only nested inside)
    // -----------------------------------------------------------------

    private static OptionGroup returnGroup() {
        // Built as locals (not chained straight into .option(...)) because
        // the listeners below need to reference these by variable, after
        // they exist.
        Option<ReturnStyle> returnStyle = returnStyleOption();
        Option<Float> transitionDuration = transitionDurationOption();
        Option<MidTransitionKeyPressBehavior> midTransitionKeyPressBehavior = midTransitionKeyPressBehaviorOption();
        Option<ReturnTarget> returnTarget = returnTargetOption();
        Option<Float> cameraAggression = cameraAggressionOption();
        Option<Float> bodyAggression = bodyAggressionOption();

        // ReturnStyle governs whether the other five in this group are
        // relevant at all.
        returnStyle.addListener((option, event) -> {
            boolean smooth = option.pendingValue() == ReturnStyle.SMOOTH;
            transitionDuration.setAvailable(smooth);
            midTransitionKeyPressBehavior.setAvailable(smooth);
            returnTarget.setAvailable(smooth);

            boolean customBlend = smooth && returnTarget.pendingValue() == ReturnTarget.CUSTOM_BLEND;
            cameraAggression.setAvailable(customBlend);
            bodyAggression.setAvailable(customBlend);
        });

        // ReturnTarget additionally governs the two aggression sliders,
        // but only while ReturnStyle is already SMOOTH -- re-check both,
        // since a listener only fires when its own option changes and
        // can't assume the other's current state.
        returnTarget.addListener((option, event) -> {
            boolean smooth = returnStyle.pendingValue() == ReturnStyle.SMOOTH;
            boolean customBlend = option.pendingValue() == ReturnTarget.CUSTOM_BLEND;
            cameraAggression.setAvailable(smooth && customBlend);
            bodyAggression.setAvailable(smooth && customBlend);
        });

        return OptionGroup.createBuilder()
                .name(Component.literal("Return"))
                .description(OptionDescription.of(Component.literal(
                        "What happens to the camera, and to your actual flight "
                                + "direction, when you exit free look."
                )))
                .option(returnStyle)
                .option(transitionDuration)
                .option(midTransitionKeyPressBehavior)
                .option(returnTarget)
                .option(cameraAggression)
                .option(bodyAggression)
                .build();
    }

    private static Option<ReturnStyle> returnStyleOption() {
        return Option.<ReturnStyle>createBuilder()
                .name(Component.literal("Style"))
                .description(OptionDescription.of(Component.literal(
                        "Snap: the camera instantly jumps back.\n\n"
                                + "Smooth: eases back over the duration set below "
                                + "instead — see Return Target for what \"eases back\" "
                                + "actually means."
                )))
                .binding(
                        FreeLookConfig.DEFAULT_RETURN_STYLE,
                        FreeLookConfig::getReturnStyle,
                        FreeLookConfig::setReturnStyle
                )
                .controller(opt -> EnumControllerBuilder.create(opt)
                        .enumClass(ReturnStyle.class)
                        .valueFormatter(FreeLookConfigScreen::formatReturnStyle))
                .build();
    }

    private static Option<Float> transitionDurationOption() {
        return Option.<Float>createBuilder()
                .name(Component.literal("Duration"))
                .description(OptionDescription.of(Component.literal(
                        "How long the Smooth return takes, in seconds. This is the "
                                + "only speed control for every Return Target below — "
                                + "none of them have their own separate timer."
                )))
                .binding(
                        FreeLookConfig.DEFAULT_TRANSITION_DURATION_SECONDS,
                        FreeLookConfig::getTransitionDurationSeconds,
                        FreeLookConfig::setTransitionDurationSeconds
                )
                .controller(opt -> FloatSliderControllerBuilder.create(opt)
                        .range(FreeLookConfig.MIN_TRANSITION_DURATION_SECONDS, FreeLookConfig.MAX_TRANSITION_DURATION_SECONDS)
                        .step(0.05F)
                        .valueFormatter(seconds -> Component.literal(String.format("%.2fs", seconds))))
                .available(FreeLookConfig.getReturnStyle() == ReturnStyle.SMOOTH)
                .build();
    }

    private static Option<MidTransitionKeyPressBehavior> midTransitionKeyPressBehaviorOption() {
        return Option.<MidTransitionKeyPressBehavior>createBuilder()
                .name(Component.literal("Mid-Transition Key Press"))
                .description(OptionDescription.of(Component.literal(
                        "What a fresh free look key press does while a Smooth "
                                + "return is still playing out.\n\n"
                                + "Allow Interrupt: stop at the current camera pose and "
                                + "immediately re-enter free look from there.\n\n"
                                + "Skip to End: instantly finish the return instead of "
                                + "re-entering free look.\n\n"
                                + "Wait Out: do nothing. The key is ignored entirely "
                                + "until the transition finishes on its own."
                )))
                .binding(
                        FreeLookConfig.DEFAULT_MID_TRANSITION_KEY_PRESS_BEHAVIOR,
                        FreeLookConfig::getMidTransitionKeyPressBehavior,
                        FreeLookConfig::setMidTransitionKeyPressBehavior
                )
                .controller(opt -> EnumControllerBuilder.create(opt)
                        .enumClass(MidTransitionKeyPressBehavior.class)
                        .valueFormatter(FreeLookConfigScreen::formatMidTransitionKeyPressBehavior))
                .available(FreeLookConfig.getReturnStyle() == ReturnStyle.SMOOTH)
                .build();
    }

    private static Option<ReturnTarget> returnTargetOption() {
        return Option.<ReturnTarget>createBuilder()
                .name(Component.literal("Return Target"))
                .description(OptionDescription.of(Component.literal(
                        "Camera to Body: your flight direction stays exactly where "
                                + "it was; the camera eases back to face it.\n\n"
                                + "Body Follows Camera: the camera stays exactly where "
                                + "you were looking; your flight direction eases to "
                                + "turn and match it.\n\n"
                                + "Custom Blend: both move, meeting at a weighted point "
                                + "between the two — set with Camera Aggression and "
                                + "Body Aggression below."
                )))
                .binding(
                        FreeLookConfig.DEFAULT_RETURN_TARGET,
                        FreeLookConfig::getReturnTarget,
                        FreeLookConfig::setReturnTarget
                )
                .controller(opt -> EnumControllerBuilder.create(opt)
                        .enumClass(ReturnTarget.class)
                        .valueFormatter(FreeLookConfigScreen::formatReturnTarget))
                .available(FreeLookConfig.getReturnStyle() == ReturnStyle.SMOOTH)
                .build();
    }

    private static Option<Float> cameraAggressionOption() {
        return Option.<Float>createBuilder()
                .name(Component.literal("Camera Aggression"))
                .description(OptionDescription.of(Component.literal(
                        "Only used by Custom Blend. How much the camera's side "
                                + "counts toward the meeting point, relative to Body "
                                + "Aggression below — not a speed. Higher pulls the "
                                + "meeting point closer to your original flight "
                                + "direction."
                )))
                .binding(
                        FreeLookConfig.DEFAULT_CAMERA_AGGRESSION,
                        FreeLookConfig::getCameraAggression,
                        FreeLookConfig::setCameraAggression
                )
                .controller(opt -> FloatSliderControllerBuilder.create(opt)
                        .range(FreeLookConfig.MIN_AGGRESSION, FreeLookConfig.MAX_AGGRESSION)
                        .step(0.05F)
                        .valueFormatter(value -> Component.literal(String.format("%.0f%%", value * 100.0F))))
                .available(
                        FreeLookConfig.getReturnStyle() == ReturnStyle.SMOOTH
                                && FreeLookConfig.getReturnTarget() == ReturnTarget.CUSTOM_BLEND
                )
                .build();
    }

    private static Option<Float> bodyAggressionOption() {
        return Option.<Float>createBuilder()
                .name(Component.literal("Body Aggression"))
                .description(OptionDescription.of(Component.literal(
                        "Only used by Custom Blend. How much the flight direction's "
                                + "side counts toward the meeting point, relative to "
                                + "Camera Aggression above — not a speed. Higher pulls "
                                + "the meeting point closer to where you were looking. "
                                + "If both sliders are 0%, the meeting point is the "
                                + "exact midpoint."
                )))
                .binding(
                        FreeLookConfig.DEFAULT_BODY_AGGRESSION,
                        FreeLookConfig::getBodyAggression,
                        FreeLookConfig::setBodyAggression
                )
                .controller(opt -> FloatSliderControllerBuilder.create(opt)
                        .range(FreeLookConfig.MIN_AGGRESSION, FreeLookConfig.MAX_AGGRESSION)
                        .step(0.05F)
                        .valueFormatter(value -> Component.literal(String.format("%.0f%%", value * 100.0F))))
                .available(
                        FreeLookConfig.getReturnStyle() == ReturnStyle.SMOOTH
                                && FreeLookConfig.getReturnTarget() == ReturnTarget.CUSTOM_BLEND
                )
                .build();
    }

    // -----------------------------------------------------------------
    // Value formatters -- YACL's enum controller falls back to raw enum
    // constant names (e.g. "BODY_FOLLOWS_CAMERA") without one of these.
    // -----------------------------------------------------------------

    private static Component formatActivationMode(ActivationMode mode) {
        return Component.literal(switch (mode) {
            case HOLD -> "Hold";
            case TOGGLE -> "Toggle";
        });
    }

    private static Component formatReturnStyle(ReturnStyle style) {
        return Component.literal(switch (style) {
            case SNAP -> "Snap";
            case SMOOTH -> "Smooth";
        });
    }

    private static Component formatMidTransitionKeyPressBehavior(MidTransitionKeyPressBehavior behavior) {
        return Component.literal(switch (behavior) {
            case ALLOW_INTERRUPT -> "Allow Interrupt";
            case SKIP_TO_END -> "Skip to End";
            case WAIT_OUT -> "Wait Out";
        });
    }

    private static Component formatReturnTarget(ReturnTarget target) {
        return Component.literal(switch (target) {
            case CAMERA_TO_BODY -> "Camera to Body";
            case BODY_FOLLOWS_CAMERA -> "Body Follows Camera";
            case CUSTOM_BLEND -> "Custom Blend";
        });
    }
}
