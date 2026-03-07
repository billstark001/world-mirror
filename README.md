# World Downloader

**Version:** 0.1.0 · **Minecraft:** 1.21.11 · **Loader:** Fabric

A client-side Fabric mod that lets you download the world you are currently playing on a multiplayer server. It silently captures chunk data, entities, and container (chest/barrel/etc.) contents as you explore, and exports everything as a fully loadable singleplayer save.

## Features

- **Chunk capture** — automatically records all received chunk data while you play
- **Entity tracking** — records nearby entities (mobs, item frames, etc.) per chunk
- **Container tracking** — saves the inventory contents of containers you open
- **One-key export** — writes a ready-to-use world save to `downloaded_world/` in your `.minecraft` directory
- **Cache clearing** — reset all captured data without restarting the game

## Keybindings

| Key | Action |
|-----|--------|
| `O` | Export captured world to `downloaded_world/` |
| `L` | Clear all captured chunk, entity, and container data |

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) ≥ 0.18.2 for Minecraft 1.21.11
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Install [LibGUI](https://github.com/CottonMC/LibGui) (required)
4. *(Optional)* Install [ModMenu](https://modrinth.com/mod/modmenu) for the in-game mod list
5. Drop `world-downloader-0.1.0.jar` into your `mods/` folder

## Usage

1. Join a multiplayer server and walk around to load chunks
2. Open any containers you want to preserve
3. Press **O** to export — the world is saved to `<.minecraft>/downloaded_world/`
4. Copy the `downloaded_world/` folder into `.minecraft/saves/`
5. Open the world in singleplayer

Press **L** at any time to clear cached data and start a fresh capture.

## Building

```bash
./gradlew build
```

Output jar: `build/libs/world-downloader-0.1.0.jar`

## Credits

- Fork of the original [World Downloader](https://modrinth.com/mod/world-downloader) by **KuudraLoremaster** (MIT license)
- Uses a modified version of [Querz/NBT](https://github.com/Querz/NBT) (MIT license)

## License

MIT
