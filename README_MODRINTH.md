Here is a streamlined, engaging version of your README formatted specifically for a Modrinth project page. I’ve focused on highlighting the player benefits, making the features easy to scan, and ensuring the setup instructions are crystal clear.

***

# World Mirror

Ever wanted to take a piece of your favorite multiplayer server offline? **World Mirror** is a client-side Fabric mod that silently captures the world around you—including terrain, entities, and container contents—and saves it as a fully playable local singleplayer world.

Whether you're backing up a base, archiving a server before a wipe, or saving a minigame map, World Mirror works quietly in the background without lagging your game.

## ✨ Key Features

* **Seamless Background Syncing:** Exports chunk data to your disk automatically (default every 10 seconds) on a separate thread, meaning **zero game freezes** while you play.
* **Smart "Dirty-Chunk" Tracking:** Only updates chunks that have changed since the last save, keeping exports lightning fast even for massive worlds.
* **Comprehensive Capture:**
    * **Multi-Dimension:** Fully supports the Overworld, Nether, End, and any custom dimensions.
    * **Entities:** Captures mobs, animals, dropped items, armor stands (including poses!), paintings, and vehicles exactly as they appear.
    * **Containers:** Intercepts inventory packets. Just open a chest, barrel, hopper, or furnace while the mod is active, and its contents will be saved to your mirrored world!
    * **Block Entities:** Persists sign text, banner patterns, player heads, beacon effects, and lectern books.
* **In-Game UI:** Press **`I`** to open a sleek status screen to view sync stats, manage your download state, and handle chunk conflicts.
* **Smart World Mapping:** Automatically maps server IPs to dedicated local folders. The same server will always export to the same folder, so you never accidentally overwrite the wrong save.
* **International Support:** Fully translated into English, Simplified Chinese, Traditional Chinese, and Japanese.

## 🎮 How to Use

1. **Join** a multiplayer server (or singleplayer world).
2. Press **`P`** to start a download session. You'll see an active status in your action bar.
3. **Explore!** Walk around to load terrain. Remember to open any containers if you want their contents saved.
4. When you're done, press **`P`** again to stop, or press **`O`** to force an immediate export.
5. **Play offline:** Your world is saved in `<.minecraft>/downloaded_worlds/` by default. *(Tip: Change the save location to your `Saves Folder` in the settings to play your mirrored worlds instantly from the singleplayer menu!)*

*Need to clear your cache and start fresh? Just press **`L`**.*

## ⚙️ Configuration & Conflicts

World Mirror is fully configurable via **Mod Menu**. Access global settings to change your save location, sync interval, and in-game logging levels.

You can also handle **Chunk Conflicts** (when a chunk already exists on your local disk) globally or per-world using three strategies:
* **Overwrite (Default):** The server chunk always replaces your local copy.
* **Ignore:** Keeps your local copy; only brand new chunks are written.
* **Manual:** Keeps the local copy but queues the chunk in your `I` status screen for you to resolve later.

## 📥 Installation & Requirements

* **Minecraft:** 1.21.11
* **Mod Loader:** [Fabric](https://fabricmc.net/use/) (≥ 0.18.2)
* **Dependencies:**
    * [Fabric API](https://modrinth.com/mod/fabric-api)
    * [LibGUI](https://github.com/CottonMC/LibGui) (≥ 15.1.0) - *Required for the status screen*
* **Optional:** [Mod Menu](https://modrinth.com/mod/modmenu) (Highly recommended for accessing the settings screen)

---
> **Note:** Because this is a client-side mod, it can only capture data the server actually sends to you. Container contents will only be saved if you physically open them, and server-side-only data (like complex mob AI paths or command block commands) will revert to defaults.

***

Would you like me to draft a punchy, 100-character "short description" for the very top of your Modrinth listing to help catch people's eyes in the search results?