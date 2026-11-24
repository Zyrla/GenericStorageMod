package com.zyria.gsm.client;

import com.zyria.gsm.block.Camouflage;
import com.zyria.gsm.menu.StorageMenu;
import net.minecraft.world.level.block.state.BlockState;
import com.zyria.gsm.network.ModNetworking;
import com.zyria.gsm.storage.StorageSystem;
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
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.recipebook.CraftingRecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.world.inventory.ContainerInput;
import com.zyria.gsm.mixin.client.RecipeBookComponentInvoker;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
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
 * The storage menu, laid out like the Creative inventory: category tabs along the top, a search bar,
 * the selected tab's items in the middle, the player's inventory at the bottom with the upgrade slots
 * on a strip beside it, and how full the system is under the menu. Drawn with vanilla textures, so
 * resource packs restyle it too.
 */
public class StorageScreen extends AbstractContainerScreen<StorageMenu> implements RecipeUpdateListener {
	private static final Identifier MAIN_TEXTURE = Identifier.withDefaultNamespace("textures/gui/container/creative_inventory/tab_item_search.png");
	private static final Identifier SCROLLER_SPRITE = Identifier.withDefaultNamespace("container/creative_inventory/scroller");
	private static final Identifier SCROLLER_DISABLED_SPRITE = Identifier.withDefaultNamespace("container/creative_inventory/scroller_disabled");

	private static final int WIDTH = 195;
	private static final int HEIGHT = 197;
	// Category tabs run along the top, like the Creative inventory.
	private static final int TAB_WIDTH = 26;
	private static final int TAB_HEIGHT = 32;
	private static final int TAB_SPACING = 27;
	private static final int TAB_Y = -28;
	// The Crafting upgrade's tab sticks out of the right side; its panel opens on the left of the menu.
	private static final int CRAFT_TAB_RIGHT = WIDTH + 28;
	private static final int CRAFT_TAB_Y = 4;
	private static final int CRAFT_TAB_HEIGHT = 26;
	/** How far the menu shifts right while the crafting panel is open, so it stays on screen. */
	private static final Identifier CRAFTING_TEXTURE = Identifier.withDefaultNamespace("textures/gui/container/crafting_table.png");
	private static final int CRAFT_SHIFT = 75;
	/** How far the menu moves down while the crafting panel is open, since the panel is taller than the menu. */
	private static final int CRAFT_SHIFT_Y = 10;
	/** Where the recipe book starts, under the crafting grid (relative to the menu's top). */
	private static final int BOOK_Y = 44;
	private static final int VISIBLE_TABS = 7;
	private static final Identifier[] TAB_SPRITES = tabSprites("unselected_");
	private static final Identifier[] TAB_SELECTED_SPRITES = tabSprites("selected_");
	private static final int SCROLLER_X = 175;
	private static final int SCROLLER_Y = 18;
	private static final int SCROLL_AREA = 90;
	// The upgrade strip sticks out of the right side, beside the inventory.
	private static final int EXT_RIGHT = WIDTH + 24;
	private static final int EXT_TOP = 109;
	private static final int EXT_BOTTOM = 191;
	// The fullness readout sits under the menu.
	private static final int FULLNESS_Y = HEIGHT + 3;
	private static final int FULLNESS_H = 10;
	// Tab editor panel, drawn over the item area.
	private static final int PANEL_X = 6;
	private static final int PANEL_Y = 2;
	private static final int PANEL_W = 183;
	private static final int PANEL_H = 108;

	private static final int TEXT_DARK = 0xFF404040;
	private static final int TEXT_LIGHT = 0xFFFFFFFF;

	private EditBox searchBox;
	/** Vanilla's recipe book, under the crafting grid left of the menu (Crafting upgrade). */
	private final CraftingRecipeBookComponent recipeBook;
	/** Opens and closes the recipe book, under the crafting arrow like on a crafting table. */
	private ImageButton recipeBookButton;
	private Button settingsButton;
	// Settings panel (opened with the gear button). Shares the overlay area with the tab editor.
	private boolean settingsOpen;
	private Button settingsNewTab;
	private Button settingsEditTab;
	private Button autoFillButton;
	private Button settingsDone;
	private Button fullnessButton;
	private Button helpButton;
	private Button rotateButton;
	// Cached "can the disguise turn?" answer, recomputed only when the disguise item changes.
	private Item lastDisguiseItem;
	private boolean disguiseTurns;
	private Button sortButton;
	private int selectedTabId = 0;
	private int tabScroll;
	/** The first item row shown. Kept as a row, not a fraction, so items coming and going don't move the view. */
	private int scrollRow;
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
		this.recipeBook = new CraftingRecipeBookComponent(menu);
	}

	// ---------------------------------------------------------------- setup

	private static final ItemStack CRAFTING_ICON = new ItemStack(net.minecraft.world.item.Items.CRAFTING_TABLE);

	@Override
	protected void init() {
		super.init();
		if (this.menu.craftingOpen) {
			this.leftPos += CRAFT_SHIFT;
			this.topPos += CRAFT_SHIFT_Y;
		}

		this.initRecipeBook();
		String previousSearch = this.searchBox != null ? this.searchBox.getValue() : "";

		this.searchBox = new EditBox(this.font, this.leftPos + 82, this.topPos + 6, 80, 9, Component.translatable("itemGroup.search"));
		this.searchBox.setMaxLength(50);
		this.searchBox.setBordered(false);
		this.searchBox.setTextColor(-1);
		this.searchBox.setValue(previousSearch);
		this.searchBox.setResponder(value -> {
			this.scrollRow = 0;
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
		this.fullnessButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
				ClientSettings.setShowFullness(!ClientSettings.showFullness());
				this.updateEditorWidgets();
			})
			.bounds(px + 63, py + 91, 96, 16)
			.tooltip(Tooltip.create(Component.translatable("gui.gsm.fullness.tooltip")))
			.build());
		// Help sits in the settings panel's bottom-right corner; hovering it shows the tips.
		this.helpButton = this.addRenderableWidget(Button.builder(Component.literal("?"), b -> {
			})
			.bounds(px + PANEL_W - 20, py + 91, 16, 16)
			.tooltip(Tooltip.create(Component.translatable("gui.gsm.help")))
			.build());

		this.updateEditorWidgets();
		this.refresh();
	}

	/** Whether the recipe book is showing: the crafting panel is open and the book is switched on. */
	private boolean bookShown() {
		return this.menu.craftingOpen && this.menu.hasCraftingUpgrade() && this.recipeBook.isVisible();
	}

	/**
	 * Sets up vanilla's recipe book under the crafting grid. The book places itself from the screen size it
	 * is given, so it gets a made-up size that puts it exactly there. It is open or closed the same as the
	 * crafting table's book (the button under the crafting arrow switches it).
	 */
	private void initRecipeBook() {
		int x = this.leftPos + StorageMenu.CRAFT_PANEL_X;
		int y = this.topPos + BOOK_Y;
		// The book puts itself at ((width - 147) / 2 - 86, (height - 166) / 2).
		this.recipeBook.init(2 * (x + 86) + RecipeBookComponent.IMAGE_WIDTH, 2 * y + RecipeBookComponent.IMAGE_HEIGHT, this.minecraft, false);
		this.addWidget(this.recipeBook);
		int gx = x;
		int gy = this.topPos + StorageMenu.CRAFT_PANEL_Y;
		this.recipeBookButton = this.addRenderableWidget(new ImageButton(gx + 72, gy + 50, 20, 18, RecipeBookComponent.RECIPE_BUTTON_SPRITES,
			b -> this.recipeBook.toggleVisibility()));
		this.recipeBookButton.visible = this.menu.craftingOpen && this.menu.hasCraftingUpgrade();
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		return !this.editing && this.bookShown() && this.recipeBook.charTyped(event) || super.charTyped(event);
	}

	@Override
	protected void slotClicked(Slot slot, int slotId, int buttonNum, ContainerInput input) {
		super.slotClicked(slot, slotId, buttonNum, input);
		if (this.bookShown()) {
			this.recipeBook.slotClicked(slot);
		}
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
		this.recipeBook.tick();
		if (this.menu.craftingOpen && !this.menu.hasCraftingUpgrade()) {
			this.setCraftingOpen(false); // the Crafting upgrade was taken out
		}
		if (this.menu.viewRevision != this.seenRevision) {
			this.seenRevision = this.menu.viewRevision;
			if (this.bookShown()) {
				// Storage changed: recount which recipes can be made.
				((RecipeBookComponentInvoker) this.recipeBook).gsm$updateStackedContents();
			}

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
		this.scrollRow = 0;
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

		// Each item's name is worked out once here, not again for every comparison while sorting.
		StorageTab.SortMode mode = tab.sortMode;
		String query = this.searchBox == null ? "" : this.searchBox.getValue().trim().toLowerCase(Locale.ROOT);
		boolean needNames = !query.isEmpty() || mode == StorageTab.SortMode.NAME || mode == StorageTab.SortMode.MOD;
		List<Row> rows = new ArrayList<>();
		for (Identifier id : tab.entries) {
			List<StoredEntry> matches = byItem.get(BuiltInRegistries.ITEM.getValue(id));
			if (matches == null) {
				continue;
			}

			for (StoredEntry entry : matches) {
				String name = needNames ? entry.stack().getHoverName().getString().toLowerCase(Locale.ROOT) : "";
				if (query.isEmpty() || name.contains(query)) {
					rows.add(new Row(entry, name, id.getNamespace()));
				}
			}
		}

		if (mode == StorageTab.SortMode.NAME) {
			rows.sort(Comparator.comparing(Row::name));
		} else if (mode == StorageTab.SortMode.COUNT) {
			rows.sort(Comparator.comparingLong((Row r) -> r.entry().count()).reversed());
		} else if (mode == StorageTab.SortMode.MOD) {
			rows.sort(Comparator.comparing(Row::namespace).thenComparing(Row::name));
		}

		List<StoredEntry> result = new ArrayList<>(rows.size());
		for (Row row : rows) {
			result.add(row.entry());
		}

		this.visible = result;
		this.tabScroll = Mth.clamp(this.tabScroll, 0, Math.max(0, this.menu.tabs.size() - VISIBLE_TABS));
	}

	private record Row(StoredEntry entry, String name, String namespace) {
	}

	private int hiddenRows() {
		int rows = (this.visible.size() + StorageMenu.GRID_COLUMNS - 1) / StorageMenu.GRID_COLUMNS;
		return Math.max(0, rows - StorageMenu.GRID_ROWS);
	}

	private boolean canScroll() {
		return this.hiddenRows() > 0;
	}

	private int firstRow() {
		// Clamped when read, not stored, so a list that is briefly shorter during an update doesn't lose the position.
		return Mth.clamp(this.scrollRow, 0, this.hiddenRows());
	}

	private float scrollFraction() {
		int hidden = this.hiddenRows();
		return hidden > 0 ? (float) this.firstRow() / hidden : 0.0F;
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
			&& mx >= this.leftPos + WIDTH && mx < this.leftPos + CRAFT_TAB_RIGHT && my >= this.topPos + CRAFT_TAB_Y && my < this.topPos + CRAFT_TAB_Y + CRAFT_TAB_HEIGHT;
	}

	private boolean inCraftPanel(double mx, double my) {
		int x = this.leftPos + StorageMenu.CRAFT_PANEL_X;
		int bottom = this.topPos + (this.bookShown() ? BOOK_Y + RecipeBookComponent.IMAGE_HEIGHT : BOOK_Y);
		return this.menu.craftingOpen && mx >= x - (this.bookShown() ? 30 : 0) && mx < x + RecipeBookComponent.IMAGE_WIDTH
			&& my >= this.topPos + StorageMenu.CRAFT_PANEL_Y && my < bottom;
	}

	private void setCraftingOpen(boolean open) {
		if (this.menu.craftingOpen != open) {
			this.menu.craftingOpen = open;
			this.rebuildWidgets(); // re-centers the menu with the panel open or closed
		}
	}

	private boolean inUpgradeStrip(double mx, double my) {
		return mx >= this.leftPos + WIDTH && mx < this.leftPos + EXT_RIGHT && my >= this.topPos + EXT_TOP && my < this.topPos + EXT_BOTTOM;
	}

	private boolean inFullness(double mx, double my) {
		return ClientSettings.showFullness() && mx >= this.leftPos && mx < this.leftPos + WIDTH
			&& my >= this.topPos + HEIGHT && my < this.topPos + FULLNESS_Y + FULLNESS_H;
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
		return super.hasClickedOutside(mx, my, xo, yo) && !this.inTabColumn(mx, my) && !this.inUpgradeStrip(mx, my) && !this.inFullness(mx, my)
			&& !this.inCraftTab(mx, my) && !this.inCraftPanel(mx, my);
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		// A scrollbar drag only lasts until the next click. If its release was missed, dragging an item
		// would otherwise move the scrollbar to the mouse's height.
		this.scrolling = false;

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
		} else if (this.bookShown() && this.recipeBook.mouseClicked(event, doubleClick)) {
			this.setFocused(this.recipeBook);
			return true;
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
				if (event.button() == 1 && !carried.isEmpty()) {
					// Right-clicking a tab while holding an item adds that item to the tab by hand.
					ClientPlayNetworking.send(new ModNetworking.AssignItem(this.menu.containerId, tab.id, carried.copyWithCount(1), true));
				} else {
					this.selectTab(tab.id);
				}

				this.playClick();
				return true;
			}

			int cell = this.cellAt(mx, my);
			if (cell != -2) {
				// Shift-click (either button) moves a full stack straight into the player's inventory.
				boolean shift = event.hasShiftDown() || this.minecraft.hasShiftDown();
				int action = switch (event.button()) {
					case 0 -> shift ? ModNetworking.CLICK_SHIFT : ModNetworking.CLICK_LEFT;
					case 1 -> shift ? ModNetworking.CLICK_SHIFT : ModNetworking.CLICK_RIGHT;
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
			|| listener == this.autoFillButton || listener == this.settingsDone || listener == this.fullnessButton
			|| listener == this.helpButton;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (event.button() == 0) {
			this.scrolling = false;
		}

		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (this.scrolling && event.button() == 0) {
			this.updateScroll(event.y());
			return true;
		}

		if (this.bookShown() && this.recipeBook.mouseDragged(event, dx, dy)) {
			return true;
		}

		return super.mouseDragged(event, dx, dy);
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
		float fraction = Mth.clamp(((float) my - top - 7.5F) / (SCROLL_AREA - 15.0F), 0.0F, 1.0F);
		this.scrollRow = Math.round(fraction * this.hiddenRows());
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
			this.scrollRow = Mth.clamp(this.firstRow() - (int) Math.signum(scrollY), 0, this.hiddenRows());
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

		if (this.bookShown() && this.recipeBook.keyPressed(event)) {
			return true;
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
		this.settingsNewTab.active = this.menu.tabs.size() < StorageTab.MAX_TABS;
		this.settingsEditTab.visible = this.settingsOpen;
		this.settingsEditTab.active = this.selectedTab() != null;
		this.autoFillButton.visible = this.settingsOpen && this.menu.hasCraftingUpgrade();
		this.autoFillButton.setMessage(Component.translatable(this.menu.craftingAutoFill() ? "gui.gsm.auto_fill.on" : "gui.gsm.auto_fill.off"));
		this.settingsDone.visible = this.settingsOpen;
		this.fullnessButton.visible = this.settingsOpen;
		this.fullnessButton.setMessage(Component.translatable(ClientSettings.showFullness() ? "gui.gsm.fullness.on" : "gui.gsm.fullness.off"));
		this.helpButton.visible = this.settingsOpen;
		this.removeButton.active = tab == null || tab.kind != StorageTab.Kind.UNSORTED;
		this.removeButton.setMessage(Component.translatable(this.removeArmed ? "gui.gsm.confirm" : "gui.gsm.remove_tab"));
		this.sortingButton.setMessage(Component.translatable(this.editSorting ? "gui.gsm.sorting.on" : "gui.gsm.sorting.off"));

		this.searchBox.setVisible(!this.editing);
		this.settingsButton.active = !this.editing;
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
		this.extractCraftingTab(graphics, false);
		this.extractPanel(graphics, x, y);
		this.extractCraftingTab(graphics, true);
		this.extractTabs(graphics, true);
		if (this.menu.craftingOpen) {
			this.extractCraftingGrid(graphics);
		}

		int scrollerY = y + SCROLLER_Y + (int) ((SCROLL_AREA - 17) * this.scrollFraction());
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
				Component modules = Component.translatable("gui.gsm.modules", this.menu.modules);
				graphics.text(this.font, modules, px + PANEL_W - 4 - this.font.width(modules), py + 3, 0xFFC0C0C0, false);
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
		blitMain(graphics, x, y, 0, 0, WIDTH, 107);
		this.extractBody(graphics, x, y + 107, 7);
		blitMain(graphics, x + 174, y + 107, 174, 128, 14, 1);
		// Three inventory rows, made from item-area rows.
		this.extractBody(graphics, x, y + 114, 54);
		blitMain(graphics, x + 8, y + 114, 8, 17, 162, 54);
		this.extractBody(graphics, x, y + 168, 4);
		// Hotbar row and the bottom edge (leaving out the scrollbar track).
		blitMain(graphics, x, y + 172, 0, 111, 174, 25);
		extractFill(graphics, x + 174, y + 172, 14, 18);
		blitMain(graphics, x + 174, y + 190, 174, 129, 14, 7);
		blitMain(graphics, x + 188, y + 172, 188, 111, 7, 25);
		// Disguise slot frame.
		blitMain(graphics, x + StorageMenu.DISGUISE_X - 1, y + StorageMenu.DISGUISE_Y - 1, 8, 17, 18, 18);
		this.extractUpgradeStrip(graphics, x, y);
	}

	/**
	 * The strip on the right holding the upgrade slots, joined onto the menu's right edge. Its edges and
	 * corners are cut from the same texture's borders, so it matches the menu (and resource packs).
	 */
	private void extractUpgradeStrip(GuiGraphicsExtractor graphics, int x, int y) {
		this.extractStrip(graphics, x, y + EXT_TOP, y + EXT_BOTTOM, x + EXT_RIGHT);
		for (int i = 0; i < StorageSystem.UPGRADE_SLOTS; i++) {
			blitMain(graphics, x + StorageMenu.UPGRADE_X - 1, y + StorageMenu.UPGRADE_Y - 1 + i * 18, 8, 17, 18, 18);
		}
	}

	/** A strip sticking out of the menu's right side (menu at {@code x}), open into the menu. */
	private void extractStrip(GuiGraphicsExtractor graphics, int x, int top, int bottom, int right) {
		// Top and bottom edges start at the menu's outer border column, so the outlines join up.
		blitMain(graphics, x + 194, top, 100, 0, right - 3 - (x + 194), 3);
		blitMain(graphics, x + 194, bottom - 3, 100, 133, right - 3 - (x + 194), 3);
		// Covers the menu's right border between them, opening the strip into the menu.
		extractFill(graphics, x + 192, top + 3, right - 3 - (x + 192), bottom - top - 6);
		blitMain(graphics, right - 3, top + 3, 192, 20, 3, bottom - top - 6);
		blitMain(graphics, right - 3, top, 192, 0, 3, 3);
		blitMain(graphics, right - 3, bottom - 3, 192, 133, 3, 3);
	}

	/**
	 * The crafting grid on top of the recipe book: label, 3 x 3 slot frames, arrow and the big result slot
	 * (those two from the crafting table's texture). Its bottom edge tucks under the book.
	 */
	private void extractCraftingGrid(GuiGraphicsExtractor graphics) {
		int x = this.leftPos + StorageMenu.CRAFT_PANEL_X;
		int y = this.topPos + StorageMenu.CRAFT_PANEL_Y;
		extractBox(graphics, x, y, RecipeBookComponent.IMAGE_WIDTH, BOOK_Y - StorageMenu.CRAFT_PANEL_Y);
		graphics.text(this.font, Component.translatable("container.crafting"), x + 8, y + 4, TEXT_DARK, false);
		for (int i = 0; i < 9; i++) {
			blitMain(graphics, x + 10 + i % 3 * 18, y + 14 + i / 3 * 18, 8, 17, 18, 18);
		}

		graphics.blit(RenderPipelines.GUI_TEXTURED, CRAFTING_TEXTURE, x + 71, y + 33, 90.0F, 35.0F, 22, 15, 256, 256);
		graphics.blit(RenderPipelines.GUI_TEXTURED, CRAFTING_TEXTURE, x + 100, y + 28, 119.0F, 30.0F, 26, 26, 256, 256);
	}

	/** A plain stretch of panel: left and right borders with light gray between. */
	private void extractBody(GuiGraphicsExtractor graphics, int x, int y, int height) {
		blitMain(graphics, x, y, 0, 20, 3, height);
		blitMain(graphics, x + 192, y, 192, 20, 3, height);
		extractFill(graphics, x + 3, y, 189, height);
	}

	/**
	 * Fills an area with the panel's background color, stretched from one pixel of the texture (between
	 * the left border and the item grid), so resource packs that recolor menus recolor this too.
	 */
	static void extractFill(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
		graphics.blit(RenderPipelines.GUI_TEXTURED, MAIN_TEXTURE, x, y, 4.0F, 20.0F, width, height, 1, 1, 256, 256);
	}

	static void blitMain(GuiGraphicsExtractor graphics, int x, int y, int u, int v, int width, int height) {
		graphics.blit(RenderPipelines.GUI_TEXTURED, MAIN_TEXTURE, x, y, (float) u, (float) v, width, height, 256, 256);
	}

	private static Identifier[] tabSprites(String state) {
		Identifier[] sprites = new Identifier[VISIBLE_TABS];
		for (int i = 0; i < VISIBLE_TABS; i++) {
			sprites[i] = Identifier.withDefaultNamespace("container/creative_inventory/tab_top_" + state + (i + 1));
		}

		return sprites;
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
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, (selected ? TAB_SELECTED_SPRITES : TAB_SPRITES)[i], tx, ty, TAB_WIDTH, TAB_HEIGHT);
			graphics.item(tab.iconStack(), tx + 5, ty + 9);
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

	/**
	 * The Crafting upgrade's tab on the right side of the menu (only with the upgrade installed). Closed,
	 * it is a separate box drawn behind the menu; open, it is drawn after the menu and joins onto it.
	 */
	private void extractCraftingTab(GuiGraphicsExtractor graphics, boolean afterPanel) {
		boolean open = this.menu.craftingOpen;
		if (!this.menu.hasCraftingUpgrade() || open != afterPanel) {
			return;
		}

		int x = this.leftPos;
		int ty = this.topPos + CRAFT_TAB_Y;
		if (open) {
			this.extractStrip(graphics, x, ty, ty + CRAFT_TAB_HEIGHT, x + CRAFT_TAB_RIGHT);
		} else {
			extractBox(graphics, x + WIDTH - 4, ty, CRAFT_TAB_RIGHT - 2 - (WIDTH - 4), CRAFT_TAB_HEIGHT);
		}

		graphics.item(CRAFTING_ICON, x + WIDTH + (open ? 5 : 2), ty + 5);
	}

	/**
	 * A whole bordered box of any size, cut from the menu texture's corners and edges. Long edges are
	 * drawn in pieces, since the texture's plain edge runs are only so long.
	 */
	static void extractBox(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
		int w = width - 6;
		int h = height - 6;
		blitMain(graphics, x, y, 0, 0, 3, 3);
		blitMain(graphics, x + width - 3, y, 192, 0, 3, 3);
		blitMain(graphics, x, y + height - 3, 0, 133, 3, 3);
		blitMain(graphics, x + width - 3, y + height - 3, 192, 133, 3, 3);
		for (int done = 0; done < w; done += 80) {
			int piece = Math.min(80, w - done);
			blitMain(graphics, x + 3 + done, y, 20, 0, piece, 3);
			blitMain(graphics, x + 3 + done, y + height - 3, 20, 133, piece, 3);
		}

		for (int done = 0; done < h; done += 100) {
			int piece = Math.min(100, h - done);
			blitMain(graphics, x, y + 3 + done, 0, 20, 3, piece);
			blitMain(graphics, x + width - 3, y + 3 + done, 192, 20, 3, piece);
		}

		extractFill(graphics, x + 3, y + 3, w, h);
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

	/** "22% full" and a bar, under the menu (unless hidden in the settings). */
	private void extractCapacity(GuiGraphicsExtractor graphics) {
		if (!ClientSettings.showFullness()) {
			return;
		}

		int x = this.leftPos + 2;
		int y = this.topPos + FULLNESS_Y;
		int percent = Math.round(this.menu.percentFull);
		Component label = Component.translatable("gui.gsm.percent_full", percent);
		graphics.text(this.font, label, x, y + 1, TEXT_LIGHT, true);

		int barX = x + this.font.width(label) + 5;
		int barEnd = this.leftPos + WIDTH - 2;
		int barY = y + 2;
		graphics.fill(barX, barY, barEnd, barY + 6, 0xFF000000);
		int filled = Math.round((barEnd - barX - 2) * Mth.clamp(this.menu.percentFull / 100.0F, 0.0F, 1.0F));
		int color = percent >= 90 ? 0xFFD03030 : percent >= 70 ? 0xFFE0B030 : 0xFF40B040;
		graphics.fill(barX + 1, barY + 1, barX + 1 + filled, barY + 5, color);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// Same order as vanilla's recipe book screens: the book over the menu, then the carried item and tooltips.
		this.extractContents(graphics, mouseX, mouseY, partialTick);
		graphics.nextStratum();
		if (this.bookShown()) {
			this.recipeBook.extractRenderState(graphics, mouseX, mouseY, partialTick);
		}
		graphics.nextStratum();
		this.extractCarriedItem(graphics, mouseX, mouseY);
		this.extractTooltip(graphics, mouseX, mouseY);
		if (this.bookShown()) {
			this.recipeBook.extractTooltip(graphics, mouseX, mouseY, this.hoveredSlot);
		}
	}

	@Override
	protected void extractSlots(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		super.extractSlots(graphics, mouseX, mouseY);
		if (this.menu.craftingOpen) {
			this.recipeBook.extractGhostRecipe(graphics, true);
		}
	}

	@Override
	public void recipesUpdated() {
		this.recipeBook.recipesUpdated();
	}

	@Override
	public void fillGhostRecipe(RecipeDisplay display) {
		this.recipeBook.fillGhostRecipe(display);
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
			graphics.setComponentTooltipForNextFrame(this.font, getTooltipFromItem(this.minecraft, entry.stack()), mouseX, mouseY);
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

		// Rounded down, so 9,999 shows as 9.9k rather than 10.0k.
		if (count < 10_000) {
			return count / 1000 + "." + count / 100 % 10 + "k";
		}

		if (count < 1_000_000) {
			return (count / 1000) + "k";
		}

		if (count < 10_000_000) {
			return count / 1_000_000 + "." + count / 100_000 % 10 + "M";
		}

		return (count / 1_000_000) + "M";
	}
}
