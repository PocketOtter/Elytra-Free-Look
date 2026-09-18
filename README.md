# Elytra Free Look

Client-side Fabric mod for Minecraft 26.2. Look around while gliding with an elytra without changing the direction you are flying.

Hold or toggle a key, move the camera, and your flight path stays put. When you stop, the camera snaps or eases back based on your config.

## Requirements

| Component           | Version           |
|---------------------|-------------------|
| Minecraft           | 26.2              |
| Java                | 25                |
| Fabric Loader       | 0.19.3+           |
| Fabric API          | 26.2 build        |
| YetAnotherConfigLib | 26.2 Fabric build |
| Mod Menu            | optional          |

Minecraft 26.2 needs Java 25. From 26.1 on, the game ships with Mojang names, so this project has no mappings line in `build.gradle`.

## Install

1. Install Fabric Loader for 26.2.
2. Put Fabric API and YetAnotherConfigLib in `mods/`.
3. Put this mod in `mods/`.
4. Optionally install Mod Menu if you want the config button in the mods list.

Default key: **Left Alt**. Rebind it under Options → Controls → Elytra Free Look.

## Config

Open the screen from Mod Menu.

- Hold or toggle
- Snap or smooth return
- On a smooth return: duration, what another key press does, and whether the camera, the body, or both move
- Custom blend sliders only apply when that return target is selected

Settings are stored in `config/elytrafreelook.json`.

## Build

```bash
gradle wrapper --gradle-version 9.5.1
chmod +x gradlew
./gradlew build
