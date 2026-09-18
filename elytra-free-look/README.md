# Elytra Free Look

A client-side-only Fabric mod for Minecraft 26.2 that decouples your camera
from your flight direction while gliding with an elytra: hold the free-look
key to look around freely — behind you, down, side to side — while your
character keeps flying dead straight in whatever direction it was already
heading.

## Versions targeted

| Component       | Version         |
|-----------------|-----------------|
| Minecraft       | 26.2            |
| Java            | 25              |
| Fabric Loader   | 0.19.3          |
| Fabric Loom     | 1.17.20         |
| Fabric API      | 0.159.0+26.2    |
| YACL            | 3.9.5+26.2-fabric |
| Mod Menu        | 20.0.1          |
| Gradle          | 9.5.1           |

**Note on Java:** Minecraft 26.2 requires Java 25 at minimum (Mojang raised
this from 21 as part of the 26.1 engine rewrite), so that's what this project
targets, even though Java 21 was the original ask.

**Note on mappings:** starting with Minecraft 26.1, the game ships fully
unobfuscated with Mojang's real names already in place. There is no more
Yarn/Mojmap "deobfuscation" step, so `build.gradle` has no `mappings` line at
all — that's correct and expected, not an oversight. For the same reason,
mod dependencies use plain `implementation`/`compileOnly` instead of the old
`modImplementation`/`modCompileOnly`.

## Project layout

```
elytra-free-look/
├── build.gradle
├── gradle.properties
├── settings.gradle
├── gradle/wrapper/gradle-wrapper.properties
└── src/main/
    ├── java/dev/pocketotter/elytrafreelook/
    │   ├── ElytraFreeLook.java              # mod ID + shared logger
    │   ├── client/
    │   │   ├── ElytraFreeLookClient.java    # ClientModInitializer: registers the keybind, drives the tick loop
    │   │   ├── FreeLookHandler.java         # state machine: gliding? free-look active? locked flight direction
    │   │   ├── FreeLookRotationHolder.java  # interface implemented via mixin on LocalPlayer
    │   │   └── config/
    │   │       ├── FreeLookConfig.java              # persisted settings (YACL ConfigClassHandler + Gson)
    │   │       ├── FreeLookConfigScreen.java         # builds the YACL screen; all options always visible
    │   │       └── ElytraFreeLookModMenuIntegration.java  # "modmenu" entrypoint
    │   └── mixin/
    │       ├── EntityTurnMixin.java         # redirects mouse-look input while free look is active
    │       ├── LocalPlayerMixin.java        # stores free-look angles; camera reads them instead of the real rotation
    │       └── package-info.java
    └── resources/
        ├── fabric.mod.json
        ├── elytrafreelook.client.mixins.json
        └── assets/elytrafreelook/lang/en_us.json
```

## Free look state and keybind (this step)

- **`FreeLookHandler`** is a static state holder (a simple singleton — there's
  only ever one local player, so static fields keep call sites simple) that
  tracks two booleans: `isGliding()` and `isFreeLookActive()`. It's driven
  once per client tick from `ElytraFreeLookClient` and logs a line only when
  either state actually *changes* (gliding start/stop, free look
  activate/deactivate) — not every tick.
- Gliding is detected as `player.isFallFlying() && chestSlot.is(Items.ELYTRA)`
  — checking both covers the rare case of something else granting
  fall-flying without an elytra equipped.
- The keybind defaults to **Left Alt**, is created with Fabric API's
  `KeyMappingHelper` (the current, non-obfuscated-era name — see note
  below), and is fully rebindable from **Options > Controls > Key Binds**
  under the **"Elytra Free Look"** category.
- How the key is interpreted (hold vs. toggle) is a separate, later concern
  — see "Configurable activation mode" below.

**API naming note:** in the old Yarn-mapped world you may be picturing
`KeyBindingHelper` / `KeyBinding` / `MinecraftClient`. Since Minecraft 26.1's
move to official Mojang mappings, the equivalent current names are
`KeyMappingHelper`, `KeyMapping`, and `Minecraft` — all used above. This
isn't a stylistic choice, it's what actually exists in 26.2's API surface.

## Configurable activation mode (this step)

- **`FreeLookConfig`** started this step as a plain in-memory static
  holder — no disk persistence, no config screen — with one setting:
  `ActivationMode.HOLD` (default) or `ActivationMode.TOGGLE`. It's since
  been rebuilt on top of YACL for persistence and a real config screen;
  see "YACL config screen, persistence, and Mod Menu" below. The point
  made at the time still held up: adding both later needed zero changes
  to any of `FreeLookConfig`'s callers.
- `FreeLookHandler.tick()` now takes both `freeLookKeyHeld` (level state)
  and `freeLookKeyJustPressed` (a one-tick edge, from
  `KeyMapping#consumeClick()`), and picks which one it uses based on
  `FreeLookConfig.getActivationMode()`:
  - **HOLD** — unchanged from before: active only while gliding *and* the
    key is physically down.
  - **TOGGLE** — a fresh press while gliding flips `freeLookActive`; the
    key being held or released otherwise does nothing. Landing (gliding
    stops) always forces free look off too, clearing any toggled-on state
    so a fresh glide starts fresh.
- **The locked flight direction is still only captured in `activate()`**,
  which only ever runs on the false→true transition of `freeLookActive` —
  that's unchanged by adding a second mode, since both modes just compute
  a `shouldBeActive` boolean and hand off to the same `activate()`/
  `deactivate()` pair. Nothing about *how* activation/deactivation behaves
  (locking, seeding free-look angles, snapping back) changed at all.
- `ElytraFreeLookClient`'s tick callback now drains
  `FREE_LOOK_KEY.consumeClick()` in a `while` loop every tick regardless of
  mode (not just when TOGGLE is active) — otherwise, switching mode at
  runtime later could replay a backlog of stale presses as unexpected
  toggles the moment TOGGLE mode turns on.

## Return style on exit (this step)

- **`FreeLookConfig`** gains two more settings: `ReturnStyle.SNAP` or
  `ReturnStyle.SMOOTH` (default `SMOOTH`), and `transitionDurationSeconds`
  (default `0.35`, clamped to `0.1`–`2.0`). Same as `ActivationMode` at the
  time: plain in-memory static fields (persistence came later — see "YACL
  config screen, persistence, and Mod Menu" below).
- **SNAP** behaves exactly like before this step: `deactivate()` writes the
  locked yaw/pitch straight into the stored free-look angles and free look
  is fully off immediately.
- **SMOOTH** introduces a third state — `FreeLookHandler.isExiting()` —
  distinct from both "fully active" and "fully inactive". `deactivate()`
  captures wherever the free-look angles currently are
  (`exitStartYaw`/`exitStartPitch`) and a start timestamp, then leaves the
  actual easing to `getCameraYaw`/`getCameraPitch`.
- **The interpolation runs once per rendered frame, not once per tick.**
  `LocalPlayerMixin`'s `getViewYRot`/`getViewXRot` hooks call
  `FreeLookHandler.getCameraYaw`/`getCameraPitch` directly, which compute
  elapsed real time via `System.nanoTime()` against the captured start
  timestamp, apply classic smoothstep easing
  (`t*t*(3-2t)`, not linear), and finish the transition — flipping
  `isExiting()` back to `false` — the moment elapsed time reaches
  `transitionDurationSeconds`. Since frames render far more often than the
  20 Hz tick loop, this is meaningfully smoother than driving the same math
  from `tick()` would be. A duplicate, cheap safety-net check also runs
  once per tick in case a frame is somehow never rendered while ticks
  keep going, so the transition can never get permanently stuck.
- **Yaw wraps the short way around.** Minecraft's yaw is an unbounded
  float that can drift past ±180° or go negative after spinning around, so
  a plain linear interpolation could occasionally spin the long way around
  the circle. `lerpAngleDegrees` normalizes the delta to the shortest
  direction first (verified against several hand-picked test cases,
  including multi-revolution deltas, before trusting it — see below).
- **What a fresh key press does mid-transition is now configurable** —
  see "Mid-transition key press and gradual follow" below. (Landing
  mid-transition still doesn't interrupt it either way; that remains
  unsolved, same as before this step.)
- **The locked flight direction never moves during the transition.**
  `EntityTurnMixin` now gates on `isControllingCamera()` (active OR
  exiting) instead of `isFreeLookActive()` alone, so mouse input keeps
  getting cancelled — and therefore the real `getYRot()`/`getXRot()` stay
  frozen — for the entire SMOOTH return, not just while fully active. Mouse
  deltas received while merely exiting are simply dropped rather than
  applied anywhere, since the displayed angle during that window is driven
  by time, not the mouse.

**On `Mth.rotlerp`:** Minecraft does have a built-in angle-lerp helper by
that name, but it's marked deprecated in every version I could confirm its
signature for, with parameter semantics I couldn't pin down confidently for
26.2. Rather than guess, `lerpAngleDegrees` is a small, explicit,
independently-verified implementation instead.

## Mid-transition key press and gradual follow

> **Terminology in this section is superseded.** `FORCE_COMPLETE` was
> later split into `SKIP_TO_END` and a new `WAIT_OUT`, and
> `FlightDirectionDuringTransition`/`STAY_LOCKED`/`GRADUALLY_FOLLOW`/`CHASE`
> were replaced by `ReturnTarget`/`CAMERA_TO_BODY`/`BODY_FOLLOWS_CAMERA`/
> `CUSTOM_BLEND`. See "One blend mechanic" below for the current design and
> exactly why the old three-separate-modes approach was replaced rather
> than patched again. This section is kept as a historical record of how
> the thinking evolved, not as documentation of current behavior.

Two `FreeLookConfig` settings, both **only relevant when `ReturnStyle` is
`SMOOTH`** — with `SNAP` there's no transition for either of them to act
on:

- **`MidTransitionKeyPressBehavior`** — `ALLOW_INTERRUPT` (default) or
  `FORCE_COMPLETE`. Governs what a fresh free-look key press does while
  `isExiting()` is true.
- **`FlightDirectionDuringTransition`** — `STAY_LOCKED` (default),
  `GRADUALLY_FOLLOW`, or `CHASE`. Three different features with three
  different end states — see "Adding CHASE" below for the third one and
  why it exists alongside the other two rather than replacing either.

**`ALLOW_INTERRUPT`** cancels the transition exactly where the camera
currently is and re-enters full free look from there — no jump, no
waiting. `resumeFromTransition()` computes the camera's current position
(in `STAY_LOCKED`/`CHASE`, wherever the eased return currently is — both
share the same camera motion; in `GRADUALLY_FOLLOW`, the fixed point the
camera's been sitting at the whole time — see below), seeds the free-look
angle there, and re-locks to the player's *current* real rotation, not the
old lock — under `GRADUALLY_FOLLOW`/`CHASE` the real rotation has already
turned partway by the time this fires.

**`FORCE_COMPLETE`** instantly finishes the transition — snapping the
free-look angle (and, under `GRADUALLY_FOLLOW`/`CHASE`, the real rotation
too) to wherever the transition's own endpoint is — rather than resuming
free look. It's a "skip the animation" input, not a re-activation one.
Notably, this is the *one* place `CHASE` snaps the body exactly onto the
locked direction — see "Adding CHASE" for why that doesn't contradict
CHASE's whole point of not forcing an exact final match.

**`GRADUALLY_FOLLOW` and `STAY_LOCKED` are two different features with two
different end states**, not two flavors of one animation — see "Fixing
gradually follow" below for the full story, including a design bug in an
earlier version of this feature that made them behave identically. As
currently implemented: `STAY_LOCKED` keeps the flight direction frozen and
eases the camera back to it; `GRADUALLY_FOLLOW` keeps the camera fixed at
the release-moment look direction and eases the flight direction to catch
up with it instead, always landing exactly there. Both use
`TransitionDuration` as their one and only speed control. `CHASE` sits
between the two — see "Adding CHASE" below.

A few implementation notes worth knowing about:

- **A fresh key press is consumed exactly once, never twice.** `tick()`
  unconditionally returns after delegating to `handleExitingTick()` any
  tick where free look was already exiting when the tick started — so a
  press that triggers `ALLOW_INTERRUPT` (which sets `freeLookActive =
  true` directly) can never *also* fall through to the plain
  HOLD/TOGGLE activation switch in that same tick. Without this, TOGGLE
  mode in particular would immediately undo its own interrupt: TOGGLE
  treats any fresh press as "flip the state", so the very press that just
  resumed free look would flip it straight back off again if it were
  allowed to reach that switch too.
- **The key press is checked *before* advancing anything, each tick.** If
  a press and the transition's natural completion landed on the exact
  same tick, checking the timer first could finish the transition and
  leave the press matching neither "still exiting" nor "freshly
  inactive" — silently swallowed. Two separate progress readers exist for
  this reason: `currentEasedProgress()` (pure, used to evaluate a
  same-tick key press against progress as of the *start* of the tick) and
  `advanceExitTransition()` (has the side effect of finishing the
  transition, used everywhere else).
- **`GRADUALLY_FOLLOW`'s (and now `CHASE`'s) real-rotation writes stay on
  the tick loop, not the render-frame path.** Neither mixin needed any
  change for this. `LocalPlayerMixin`/`EntityTurnMixin` still only ever
  read `isControllingCamera()`/`getCameraYaw()`/`getCameraPitch()`. Only
  the *flight direction* turn (which mutates the actual player entity, and
  therefore elytra velocity) runs from `tick()`, at the tick rate
  movement/physics naturally operates at — see "Fixing gradually follow"
  for why this also means there's no cross-path race to reconcile for
  `GRADUALLY_FOLLOW`, and "Adding CHASE" for why the race that *does*
  structurally exist for `CHASE` (since its camera motion is
  render-driven, same as `STAY_LOCKED`) turns out to be harmless anyway.
- **`setYRot`/`setXRot` don't touch `yRotO`/`xRotO`.** The follow/chase
  turn only updates the "current" rotation Entity setters, not the paired
  "old" values render-interpolation uses for smoothing *other* viewers'
  view of this player's model between ticks. This doesn't affect the
  first-person camera (which is fully overridden by `LocalPlayerMixin`
  regardless) or elytra velocity (calculated from the current-tick
  rotation, not the interpolated one) — the only possible effect is a
  faint one-tick discontinuity in how smoothly *other clients* or a
  third-person self-view see the model rotate during `GRADUALLY_FOLLOW`
  or `CHASE`. Flagged here rather than silently glossed over; fixing it
  properly would need an `Entity` accessor mixin for those fields, which
  felt like more scope than this step called for.
- **Mouse input does nothing for the entire exit transition, in any of
  the three sub-modes — on purpose.** This is a considered simplification,
  documented explicitly in `EntityTurnMixin`, not an oversight: letting
  the mouse keep moving mid-transition raises real design questions
  (retargeting an easing camera? moving a follow/chase target live,
  turning "fly toward where you looked" into "keep flying toward wherever
  you're currently looking"? interaction with `ALLOW_INTERRUPT`?) that are
  a separate feature to design properly, not a one-line fix.

## Camera/flight-direction decoupling (this step)

Two mixins now do the actual work, both registered in
`elytrafreelook.client.mixins.json`:

- **`EntityTurnMixin`** targets `Entity#turn(double, double)` — the single
  method `MouseHandler#turnPlayer()` calls every frame to apply accumulated
  mouse movement, for any entity that can be turned. While free look is
  active and `this` is the client's own player, it cancels the vanilla
  method entirely and instead applies the same yaw/pitch-per-mouse-unit
  math to a separate pair of "free-look" angles. `getYRot()`/`getXRot()`
  are never called in that path, so they simply stop receiving input —
  there's nothing to separately "lock."
- **`LocalPlayerMixin`** adds the storage for those free-look angles (via
  the `FreeLookRotationHolder` interface) and injects into
  `LocalPlayer#getViewYRot`/`getViewXRot`, returning the free-look angle
  instead of the real one while active.

The reason this pair of methods is the right hook: `Camera#setup` (called
every frame by the renderer, in **both** first- and third-person) gets its
rotation by calling `entity.getViewYRot(partialTick)` and `getViewXRot(...)`
— third person just additionally offsets the camera position backwards
along that same direction. `LocalPlayer` already overrides both methods to
return live, uninterpolated rotation specifically for camera purposes,
separate from whatever drives movement. I confirmed this call chain against
a real snippet of vanilla source quoted in a Mojang bug report
([MC-258579](https://bugs-legacy.mojang.com/browse/MC-258579)) rather than
assuming it — worth a sanity-check build on your end too, since this
environment has no network access to compile against the actual Minecraft
26.2 jar.

**Net effect:** `FreeLookHandler.activate()` captures `getYRot()`/`getXRot()`
as the locked flight direction and seeds the free-look angles with that same
value (no camera jump on activation). While active, mouse movement only
ever changes the free-look angles the camera reads — the entity's real
rotation, which still drives elytra velocity and the rendered model's
facing, is frozen by omission. On exit, with the default `SMOOTH` return
style, the camera eases from wherever it was looking back to that same
locked direction over ~0.35s; with `SNAP`, it jumps there instantly. Either
way, since the real rotation never moved in the first place, the camera
ends up lined back up with the direction you're actually flying.

**Testing this step:** equip an elytra, jump off somewhere high, start
gliding, and hold Left Alt (or press it once if you've switched to
`ActivationMode.TOGGLE`). You should be able to look freely around —
behind you, down at the ground, side to side — while the character keeps
flying dead straight. Releasing Alt (default `ReturnStyle.SMOOTH`) should
ease the view back to straight-ahead over about a third of a second rather
than snapping instantly; setting `FreeLookConfig.setReturnStyle(SNAP)`
should make it instant instead.

To test this step's additions specifically: release Alt to start a SMOOTH
return, then press it again partway through. With the default
`ALLOW_INTERRUPT`, free look should resume immediately from wherever the
camera was mid-ease, with no jump. Switch to `SKIP_TO_END` (this setting
was called `FORCE_COMPLETE` at the time this paragraph was originally
written) and the same press should instead instantly finish the return
and leave free look off. Separately, set
`ReturnTarget.BODY_FOLLOWS_CAMERA` (originally
`FlightDirectionDuringTransition.GRADUALLY_FOLLOW` — see "One blend
mechanic" for the rename), look sharply to one side, and release: the
camera should hold still exactly where you were looking while the
character visibly banks/turns to face that same direction over
`TransitionDuration` — a real, visible turn, not a wobble that resolves
back to the original heading. (An earlier version of this had a bug where
it wobbled and then reverted to `CAMERA_TO_BODY`'s ending regardless —
see "Fixing gradually follow" for the full story.)

None of this has been compiled against a real Minecraft jar in this
sandbox, so treat it as a strong first draft to build and verify rather
than guaranteed-correct code.

The mod is declared `"environment": "client"` in `fabric.mod.json`, so
Fabric Loader will refuse to load it on a dedicated server — there's no
server-side code path at all.

## Getting started

1. Install **JDK 25**.
2. Generate the Gradle wrapper scripts (not included here since the wrapper
   `.jar` is a binary file):
   ```
   gradle wrapper --gradle-version 9.5.1
   ```
   (Or just open the project in IntelliJ IDEA with the Minecraft Development
   plugin — it will offer to do this for you.)
3. Import the project into IntelliJ IDEA (recommended) or VS Code.
4. Run the client:
   ```
   ./gradlew runClient
   ```
   You should see `Elytra Free Look initialized (client)` in the log once
   the game reaches the main menu. Since YACL and Mod Menu are both
   declared as plain `implementation` dependencies, they're automatically
   present in this dev run too — no separate jars to drop anywhere — so
   the config button should show up in Mod Menu's mods list immediately.

The project ships under the package `dev.pocketotter.elytrafreelook`
(`maven_group=dev.pocketotter` in `gradle.properties`). If you rename it
to your own namespace, **three separate things all have to agree**, or
Fabric Loader fails at runtime with a `ClassNotFoundException` for
whichever entrypoint it tries first — this happened once already during
this project's development, from exactly this kind of partial rename:

1. The actual Java source: both the directory structure under
   `src/main/java/` *and* the `package ...;` declaration at the top of
   every file, kept in sync with each other.
2. Every fully-qualified class name in `fabric.mod.json`'s
   `"entrypoints"` block (currently two: `client` and `modmenu`).
3. The `"package"` field in `elytrafreelook.client.mixins.json`.

Changing `maven_group` in `gradle.properties` alone does **not** rename
any of the above — it only affects the Gradle project's own Maven
publishing coordinates. An IDE's "rename package" refactor (IntelliJ:
right-click the package, Refactor > Rename) handles #1 correctly and is
the safest way to do this, but it won't touch #2 or #3 — those two are
plain text in JSON files and need updating by hand regardless of which
method you use for #1.

## YACL config screen, persistence, and Mod Menu (this step)

`FreeLookConfig` is no longer a bare in-memory holder — it's now backed by
YACL's `ConfigClassHandler`, which both drives a full settings screen and
persists to `config/elytrafreelook.json` (plain JSON, via YACL's built-in
Gson serialization) in the standard per-instance Fabric config folder.

**The public API didn't change.** Every getter/setter
(`FreeLookConfig.getActivationMode()`, `setReturnStyle(...)`, etc.) has the
exact same name and signature as before. `FreeLookHandler` and the mixins
needed zero changes beyond one new import — confirmed by grepping every
`FreeLookConfig.*(` call site against the class's actual method list
before considering this done. Internally, each getter/setter now reads or
writes through `HANDLER.instance()` fresh every call (never a cached
reference), so a config reload is picked up everywhere immediately with no
extra plumbing.

**New files, all under `client/config/`:**
- `FreeLookConfig.java` — the `@SerialEntry`-annotated data YACL
  serializes, doubling as the same static facade every earlier step
  already used. Deliberately has no private constructor (unlike this
  mod's other static-holder classes) — YACL needs to construct instances
  of it via reflection, including a separate "defaults" instance for
  reset-to-default comparisons in the UI, which needs an accessible no-arg
  constructor.
- `FreeLookConfigScreen.java` — builds the actual YACL screen: two option
  groups ("Activation", "Return"), all seven settings with names
  and descriptions, and conditional visibility (see below).
  `createScreen(Screen parent)` has no Mod Menu dependency at all — it's
  the one place a future keybind should call into, exactly as directly as
  Mod Menu's own config button does.
- `ElytraFreeLookModMenuIntegration.java` — the `"modmenu"` entrypoint.
  Mod Menu is a soft dependency (`"suggests"`, not `"depends"`, in
  fabric.mod.json) — this class only ever loads if Mod Menu is present
  looking for it, so its absence can't crash anything.

**Conditional visibility works correctly** via `Option#addListener` +
`Option#pendingValue()` + `Option#setAvailable(...)`: (using this step's
current option names — see "One blend mechanic" above for the rename)
`Duration`, `Mid-Transition Key Press`, and `Return Target` are hidden
unless `Style` is `Smooth`; `Camera Aggression`/`Body Aggression` are
additionally hidden unless `Return Target` is `Custom Blend`. This is
worth being explicit about because an earlier draft of this section — and
the code itself, for a while — claimed the opposite: that this mechanism
"was attempted and didn't work," based on a debugging session that
concluded the dependent options never actually grayed out. That debugging
session was chasing a different, unrelated problem which turned out not
to be a real bug either — and once directly re-tested in isolation, the
graying-out worked exactly as designed the whole time. The
conditional-visibility code had been removed on the strength of that
mistaken conclusion; it's been restored. The concrete lesson: a debugging
session's conclusions about *unrelated* code shouldn't be trusted just
because they were reached while genuinely, carefully investigating
something else — each claim needed its own direct check, and this one
didn't get one at the time.

**Two dependency changes in fabric.mod.json:** YACL is now a hard
`"depends"` (the whole config system is built on it — no fallback config
UI without it, same as virtually every other YACL-based mod), while Mod
Menu stays a soft `"suggests"` (nice for discoverability, never required).

**On confidence:** YACL and Mod Menu are both actively-developed community
libraries, not part of Fabric's own toolchain, so I can't lean on the same
kind of first-party documentation the rest of this project has used. What's
held up under actual testing so far: the exact Maven coordinates and
repositories for both libraries at 26.2; `implementation` (not
`modImplementation`) being correct for both; the `ConfigClassHandler` +
`@SerialEntry` + `GsonConfigSerializerBuilder` persistence pattern
(confirmed via a real crash stack trace, and config values do load/save/
persist correctly); the `dev.isxander.yacl3.api` /
`dev.isxander.yacl3.api.controller` package split for the screen-building
classes (the screen renders, options bind and save correctly); and now,
directly confirmed rather than assumed, the conditional-visibility
mechanism itself. If the build complains about a specific method not
existing on a YACL builder class elsewhere in this file, that's still the
likeliest remaining risk — the fix should be a small local signature
adjustment, not a structural one.

**Companion mods needed at runtime (not compile time):** Mod Menu 20.0.1
itself depends on Text Placeholder API to function — that's Mod Menu's
own dependency, declared in its own fabric.mod.json, not something this
project's build.gradle needs to reference. Anyone wanting the Mod Menu
button will need Text Placeholder API installed alongside Mod Menu; this
mod itself doesn't call it directly.

**Testing this step:** launch once, then check
`config/elytrafreelook.json` was created (with default values) after
`FreeLookConfig.load()` runs on init. Open the config screen (via Mod
Menu's mods list, once Mod Menu, Fabric API, and Text Placeholder API are
all installed) and confirm every setting is visible, editable, and has a
readable name/description, and that the settings gated behind
`Style == Smooth` gray out correctly when it's set to `Snap`. (The exact
option names/values referenced here have since changed — see "One blend
mechanic" above for what to actually test today.) Change a few values,
click Save, relaunch, and confirm they persisted.

## Fixing gradually follow (this step)

> **Terminology in this section is superseded** — see the note at the top
> of "Mid-transition key press and gradual follow" above, and "One blend
> mechanic" below for the current design.

GRADUALLY_FOLLOW was broken since it was introduced: it converged on
exactly the same ending as STAY_LOCKED, just with a visible wobble along
the way. This was caught and diagnosed from actual in-game testing, not
found by re-reading the code — worth stating plainly rather than
glossing over.

**What was wrong.** The old design treated STAY_LOCKED and GRADUALLY_FOLLOW
as one animation with an optional extra: the camera *always* eased from
the release-moment look direction back to the locked flight heading, every
frame, regardless of mode; GRADUALLY_FOLLOW additionally nudged the body
toward wherever the camera currently was; and when the camera's timer ran
out, the body got snapped straight onto the locked heading — overwriting
whatever progress the nudge had made. Three things going on where there
should have been one: the camera was chasing the old heading, the body was
chasing the camera (a target actively moving away from where you'd looked
and toward where the body already was), and the finishing step then
deleted the body's progress entirely. The two modes were never actually
different features — GRADUALLY_FOLLOW was STAY_LOCKED with an unnecessary
detour.

There was a second-order bug too: because the camera's transition could
finish itself from a render-frame call at any point between two ticks,
but the body-snap needed the tick loop's `Player` reference, a
`followFinalizeOwed` flag existed purely to bridge that gap — meaning if
the render path finished the transition mid-nudge, the camera would
already show the real (still catching up) rotation for up to one frame
before the tick loop's snap caught up and moved it again. Two visible
pops instead of one clean stop.

**The fix treats them as genuinely different features with different end
states**, matching the table in `FreeLookConfig.FlightDirectionDuringTransition`'s
javadoc: under STAY_LOCKED, both the camera and the body end up at the old
flight heading; under GRADUALLY_FOLLOW, both end up at wherever you were
looking instead. Concretely:

- **STAY_LOCKED** (unchanged): camera eases `exitStart → locked` every
  rendered frame; body stays at `locked`, never touched.
- **GRADUALLY_FOLLOW** (rewritten): camera holds fixed at `exitStart` for
  the *entire* transition — it never eases anywhere, full stop — while the
  body eases `locked → exitStart` once per tick, using the same
  time-based, smoothstep-eased progress math the camera transition uses,
  just with the roles reversed.

Both modes reuse the exact same two captured values —
`lockedYaw`/`lockedPitch` (the real rotation at activation) and
`exitStartYaw`/`exitStartPitch` (the free-look angle at the moment of
release) — no new fields were needed, only which one is the start and
which is the target, and which one (camera or body) actually animates.

This also eliminates the second-order race entirely, by construction:
since the render path's `getCameraYaw`/`getCameraPitch` just return the
constant `exitStart` throughout a GRADUALLY_FOLLOW transition — never
calling `advanceExitTransition()` — only `tick()` can ever decide that
transition is over. There's no other path that could finish it first.
`followFinalizeOwed` is gone; there's nothing left for it to bridge.

**`FollowAggression` was removed at this point, not kept alongside
`TransitionDuration`.** With the body now animating over a fixed duration
(the same one STAY_LOCKED's camera uses) rather than chasing an
aggression-based per-tick catch-up rate, keeping a second speed knob would
have meant two time constants governing what's conceptually one "how long
does this transition take" question — exactly the kind of unclear-
ownership setup that made the original bug hard to reason about.
`TransitionDuration` became the single control for both sub-modes. (It
came back in a later step, scoped to a new third mode this same aggression
-based mechanic turned out to genuinely suit — see "Adding CHASE" below.
The reasoning above still held for the two modes that existed at the
time; it just turned out not to be the whole story once a third,
differently-shaped mode entered the picture.)

**Mouse input during the exit transition is unchanged in behavior — still
dropped entirely — but is now an explicit, documented decision rather
than an implicit side effect of the old gating logic.** See
`EntityTurnMixin`'s class javadoc for the reasoning, and "Next steps"
below for the alternative (letting the mouse keep moving the target live)
that was deliberately left for a future step rather than folded in here.

## Adding CHASE, and what "gradually follow" turned into (this step)

> **`CHASE` itself is superseded** — replaced by `CUSTOM_BLEND`, which
> keeps the "both move" idea but with a provably precise end pose instead
> of `CHASE`'s organic, aggression-based approach. See "One blend
> mechanic" below.

The "gradually follow bug" from the previous step turned out not to be a
bug in the sense it was diagnosed as. The *actual* problem was narrower
than the whole mechanic: an automatic snap-to-`locked` at the end of every
transition, silently overwriting whatever progress the body-chases-camera
motion had made. The mechanic itself — camera returning while the body
chases it — was never the issue, and turned out to be a genuinely liked
feel once seen working correctly (before the fix that removed it
entirely, from testing an older build). Rather than bring back the old,
buggy code wholesale, that mechanic was rebuilt as a *third* mode living
alongside the other two, with the one destructive line removed rather
than the whole feature.

**`FlightDirectionDuringTransition` now has three values:**

- **`STAY_LOCKED`** — unchanged.
- **`GRADUALLY_FOLLOW`** — unchanged from the previous step: camera fixed
  at the release-moment look direction, body eases cleanly to meet it,
  always landing exactly there.
- **`CHASE`** (new) — the camera eases `exitStart → locked` on *exactly*
  the same schedule `STAY_LOCKED` uses, while the body simultaneously
  chases wherever the camera currently is, once per tick, at a rate set
  by `FollowAggression` (which is back, now scoped to this one mode only).
  When the camera's transition ends, the body simply **stops being
  nudged** — no snap, no correction, no matter how far off it still is.
  That's the actual fix: not changing the mechanic, removing the one line
  that overwrote its result.

**Why `CHASE`'s render-vs-tick race is harmless, unlike what
`GRADUALLY_FOLLOW`'s used to be.** `CHASE` shares `STAY_LOCKED`'s camera
motion, which means — same as `STAY_LOCKED` — the render path can finish
the transition (flip `isExiting()` to `false`) at any point between two
ticks, before `tick()` gets a chance to run `advanceChaseBodyTurn()` one
more time. Under the old `GRADUALLY_FOLLOW` design this mattered a lot,
because a corrective snap needed to happen at exactly the right moment. It
doesn't matter here, because `CHASE` performs no corrective action at
completion at all — `advanceChaseBodyTurn()` either gets to nudge the body
one more tick's worth before the flag flips, or it doesn't, and either way
there's nothing left to reconcile afterward. Removing the "correct thing
at the end" requirement didn't just fix the bug — it also happened to
dissolve the timing hazard around it.

**`FORCE_COMPLETE` is the one place `CHASE` *does* snap the body exactly
onto `locked`**, and this doesn't quietly reintroduce the original bug:
that bug was an *automatic*, unconditional snap firing every single time
a transition ended normally. `FORCE_COMPLETE` only fires from an explicit
player key press specifically asking to skip the animation — a
deliberate override, not a passive default silently overwriting organic
progress. The distinction is about *whose decision* the snap is, not
about whether a snap ever happens.

**`easingCameraYawAt`/`easingCameraPitchAt`** factor out the one motion
`STAY_LOCKED` and `CHASE` now share (`getCameraYaw`/`getCameraPitch`,
`resumeFromTransition`, and `advanceChaseBodyTurn` all call into these
rather than duplicating the lerp), which makes "these two modes' cameras
behave identically" a structural fact in the code, not just something the
docs claim.

## One blend mechanic, not three separate modes (this step)

The previous two steps each patched `ReturnTarget`'s predecessor by adding
another mode alongside the existing ones (`GRADUALLY_FOLLOW` next to
`STAY_LOCKED`, then `CHASE` next to both). This step replaces that whole
approach: `ReturnTarget` is now one mechanic with a single dial, not three
independently-coded behaviors that happen to look similar.

**The core idea:** every transition computes one `blendTarget` — a point
between `lockedYaw`/`lockedPitch` (the flight direction's heading when
free look activated) and `exitStartYaw`/`exitStartPitch` (wherever the
camera was looking when it deactivated) — at the moment the transition
starts, and never recomputes it. The camera always eases
`exitStart → blendTarget`; the flight direction always eases
`locked → blendTarget`, when it has any distance to cover at all. Both use
the exact same `TransitionDuration` and the same smoothstep curve, so they
don't just end up *close* to the same point — they're evaluating the
identical function at the identical `t`, so they land on it exactly.

**`ReturnTarget` has three values, each just a different way of picking
the blend ratio:**

- **`CAMERA_TO_BODY`** (default) — ratio fixed at `1.0`, so
  `blendTarget == locked`. The flight direction's "ease toward
  `blendTarget`" computation becomes `locked → locked`: genuinely zero
  distance, not a value that happens not to change after the fact.
  `bodyNeedsMotion` is explicitly `false` in this case, so the flight
  direction is never written to at all — the old `STAY_LOCKED` guarantee,
  now literal rather than incidental.
- **`BODY_FOLLOWS_CAMERA`** — ratio fixed at `0.0`, so
  `blendTarget == exitStart`. The camera's computation becomes
  `exitStart → exitStart`, a constant — it never needed a special-cased
  "fixed camera" branch the way the old `GRADUALLY_FOLLOW` did; the
  general formula just naturally produces a constant here.
- **`CUSTOM_BLEND`** — ratio computed from two new sliders,
  `CameraAggression` and `BodyAggression`, as
  `camera / (camera + body)`, falling back to `0.5` if both are `0` (an
  explicit divide-by-zero guard, not an arbitrary special case — see
  `FreeLookHandler.computeBlendRatio()`). These are relative *weights*
  determining *where* the blend point sits, not speeds — `Duration`
  remains the only time control, same as the other two.

**This replaces `CHASE`'s exponential-decay "gets close but never
provably exact" approach with something that's exact by construction.**
`CHASE` worked by having the flight direction chase the camera's
*current* position every tick at a configurable catch-up rate, with no
guaranteed final gap. `CUSTOM_BLEND` instead computes one fixed
`blendTarget` up front and eases both sides toward it deterministically —
the two aggression sliders decide *where* that point is, and the answer
is always precise, never approximate.

**Why the render-vs-tick race is harmless here, structurally, not just in
this particular case.** The camera's transition can finish itself (flip
`isExiting()` to `false`) from any rendered frame, at any point between
two ticks — before `tick()` gets a chance to run the flight direction's
own update one more time. This is fine because that update
(`advanceBodyBlend`) performs no corrective action of its own; it simply
stops being called once `isExiting()` is `false`. What *does* need
reconciling is the final precise snap onto `blendTarget` (since a real
duration budget, unlike `CHASE`'s open-ended catch-up, is meant to
provably finish exactly on target): `bodyFinalizePending` bridges that gap
the same way it did for `GRADUALLY_FOLLOW` two steps ago — set whenever
the flight direction has any motion to perform, checked unconditionally
at the top of every `tick()` call regardless of what actually finished the
transition, and cleared once the flight direction has been snapped
exactly onto `blendTarget`.

**`MidTransitionKeyPressBehavior` gained a third value, and one existing
value got renamed to avoid a naming collision:** `FORCE_COMPLETE` (the
"instantly finish" behavior) is now called `SKIP_TO_END` — freeing up
"force complete" as a name, since it was ambiguous between two genuinely
different ideas across this project's history. The new third value,
`WAIT_OUT`, is the *other* thing "force complete" used to informally mean:
the key press is entirely ignored — not interrupted, not skipped, just
inert — and the transition continues exactly as if it had never happened,
right up until it finishes on its own. In code, `WAIT_OUT` is the simplest
of the three: `ALLOW_INTERRUPT` and `SKIP_TO_END` both call a method and
`return` immediately; `WAIT_OUT` just `break`s out of the switch and falls
through to the same per-tick advancement that runs on any tick with no key
press at all.

**Config keys changed, and old saved values for these two settings won't
carry over.** `flightDirectionDuringTransition` (values `STAY_LOCKED` /
`GRADUALLY_FOLLOW` / `CHASE`) is now `returnTarget` (values
`CAMERA_TO_BODY` / `BODY_FOLLOWS_CAMERA` / `CUSTOM_BLEND`), and
`midTransitionKeyPressBehavior`'s old `FORCE_COMPLETE` value no longer
exists. An existing `config/elytrafreelook.json` will have Gson silently
ignore the unrecognized old keys/values and fall back to this version's
defaults for just those two settings — nothing crashes, but it's worth
knowing about rather than being surprised by.

**Config screen changes:** the group is now named "Return" (was "Return
Behavior"), `FlightDirectionDuringTransition`'s option is now "Return
Target" with its own full three-paragraph description (blank line between
each mode, per explicit request, using literal `\n\n` inside the
`Component.literal` string — Minecraft's option-description rendering
already handles multi-line text, confirmed by every multi-line description
in this screen rendering correctly in earlier testing), and
`CameraAggression`/`BodyAggression` are two separate sliders, both hidden
unless `ReturnTarget` is `CUSTOM_BLEND` (nested one level inside the
existing `ReturnStyle == SMOOTH` visibility gate, same pattern as before).

## Next steps

- **Landing mid-transition still doesn't interrupt it.** All three
  `MidTransitionKeyPressBehavior` values handle a fresh *key press*
  mid-transition, but gliding stopping (e.g. touching down) doesn't
  currently cut a SMOOTH return short either way — it just keeps easing
  to completion regardless of whether you're still airborne. Worth
  deciding whether that should change.
- **A real keybind for opening the config screen.** `FreeLookConfigScreen.createScreen(Screen)`
  was deliberately kept Mod-Menu-agnostic for exactly this — see "YACL
  config screen, persistence, and Mod Menu" above.
- **Mouse input during the exit transition is currently just dropped.**
  Explicitly documented as a deliberate choice (see `EntityTurnMixin`),
  rather than an unremarked gap — but letting the mouse keep moving the
  blend target live (or retarget an easing camera) is a real alternative
  worth designing properly, including how it'd interact with
  `ALLOW_INTERRUPT`.
- **`yRotO`/`xRotO` sync when the flight direction moves.** Flagged in
  detail in earlier sections — `advanceBodyBlend` only updates the
  "current" rotation, not the paired "old" values other clients' render
  interpolation uses. Would need an `Entity` accessor mixin to fix
  properly.
- **Third-person camera collision while free-looking.** Not touched in this
  step — worth checking that looking straight back doesn't clip the camera
  into the player model in a way that feels wrong.
- **Riding-while-gliding edge cases** (e.g. an elytra-wearing passenger) —
  `EntityTurnMixin`'s player-identity check only ever matches the client's
  own player, so this should already be inert for anything else, but it
  hasn't been explicitly tested.
