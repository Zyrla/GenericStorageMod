# Generic Storage Mod

**Storage you can build into the walls.** Place Storage Modules next to each other and they join into one big shared inventory, with Creative-style tabs, search, and sorting. Disguise any module as a normal block and nobody will know your storage is there.

<!-- Screenshot: the storage menu, with tabs along the top and items in the grid -->
<!-- Screenshot: a wall of disguised modules next to a normal wall -->

---

## Features

### 🧱 Modules that join together
- Each **Storage Module** holds 27 slots, the same as a chest.
- Modules that touch on a **face, edge or corner** join into one **storage system**, with no size limit.
- Right-click **any** module to open the whole system's shared inventory.
- Systems **merge** automatically when a new module connects them, and **split** when you break the module holding them together. No items are lost either way.

### 📂 A Creative-style menu
- **Category tabs** along the top. Built in: *All Items, Unsorted, Building Blocks, Tools, Food, Ores, Redstone*.
- **Make your own tabs**, with rules based on item tags or item ids, like `#c:ores, minecraft:coal`.
- **Search** within the selected tab.
- **Sort** each tab by order added, name, count or mod.
- **Drag tabs** left or right to reorder them.
- A **capacity bar** shows how full the system is, for example "62% full".
- Items show once with their total count, so even huge systems stay easy to browse.

### 🎭 Disguises
- Put a full, solid block into the **disguise slot** and the module looks exactly like that block, tinted grass included.
- The **↻ button** turns blocks that face a direction, like logs, pillars and observers.
- The block stays in the slot and drops back when the module is broken.

### ⚙️ Automation
- **Hoppers** (and pipes from mods using Fabric's transfer API) can insert into and pull from any side of any module.

### ⬆️ Upgrades
Four upgrade slots per system. Upgrades work for the whole system, and every upgrade explains itself in its tooltip.

| Upgrade | Effect |
|---|---|
| **Crafting Upgrade** | Adds a crafting tab to the menu that opens a 3×3 crafting panel next to your storage |
| **Capacity Upgrade I** | +4 slots (half a row) in every module |
| **Capacity Upgrade II** | +9 slots (a full row) in every module |

A Capacity Upgrade can't be taken out while its extra slots hold items that wouldn't fit elsewhere, so items are never lost.

### ☰ Settings
- Create and edit tabs.
- **Crafting auto-fill** (needs the Crafting Upgrade): after each craft, the grid refills from storage with the same ingredients. Shift-click the result to craft as many as your storage allows.

---

## Recipes

All recipes unlock in the recipe book as you go.

**Storage Module** — unlocks when you pick up copper or a barrel

| | | |
|:-:|:-:|:-:|
| Copper Ingot | Redstone | Copper Ingot |
| Any Planks | Barrel | Any Planks |
| Copper Ingot | Redstone | Copper Ingot |

**Crafting Upgrade** — unlocks when you have a Storage Module

| | | |
|:-:|:-:|:-:|
| Iron Ingot | Redstone | Iron Ingot |
| Redstone | Crafting Table | Redstone |
| Iron Ingot | Redstone | Iron Ingot |

**Capacity Upgrade I** — unlocks when you have a Storage Module

| | | |
|:-:|:-:|:-:|
| Gold Ingot | Barrel | Gold Ingot |
| Barrel | Ender Pearl | Barrel |
| Gold Ingot | Barrel | Gold Ingot |

**Capacity Upgrade II** — unlocks when you have a Capacity Upgrade I

| | | |
|:-:|:-:|:-:|
| Gold Ingot | Shulker Shell | Gold Ingot |
| Diamond | Capacity Upgrade I | Diamond |
| Gold Ingot | Shulker Shell | Gold Ingot |

---

## How to use

| Action | How |
|---|---|
| Take a stack | Left-click an item |
| Take half a stack | Right-click an item |
| Move a stack to your inventory | Shift-click an item |
| Store what you're holding | Click anywhere in the item area (right-click stores one) |
| Store from your inventory | Shift-click it |
| Add an item to a tab | Click the tab while holding that item |
| Remove an item from a tab | Hover it and press **Delete** |
| Reorder tabs | Drag a tab left or right |
| Disguise a module | Put a block in the slot beside your inventory |

Click the **?** button in the menu for a quick reminder.

---

## How systems merge and split

- **Merging:** when a module connects two or more systems, they become one. The merged system keeps the tabs of the system with the most modules (then the one with the most free space). Items from the other systems' tabs go to *Unsorted*, and their upgrades fill any empty upgrade slots.
- **Splitting:** when breaking a module divides a system, every remaining group becomes its own system with a copy of the tabs. Upgrades stay with the largest group. The broken module's items go to the group with the most free space first. Groups that tie split the items evenly, and anything left over drops.
- **Breaking a module** without splitting moves its items into the system's free space. Anything that doesn't fit drops.

---

## Configuration

`config/gsm.json` is created the first time you launch. On a server, the server's file is the one that counts.

| Setting | Default | What it does |
|---|---|---|
| `slots_per_module` | `27` | Slots in each new module (1–270). Lowering it never removes items. |
| `allow_merging` | `true` | If `false`, a module touching two systems joins only the larger one. |
| `sync_interval_ticks` | `2` | How often open menus update while hoppers move items (20 = once per second). |

---

## Requirements

- **Minecraft 26.2**
- **Fabric Loader** 0.20.1 or newer
- **[Fabric API](https://modrinth.com/mod/fabric-api)**
- Java 25 (included with the official launcher for 26.2)

Install it on **both the client and the server**.

---

## License

The code is released under the **MIT License**, so you're free to include it in modpacks.
The textures are edited from Minecraft's own (barrel, crafting table) and belong to Mojang Studios.
