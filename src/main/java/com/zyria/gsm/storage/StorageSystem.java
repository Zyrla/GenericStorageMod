package com.zyria.gsm.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenCustomHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.zyria.gsm.ModConfig;
import com.zyria.gsm.item.UpgradeItem;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * One storage system: a group of touching modules that works like one big chest.
 * Each module keeps its own slots (27 by default, see the settings file); the system pools them.
 */
public final class StorageSystem {

	public static final Hash.Strategy<ItemStack> ITEM_AND_COMPONENTS = new Hash.Strategy<>() {
		@Override
		public int hashCode(ItemStack stack) {
			return ItemStack.hashItemAndComponents(stack);
		}

		@Override
		public boolean equals(ItemStack a, ItemStack b) {
			return a == b || a != null && b != null && ItemStack.isSameItemSameComponents(a, b);
		}
	};

	private record ModuleData(long pos, List<ItemStack> items) {
		static final Codec<ModuleData> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.LONG.fieldOf("pos").forGetter(ModuleData::pos),
			ItemStack.OPTIONAL_CODEC.listOf().fieldOf("items").forGetter(ModuleData::items)
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
	/** Module position (as a long) to that module's slots. */
	final Long2ObjectLinkedOpenHashMap<ItemStack[]> modules = new Long2ObjectLinkedOpenHashMap<>();
	public final List<StorageTab> tabs;
	int nextTabId;
	/** Bumped on every change so open menus know to resend their view. */
	private int version;
	private int tabsVersion;
	private List<StoredEntry> aggregateCache;

	// Running index, kept up to date on every slot change so lookups don't scan every slot.
	/** Item type -> number of slots holding it, in the order types first arrived. */
	private final Object2IntLinkedOpenHashMap<Item> typeSlots = new Object2IntLinkedOpenHashMap<>();
	private int emptySlotCount;
	private int totalSlotCount;
	/** Item types that arrived since the last change was published; they get sorted into tabs then. */
	private final LinkedHashSet<Item> pendingNewTypes = new LinkedHashSet<>();
	private double usedSlotsCache;
	private int usedSlotsVersion = -1;
	Runnable dirtyListener = () -> {
	};

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

		for (ModuleData module : modules) {
			// Never shrink a saved module below what it holds, even if the setting was lowered.
			int used = 0;
			for (int i = 0; i < module.items().size(); i++) {
				if (!module.items().get(i).isEmpty()) {
					used = i + 1;
				}
			}

			ItemStack[] slots = emptySlots(Math.max(system.slotsPerModule(), used));
			for (int i = 0; i < Math.min(slots.length, module.items().size()); i++) {
				slots[i] = module.items().get(i);
			}

			system.modules.put(module.pos(), slots);
		}

		system.rebuildIndex();
		return system;
	}

	private List<ModuleData> moduleData() {
		List<ModuleData> list = new ArrayList<>(this.modules.size());
		for (var entry : this.modules.long2ObjectEntrySet()) {
			list.add(new ModuleData(entry.getLongKey(), Arrays.asList(entry.getValue())));
		}

		return list;
	}

	// ---------------------------------------------------------------- upgrades

	/** Slots each module should have: the configured base plus any Capacity upgrades. */
	public int slotsPerModule() {
		int extra = 0;
		for (ItemStack stack : this.upgrades) {
			extra += UpgradeItem.extraSlots(stack);
		}

		return ModConfig.get().slots_per_module + extra;
	}

	public int upgradeCount(UpgradeItem.Type type) {
		int count = 0;
		for (ItemStack stack : this.upgrades) {
			if (UpgradeItem.is(stack, type)) {
				count++;
			}
		}

		return count;
	}

	public boolean hasUpgrade(UpgradeItem.Type type) {
		return this.upgradeCount(type) > 0;
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

	/**
	 * Whether every module could shrink to {@code size} slots: the items in the slots that would go
	 * must fit in the empty slots that stay.
	 */
	public boolean canShrinkTo(int size) {
		int displaced = 0;
		int room = 0;
		for (ItemStack[] slots : this.modules.values()) {
			for (int i = 0; i < slots.length; i++) {
				if (i < size) {
					if (slots[i].isEmpty()) {
						room++;
					}
				} else if (!slots[i].isEmpty()) {
					displaced++;
				}
			}
		}

		return displaced <= room;
	}

	/** Whether this upgrade can be taken out right now without losing items (only Capacity upgrades can block). */
	public boolean canRemoveUpgrade(ItemStack upgrade) {
		int extra = UpgradeItem.extraSlots(upgrade);
		return extra == 0 || this.canShrinkTo(this.slotsPerModule() - extra);
	}

	/**
	 * Resizes every module to {@link #slotsPerModule()}. Modules always grow; they only shrink when the
	 * items in the removed slots fit elsewhere in the system (otherwise they keep their extra slots).
	 */
	public void applyUpgrades() {
		int size = this.slotsPerModule();
		boolean shrink = this.canShrinkTo(size);
		List<ItemStack> displaced = new ArrayList<>();
		boolean resized = false;
		for (long key : this.modules.keySet().toLongArray()) {
			ItemStack[] slots = this.modules.get(key);
			if (slots.length == size || slots.length > size && !shrink) {
				continue;
			}

			ItemStack[] resizedSlots = emptySlots(size);
			for (int i = 0; i < slots.length; i++) {
				if (i < size) {
					resizedSlots[i] = slots[i];
				} else if (!slots[i].isEmpty()) {
					displaced.add(slots[i]);
				}
			}

			this.modules.put(key, resizedSlots);
			resized = true;
		}

		if (resized) {
			this.modulesChanged();
			for (ItemStack stack : displaced) {
				this.insert(stack); // canShrinkTo guaranteed room for these
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
			fresh.removeIf(item -> !this.typeSlots.containsKey(item));
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

	/** The system as a Fabric item storage. Rebuilt when modules join or leave. */
	public SystemItemStorage itemStorage() {
		if (this.itemStorage == null) {
			this.itemStorage = SystemItemStorage.create(this);
		}

		return this.itemStorage;
	}

	/** Must be called whenever {@link #modules} gains or loses a module. */
	void modulesChanged() {
		this.itemStorage = null;
		this.rebuildIndex();
	}

	/** Called after a hopper or pipe commits a change to the slots. New item types are sorted in markChanged. */
	void onExternalChange() {
		this.markChanged();
	}

	// ---------------------------------------------------------------- index

	private void rebuildIndex() {
		this.typeSlots.clear();
		this.emptySlotCount = 0;
		this.totalSlotCount = 0;
		for (ItemStack[] slots : this.modules.values()) {
			this.totalSlotCount += slots.length;
			for (ItemStack stack : slots) {
				if (stack.isEmpty()) {
					this.emptySlotCount++;
				} else {
					this.typeSlots.addTo(stack.getItem(), 1);
				}
			}
		}

		this.usedSlotsVersion = -1;
	}

	/**
	 * Records that a slot went from holding {@code before} to holding {@code after} (null = empty).
	 * Only needed when the slot's item type changes, not when a stack just grows or shrinks.
	 */
	void onSlotChanged(Item before, Item after) {
		if (before == after) {
			return;
		}

		if (before == null) {
			this.emptySlotCount--;
		} else if (this.typeSlots.addTo(before, -1) <= 1) {
			this.typeSlots.removeInt(before);
		}

		if (after == null) {
			this.emptySlotCount++;
		} else if (this.typeSlots.addTo(after, 1) == 0) {
			this.pendingNewTypes.add(after);
		}
	}

	public int emptySlotCount() {
		return this.emptySlotCount;
	}

	// ---------------------------------------------------------------- capacity

	public int moduleCount() {
		return this.modules.size();
	}

	public int totalSlots() {
		return this.totalSlotCount;
	}

	/** Sum over slots of (items in slot / max stack size). */
	public double usedSlots() {
		if (this.usedSlotsVersion == this.version) {
			return this.usedSlotsCache;
		}

		double used = 0;
		for (ItemStack[] slots : this.modules.values()) {
			for (ItemStack stack : slots) {
				if (!stack.isEmpty()) {
					used += (double) stack.getCount() / stack.getMaxStackSize();
				}
			}
		}

		this.usedSlotsCache = used;
		this.usedSlotsVersion = this.version;
		return used;
	}

	public double freeSpace() {
		return this.totalSlots() - this.usedSlots();
	}

	public float percentFull() {
		int total = this.totalSlots();
		return total == 0 ? 0F : (float) (this.usedSlots() / total * 100.0);
	}

	// ---------------------------------------------------------------- items

	public boolean containsItem(Item item) {
		return this.typeSlots.containsKey(item);
	}

	/** Inserts as much of the stack as fits. Returns what did not fit. The argument is not modified. */
	public ItemStack insert(ItemStack stack) {
		if (stack.isEmpty()) {
			return ItemStack.EMPTY;
		}

		ItemStack remaining = stack.copy();
		int startCount = remaining.getCount();

		// First top up stacks of the same item (skipped when none are stored).
		if (this.containsItem(remaining.getItem()) && remaining.isStackable()) {
			topUp:
			for (ItemStack[] slots : this.modules.values()) {
				for (ItemStack slot : slots) {
					if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, remaining)) {
						int room = slot.getMaxStackSize() - slot.getCount();
						if (room > 0) {
							int moved = Math.min(room, remaining.getCount());
							slot.grow(moved);
							remaining.shrink(moved);
							if (remaining.isEmpty()) {
								break topUp;
							}
						}
					}
				}
			}
		}

		// Then fill empty slots (skipped when the system is full).
		if (!remaining.isEmpty() && this.emptySlotCount > 0) {
			fill:
			for (ItemStack[] slots : this.modules.values()) {
				for (int i = 0; i < slots.length; i++) {
					if (slots[i].isEmpty()) {
						int moved = Math.min(remaining.getMaxStackSize(), remaining.getCount());
						slots[i] = remaining.copyWithCount(moved);
						this.onSlotChanged(null, slots[i].getItem());
						remaining.shrink(moved);
						if (remaining.isEmpty() || this.emptySlotCount == 0) {
							break fill;
						}
					}
				}
			}
		}

		if (remaining.getCount() != startCount) {
			this.markChanged();
		}

		return remaining.isEmpty() ? ItemStack.EMPTY : remaining;
	}

	/** Takes up to {@code amount} items matching the prototype (item and components). Partial stacks go first. */
	public ItemStack extract(ItemStack prototype, int amount) {
		if (prototype.isEmpty() || amount <= 0 || !this.containsItem(prototype.getItem())) {
			return ItemStack.EMPTY;
		}

		record SlotRef(ItemStack[] slots, int index) {
		}

		List<SlotRef> matches = new ArrayList<>();
		for (ItemStack[] slots : this.modules.values()) {
			for (int i = 0; i < slots.length; i++) {
				if (!slots[i].isEmpty() && ItemStack.isSameItemSameComponents(slots[i], prototype)) {
					matches.add(new SlotRef(slots, i));
				}
			}
		}

		matches.sort(Comparator.comparingInt(ref -> ref.slots()[ref.index()].getCount()));
		ItemStack result = ItemStack.EMPTY;
		for (SlotRef ref : matches) {
			if (result.getCount() >= amount) {
				break;
			}

			ItemStack slot = ref.slots()[ref.index()];
			int take = Math.min(amount - result.getCount(), slot.getCount());
			if (result.isEmpty()) {
				result = slot.copyWithCount(take);
			} else {
				result.grow(take);
			}

			Item item = slot.getItem();
			slot.shrink(take);
			if (slot.isEmpty()) {
				ref.slots()[ref.index()] = ItemStack.EMPTY;
				this.onSlotChanged(item, null);
			}
		}

		if (!result.isEmpty()) {
			this.markChanged();
		}

		return result;
	}

	public long count(ItemStack prototype) {
		long total = 0;
		if (!this.containsItem(prototype.getItem())) {
			return 0;
		}

		for (ItemStack[] slots : this.modules.values()) {
			for (ItemStack stack : slots) {
				if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, prototype)) {
					total += stack.getCount();
				}
			}
		}

		return total;
	}

	/** Every distinct item (item + components) in the system with its total count, in storage order. */
	public List<StoredEntry> aggregate() {
		if (this.aggregateCache == null) {
			this.aggregateCache = List.copyOf(this.computeAggregate());
		}

		return this.aggregateCache;
	}

	private List<StoredEntry> computeAggregate() {
		Object2LongOpenCustomHashMap<ItemStack> counts = new Object2LongOpenCustomHashMap<>(ITEM_AND_COMPONENTS);
		List<ItemStack> order = new ArrayList<>();
		for (ItemStack[] slots : this.modules.values()) {
			for (ItemStack stack : slots) {
				if (stack.isEmpty()) {
					continue;
				}

				if (!counts.containsKey(stack)) {
					ItemStack key = stack.copyWithCount(1);
					order.add(key);
					counts.put(key, 0L);
				}

				counts.addTo(stack, stack.getCount());
			}
		}

		List<StoredEntry> result = new ArrayList<>(order.size());
		for (ItemStack key : order) {
			result.add(new StoredEntry(key, counts.getLong(key)));
		}

		return result;
	}

	/** Distinct item types in the system, in storage order. */
	public Set<Item> presentItems() {
		return this.typeSlots.keySet();
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

	/** Moves a tab to a new position in the tab list. */
	public void moveTab(int tabId, int newIndex) {
		StorageTab tab = this.tab(tabId);
		if (tab == null) {
			return;
		}

		this.tabs.remove(tab);
		this.tabs.add(Math.clamp(newIndex, 0, this.tabs.size()), tab);
		this.tabsChanged();
		this.markChanged();
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
