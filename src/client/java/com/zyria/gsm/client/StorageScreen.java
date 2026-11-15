package com.zyria.gsm.client;

import com.zyria.gsm.block.Camouflage;
import com.zyria.gsm.menu.StorageMenu;
import net.minecraft.world.level.block.state.BlockState;
import com.zyria.gsm.network.ModNetworking;
import com.zyria.gsm.storage.StorageTab;
import com.zyria.gsm.storage.StoredEntry;
import com.zyria.gsm.storage.TabRule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * The storage menu, laid out like the Creative inventory: category tabs on the left, a search bar on
 * top, the selected tab's items in the middle, how full the system is on the right, and the player's
 * inventory at the bottom. All textures are vanilla placeholders.
 */
public class StorageScreen extends AbstractContainerScreen<StorageMenu> {
	private static final Identifier MAIN_TEXTURE = Identifier.withDefaultNamespace("textures/gui/container/creative_inventory/tab_item_search.png");
	private static final Identifier SCROLLER_SPRITE = Identifier.withDefaultNamespace("container/creative_inventory/scroller");
	private static final Identifier SCROLLER_DISABLED_SPRITE = Identifier.withDefaultNamespace("container/creative_inventory/scroller_disabled");
	private static final Identifier TAB_SPRITE = Identifier.withDefaultNamespace("recipe_book/tab");
	private static final Identifier TAB_SELECTED_SPRITE = Identifier.withDefaultNamespace("recipe_book/tab_selected");

	private static final int WIDTH = 195;
	private static final int HEIGHT = 197;
	private static final int PANEL_LIGHT = 0xFFC6C6C6;
	// Category tabs run along the top, like the Creative inventory.
	private static final int TAB_WIDTH = 26;
	private static final int TAB_HEIGHT = 32;
	private static final int TAB_SPACING = 27;
	private static final int TAB_Y = -28;
	// The Crafting upgrade's tab sits on the left; its panel opens to the left of that.
	private static final Identifier CRAFTING_TEXTURE = Identifier.withDefaultNamespace("textures/gui/container/crafting_table.png");
	private static final int SIDE_TAB_WIDTH = 35;
	private static final int SIDE_TAB_HEIGHT = 27;
	private static final int CRAFT_TAB_X = -32;
	private static final int CRAFT_TAB_Y = 4;
	private static final int CRAFT_PANEL_W = 136;
	private static final int CRAFT_PANEL_H = 82;
	/** How far the menu shifts right while the crafting panel is open, so it stays on screen. */
	private static final int CRAFT_SHIFT = 84;
	private static final int VISIBLE_TABS = 7;
	private static final int SCROLLER_X = 175;
	private static final int SCROLLER_Y = 18;
	private static final int SCROLL_AREA = 90;
	private static final int SIDE_X = WIDTH + 6;
	private static final int SIDE_WIDTH = 80;
	// Tab editor panel, drawn over the item area.
	private static final int PANEL_X = 6;
	private static final int PANEL_Y = 2;
	private static final int PANEL_W = 183;
	private static final int PANEL_H = 108;

	private static final int TEXT_DARK = 0xFF404040;
	private static final int TEXT_LIGHT = 0xFFFFFFFF;

	private EditBox searchBox;
	private Button settingsButton;
	// Settings panel (opened with the gear button). Shares the overlay area with the tab editor.
	private boolean settingsOpen;
	private Button settingsNewTab;
	private Button settingsEditTab;
	private Button autoFillButton;
	private Button settingsDone;
	private Button helpButton;
	private Button rotateButton;
	// Cached "can the disguise turn?" answer, recomputed only when the disguise item changes.
	private Item lastDisguiseItem;
	private boolean disguiseTurns;
	private Button sortButton;
	// Tab dragging.
	private int pressedTabId = -1;
	private double pressX;
	private boolean draggingTab;
	private double dragX;

	private int selectedTabId = 0;
	private int tabScroll;
	private float scrollOffs;
	private boolean scrolling;
	private List<StoredEntry> visible = List.of();
	private int seenRevision = -1;
	private boolean selectNewTab;
	private int tabCountBeforeCreate;

	// Tab editor.
	private boolean editing;
	private int editingTabId = -1;
	private boolean editSorting = true;
	private boolean removeArmed;
	private EditBox nameBox;
	private EditBox iconBox;
	private EditBox rulesBox;
	private Button sortingButton;
	private Button saveButton;
	private Button cancelButton;
	private Button removeButton;

	public StorageScreen(StorageMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, WIDTH, HEIGHT);
	}

	// ---------------------------------------------------------------- setup

	private static final ItemStack CRAFTING_ICON = new ItemStack(net.minecraft.world.item.Items.CRAFTING_TABLE);

	@Override
	protected void init() {
		super.init();
		if (this.menu.craftingOpen) {
			this.leftPos += CRAFT_SHIFT;
		}
		String previousSearch = this.searchBox != null ? this.searchBox.getValue() : "";

		this.searchBox = new EditBox(this.font, this.leftPos + 82, this.topPos + 6, 80, 9, Component.translatable("itemGroup.search"));
		this.searchBox.setMaxLength(50);
		this.searchBox.setBordered(false);
		this.searchBox.setTextColor(-1);
		this.searchBox.setValue(previousSearch);
		this.searchBox.setResponder(value -> {
			this.scrollOffs = 0;
			this.refresh();
		});
		this.addRenderableWidget(this.searchBox);

		// Small buttons in the column under the disguise slot, lined up with the inventory rows.
		int columnX = this.leftPos + StorageMenu.DISGUISE_X - 1;
		this.rotateButton = this.addRenderableWidget(Button.builder(Component.literal("\u21BB"),
				b -> ClientPlayNetworking.send(new ModNetworking.RotateDisguise(this.menu.containerId)))
			.bounds(columnX, this.topPos + 132, 18, 18)
			.tooltip(Tooltip.create(Component.translatable("gui.gsm.rotate_disguise")))
			.build());
		this.settingsButton = this.addRenderableWidget(Button.builder(Component.literal("\u2630"), b -> this.openSettings())
			.bounds(columnX, this.topPos + 150, 18, 18)
			.tooltip(Tooltip.create(Component.translatable("gui.gsm.settings")))
			.build());

		// Sort button right against the left end of the search bar (the search field starts at x = 80).
		this.sortButton = this.addRenderableWidget(Button.builder(Component.literal("\u21C5"), b -> this.cycleSortMode())
			.bounds(this.leftPos + 64, this.topPos + 3, 14, 12)
			.build());
		this.helpButton = this.addRenderableWidget(Button.builder(Component.literal("?"), b -> {
			})
			.bounds(columnX, this.topPos + 172, 18, 18)
			.tooltip(Tooltip.create(Component.translatable("gui.gsm.help")))
			.build());

		int px = this.leftPos + PANEL_X;
		int py = this.topPos + PANEL_Y;
		this.nameBox = this.addRenderableWidget(new EditBox(this.font, px + 4, py + 13, 110, 14, Component.translatable("gui.gsm.tab_name")));
		this.nameBox.setMaxLength(32);
		this.iconBox = this.addRenderableWidget(new EditBox(this.font, px + 4, py + 30, 110, 14, Component.translatable("gui.gsm.tab_icon")));
		this.iconBox.setMaxLength(100);
		this.iconBox.setHint(Component.literal("minecraft:apple"));
		this.rulesBox = this.addRenderableWidget(new EditBox(this.font, px + 4, py + 57, PANEL_W - 8, 14, Component.translatable("gui.gsm.tab_rules")));
		this.rulesBox.setMaxLength(2000);
		this.rulesBox.setHint(Component.literal("#c:ores, minecraft:coal"));
		this.sortingButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
				this.editSorting = !this.editSorting;
				this.updateEditorWidgets();
			})
			.bounds(px + 4, py + 74, 110, 16)
			.tooltip(Tooltip.create(Component.translatable("gui.gsm.sorting.tooltip")))
			.build());
		this.saveButton = this.addRenderableWidget(Button.builder(Component.translatable("gui.gsm.save"), b -> this.saveEdit())
			.bounds(px + 4, py + 91, 56, 16).build());
		this.cancelButton = this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> this.stopEditing())
			.bounds(px + 63, py + 91, 56, 16).build());
		this.removeButton = this.addRenderableWidget(Button.builder(Component.translatable("gui.gsm.remove_tab"), b -> this.removeTab())
			.bounds(px + 122, py + 91, 57, 16).build());

		// Settings panel.
		this.settingsNewTab = this.addRenderableWidget(Button.builder(Component.translatable("gui.gsm.new_tab"), b -> this.startEditing(-1))
			.bounds(px + 4, py + 26, 86, 16).build());
		this.settingsEditTab = this.addRenderableWidget(Button.builder(Component.translatable("gui.gsm.edit_tab"), b -> this.startEditing(this.selectedTabId))
			.bounds(px + 93, py + 26, 86, 16).build());
		this.autoFillButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> ClientPlayNetworking.send(
				new ModNetworking.ChangeSetting(this.menu.containerId, ModNetworking.SETTING_CRAFTING_AUTO_FILL, this.menu.craftingAutoFill() ? 0 : 1)))
			.bounds(px + 4, py + 58, PANEL_W - 8, 16)
			.tooltip(Tooltip.create(Component.translatable("gui.gsm.auto_fill.tooltip")))
			.build());
		this.settingsDone = this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.stopEditing())
			.bounds(px + 4, py + 91, 56, 16).build());

		this.updateEditorWidgets();
		this.refresh();
	}

	private void openSettings() {
		this.editing = true; // the overlay is open: the item area ignores clicks
		this.settingsOpen = true;
		this.searchBox.setFocused(false);
		this.setFocused(null);
		this.updateEditorWidgets();
	}

	@Override
	protected void containerTick() {
		super.containerTick();
		this.rotateButton.active = !this.editing && this.disguiseCanTurn();
		if (this.settingsOpen) {
			// The Crafting Upgrade can be added or removed while the panel is open.
			this.autoFillButton.visible = this.menu.hasCraftingUpgrade();
			// The setting lives on the server; show whatever it currently is.
			this.autoFillButton.setMessage(Component.translatable(this.menu.craftingAutoFill() ? "gui.gsm.auto_fill.on" : "gui.gsm.auto_fill.off"));
		}
		if (this.menu.craftingOpen && !this.menu.hasCraftingUpgrade()) {
			this.setCraftingOpen(false); // the Crafting upgrade was taken out
		}
		if (this.menu.viewRevision != this.seenRevision) {
			this.seenRevision = this.menu.viewRevision;
			if (this.selectNewTab && this.menu.tabs.size() > this.tabCountBeforeCreate) {
				this.selectNewTab = false;
				StorageTab newest = this.menu.tabs.get(this.menu.tabs.size() - 1);
				this.selectTab(newest.id);
			}

			this.refresh();
			// The tabs arrive after the screen opens, so the Edit button's state must be updated here too.
			this.updateEditorWidgets();
		}
	}

	// ---------------------------------------------------------------- data

	private StorageTab selectedTab() {
		for (StorageTab tab : this.menu.tabs) {
			if (tab.id == this.selectedTabId) {
				return tab;
			}
		}

		if (!this.menu.tabs.isEmpty()) {
			this.selectedTabId = this.menu.tabs.get(0).id;
			return this.menu.tabs.get(0);
		}

		return null;
	}

	private void selectTab(int tabId) {
		this.selectedTabId = tabId;
		this.scrollOffs = 0;
		int index = this.indexOfTab(tabId);
		if (index >= 0) {
			if (index < this.tabScroll) {
				this.tabScroll = index;
			} else if (index >= this.tabScroll + VISIBLE_TABS) {
				this.tabScroll = index - VISIBLE_TABS + 1;
			}
		}

		this.refresh();
		this.updateEditorWidgets();
	}

	private int indexOfTab(int tabId) {
		for (int i = 0; i < this.menu.tabs.size(); i++) {
			if (this.menu.tabs.get(i).id == tabId) {
				return i;
			}
		}

		return -1;
	}

	/** Rebuilds the item list: the selected tab's items in the tab's order, filtered by the search. */
	private void refresh() {
		StorageTab tab = this.selectedTab();
		if (tab == null) {
			this.visible = List.of();
			return;
		}

		Map<Item, List<StoredEntry>> byItem = new LinkedHashMap<>();
		for (StoredEntry entry : this.menu.entries) {
			byItem.computeIfAbsent(entry.stack().getItem(), item -> new ArrayList<>()).add(entry);
		}

		String query = this.searchBox == null ? "" : this.searchBox.getValue().trim().toLowerCase(Locale.ROOT);
		List<StoredEntry> result = new ArrayList<>();
		for (Identifier id : tab.entries) {
			List<StoredEntry> matches = byItem.get(BuiltInRegistries.ITEM.getValue(id));
			if (matches == null) {
				continue;
			}

			for (StoredEntry entry : matches) {
				if (query.isEmpty() || entry.stack().getHoverName().getString().toLowerCase(Locale.ROOT).contains(query)) {
					result.add(entry);
				}
			}
		}

		StorageTab.SortMode mode = tab.sortMode;
		if (mode == StorageTab.SortMode.NAME) {
			result.sort(Comparator.comparing(e -> e.stack().getHoverName().getString().toLowerCase(Locale.ROOT)));
		} else if (mode == StorageTab.SortMode.COUNT) {
			result.sort(Comparator.comparingLong(StoredEntry::count).reversed());
		} else if (mode == StorageTab.SortMode.MOD) {
			result.sort(Comparator.comparing((StoredEntry e) -> BuiltInRegistries.ITEM.getKey(e.stack().getItem()).getNamespace())
				.thenComparing(e -> e.stack().getHoverName().getString().toLowerCase(Locale.ROOT)));
		}

		this.visible = result;
		this.tabScroll = Mth.clamp(this.tabScroll, 0, Math.max(0, this.menu.tabs.size() - VISIBLE_TABS));
		if (!this.canScroll()) {
			this.scrollOffs = 0;
		}
	}

	private int hiddenRows() {
		int rows = (this.visible.size() + StorageMenu.GRID_COLUMNS - 1) / StorageMenu.GRID_COLUMNS;
		return Math.max(0, rows - StorageMenu.GRID_ROWS);
	}

	private boolean canScroll() {
		return this.hiddenRows() > 0;
	}

	private int firstRow() {
		return Math.round(this.scrollOffs * this.hiddenRows());
	}

	// ---------------------------------------------------------------- hit testing

	/** Index into {@link #visible} of the grid cell under the mouse, or -1 for an empty cell, or -2 outside the grid. */
	private int cellAt(double mx, double my) {
		int x = (int) Math.floor(mx) - this.leftPos - StorageMenu.GRID_X;
		int y = (int) Math.floor(my) - this.topPos - StorageMenu.GRID_Y;
		if (x < 0 || y < 0 || x >= StorageMenu.GRID_COLUMNS * 18 || y >= StorageMenu.GRID_ROWS * 18) {
			return -2;
		}

		int index = (this.firstRow() + y / 18) * StorageMenu.GRID_COLUMNS + x / 18;
		return index < this.visible.size() ? index : -1;
	}

	/** The tab under the mouse, or null. */
	private StorageTab tabAt(double mx, double my) {
		int x = (int) Math.floor(mx) - this.leftPos;
		int y = (int) Math.floor(my) - this.topPos - TAB_Y;
		if (x < 0 || y < 0 || y >= -TAB_Y || x % TAB_SPACING >= TAB_WIDTH) {
			return null;
		}

		int column = x / TAB_SPACING;
		int index = this.tabScroll + column;
		if (column >= VISIBLE_TABS || index >= this.menu.tabs.size()) {
			return null;
		}

		return this.menu.tabs.get(index);
	}

	/** The row of category tabs above the menu, including the scroll arrows at each end. */
	private boolean inTabColumn(double mx, double my) {
		return mx >= this.leftPos - 12 && mx < this.leftPos + VISIBLE_TABS * TAB_SPACING + 12
			&& my >= this.topPos + TAB_Y && my < this.topPos;
	}

	private int maxTabScroll() {
		return Math.max(0, this.menu.tabs.size() - VISIBLE_TABS);
	}

	/** -1 over the "scroll tabs left" arrow, 1 over "scroll tabs right", 0 elsewhere. */
	private int tabArrowAt(double mx, double my) {
		if (my < this.topPos - 22 || my >= this.topPos - 6) {
			return 0;
		}

		if (mx >= this.leftPos - 12 && mx < this.leftPos && this.tabScroll > 0) {
			return -1;
		}

		int right = this.leftPos + VISIBLE_TABS * TAB_SPACING;
		if (mx >= right && mx < right + 12 && this.tabScroll < this.maxTabScroll()) {
			return 1;
		}

		return 0;
	}

	private boolean inCraftTab(double mx, double my) {
		return this.menu.hasCraftingUpgrade()
			&& mx >= this.leftPos + CRAFT_TAB_X && mx < this.leftPos && my >= this.topPos + CRAFT_TAB_Y && my < this.topPos + CRAFT_TAB_Y + SIDE_TAB_HEIGHT;
	}

	private boolean inCraftPanel(double mx, double my) {
		int x = this.leftPos + StorageMenu.CRAFT_PANEL_X;
		int y = this.topPos + StorageMenu.CRAFT_PANEL_Y;
		return this.menu.craftingOpen && mx >= x && mx < x + CRAFT_PANEL_W && my >= y && my < y + CRAFT_PANEL_H;
	}

	private void setCraftingOpen(boolean open) {
		if (this.menu.craftingOpen != open) {
			this.menu.craftingOpen = open;
			this.rebuildWidgets(); // re-centers the menu with the panel open or closed
		}
	}

	private boolean inSidePanel(double mx, double my) {
		return mx >= this.leftPos + WIDTH && mx < this.leftPos + SIDE_X + SIDE_WIDTH && my >= this.topPos && my < this.topPos + 90;
	}

	private boolean inScroller(double mx, double my) {
		int x = this.leftPos + SCROLLER_X;
		int y = this.topPos + SCROLLER_Y;
		return mx >= x && mx < x + 14 && my >= y && my < y + SCROLL_AREA;
	}

	private boolean inEditorPanel(double mx, double my) {
		int x = this.leftPos + PANEL_X;
		int y = this.topPos + PANEL_Y;
		return mx >= x && mx < x + PANEL_W && my >= y && my < y + PANEL_H;
	}

	@Override
	protected boolean hasClickedOutside(double mx, double my, int xo, int yo) {
		return super.hasClickedOutside(mx, my, xo, yo) && !this.inTabColumn(mx, my) && !this.inSidePanel(mx, my)
			&& !this.inCraftTab(mx, my) && !this.inCraftPanel(mx, my);
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();

		if (this.editing) {
			Optional<GuiEventListener> child = this.getChildAt(mx, my);
			if (child.isPresent() && this.isEditorWidget(child.get()) && child.get().mouseClicked(event, doubleClick)) {
				this.setFocused(child.get());
				if (event.button() == 0) {
					this.setDragging(true);
				}

				return true;
			}

			if (this.inEditorPanel(mx, my)) {
				return true;
			}
		} else {
			int arrow = this.tabArrowAt(mx, my);
			if (arrow != 0) {
				this.tabScroll = Mth.clamp(this.tabScroll + arrow, 0, this.maxTabScroll());
				this.playClick();
				return true;
			}

			if (this.inCraftTab(mx, my)) {
				this.setCraftingOpen(!this.menu.craftingOpen);
				this.playClick();
				return true;
			}

			StorageTab tab = this.tabAt(mx, my);
			if (tab != null) {
				ItemStack carried = this.menu.getCarried();
				if (!carried.isEmpty()) {
					// Dropping a held item on a tab assigns that item to the tab by hand.
					ClientPlayNetworking.send(new ModNetworking.AssignItem(this.menu.containerId, tab.id, carried.copyWithCount(1), true));
				} else {
					this.selectTab(tab.id);
					// Holding and moving the mouse drags the tab to a new position.
					this.pressedTabId = tab.id;
					this.pressX = mx;
				}

				this.playClick();
				return true;
			}

			int cell = this.cellAt(mx, my);
			if (cell != -2) {
				int action = switch (event.button()) {
					case 0 -> event.hasShiftDown() ? ModNetworking.CLICK_SHIFT : ModNetworking.CLICK_LEFT;
					case 1 -> ModNetworking.CLICK_RIGHT;
					default -> -1;
				};
				if (action >= 0) {
					ItemStack clicked = cell >= 0 ? this.visible.get(cell).stack().copyWithCount(1) : ItemStack.EMPTY;
					ClientPlayNetworking.send(new ModNetworking.ClickEntry(this.menu.containerId, clicked, action));
				}

				return true;
			}

			if (this.inScroller(mx, my) && this.canScroll()) {
				this.scrolling = true;
				this.updateScroll(my);
				return true;
			}
		}

		return super.mouseClicked(event, doubleClick);
	}

	private boolean isEditorWidget(GuiEventListener listener) {
		return listener == this.nameBox || listener == this.iconBox || listener == this.rulesBox
			|| listener == this.sortingButton || listener == this.saveButton || listener == this.cancelButton
			|| listener == this.removeButton || listener == this.settingsNewTab || listener == this.settingsEditTab
			|| listener == this.autoFillButton || listener == this.settingsDone;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (event.button() == 0) {
			this.scrolling = false;
			boolean wasDragging = this.draggingTab;
			int draggedId = this.pressedTabId;
			this.pressedTabId = -1;
			this.draggingTab = false;
			if (wasDragging) {
				int from = this.indexOfTab(draggedId);
				int to = this.tabScroll + this.dropSlot(event.x());
				if (to > from) {
					to--;
				}

				to = Mth.clamp(to, 0, this.menu.tabs.size() - 1);
				if (from >= 0 && to != from) {
					ClientPlayNetworking.send(new ModNetworking.MoveTab(this.menu.containerId, draggedId, to));
				}

				return true;
			}
		}

		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (this.scrolling) {
			this.updateScroll(event.y());
			return true;
		}

		if (this.pressedTabId >= 0 && (this.draggingTab || Math.abs(event.x() - this.pressX) > 4)) {
			this.draggingTab = true;
			this.dragX = event.x();
			return true;
		}

		return super.mouseDragged(event, dx, dy);
	}

	/** The gap between visible tabs nearest the mouse (0 = left of the first visible tab). */
	private int dropSlot(double mx) {
		int slot = (int) Math.floor((mx - this.leftPos) / TAB_SPACING + 0.5);
		int visibleCount = Math.min(VISIBLE_TABS, this.menu.tabs.size() - this.tabScroll);
		return Mth.clamp(slot, 0, visibleCount);
	}

	/** Whether the block in the disguise slot can face more than one direction (logs, pillars, observers...). */
	private boolean disguiseCanTurn() {
		ItemStack item = this.menu.getSlot(StorageMenu.DISGUISE_SLOT).getItem();
		if (item.getItem() != this.lastDisguiseItem) {
			this.lastDisguiseItem = item.getItem();
			BlockState state = Camouflage.stateFor(item);
			this.disguiseTurns = state != null && this.minecraft.level != null
				&& Camouflage.orientations(state, this.minecraft.level, this.menu.modulePos).size() > 1;
		}

		return this.disguiseTurns;
	}

	private void cycleSortMode() {
		StorageTab tab = this.selectedTab();
		if (tab != null) {
			ClientPlayNetworking.send(new ModNetworking.SetSortMode(this.menu.containerId, tab.id, tab.sortMode.next().ordinal()));
		}
	}

	private void updateScroll(double my) {
		int top = this.topPos + SCROLLER_Y;
		this.scrollOffs = Mth.clamp(((float) my - top - 7.5F) / (SCROLL_AREA - 15.0F), 0.0F, 1.0F);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		if (this.editing) {
			return super.mouseScrolled(mx, my, scrollX, scrollY);
		}

		if (this.inTabColumn(mx, my)) {
			this.tabScroll = Mth.clamp(this.tabScroll - (int) Math.signum(scrollY), 0, this.maxTabScroll());
			return true;
		}

		if (this.canScroll() && (this.cellAt(mx, my) != -2 || this.inScroller(mx, my))) {
			this.scrollOffs = Mth.clamp(this.scrollOffs - (float) (scrollY / this.hiddenRows()), 0.0F, 1.0F);
			return true;
		}

		return super.mouseScrolled(mx, my, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (this.editing) {
			if (event.isEscape()) {
				this.stopEditing();
				return true;
			}

			GuiEventListener focused = this.getFocused();
			if (focused instanceof EditBox box) {
				// Keep typed letters (like the inventory key) from closing the menu.
				return box.keyPressed(event) || true;
			}

			return super.keyPressed(event);
		}

		if (this.searchBox.isFocused()) {
			if (this.searchBox.keyPressed(event)) {
				return true;
			}

			if (!event.isEscape()) {
				return true;
			}
		}

		if (event.key() == InputConstants.KEY_DELETE) {
			// Delete takes the hovered item out of the selected tab by hand.
			int cell = this.cellAt(this.lastMouseX(), this.lastMouseY());
			StorageTab tab = this.selectedTab();
			if (cell >= 0 && tab != null) {
				ClientPlayNetworking.send(new ModNetworking.AssignItem(this.menu.containerId, tab.id, this.visible.get(cell).stack().copyWithCount(1), false));
				return true;
			}
		}

		return super.keyPressed(event);
	}

	private double lastMouseX() {
		return this.minecraft.mouseHandler.getScaledXPos(this.minecraft.getWindow());
	}

	private double lastMouseY() {
		return this.minecraft.mouseHandler.getScaledYPos(this.minecraft.getWindow());
	}

	private void playClick() {
		this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
	}

	// ---------------------------------------------------------------- tab editor

	private void startEditing(int tabId) {
		this.editing = true;
		this.settingsOpen = false;
		this.editingTabId = tabId;
		this.removeArmed = false;
		StorageTab tab = tabId >= 0 ? this.selectedTab() : null;
		if (tab != null) {
			this.nameBox.setValue(tab.name);
			this.iconBox.setValue(tab.icon.getPath().equals("air") ? "" : tab.icon.toString());
			this.rulesBox.setValue(String.join(", ", tab.rules.stream().map(TabRule::toString).toList()));
			this.editSorting = tab.sorting;
		} else {
			this.editingTabId = -1;
			this.nameBox.setValue(Component.translatable("gui.gsm.default_tab_name").getString());
			this.iconBox.setValue("");
			this.rulesBox.setValue("");
			this.editSorting = true;
		}

		this.searchBox.setFocused(false);
		this.setFocused(this.nameBox);
		this.updateEditorWidgets();
	}

	private void stopEditing() {
		this.editing = false;
		this.settingsOpen = false;
		this.removeArmed = false;
		this.setFocused(null);
		this.updateEditorWidgets();
	}

	private StorageTab editingTab() {
		if (this.editingTabId < 0) {
			return null;
		}

		for (StorageTab tab : this.menu.tabs) {
			if (tab.id == this.editingTabId) {
				return tab;
			}
		}

		return null;
	}

	private void saveEdit() {
		List<String> rules = Arrays.stream(this.rulesBox.getValue().split(","))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.map(s -> s.length() > 256 ? s.substring(0, 256) : s)
			.limit(128)
			.toList();
		if (this.editingTabId < 0) {
			this.selectNewTab = true;
			this.tabCountBeforeCreate = this.menu.tabs.size();
		}

		ClientPlayNetworking.send(new ModNetworking.EditTab(this.menu.containerId, this.editingTabId,
			this.nameBox.getValue(), this.iconBox.getValue().trim(), this.editSorting, rules));
		this.stopEditing();
	}

	private void removeTab() {
		if (!this.removeArmed) {
			this.removeArmed = true;
			this.updateEditorWidgets();
			return;
		}

		ClientPlayNetworking.send(new ModNetworking.RemoveTab(this.menu.containerId, this.editingTabId));
		this.stopEditing();
	}

	private void updateEditorWidgets() {
		StorageTab tab = this.editingTab();
		boolean hasRules = tab == null || tab.kind == StorageTab.Kind.CATEGORY;

		boolean editor = this.editing && !this.settingsOpen;
		this.nameBox.setVisible(editor);
		this.iconBox.setVisible(editor);
		this.rulesBox.setVisible(editor && hasRules);
		this.sortingButton.visible = editor;
		this.saveButton.visible = editor;
		this.cancelButton.visible = editor;
		this.removeButton.visible = editor && this.editingTabId >= 0;

		this.settingsNewTab.visible = this.settingsOpen;
		this.settingsEditTab.visible = this.settingsOpen;
		this.settingsEditTab.active = this.selectedTab() != null;
		this.autoFillButton.visible = this.settingsOpen && this.menu.hasCraftingUpgrade();
		this.autoFillButton.setMessage(Component.translatable(this.menu.craftingAutoFill() ? "gui.gsm.auto_fill.on" : "gui.gsm.auto_fill.off"));
		this.settingsDone.visible = this.settingsOpen;
		this.removeButton.active = tab == null || tab.kind != StorageTab.Kind.UNSORTED;
		this.removeButton.setMessage(Component.translatable(this.removeArmed ? "gui.gsm.confirm" : "gui.gsm.remove_tab"));
		this.sortingButton.setMessage(Component.translatable(this.editSorting ? "gui.gsm.sorting.on" : "gui.gsm.sorting.off"));

		this.searchBox.setVisible(!this.editing);
		this.settingsButton.active = !this.editing;
		this.helpButton.active = !this.editing;
		this.sortButton.active = !this.editing && this.selectedTab() != null;
		StorageTab sortTab = this.selectedTab();
		if (sortTab != null) {
			this.sortButton.setTooltip(Tooltip.create(Component.translatable("gui.gsm.sort",
				Component.translatable("gui.gsm.sort." + sortTab.sortMode.name().toLowerCase(Locale.ROOT)))));
		}
	}

	// ---------------------------------------------------------------- rendering

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		int x = this.leftPos;
		int y = this.topPos;

		this.extractTabs(graphics, false);
		this.extractCraftingTab(graphics);
		this.extractPanel(graphics, x, y);
		this.extractTabs(graphics, true);
		if (this.menu.craftingOpen) {
			this.extractCraftingPanel(graphics);
		}

		int scrollerY = y + SCROLLER_Y + (int) ((SCROLL_AREA - 17) * this.scrollOffs);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, this.canScroll() ? SCROLLER_SPRITE : SCROLLER_DISABLED_SPRITE, x + SCROLLER_X, scrollerY, 12, 15);

		this.extractGrid(graphics, mouseX, mouseY);
		this.extractCapacity(graphics);

		if (this.editing) {
			graphics.nextStratum();
			int px = x + PANEL_X;
			int py = y + PANEL_Y;
			graphics.fill(px, py, px + PANEL_W, py + PANEL_H, 0xF0202020);
			graphics.outline(px, py, PANEL_W, PANEL_H, 0xFFA0A0A0);
			if (this.settingsOpen) {
				graphics.text(this.font, Component.translatable("gui.gsm.settings"), px + 4, py + 3, TEXT_LIGHT, false);
				graphics.text(this.font, Component.translatable("gui.gsm.settings.tabs"), px + 4, py + 16, 0xFFC0C0C0, false);
				if (this.menu.hasCraftingUpgrade()) {
					graphics.text(this.font, Component.translatable("gui.gsm.settings.crafting"), px + 4, py + 48, 0xFFC0C0C0, false);
				}
				return;
			}

			StorageTab tab = this.editingTab();
			Component heading = tab == null
				? Component.translatable("gui.gsm.new_tab_title")
				: Component.translatable("gui.gsm.edit_tab_title", tab.name);
			graphics.text(this.font, heading, px + 4, py + 3, TEXT_LIGHT, false);
			graphics.text(this.font, Component.translatable("gui.gsm.tab_name"), px + 120, py + 16, 0xFFC0C0C0, false);
			graphics.text(this.font, Component.translatable("gui.gsm.tab_icon"), px + 120, py + 33, 0xFFC0C0C0, false);
			if (tab == null || tab.kind == StorageTab.Kind.CATEGORY) {
				graphics.text(this.font, Component.translatable("gui.gsm.tab_rules"), px + 4, py + 47, 0xFFC0C0C0, false);
			} else {
				graphics.text(this.font, Component.translatable("gui.gsm.builtin_rules"), px + 4, py + 52, 0xFF909090, false);
			}
		}
	}

	/**
	 * Builds the panel from pieces of the Creative inventory texture: the search bar and item area on
	 * top, then the player's three inventory rows with the hotbar under them (like a chest), plus the
	 * disguise slot beside the inventory.
	 */
	private void extractPanel(GuiGraphicsExtractor graphics, int x, int y) {
		// Search bar, 5 item rows and the scrollbar track (cut short to the item area).
		this.blitMain(graphics, x, y, 0, 0, WIDTH, 107);
		this.extractBody(graphics, x, y + 107, 7);
		this.blitMain(graphics, x + 174, y + 107, 174, 128, 14, 1);
		// Three inventory rows, made from item-area rows.
		this.extractBody(graphics, x, y + 114, 54);
		this.blitMain(graphics, x + 8, y + 114, 8, 17, 162, 54);
		this.extractBody(graphics, x, y + 168, 4);
		// Hotbar row and the bottom edge (leaving out the scrollbar track).
		this.blitMain(graphics, x, y + 172, 0, 111, 174, 25);
		graphics.fill(x + 174, y + 172, x + 188, y + 190, PANEL_LIGHT);
		this.blitMain(graphics, x + 174, y + 190, 174, 129, 14, 7);
		this.blitMain(graphics, x + 188, y + 172, 188, 111, 7, 25);
		// Disguise slot frame.
		this.blitMain(graphics, x + StorageMenu.DISGUISE_X - 1, y + StorageMenu.DISGUISE_Y - 1, 8, 17, 18, 18);
	}

	/** A plain stretch of panel: left and right borders with light gray between. */
	private void extractBody(GuiGraphicsExtractor graphics, int x, int y, int height) {
		this.blitMain(graphics, x, y, 0, 20, 3, height);
		this.blitMain(graphics, x + 192, y, 192, 20, 3, height);
		graphics.fill(x + 3, y, x + 192, y + height, PANEL_LIGHT);
	}

	private void blitMain(GuiGraphicsExtractor graphics, int x, int y, int u, int v, int width, int height) {
		graphics.blit(RenderPipelines.GUI_TEXTURED, MAIN_TEXTURE, x, y, (float) u, (float) v, width, height, 256, 256);
	}

	private void extractTabs(GuiGraphicsExtractor graphics, boolean selectedPass) {
		for (int i = 0; i < VISIBLE_TABS; i++) {
			int index = this.tabScroll + i;
			if (index >= this.menu.tabs.size()) {
				break;
			}

			StorageTab tab = this.menu.tabs.get(index);
			boolean selected = tab.id == this.selectedTabId;
			if (selected != selectedPass) {
				continue;
			}

			int tx = this.leftPos + i * TAB_SPACING;
			int ty = this.topPos + TAB_Y;
			Identifier sprite = Identifier.withDefaultNamespace("container/creative_inventory/tab_top_" + (selected ? "selected_" : "unselected_") + (i + 1));
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, tx, ty, TAB_WIDTH, TAB_HEIGHT);
			graphics.item(tab.iconStack(), tx + 5, ty + 9);
		}

		if (selectedPass && this.draggingTab) {
			// Where the dragged tab will land, and the tab itself following the mouse.
			int lineX = this.leftPos + this.dropSlot(this.dragX) * TAB_SPACING - 1;
			graphics.fill(lineX, this.topPos + TAB_Y, lineX + 2, this.topPos, TEXT_LIGHT);
			int index = this.indexOfTab(this.pressedTabId);
			if (index >= 0) {
				graphics.item(this.menu.tabs.get(index).iconStack(), (int) this.dragX - 8, this.topPos + TAB_Y + 9);
			}
		}

		if (!selectedPass) {
			if (this.tabScroll > 0) {
				graphics.centeredText(this.font, "\u25C0", this.leftPos - 6, this.topPos - 18, TEXT_LIGHT);
			}

			if (this.tabScroll < this.maxTabScroll()) {
				graphics.centeredText(this.font, "\u25B6", this.leftPos + VISIBLE_TABS * TAB_SPACING + 6, this.topPos - 18, TEXT_LIGHT);
			}
		}
	}

	/** The Crafting upgrade's tab on the left side of the menu (only with the upgrade installed). */
	private void extractCraftingTab(GuiGraphicsExtractor graphics) {
		if (!this.menu.hasCraftingUpgrade()) {
			return;
		}

		boolean open = this.menu.craftingOpen;
		int tx = this.leftPos + CRAFT_TAB_X - (open ? 2 : 0);
		int ty = this.topPos + CRAFT_TAB_Y;
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, open ? TAB_SELECTED_SPRITE : TAB_SPRITE, tx, ty, SIDE_TAB_WIDTH, SIDE_TAB_HEIGHT);
		graphics.item(CRAFTING_ICON, tx + 9, ty + 5);
	}

	/** The crafting panel, built from the vanilla crafting table texture (left edge, grid area, right edge). */
	private void extractCraftingPanel(GuiGraphicsExtractor graphics) {
		int x = this.leftPos + StorageMenu.CRAFT_PANEL_X;
		int y = this.topPos + StorageMenu.CRAFT_PANEL_Y;
		graphics.blit(RenderPipelines.GUI_TEXTURED, CRAFTING_TEXTURE, x, y, 0.0F, 0.0F, 4, 78, 256, 256);
		graphics.blit(RenderPipelines.GUI_TEXTURED, CRAFTING_TEXTURE, x + 4, y, 24.0F, 0.0F, 128, 78, 256, 256);
		graphics.blit(RenderPipelines.GUI_TEXTURED, CRAFTING_TEXTURE, x + 132, y, 172.0F, 0.0F, 4, 78, 256, 256);
		graphics.blit(RenderPipelines.GUI_TEXTURED, CRAFTING_TEXTURE, x, y + 78, 0.0F, 162.0F, 4, 4, 256, 256);
		graphics.blit(RenderPipelines.GUI_TEXTURED, CRAFTING_TEXTURE, x + 4, y + 78, 24.0F, 162.0F, 128, 4, 256, 256);
		graphics.blit(RenderPipelines.GUI_TEXTURED, CRAFTING_TEXTURE, x + 132, y + 78, 172.0F, 162.0F, 4, 4, 256, 256);
		graphics.text(this.font, Component.translatable("container.crafting"), x + 8, y + 6, TEXT_DARK, false);
	}

	private void extractGrid(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int first = this.firstRow() * StorageMenu.GRID_COLUMNS;
		for (int i = 0; i < StorageMenu.GRID_COLUMNS * StorageMenu.GRID_ROWS; i++) {
			int index = first + i;
			int sx = this.leftPos + StorageMenu.GRID_X + (i % StorageMenu.GRID_COLUMNS) * 18;
			int sy = this.topPos + StorageMenu.GRID_Y + (i / StorageMenu.GRID_COLUMNS) * 18;
			if (!this.editing && isSameCell(mouseX, mouseY, sx, sy)) {
				graphics.fill(sx, sy, sx + 16, sy + 16, 0x80FFFFFF);
			}

			if (index >= this.visible.size()) {
				continue;
			}

			StoredEntry entry = this.visible.get(index);
			graphics.item(entry.stack(), sx, sy);
			graphics.itemDecorations(this.font, entry.stack(), sx, sy, formatCount(entry.count()));
		}
	}

	private static boolean isSameCell(int mouseX, int mouseY, int sx, int sy) {
		return mouseX >= sx && mouseX < sx + 18 && mouseY >= sy && mouseY < sy + 18;
	}

	private void extractCapacity(GuiGraphicsExtractor graphics) {
		int x = this.leftPos + SIDE_X;
		int y = this.topPos + 4;
		int percent = Math.round(this.menu.percentFull);
		graphics.text(this.font, Component.translatable("gui.gsm.percent_full", percent), x, y, TEXT_LIGHT, true);

		int barY = y + 12;
		graphics.fill(x, barY, x + SIDE_WIDTH, barY + 6, 0xFF000000);
		int filled = Math.round((SIDE_WIDTH - 2) * Mth.clamp(this.menu.percentFull / 100.0F, 0.0F, 1.0F));
		int color = percent >= 90 ? 0xFFD03030 : percent >= 70 ? 0xFFE0B030 : 0xFF40B040;
		graphics.fill(x + 1, barY + 1, x + 1 + filled, barY + 5, color);

		graphics.text(this.font, Component.translatable("gui.gsm.modules", this.menu.modules), x, barY + 10, 0xFFC0C0C0, true);

		// Upgrade slots (2 x 2) under the capacity readout.
		graphics.text(this.font, Component.translatable("gui.gsm.upgrades"), x, this.topPos + StorageMenu.UPGRADE_Y - 11, TEXT_LIGHT, true);
		for (int i = 0; i < 4; i++) {
			this.blitMain(graphics, this.leftPos + StorageMenu.UPGRADE_X - 1 + (i % 2) * 18, this.topPos + StorageMenu.UPGRADE_Y - 1 + (i / 2) * 18, 8, 17, 18, 18);
		}
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!this.editing) {
			graphics.text(this.font, this.title, 8, 6, TEXT_DARK, false);
		}
	}

	@Override
	protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		super.extractTooltip(graphics, mouseX, mouseY);
		if (this.editing || !this.menu.getCarried().isEmpty()) {
			return;
		}

		if (this.inCraftTab(mouseX, mouseY)) {
			graphics.setComponentTooltipForNextFrame(this.font, List.of(Component.translatable("container.crafting")), mouseX, mouseY);
			return;
		}

		if (this.hoveredSlot != null && this.hoveredSlot.index == StorageMenu.DISGUISE_SLOT && !this.hoveredSlot.hasItem()) {
			List<Component> lines = new ArrayList<>();
			lines.add(Component.translatable("gui.gsm.disguise"));
			for (String line : Component.translatable("gui.gsm.disguise.hint").getString().split("\n")) {
				lines.add(Component.literal(line).withStyle(net.minecraft.ChatFormatting.GRAY));
			}

			graphics.setComponentTooltipForNextFrame(this.font, lines, mouseX, mouseY);
			return;
		}

		int cell = this.cellAt(mouseX, mouseY);
		if (cell >= 0) {
			StoredEntry entry = this.visible.get(cell);
			List<Component> lines = new ArrayList<>(getTooltipFromItem(this.minecraft, entry.stack()));
			lines.add(Component.translatable("gui.gsm.stored", String.format(Locale.ROOT, "%,d", entry.count())).withStyle(net.minecraft.ChatFormatting.GRAY));
			graphics.setComponentTooltipForNextFrame(this.font, lines, mouseX, mouseY);
			return;
		}

		StorageTab tab = this.tabAt(mouseX, mouseY);
		if (tab != null) {
			List<Component> lines = new ArrayList<>();
			lines.add(Component.literal(tab.name));
			if (!tab.sorting) {
				lines.add(Component.translatable("gui.gsm.sorting.off").withStyle(net.minecraft.ChatFormatting.GRAY));
			}

			graphics.setComponentTooltipForNextFrame(this.font, lines, mouseX, mouseY);
		}
	}

	/** Short count text that fits a slot: 999, 1.2k, 12k, 123k, 1.2M. */
	static String formatCount(long count) {
		if (count < 1000) {
			return Long.toString(count);
		}

		if (count < 10_000) {
			return String.format(Locale.ROOT, "%.1fk", count / 1000.0);
		}

		if (count < 1_000_000) {
			return (count / 1000) + "k";
		}

		if (count < 10_000_000) {
			return String.format(Locale.ROOT, "%.1fM", count / 1_000_000.0);
		}

		return (count / 1_000_000) + "M";
	}
}
