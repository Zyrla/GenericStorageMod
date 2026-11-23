package com.zyria.gsm.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenCustomHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.zyria.gsm.ModConfig;
import com.zyria.gsm.item.UpgradeItem;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * One storage system: a group of touching modules that works like one big chest.
 * <p>
 * Modules have no slots. Each module holds a number of stacks' worth of items (27 by default, see the
 * settings file) in any mix: every item costs 1/max-stack-size of a stack, so a module can hold 1,728 stone,
 * 432 ender pearls, 27 swords, or one each of 1,728 different stackable items. The system pools its modules.
 */
public final class StorageSystem {

	public static final Hash.Strategy<ItemStack> ITEM_AND_COMPONENTS = new Hash.Strategy<>() {
		@Override
		public int hashCode(ItemStack stack) {
			return stack == null ? 0 : ItemStack.hashItemAndComponents(stack);
		}

		@Override
		public boolean equals(ItemStack a, ItemStack b) {
			return a == b || a != null && b != null && ItemStack.isSameItemSameComponents(a, b);
		}
	};

	/**
	 * Capacity units in one stack's worth. Divisible by every stack size from 1 to 16 and by 32 and 64, so
	 * vanilla items cost an exact number of units (other sizes round up by a tiny fraction).
	 */
	public static final long UNITS_PER_STACK = 2_882_880L;

	/** Capacity units one item of this kind takes up. */
	public static long unitCost(ItemStack stack) {
		int max = Math.max(1, stack.getMaxStackSize());
		return (UNITS_PER_STACK + max - 1) / max;
	}

	/** One module's contents: item (count-1 prototype) to amount, plus the capacity units in use. */
	public static final class Module {
		final Object2LongLinkedOpenCustomHashMap<ItemStack> items = new Object2LongLinkedOpenCustomHashMap<>(ITEM_AND_COMPONENTS);
		long used;
	}

	private record Content(ItemStack item, long count) {
		static final Codec<Content> CODEC = RecordCodecBuilder.create(i -> i.group(
			ItemStack.CODEC.fieldOf("item").forGetter(Content::item),
			Codec.LONG.fieldOf("count").forGetter(Content::count)
		).apply(i, Content::new));
	}

	/** Saved module. "items" is the old slot format (still read, converted on load); "contents" is the current one. */
	private record ModuleData(long pos, List<ItemStack> items, List<Content> contents) {
		static final Codec<ModuleData> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.LONG.fieldOf("pos").forGetter(ModuleData::pos),
			ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("items", List.of()).forGetter(ModuleData::items),
			Content.CODEC.listOf().optionalFieldOf("contents", List.of()).forGetter(ModuleData::contents)
		).apply(i, ModuleData::new));
	}

	public static final Codec<StorageSystem> CODEC = RecordCodecBuilder.create(i -> i.group(
		UUIDUtil.CODEC.fieldOf("id").forGetter(s -> s.id),
		ModuleData.CODEC.listOf().fieldOf("modules").forGetter(StorageSystem::moduleData),
		StorageTab.CODEC.listOf().fieldOf("tabs").forGetter(s -> s.tabs),
		Codec.INT.optionalFieldOf("next_tab_id", StorageTab.FIRST_CUSTOM_ID).forGetter(s -> s.nextTabId),
		ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("upgrades", List.of()).forGetter(s -> Arrays.asList(s.upgrades)),
		Codec.BOOL.optionalFieldOf("crafting_auto_fill", true).forGetter(s -> s.craftingAutoFill)
	).apply(i, StorageSystem::fromData));

	/** Setting: refill the crafting grid from storage after each craft. Shared by everyone using the system. */
	boolean craftingAutoFill = true;

	public boolean craftingAutoFill() {
		return this.craftingAutoFill;
	}

	public void setCraftingAutoFill(boolean value) {
		if (this.craftingAutoFill != value) {
			this.craftingAutoFill = value;
			this.dirtyListener.run();
		}
	}

	public static final int UPGRADE_SLOTS = 4;

	/** The system's upgrade slots. Upgrades apply to every module in the system. */
	final ItemStack[] upgrades = emptySlots(UPGRADE_SLOTS);

	public final UUID id;
	/** Module position (as a long) to that module's contents. */
	final Long2ObjectLinkedOpenHashMap<Module> modules = new Long2ObjectLinkedOpenHashMap<>();
	public final List<StorageTab> tabs;
	int nextTabId;
	/** Bumped on every change so open menus know to resend their view. */
	private int version;
	private int tabsVersion;
	private List<StoredEntry> aggregateCache;

	// Running index, kept up to date on every change so lookups don't scan every module.
	/** Every distinct item (item + components) to its total amount, in the order they first arrived. */
	private final Object2LongLinkedOpenCustomHashMap<ItemStack> totals = new Object2LongLinkedOpenCustomHashMap<>(ITEM_AND_COMPONENTS);
	/** Item type to the number of distinct entries of it (different components count separately). */
	private final Object2IntLinkedOpenHashMap<Item> typeKeys = new Object2IntLinkedOpenHashMap<>();
	private long usedUnits;
	/** Item types that arrived since the last change was published; they get sorted into tabs then. */
	private final LinkedHashSet<Item> pendingNewTypes = new LinkedHashSet<>();
	/** While a hopper/pipe transaction is open: every change made, so it can be undone. */
	List<Change> journal;
	Runnable dirtyListener = () -> {
	};

	record Change(Module module, ItemStack item, long delta) {
	}

	public StorageSystem(UUID id, List<StorageTab> tabs, int nextTabId) {
		this.id = id;
		this.tabs = new ArrayList<>(tabs);
		this.nextTabId = nextTabId;
	}

	private static StorageSystem fromData(UUID id, List<ModuleData> modules, List<StorageTab> tabs, int nextTabId, List<ItemStack> upgrades, boolean craftingAutoFill) {
		StorageSystem system = new StorageSystem(id, tabs, nextTabId);
		system.craftingAutoFill = craftingAutoFill;
		for (int i = 0; i < Math.min(UPGRADE_SLOTS, upgrades.size()); i++) {
			system.upgrades[i] = upgrades.get(i);
		}

		for (ModuleData data : modules) {
			Module module = new Module();
			for (ItemStack stack : data.items()) {
				if (!stack.isEmpty()) {
					module.items.addTo(stack.copyWithCount(1), stack.getCount());
				}
			}

			for (Content content : data.contents()) {
				if (!content.item().isEmpty() && content.count() > 0) {
					module.items.addTo(content.item().copyWithCount(1), content.count());
				}
			}

			system.modules.put(data.pos(), module);
		}

		system.rebuildIndex();
		return system;
	}

	private List<ModuleData> moduleData() {
		List<ModuleData> list = new ArrayList<>(this.modules.size());
		for (var entry : this.modules.long2ObjectEntrySet()) {
			List<Content> contents = new ArrayList<>(entry.getValue().items.size());
			for (var item : entry.getValue().items.object2LongEntrySet()) {
				contents.add(new Content(item.getKey(), item.getLongValue()));
			}

			list.add(new ModuleData(entry.getLongKey(), List.of(), contents));
		}

		return list;
	}

	// ---------------------------------------------------------------- upgrades

	/** Stacks' worth each module holds: the configured base plus any Capacity upgrades. */
	public int slotsPerModule() {
		int extra = 0;
		for (ItemStack stack : this.upgrades) {
			extra += UpgradeItem.extraSlots(stack);
		}

		return ModConfig.get().slots_per_module + extra;
	}

	public ItemStack upgrade(int slot) {
		return this.upgrades[slot];
	}

	public void setUpgrade(int slot, ItemStack stack) {
		this.upgrades[slot] = stack;
	}

	/** Puts an upgrade in the first empty upgrade slot. Returns false if all are full. */
	boolean addUpgrade(ItemStack stack) {
		for (int i = 0; i < UPGRADE_SLOTS; i++) {
			if (this.upgrades[i].isEmpty()) {
				this.upgrades[i] = stack;
				return true;
			}
		}

		return false;
	}

	/** Takes every upgrade out (used when a system's upgrades are handed elsewhere). */
	List<ItemStack> takeUpgrades() {
		List<ItemStack> taken = new ArrayList<>();
		for (int i = 0; i < UPGRADE_SLOTS; i++) {
			if (!this.upgrades[i].isEmpty()) {
				taken.add(this.upgrades[i]);
				this.upgrades[i] = ItemStack.EMPTY;
			}
		}

		return taken;
	}

	/** Whether everything stored would still fit if every module held {@code size} stacks' worth. */
	public boolean canShrinkTo(int size) {
		return this.usedUnits <= (long) size * UNITS_PER_STACK * this.modules.size();
	}

	/** Whether this upgrade can be taken out right now without losing items (only Capacity upgrades can block). */
	public boolean canRemoveUpgrade(ItemStack upgrade) {
		int extra = UpgradeItem.extraSlots(upgrade);
		return extra == 0 || this.canShrinkTo(this.slotsPerModule() - extra);
	}

	/**
	 * Called when the upgrades (and so every module's capacity) change. Modules holding more than they now
	 * can pass the extra on to modules with room. If there isn't room, they simply stay over-full (nothing is
	 * lost; they take nothing new until they drop below their capacity).
	 */
	public void applyUpgrades() {
		long capacity = this.moduleCapacity();
		for (Module module : this.modules.values()) {
			if (module.used <= capacity) {
				continue;
			}

			for (ItemStack item : new ArrayList<>(module.items.keySet())) {
				if (module.used <= capacity) {
					break;
				}

				long cost = unitCost(item);
				long excess = Math.min(module.items.getLong(item), (module.used - capacity + cost - 1) / cost);
				long moved = this.insertAmount(item, excess, module);
				this.change(module, item, -moved);
			}
		}

		this.markChanged();
	}

	static ItemStack[] emptySlots(int size) {
		ItemStack[] slots = new ItemStack[size];
		Arrays.fill(slots, ItemStack.EMPTY);
		return slots;
	}

	public int version() {
		return this.version;
	}

	public void markChanged() {
		if (!this.pendingNewTypes.isEmpty()) {
			// Only the new item types need sorting into tabs, not everything stored.
			List<Item> fresh = new ArrayList<>(this.pendingNewTypes);
			this.pendingNewTypes.clear();
			fresh.removeIf(item -> !this.typeKeys.containsKey(item));
			if (!fresh.isEmpty()) {
				this.sortIntoTabs(fresh, false);
			}
		}

		this.version++;
		this.aggregateCache = null;
		this.dirtyListener.run();
	}

	/** Bumped when tabs change (names, rules, order, sort mode or the items they hold). */
	public int tabsVersion() {
		return this.tabsVersion;
	}

	void tabsChanged() {
		this.tabsVersion++;
	}

	// ---------------------------------------------------------------- hoppers and pipes

	private SystemItemStorage itemStorage;

	/** The system as a Fabric item storage, for hoppers and pipes. */
	public SystemItemStorage itemStorage() {
		if (this.itemStorage == null) {
			this.itemStorage = new SystemItemStorage(this);
		}

		return this.itemStorage;
	}

	/** Must be called whenever {@link #modules} gains or loses a module. */
	void modulesChanged() {
		this.rebuildIndex();
	}

	/** Called after a hopper or pipe commits its changes. New item types are sorted in markChanged. */
	void onExternalChange() {
		this.markChanged();
	}

	/** Undoes journaled changes back to {@code size} entries (a hopper/pipe transaction was cancelled). */
	void undoTo(int size) {
		List<Change> journal = this.journal;
		this.journal = null; // the undo itself must not be journaled
		for (int i = journal.size() - 1; i >= size; i--) {
			Change change = journal.remove(i);
			this.change(change.module(), change.item(), -change.delta());
		}

		this.journal = journal;
	}

	// ---------------------------------------------------------------- index

	private void rebuildIndex() {
		this.totals.clear();
		this.typeKeys.clear();
		this.usedUnits = 0;
		for (Module module : this.modules.values()) {
			module.used = 0;
			for (var entry : module.items.object2LongEntrySet()) {
				ItemStack item = entry.getKey();
				long amount = entry.getLongValue();
				module.used += unitCost(item) * amount;
				if (!this.totals.containsKey(item)) {
					this.totals.put(item, amount);
					this.typeKeys.addTo(item.getItem(), 1);
				} else {
					this.totals.addTo(item, amount);
				}
			}

			this.usedUnits += module.used;
		}
	}

	/** Adds (or with a negative delta, removes) items in one module, keeping the index and journal right. */
	private void change(Module module, ItemStack item, long delta) {
		if (delta == 0) {
			return;
		}

		long inModule = module.items.getLong(item) + delta;
		if (inModule <= 0) {
			module.items.removeLong(item);
		} else if (module.items.containsKey(item)) {
			module.items.put(item, inModule); // keeps the stored key
		} else {
			module.items.put(item.copyWithCount(1), inModule);
		}

		long units = unitCost(item) * delta;
		module.used += units;
		this.usedUnits += units;

		long total = this.totals.getLong(item) + delta;
		if (total <= 0) {
			this.totals.removeLong(item);
			if (this.typeKeys.addTo(item.getItem(), -1) <= 1) {
				this.typeKeys.removeInt(item.getItem());
			}
		} else if (this.totals.containsKey(item)) {
			this.totals.put(item, total);
		} else {
			this.totals.put(item.copyWithCount(1), total);
			if (this.typeKeys.addTo(item.getItem(), 1) == 0) {
				this.pendingNewTypes.add(item.getItem());
			}
		}

		if (this.journal != null) {
			this.journal.add(new Change(module, item.copyWithCount(1), delta));
		}
	}

	// ---------------------------------------------------------------- capacity

	public int moduleCount() {
		return this.modules.size();
	}

	/** Capacity units each module holds. */
	public long moduleCapacity() {
		return (long) this.slotsPerModule() * UNITS_PER_STACK;
	}

	/** Stacks' worth still free (can be negative while modules are over-full). */
	public double freeSpace() {
		return (double) (this.moduleCapacity() * this.modules.size() - this.usedUnits) / UNITS_PER_STACK;
	}

	/** How full the system is by item amount (32 stone is half a stack's worth). */
	public float percentFull() {
		long capacity = this.moduleCapacity() * this.modules.size();
		return capacity == 0 ? 0F : (float) (this.usedUnits * 100.0 / capacity);
	}

	// ---------------------------------------------------------------- items

	/** Inserts as much of the stack as fits. Returns what did not fit. The argument is not modified. */
	public ItemStack insert(ItemStack stack) {
		if (stack.isEmpty()) {
			return ItemStack.EMPTY;
		}

		long moved = this.insertAmount(stack, stack.getCount(), null);
		if (moved > 0) {
			this.markChanged();
		}

		return moved == stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - (int) moved);
	}

	/**
	 * Adds up to {@code amount} of the item, filling modules that already hold it first, then any module
	 * with room. Skips {@code except}. Returns how many were added. Does not publish the change.
	 */
	long insertAmount(ItemStack item, long amount, Module except) {
		if (amount <= 0) {
			return 0;
		}

		long cost = unitCost(item);
		long capacity = this.moduleCapacity();
		long left = amount;
		if (this.totals.containsKey(item)) {
			for (Module module : this.modules.values()) {
				if (left == 0) {
					break;
				}

				if (module != except && module.items.containsKey(item)) {
					left -= this.fill(module, item, left, cost, capacity);
				}
			}
		}

		for (Module module : this.modules.values()) {
			if (left == 0) {
				break;
			}

			if (module != except) {
				left -= this.fill(module, item, left, cost, capacity);
			}
		}

		return amount - left;
	}

	private long fill(Module module, ItemStack item, long amount, long cost, long capacity) {
		long room = (capacity - module.used) / cost;
		long moved = Math.min(room, amount);
		if (moved <= 0) {
			return 0;
		}

		this.change(module, item, moved);
		return moved;
	}

	/** Takes up to {@code amount} items matching the prototype (item and components). */
	public ItemStack extract(ItemStack prototype, int amount) {
		long taken = this.extractAmount(prototype, amount);
		if (taken <= 0) {
			return ItemStack.EMPTY;
		}

		this.markChanged();
		return prototype.copyWithCount((int) taken);
	}

	/** Removes up to {@code amount} of the item, from any modules. Returns how many. Does not publish the change. */
	long extractAmount(ItemStack prototype, long amount) {
		if (prototype.isEmpty() || amount <= 0 || !this.totals.containsKey(prototype)) {
			return 0;
		}

		long left = amount;
		for (Module module : this.modules.values()) {
			if (left == 0) {
				break;
			}

			long have = module.items.getLong(prototype);
			if (have > 0) {
				long take = Math.min(have, left);
				this.change(module, prototype, -take);
				left -= take;
			}
		}

		return amount - left;
	}

	/**
	 * Moves a broken module's contents into this system's modules and publishes the change once.
	 * Returns what didn't fit, as normal-sized stacks.
	 */
	List<ItemStack> absorb(Module removed) {
		List<ItemStack> overflow = new ArrayList<>();
		for (var entry : removed.items.object2LongEntrySet()) {
			ItemStack item = entry.getKey();
			long amount = entry.getLongValue();
			toStacks(item, amount - this.insertAmount(item, amount, null), overflow);
		}

		this.markChanged();
		return overflow;
	}

	/** Splits an amount of one item into stacks of at most its max stack size. */
	static void toStacks(ItemStack item, long amount, List<ItemStack> out) {
		int max = Math.max(1, item.getMaxStackSize());
		while (amount > 0) {
			int count = (int) Math.min(max, amount);
			out.add(item.copyWithCount(count));
			amount -= count;
		}
	}

	/** Most of one item the recipe finder is told about, so its int counts can't overflow. */
	public static final int CRAFTING_COUNT_CAP = 100_000;

	/** Tells the recipe finder about every stored item crafting may use (not damaged, enchanted or renamed). */
	public void fillStackedContents(StackedItemContents contents) {
		for (var entry : this.totals.object2LongEntrySet()) {
			ItemStack item = entry.getKey();
			if (Inventory.isUsableForCrafting(item)) {
				int count = (int) Math.min(entry.getLongValue(), CRAFTING_COUNT_CAP);
				contents.accountStack(item.copyWithCount(count), count);
			}
		}
	}

	/**
	 * Takes up to {@code amount} of the item for the crafting grid, all of one kind (same components) so it
	 * fits in one slot. Only items crafting may use.
	 */
	public ItemStack extractForCrafting(Holder<Item> item, int amount) {
		for (ItemStack key : this.totals.keySet()) {
			if (key.is(item) && Inventory.isUsableForCrafting(key)) {
				return this.extract(key, amount);
			}
		}

		return ItemStack.EMPTY;
	}

	public long count(ItemStack prototype) {
		return this.totals.getLong(prototype);
	}

	/** Every distinct item (item + components) in the system with its total count, in arrival order. */
	public List<StoredEntry> aggregate() {
		if (this.aggregateCache == null) {
			List<StoredEntry> result = new ArrayList<>(this.totals.size());
			for (var entry : this.totals.object2LongEntrySet()) {
				result.add(new StoredEntry(entry.getKey().copy(), entry.getLongValue()));
			}

			this.aggregateCache = List.copyOf(result);
		}

		return this.aggregateCache;
	}

	/** Distinct items (item + components) currently stored, in arrival order. Do not modify. */
	Set<ItemStack> storedKeys() {
		return this.totals.keySet();
	}

	/** Distinct item types in the system, in storage order. */
	public Set<Item> presentItems() {
		return this.typeKeys.keySet();
	}

	// ---------------------------------------------------------------- tabs

	public StorageTab tab(int tabId) {
		for (StorageTab tab : this.tabs) {
			if (tab.id == tabId) {
				return tab;
			}
		}

		return null;
	}

	public StorageTab unsortedTab() {
		for (StorageTab tab : this.tabs) {
			if (tab.kind == StorageTab.Kind.UNSORTED) {
				return tab;
			}
		}

		return null;
	}

	/**
	 * Runs automatic sorting. Tabs with sorting on take in present items their rules match.
	 * Items already in a tab stay, whatever the setting. Manual removals are never re-added.
	 */
	public void resort() {
		this.pendingNewTypes.clear();
		this.sortIntoTabs(this.presentItems(), true);
	}

	/**
	 * Sorts the given item types into tabs. With {@code cleanup}, also takes automatically sorted items
	 * out of Unsorted once a category claims them (only needed after tab or rule changes).
	 */
	private void sortIntoTabs(java.util.Collection<Item> present, boolean cleanup) {
		Set<Identifier> claimed = new HashSet<>();
		boolean changed = false;

		for (StorageTab tab : this.tabs) {
			if (tab.kind != StorageTab.Kind.CATEGORY) {
				continue;
			}

			if (tab.sorting) {
				for (Item item : present) {
					Identifier key = BuiltInRegistries.ITEM.getKey(item);
					if (!tab.entries.contains(key) && !tab.excluded.contains(key) && tab.matchesRules(item)) {
						changed |= tab.entries.add(key);
					}
				}
			}

			claimed.addAll(tab.entries);
		}

		for (StorageTab tab : this.tabs) {
			if (tab.kind == StorageTab.Kind.ALL && tab.sorting) {
				for (Item item : present) {
					Identifier key = BuiltInRegistries.ITEM.getKey(item);
					if (!tab.excluded.contains(key)) {
						changed |= tab.entries.add(key);
					}
				}
			} else if (tab.kind == StorageTab.Kind.UNSORTED) {
				// Automatically sorted items leave Unsorted once a category claims them.
				if (cleanup) {
					changed |= tab.entries.removeIf(key -> claimed.contains(key) && !tab.manual.contains(key));
				}
				if (tab.sorting) {
					for (Item item : present) {
						Identifier key = BuiltInRegistries.ITEM.getKey(item);
						if (!claimed.contains(key) && !tab.excluded.contains(key)) {
							changed |= tab.entries.add(key);
						}
					}
				}
			}
		}

		if (changed) {
			this.tabsChanged();
		}
	}

	/** Manually puts an item into a tab, or manually takes it out. */
	public void assign(int tabId, Item item, boolean add) {
		StorageTab tab = this.tab(tabId);
		if (tab == null) {
			return;
		}

		Identifier key = BuiltInRegistries.ITEM.getKey(item);
		if (add) {
			tab.excluded.remove(key);
			tab.entries.add(key);
			tab.manual.add(key);
		} else {
			tab.entries.remove(key);
			tab.manual.remove(key);
			tab.excluded.add(key);
		}

		this.tabsChanged();
		this.resort();
		this.markChanged();
	}

	/** Creates (tabId &lt; 0) or edits a tab. Returns the tab id. */
	public int editTab(int tabId, String name, Identifier icon, boolean sorting, List<TabRule> rules) {
		StorageTab tab;
		if (tabId < 0) {
			tab = new StorageTab(this.nextTabId++, name, StorageTab.Kind.CATEGORY, sorting, icon, rules, List.of(), List.of(), List.of(), StorageTab.SortMode.ARRIVAL);
			this.tabs.add(tab);
		} else {
			tab = this.tab(tabId);
			if (tab == null) {
				return -1;
			}

			tab.name = name;
			tab.icon = icon;
			tab.sorting = sorting;
			if (tab.kind == StorageTab.Kind.CATEGORY) {
				tab.rules.clear();
				tab.rules.addAll(rules);
			}
		}

		this.tabsChanged();
		this.resort();
		this.markChanged();
		return tab.id;
	}

	/** Removes a tab. Its items go to Unsorted. The Unsorted tab itself can't be removed. */
	public boolean removeTab(int tabId) {
		StorageTab tab = this.tab(tabId);
		if (tab == null || tab.kind == StorageTab.Kind.UNSORTED) {
			return false;
		}

		this.tabs.remove(tab);
		this.moveToUnsorted(tab.entries);
		this.tabsChanged();
		this.resort();
		this.markChanged();
		return true;
	}

	/** Changes how a tab orders its items in the menu. */
	public void setSortMode(int tabId, StorageTab.SortMode mode) {
		StorageTab tab = this.tab(tabId);
		if (tab == null || tab.sortMode == mode) {
			return;
		}

		tab.sortMode = mode;
		this.tabsChanged();
		this.markChanged();
	}

	/** Adds the given items (those present in the system) to Unsorted as manual entries. */
	void moveToUnsorted(Iterable<Identifier> items) {
		StorageTab unsorted = this.unsortedTab();
		if (unsorted == null) {
			return;
		}

		Set<Identifier> present = new HashSet<>();
		for (Item item : this.presentItems()) {
			present.add(BuiltInRegistries.ITEM.getKey(item));
		}

		for (Identifier key : items) {
			if (present.contains(key)) {
				unsorted.excluded.remove(key);
				unsorted.entries.add(key);
				unsorted.manual.add(key);
			}
		}

		this.tabsChanged();
	}

	/** Items currently claimed by any category tab. */
	Set<Identifier> claimedByCategories() {
		Set<Identifier> claimed = new HashSet<>();
		for (StorageTab tab : this.tabs) {
			if (tab.kind == StorageTab.Kind.CATEGORY) {
				claimed.addAll(tab.entries);
			}
		}

		return claimed;
	}

	public List<StorageTab> copyTabs() {
		List<StorageTab> copy = new ArrayList<>(this.tabs.size());
		for (StorageTab tab : this.tabs) {
			copy.add(tab.copy());
		}

		return copy;
	}
}
