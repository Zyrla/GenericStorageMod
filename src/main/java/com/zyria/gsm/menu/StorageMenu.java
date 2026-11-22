package com.zyria.gsm.menu;

import com.zyria.gsm.network.ModNetworking;
import com.zyria.gsm.registry.ModContent;
import com.zyria.gsm.storage.StorageSystem;
import com.zyria.gsm.storage.StorageSystemManager;
import com.zyria.gsm.storage.StorageTab;
import com.zyria.gsm.storage.StoredEntry;
import com.zyria.gsm.storage.TabRule;
import com.zyria.gsm.ModConfig;
import com.zyria.gsm.block.StorageModuleBlockEntity;
import java.util.ArrayList;
import it.unimi.dsi.fastutil.objects.Object2LongOpenCustomHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenCustomHashMap;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import com.zyria.gsm.item.UpgradeItem;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
//? if >=26.3
/*import net.minecraft.util.Prediction;*/
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * The shared menu for a storage system. Only the player's inventory uses real slots; the system's
 * items are synced as a list of distinct items with counts and moved with custom packets.
 */
public class StorageMenu extends AbstractContainerMenu {
	public static final int GRID_X = 9;
	public static final int GRID_Y = 18;
	public static final int GRID_COLUMNS = 9;
	public static final int GRID_ROWS = 5;
	public static final int INVENTORY_Y = 115;
	public static final int HOTBAR_Y = 173;
	public static final int DISGUISE_X = 174;
	public static final int DISGUISE_Y = 115;
	/** Slots 0-35 are the player's inventory; this is the opened module's disguise slot. */
	public static final int DISGUISE_SLOT = 36;
	/** The system's upgrade slots (2 x 2), in the side panel under the capacity bar. */
	public static final int UPGRADE_SLOT_START = 37;
	public static final int UPGRADE_X = 202;
	public static final int UPGRADE_Y = 49;
	/** Crafting panel (Crafting upgrade): result slot, then the 3 x 3 grid. Drawn left of the menu. */
	public static final int RESULT_SLOT = UPGRADE_SLOT_START + StorageSystem.UPGRADE_SLOTS;
	public static final int CRAFT_SLOT_START = RESULT_SLOT + 1;
	public static final int CRAFT_PANEL_X = -168;
	public static final int CRAFT_PANEL_Y = 0;

	private final Container upgrades;
	private final TransientCraftingContainer craftSlots = new TransientCraftingContainer(this, 3, 3);
	private final ResultContainer resultSlots = new ResultContainer();
	/** Client side: whether the crafting panel is open. */
	public boolean craftingOpen;

	public final BlockPos modulePos;
	private final Player player;

	// Server side: what this player's client already has.
	private StorageSystem sentSystem;
	private int sentVersion = -1;
	private int sentTabsVersion = -1;
	private Object2LongOpenCustomHashMap<ItemStack> sentCounts = new Object2LongOpenCustomHashMap<>(StorageSystem.ITEM_AND_COMPONENTS);
	private long lastSendTick = Long.MIN_VALUE;
	private boolean forceSend;

	// Client side: the latest view from the server, kept up to date by applying changes.
	private final Object2ObjectLinkedOpenCustomHashMap<ItemStack, StoredEntry> clientEntries = new Object2ObjectLinkedOpenCustomHashMap<>(StorageSystem.ITEM_AND_COMPONENTS);
	public List<StoredEntry> entries = List.of();
	public List<StorageTab> tabs = List.of();
	public int modules;
	public float percentFull;
	public int viewRevision;

	/** Client side. The disguise slot's contents arrive through normal slot syncing. */
	public StorageMenu(int containerId, Inventory inventory, BlockPos modulePos) {
		this(containerId, inventory, modulePos, new SimpleContainer(1));
	}

	public StorageMenu(int containerId, Inventory inventory, BlockPos modulePos, Container disguise) {
		super(ModContent.STORAGE_MENU, containerId);
		this.modulePos = modulePos;
		this.player = inventory.player;

		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				this.addSlot(new Slot(inventory, col + row * 9 + 9, GRID_X + col * 18, INVENTORY_Y + row * 18));
			}
		}

		for (int col = 0; col < 9; col++) {
			this.addSlot(new Slot(inventory, col, GRID_X + col * 18, HOTBAR_Y));
		}

		this.addSlot(new DisguiseSlot(disguise, this.player, modulePos, DISGUISE_X, DISGUISE_Y));

		// Server: the live system's upgrades. Client: a copy kept in sync by normal slot syncing.
		this.upgrades = this.player.level() instanceof ServerLevel serverLevel
			? new UpgradeContainer(serverLevel, modulePos)
			: new SimpleContainer(StorageSystem.UPGRADE_SLOTS);
		for (int i = 0; i < StorageSystem.UPGRADE_SLOTS; i++) {
			this.addSlot(new UpgradeSlot(this.upgrades, i, UPGRADE_X + (i % 2) * 18, UPGRADE_Y + (i / 2) * 18));
		}

		int px = CRAFT_PANEL_X;
		int py = CRAFT_PANEL_Y;
		this.addSlot(new ResultSlot(this.player, this.craftSlots, this.resultSlots, 0, px + 104, py + 35) {
			@Override
			public boolean isActive() {
				return StorageMenu.this.craftingVisible();
			}

			@Override
			public void onTake(Player player, ItemStack stack) {
				ItemStack[] before = StorageMenu.this.snapshotGrid();
				super.onTake(player, stack);
				StorageMenu.this.autoFillGrid(before);
			}
		});

		// Syncs the system's crafting auto-fill setting to the client, for the Settings panel.
		this.addDataSlot(new DataSlot() {
			@Override
			public int get() {
				StorageSystem system = StorageMenu.this.system();
				return system != null ? (system.craftingAutoFill() ? 1 : 0) : StorageMenu.this.clientAutoFill;
			}

			@Override
			public void set(int value) {
				StorageMenu.this.clientAutoFill = value;
			}
		});
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 3; col++) {
				this.addSlot(new Slot(this.craftSlots, col + row * 3, px + 10 + col * 18, py + 17 + row * 18) {
					@Override
					public boolean isActive() {
						return StorageMenu.this.craftingVisible();
					}
				});
			}
		}
	}

	/** Client side: the system's crafting auto-fill setting (1 = on), synced through a data slot. */
	private int clientAutoFill = 1;

	public boolean craftingAutoFill() {
		StorageSystem system = this.system();
		return system != null ? system.craftingAutoFill() : this.clientAutoFill != 0;
	}

	private ItemStack[] snapshotGrid() {
		ItemStack[] copy = new ItemStack[this.craftSlots.getContainerSize()];
		for (int i = 0; i < copy.length; i++) {
			copy[i] = this.craftSlots.getItem(i).copy();
		}

		return copy;
	}

	/**
	 * Crafting auto-fill: after a craft, tops each grid slot back up from storage with the same item it
	 * held before, so the same recipe stays ready. Slots holding leftovers (like empty buckets) are skipped.
	 */
	private void autoFillGrid(ItemStack[] before) {
		StorageSystem system = this.system();
		if (system == null || !system.craftingAutoFill()) {
			return;
		}

		for (int i = 0; i < before.length; i++) {
			ItemStack wanted = before[i];
			ItemStack current = this.craftSlots.getItem(i);
			if (wanted.isEmpty() || current.getCount() >= wanted.getCount()) {
				continue;
			}

			if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, wanted)) {
				continue; // something else (a leftover) is in the slot now
			}

			ItemStack refill = system.extract(wanted, wanted.getCount() - current.getCount());
			if (refill.isEmpty()) {
				continue;
			}

			if (current.isEmpty()) {
				this.craftSlots.setItem(i, refill);
			} else {
				current.grow(refill.getCount());
				this.craftSlots.setItem(i, current);
			}
		}
	}

	public void handleSetting(int setting, int value) {
		StorageSystem system = this.system();
		if (system != null && setting == ModNetworking.SETTING_CRAFTING_AUTO_FILL) {
			system.setCraftingAutoFill(value != 0);
		}
	}

	/** Whether the system has a Crafting upgrade. Works on both sides (upgrade slots are synced). */
	public boolean hasCraftingUpgrade() {
		for (int i = 0; i < this.upgrades.getContainerSize(); i++) {
			if (UpgradeItem.is(this.upgrades.getItem(i), UpgradeItem.Type.CRAFTING)) {
				return true;
			}
		}

		return false;
	}

	/** Crafting slots only work with the upgrade in, and on the client only while the panel is open. */
	private boolean craftingVisible() {
		return this.hasCraftingUpgrade() && (!this.player.level().isClientSide() || this.craftingOpen);
	}

	// ---------------------------------------------------------------- crafting

	@Override
	public void slotsChanged(Container container) {
		super.slotsChanged(container);
		if (container == this.craftSlots && this.player instanceof ServerPlayer serverPlayer
			&& serverPlayer.level() instanceof ServerLevel level) {
			this.updateCraftingResult(serverPlayer, level);
		}
	}

	/** Same as a crafting table: find the recipe for the grid and show its result. */
	private void updateCraftingResult(ServerPlayer player, ServerLevel level) {
		CraftingInput input = this.craftSlots.asCraftInput();
		ItemStack result = ItemStack.EMPTY;
		Optional<RecipeHolder<CraftingRecipe>> recipe = level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level);
		if (recipe.isPresent() && this.resultSlots.setRecipeUsed(player, recipe.get())) {
			ItemStack assembled = recipe.get().value().assemble(input);
			if (assembled.isItemEnabled(level.enabledFeatures())) {
				result = assembled;
			}
		}

		this.resultSlots.setItem(0, result);
		this.setRemoteSlot(RESULT_SLOT, result);
		player.connection.send(new ClientboundContainerSetSlotPacket(this.containerId, this.incrementStateId(), RESULT_SLOT, result));
	}

	/** Moves everything in the crafting grid back into storage (or the player's inventory if storage is full). */
	private void returnCraftingGrid(Player player) {
		StorageSystem system = this.system();
		for (int i = 0; i < this.craftSlots.getContainerSize(); i++) {
			ItemStack stack = this.craftSlots.removeItemNoUpdate(i);
			if (stack.isEmpty()) {
				continue;
			}

			ItemStack rest = system != null ? system.insert(stack) : stack;
			if (!rest.isEmpty()) {
				//? if >=26.3 {
				/*player.getInventory().placeItemBackInInventory(rest, Prediction.SERVER_ONLY);
				*///?} else
				player.getInventory().placeItemBackInInventory(rest);
			}
		}
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		if (!player.level().isClientSide()) {
			this.returnCraftingGrid(player);
		}
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack carried, Slot target) {
		return target.container != this.resultSlots && super.canTakeItemForPickAll(carried, target);
	}

	private StorageSystem system() {
		if (this.player.level() instanceof ServerLevel serverLevel) {
			return StorageSystemManager.get(serverLevel).systemAt(this.modulePos);
		}

		return null;
	}

	@Override
	public boolean stillValid(Player player) {
		if (player.level().isClientSide()) {
			return true;
		}

		return player.level().getBlockState(this.modulePos).is(ModContent.STORAGE_MODULE)
			&& this.system() != null
			&& player.isWithinBlockInteractionRange(this.modulePos, 4.0);
	}

	/**
	 * Shift-click. From the player's inventory: upgrades go to the upgrade slots, everything else into
	 * storage. From the disguise, upgrade or crafting slots: back to the player's inventory. The crafting
	 * result crafts as many as fit, like a crafting table.
	 */
	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		Slot slot = this.slots.get(slotIndex);
		if (!slot.hasItem()) {
			return ItemStack.EMPTY;
		}

		if (slotIndex == RESULT_SLOT) {
			ItemStack stack = slot.getItem();
			ItemStack crafted = stack.copy();
			stack.getItem().onCraftedBy(stack, player);
			if (!this.moveItemStackTo(stack, 0, 36, true)) {
				return ItemStack.EMPTY;
			}

			slot.onQuickCraft(stack, crafted);
			if (stack.isEmpty()) {
				slot.setByPlayer(ItemStack.EMPTY);
			} else {
				slot.setChanged();
			}

			if (stack.getCount() == crafted.getCount()) {
				return ItemStack.EMPTY;
			}

			slot.onTake(player, stack);
			//? if >=26.3 {
			/*player.drop(stack, false, Prediction.SERVER_ONLY);
			*///?} else
			player.drop(stack, false);
			return crafted; // returning the craft makes the game repeat it until the grid runs out
		}

		if (slotIndex >= DISGUISE_SLOT) {
			// Disguise, upgrade and crafting grid slots: back to the player's inventory.
			if (slot.mayPickup(player)) {
				ItemStack stack = slot.getItem().copy();
				if (this.moveItemStackTo(stack, 0, 36, false)) {
					slot.setByPlayer(stack.isEmpty() ? ItemStack.EMPTY : stack);
				}
			}

			return ItemStack.EMPTY;
		}

		if (slot.getItem().getItem() instanceof UpgradeItem) {
			ItemStack stack = slot.getItem();
			if (this.moveItemStackTo(stack, UPGRADE_SLOT_START, RESULT_SLOT, false)) {
				slot.setChanged();
				return ItemStack.EMPTY;
			}
		}

		StorageSystem system = this.system();
		if (system != null && slot.hasItem()) {
			ItemStack rest = system.insert(slot.getItem());
			slot.setByPlayer(rest);
			slot.setChanged();
		}

		return ItemStack.EMPTY;
	}

	@Override
	public void broadcastChanges() {
		if (!this.player.level().isClientSide() && !this.craftSlots.isEmpty() && !this.hasCraftingUpgrade()) {
			// The Crafting upgrade was taken out: give back whatever was left in the grid.
			this.returnCraftingGrid(this.player);
		}

		super.broadcastChanges();
		if (!(this.player instanceof ServerPlayer serverPlayer)) {
			return;
		}

		StorageSystem system = this.system();
		if (system == null || system == this.sentSystem && system.version() == this.sentVersion) {
			return;
		}

		// While hoppers keep moving items, only update every few ticks. The player's own clicks show at once.
		long now = serverPlayer.level().getGameTime();
		boolean full = system != this.sentSystem;
		if (!full && !this.forceSend && now - this.lastSendTick < ModConfig.get().sync_interval_ticks) {
			return;
		}

		this.forceSend = false;
		this.lastSendTick = now;

		// Only send items whose count changed (0 means the item is gone).
		List<StoredEntry> changes = new ArrayList<>();
		List<StoredEntry> all = system.aggregate();
		Object2LongOpenCustomHashMap<ItemStack> current = new Object2LongOpenCustomHashMap<>(all.size(), StorageSystem.ITEM_AND_COMPONENTS);
		for (StoredEntry entry : all) {
			current.put(entry.stack(), entry.count());
			if (full || this.sentCounts.getLong(entry.stack()) != entry.count()) {
				changes.add(entry);
			}
		}

		if (!full) {
			for (ItemStack key : this.sentCounts.keySet()) {
				if (!current.containsKey(key)) {
					changes.add(new StoredEntry(key, 0));
				}
			}
		}

		this.sentCounts = current;

		// Tabs only go along when they changed. Copies: in singleplayer the payload reaches the client as-is.
		Optional<List<StorageTab>> tabs = full || system.tabsVersion() != this.sentTabsVersion
			? Optional.of(system.copyTabs())
			: Optional.empty();

		this.sentSystem = system;
		this.sentVersion = system.version();
		this.sentTabsVersion = system.tabsVersion();
		ServerPlayNetworking.send(serverPlayer, new ModNetworking.SyncView(
			this.containerId, full, changes, tabs, system.moduleCount(), system.percentFull()
		));
	}

	/** Makes the next update go out right away, for changes the player made themselves. */
	private void sendNow() {
		this.forceSend = true;
		this.broadcastChanges();
	}

	// ---------------------------------------------------------------- client

	public void applyView(ModNetworking.SyncView view) {
		if (view.full()) {
			this.clientEntries.clear();
		}

		for (StoredEntry entry : view.changes()) {
			if (entry.count() <= 0) {
				this.clientEntries.remove(entry.stack());
			} else {
				this.clientEntries.put(entry.stack(), entry);
			}
		}

		this.entries = List.copyOf(this.clientEntries.values());
		view.tabs().ifPresent(tabs -> this.tabs = tabs);
		this.modules = view.modules();
		this.percentFull = view.percentFull();
		this.viewRevision++;
	}

	// ---------------------------------------------------------------- server packet handlers

	public void handleEntryClick(ServerPlayer player, ItemStack clicked, int action) {
		StorageSystem system = this.system();
		if (system == null) {
			return;
		}

		ItemStack carried = this.getCarried();
		switch (action) {
			case ModNetworking.CLICK_LEFT -> {
				if (!carried.isEmpty()) {
					this.setCarried(system.insert(carried));
				} else if (!clicked.isEmpty()) {
					this.setCarried(system.extract(clicked, clicked.getMaxStackSize()));
				}
			}
			case ModNetworking.CLICK_RIGHT -> {
				if (!carried.isEmpty()) {
					ItemStack rest = system.insert(carried.copyWithCount(1));
					if (rest.isEmpty()) {
						ItemStack newCarried = carried.copy();
						newCarried.shrink(1);
						this.setCarried(newCarried);
					}
				} else if (!clicked.isEmpty()) {
					long available = Math.min(system.count(clicked), clicked.getMaxStackSize());
					int half = (int) ((available + 1) / 2);
					this.setCarried(system.extract(clicked, half));
				}
			}
			case ModNetworking.CLICK_SHIFT -> {
				if (!clicked.isEmpty()) {
					ItemStack taken = system.extract(clicked, clicked.getMaxStackSize());
					player.getInventory().add(taken);
					if (!taken.isEmpty()) {
						ItemStack rest = system.insert(taken);
						if (!rest.isEmpty()) {
							//? if >=26.3 {
							/*player.drop(rest, false, Prediction.SERVER_ONLY);
							*///?} else
							player.drop(rest, false);
						}
					}
				}
			}
			default -> {
			}
		}

		this.sendNow();
	}

	public void handleAssign(int tabId, ItemStack stack, boolean add) {
		StorageSystem system = this.system();
		if (system != null && !stack.isEmpty()) {
			system.assign(tabId, stack.getItem(), add);
			this.sendNow();
		}
	}

	public void handleEditTab(int tabId, String name, String icon, boolean sorting, List<String> ruleTexts) {
		StorageSystem system = this.system();
		if (system == null) {
			return;
		}

		String cleanName = name.strip();
		if (cleanName.isEmpty()) {
			cleanName = "Tab";
		}

		if (cleanName.length() > 32) {
			cleanName = cleanName.substring(0, 32);
		}

		Identifier iconId = icon.isBlank() ? null : Identifier.tryParse(icon.strip());
		if (iconId == null) {
			iconId = Identifier.withDefaultNamespace("air");
		}

		List<TabRule> rules = new ArrayList<>();
		for (String text : ruleTexts) {
			if (!text.isBlank()) {
				TabRule.parse(text).result().ifPresent(rules::add);
			}
		}

		if (tabId >= 0 ? system.tab(tabId) == null : system.tabs.size() >= StorageTab.MAX_TABS) {
			return;
		}

		system.editTab(tabId, cleanName, iconId, sorting, rules);
		this.sendNow();
	}

	public void handleRemoveTab(int tabId) {
		StorageSystem system = this.system();
		if (system != null) {
			system.removeTab(tabId);
			this.sendNow();
		}
	}

	public void handleSortMode(int tabId, int mode) {
		StorageSystem system = this.system();
		StorageTab.SortMode[] modes = StorageTab.SortMode.values();
		if (system != null && mode >= 0 && mode < modes.length) {
			system.setSortMode(tabId, modes[mode]);
			this.sendNow();
		}
	}

	/** Turns the opened module's disguise to its next direction. */
	public void handleRotateDisguise() {
		if (this.player.level().getBlockEntity(this.modulePos) instanceof StorageModuleBlockEntity module) {
			module.rotateDisguise();
		}
	}
}
