# Generic Storage Mod

Storage Modules are blocks that store items. Put them next to each other and they share one big inventory. You can disguise them as normal blocks to hide your storage in your builds.

## Features

- **Modules join together.** Each module has 27 slots. Modules that touch (side, edge or corner) become one storage system. Open any module to see everything.
- **Tabs and search.** Items are sorted into tabs like Building Blocks, Tools and Food. You can make your own tabs, search, and change how each tab is sorted.
- **Disguises.** Put a solid block in the disguise slot and the module looks like that block.
- **Hoppers work.** Hoppers can put items in and take items out.
- **Upgrades.** Add a crafting grid to the menu, or more slots to every module.

## Recipes

**Storage Module**

| | | |
|:-:|:-:|:-:|
| Copper Ingot | Redstone | Copper Ingot |
| Planks | Barrel | Planks |
| Copper Ingot | Redstone | Copper Ingot |

**Crafting Upgrade** (adds a crafting grid to the menu)

| | | |
|:-:|:-:|:-:|
| Iron Ingot | Redstone | Iron Ingot |
| Redstone | Crafting Table | Redstone |
| Iron Ingot | Redstone | Iron Ingot |

**Capacity Upgrade I** (+4 slots in every module)

| | | |
|:-:|:-:|:-:|
| Gold Ingot | Barrel | Gold Ingot |
| Barrel | Ender Pearl | Barrel |
| Gold Ingot | Barrel | Gold Ingot |

**Capacity Upgrade II** (+9 slots in every module)

| | | |
|:-:|:-:|:-:|
| Gold Ingot | Shulker Shell | Gold Ingot |
| Diamond | Capacity Upgrade I | Diamond |
| Gold Ingot | Shulker Shell | Gold Ingot |

## How to use

- **Take items:** left-click for a stack, right-click for half, shift-click to send to your inventory.
- **Store items:** click with an item in the item area, or shift-click it in your inventory.
- **Add an item to a tab:** right-click the tab while holding the item.
- **Remove an item from a tab:** hover over it and press Delete.
- **Upgrades:** put them in the upgrade slots on the right. They work for the whole system.
- **Settings:** the menu button opens settings for tabs and crafting auto-fill (refills the crafting grid from storage after each craft).

Breaking a module never loses items. Its items move into the rest of the system, or drop if there is no room.

## Settings file

`config/gsm.json` lets you change the slots per module, turn off merging, and change how often the menu updates.

## Requirements

- Minecraft 26.1 to 26.3 (download the file for your version)
- Fabric Loader 0.20.1 or newer
- Fabric API

Install on both the client and the server.

## Building

Run `gradlew buildAndCollect`. The jars for every supported version end up in `build/libs/<mod version>/`.

## License

The code is under the MIT License, so it is free to use in modpacks. The textures are based on Minecraft's own and belong to Mojang.
