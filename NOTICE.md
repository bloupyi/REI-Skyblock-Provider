# Sources and attributions

This project is distributed under LGPL-3.0-or-later. The LGPL text is in LICENSE,
and the supplementary GPL-3.0 text is in licenses/GPL-3.0.txt.

The REI integration and item conversion were studied and adapted from
Skyblocker, by the SkyblockerMod contributors, under LGPL-3.0-or-later:
https://github.com/SkyblockerMod/Skyblocker

LegacyStringNbtReader.java adapts their Minecraft 1.8 SNBT reader and retains
its LGPL licensing. The other classes form an independent integration with
its own data model and no Skyblocker dependency.

Downloaded data comes from NotEnoughUpdates-REPO:
https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO
Copyright (c) 2020 Moulberry. The MIT license is reproduced in licenses/NEU-MIT.txt.
Items and recipes are not bundled in the production JAR. The constants
`petnums.json` and `leveling.json` from revision `b19b032b79527c1999e278535160f411ad95bece` are bundled
as fallbacks for older caches and offline startup. Constants from the downloaded
repository take priority. Their MIT license is reproduced in licenses/NEU-MIT.txt.

The official catalogue is downloaded from https://api.hypixel.net/v2/resources/skyblock/items.
It remains subject to the Hypixel API terms and policies:
https://developer.hypixel.net/policies
Responses are not bundled in the production JAR.

Legacy Item DFU, by AzureAaron, is bundled as a library under Apache-2.0.
Its license is available in the nested JAR:
https://github.com/AzureAaron/legacy-item-dfu

The Gradle scripts and wrapper originate from the Skyblocker repository.
Gradle is distributed under Apache-2.0:
https://github.com/gradle/gradle/blob/master/LICENSE

Hypixel, Minecraft, and their content belong to their respective owners.
This project is not affiliated with Hypixel or Mojang.

Profile synchronization uses the public Ursa Minor client protocol,
a NotEnoughUpdates service: https://github.com/NotEnoughUpdates/ursa-minor
The client implements the documented session ownership proof. No Ursa server,
Hypixel API key, or login token is bundled in this project.
