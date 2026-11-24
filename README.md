# Generic Storage Mod

Storage Modules are blocks that store items. Put them next to each other and they share one big inventory. You can disguise them as normal blocks to hide your storage in your builds.

## Features

- **Modules join together.** Each module holds 27 stacks of items in any mix: 1,728 stone, or one each of 1,728 different items. Unstackable items like tools take a whole stack of room each. Modules that touch (side, edge or corner) become one storage system. Open any module to see everything.
- **Tabs and search.** Items are sorted into tabs like Building Blocks, Tools and Food. You can make your own tabs, search, and change how each tab is sorted.
- **Disguises.** Put a solid block in the disguise slot and the module looks like that block.
- **Hoppers work.** Hoppers can put items in and take items out.
- **Upgrades.** Add a crafting grid with a recipe book that knows what is in storage, or more room to every module.

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

**Capacity Upgrade I** (+4 stacks of room in every module)

| | | |
|:-:|:-:|:-:|
| Gold Ingot | Barrel | Gold Ingot |
| Barrel | Ender Pearl | Barrel |
| Gold Ingot | Barrel | Gold Ingot |

**Capacity Upgrade II** (+9 stacks of room in every module)

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
- **Upgrades:** put them in the upgrade slots on the strip beside your inventory. They work for the whole system.
- **Crafting:** with the Crafting Upgrade, the crafting table tab on the right opens a crafting grid and recipe book. Click a recipe to fill the grid from storage and your inventory; shift-click to fill as many as possible. The book button under the arrow shows or hides the recipe book.
- **Settings:** the menu button opens settings for tabs, crafting auto-fill (refills the crafting grid from storage after each craft), and showing or hiding the fullness bar under the menu. The ? button there lists the controls.

Breaking a module never loses items. Its items move into the rest of the system, or drop if there is no room.

## Settings file

`config/gsm.json` lets you change how many stacks each module holds, turn off merging, and change how often the menu updates.

## Requirements

- Minecraft 26.1 to 26.3 (download the file for your version)
- Fabric Loader 0.20.1 or newer
- Fabric API

Install on both client and server for server support

## License

The License is [Here](https://github.com/Zyrla/GenericStorageMod/blob/bb06bc189b3ba89b541a11df8395c20129d6c997/LICENSE)
