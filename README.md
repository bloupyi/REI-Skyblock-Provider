# REI Skyblock Provider

A client-side Fabric mod bringing Hypixel SkyBlock items and recipes to Roughly Enough Items.

## Build

Install **JDK 25** and set `JAVA_HOME`. The Gradle wrapper is included. An Internet connection is required to download dependencies.

Windows:

```powershell
.\gradlew.bat build
```

Linux / macOS:

```sh
chmod +x gradlew
./gradlew build
```

Install `build/libs/rei-skyblock-provider-1.6.3+mc26.2.jar`. The `-sources` JAR contains source code and should not be installed as a mod. A project archive is generated in `build/distributions/`.

The standard build runs unit tests. It requires no existing Minecraft installation, local NEU archive, or API key.

## Installation

Use Minecraft **26.2**, Java **25**, Fabric Loader **0.19.5**, Fabric API **0.161.0+26.2**, REI **26.2.820**, Architectury API **21.0.2**, and Cloth Config **26.2.155**.

Place the mod JAR and dependencies in your instance's `mods` folder, keeping only one version of this provider. Legacy Item DFU is bundled. Skyblocker is not required.

Data downloads in the background. By default, items appear only on Hypixel SkyBlock. Recipe and usage keys follow your REI settings, usually **R** and **U**.

## Features

- SkyBlock items with identifiers, components, names, lore, and support for compatible resource packs.
- 3×3 crafting, Forge, Kat, NPC shops, trades, loot sources, and wiki links.
- A **+** button opening the server recipe and its Supercraft entry, without automating crafting or purchases.
- Recursive crafting trees, a shopping list accounting for inventory and sacks, and requirement displays.
- Background data synchronization, an offline cache, and observations from menus opened in game.

To add a shopping goal: **Recipe → Craft tree → quantity → Apply → Add to list**. The main inventory and hotbar are counted. Equipped armor, the offhand, Ender Chest, and backpacks are excluded. Unknown stock stays marked as unknown. Sack quantities older than 24 hours are ignored.

Skyblocker's REI providers are filtered to prevent duplicates while keeping its other features available. Optional SkyCofl integration supplies price tooltips for virtual items when supported by the installed version.

## API and configuration

On joining and switching profiles, the mod uses [NotEnoughUpdates' public Ursa proxy](https://github.com/NotEnoughUpdates/ursa-minor#client-usage), without a permanent Hypixel API key. Enable Inventory, Skills, and Collections sharing in SkyBlock's API settings.

Session ownership is verified through Mojang. Ursa receives your Minecraft username and a random verification identifier, then returns a temporary token kept only in memory for 55 minutes. Your Minecraft access token is not sent to Ursa. The service also receives the requested UUID and your connection's IP address. A valid online Minecraft session is required.

Successful responses are reused for five minutes. Recent in-game observations take priority, and missing fields are not replaced with zeroes. During outages or rate limiting, existing caches and menu observations remain usable. Farming and Taming caps supplied by the profile are respected. The API reader uses the base Foraging cap; in-game observations can supplement it.

Configuration is created at `config/rei_skyblock_provider.json`. Set `onlyOnHypixelSkyblock=false` to show items in singleplayer. `autoUpdateRepository` controls downloads, `updateIntervalMinutes` sets their interval, and `enableProfileApi` controls profile synchronization.

Caches and observations stay in `config/rei_skyblock_provider/` and should not be published. An alternative HTTPS backend returning Hypixel's `success/profiles` format can be configured using `profileApiUrl` containing `{uuid}` and `profileApiAuthHeader`. If needed, its authentication value can be stored locally in `config/rei_skyblock_provider/profile-api-key.txt`. Ursa does not require this file.

## Commands

- `/reisbshopping`: open the shopping list.
- `/reisbplan ITEM_ID`: open a crafting tree.
- `/reisbprofilesync`: request profile synchronization, subject to request cooldowns.
- `/reisbstatus`: show data source, API, and REI status.
- `/reisbreload`: reread configuration and reload data.
- `/reisbupdate`: request a remote data check.

## Additional tests

Run unit tests alone with `./gradlew test` or `.\gradlew.bat test`.

Client tests require a ZIP archive of the [NEU repository](https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO) and a graphical environment:

```powershell
.\gradlew.bat runClientGameTest "-PrepositoryArchive=C:/path/to/NotEnoughUpdates-REPO-master.zip"
```

They use `run/clientGameTest/`, disposable worlds, and local fixtures, without a real Hypixel account. The optional `repositoryAudit` task accepts the same parameter. Add `-PhypixelItems=C:/path/to/hypixel-items.json` to include a saved official item catalogue response. Unit test reports are written to `build/reports/tests/test/`.

## Data sources and license

Data comes from [NotEnoughUpdates-REPO](https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO) and the [Hypixel item catalogue](https://api.hypixel.net/v2/resources/skyblock/items). Recipe availability depends on these sources. Custom textures require a compatible resource pack.

Licensed under **LGPL-3.0-or-later**. Reused component attributions and licenses are retained in [NOTICE.md](NOTICE.md), [LICENSE](LICENSE), and [licenses/](licenses/).

This project is not affiliated with Hypixel or Mojang.
