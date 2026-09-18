/**
 * Home for Elytra Free Look's Mixins, registered in
 * {@code elytrafreelook.client.mixins.json} under {@code "client"}.
 *
 * <ul>
 *   <li>{@link dev.pocketotter.elytrafreelook.mixin.EntityTurnMixin} —
 *   redirects mouse-look input into the free-look angles instead of the
 *   entity's real rotation while free look is active.</li>
 *   <li>{@link dev.pocketotter.elytrafreelook.mixin.LocalPlayerMixin} — makes
 *   the camera render using those free-look angles instead of the real
 *   ones.</li>
 * </ul>
 *
 * Any further mixin classes just need to be dropped in here and listed in
 * the config's {@code "client"} array — no other setup required.
 */
package dev.pocketotter.elytrafreelook.mixin;
