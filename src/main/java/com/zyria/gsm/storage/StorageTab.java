package com.zyria.gsm.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A category tab. Tabs are views of the shared inventory: they hold item ids, never items.
 * <p>
 * {@link #entries} is the tab's ordered item list (the order items were sorted or placed into it).
 * {@link #manual} marks entries the player assigned by hand, and {@link #excluded} holds items the
 * player removed by hand, so automatic rules never add them back (a manual assignment always beats a rule).
 */
public final class StorageTab {
	public enum Kind {
		ALL,
		UNSORTED,
		CATEGORY;

		public static final Codec<Kind> CODEC = Codec.STRING.xmap(s -> Kind.valueOf(s.toUpperCase(Locale.ROOT)), k -> k.name().toLowerCase(Locale.ROOT));
	}

	/** How a tab orders its items in the menu. ARRIVAL is the order items were sorted or placed into the tab. */
	public enum SortMode {
		ARRIVAL,
		NAME,
		COUNT,
		MOD;

		public static final Codec<SortMode> CODEC = Codec.STRING.xmap(s -> SortMode.valueOf(s.toUpperCase(Locale.ROOT)), m -> m.name().toLowerCase(Locale.ROOT));

		public SortMode next() {
			return values()[(this.ordinal() + 1) % values().length];
		}
	}

	public static final Codec<StorageTab> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.INT.fieldOf("id").forGetter(t -> t.id),
		Codec.STRING.fieldOf("name").forGetter(t -> t.name),
		Kind.CODEC.fieldOf("kind").forGetter(t -> t.kind),
		Codec.BOOL.optionalFieldOf("sorting", true).forGetter(t -> t.sorting),
		Identifier.CODEC.optionalFieldOf("icon", Identifier.withDefaultNamespace("air")).forGetter(t -> t.icon),
		TabRule.CODEC.listOf().optionalFieldOf("rules", List.of()).forGetter(t -> List.copyOf(t.rules)),
		Identifier.CODEC.listOf().optionalFieldOf("entries", List.of()).forGetter(t -> List.copyOf(t.entries)),
		Identifier.CODEC.listOf().optionalFieldOf("manual", List.of()).forGetter(t -> List.copyOf(t.manual)),
		Identifier.CODEC.listOf().optionalFieldOf("excluded", List.of()).forGetter(t -> List.copyOf(t.excluded)),
		SortMode.CODEC.optionalFieldOf("sort", SortMode.ARRIVAL).forGetter(t -> t.sortMode)
	).apply(i, StorageTab::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, StorageTab> STREAM_CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC);

	public final int id;
	public String name;
	public final Kind kind;
	public boolean sorting;
	/** Icon item id. "minecraft:air" means "use the first item in the tab". */
	public Identifier icon;
	public final List<TabRule> rules;
	public final LinkedHashSet<Identifier> entries;
	public final LinkedHashSet<Identifier> manual;
	public final LinkedHashSet<Identifier> excluded;
	public SortMode sortMode;

	public StorageTab(int id, String name, Kind kind, boolean sorting, Identifier icon, List<TabRule> rules,
					  List<Identifier> entries, List<Identifier> manual, List<Identifier> excluded, SortMode sortMode) {
		this.id = id;
		this.name = name;
		this.kind = kind;
		this.sorting = sorting;
		this.icon = icon;
		this.rules = new ArrayList<>(rules);
		this.entries = new LinkedHashSet<>(entries);
		this.manual = new LinkedHashSet<>(manual);
		this.excluded = new LinkedHashSet<>(excluded);
		this.sortMode = sortMode;
	}

	public static StorageTab create(int id, String name, Kind kind, Item icon, List<TabRule> rules) {
		return new StorageTab(id, name, kind, true, BuiltInRegistries.ITEM.getKey(icon), rules, List.of(), List.of(), List.of(), SortMode.ARRIVAL);
	}

	public StorageTab copy() {
		return new StorageTab(this.id, this.name, this.kind, this.sorting, this.icon, this.rules,
			List.copyOf(this.entries), List.copyOf(this.manual), List.copyOf(this.excluded), this.sortMode);
	}

	public boolean matchesRules(Item item) {
		for (TabRule rule : this.rules) {
			if (rule.matches(item)) {
				return true;
			}
		}

		return false;
	}

	/** Client-side cache; tabs are replaced with fresh copies whenever they change. */
	private ItemStack iconCache;

	public ItemStack iconStack() {
		if (this.iconCache == null) {
			this.iconCache = this.computeIcon();
		}

		return this.iconCache;
	}

	private ItemStack computeIcon() {
		Item item = BuiltInRegistries.ITEM.getValue(this.icon);
		if (item == null || item == Items.AIR) {
			for (Identifier entry : this.entries) {
				Item first = BuiltInRegistries.ITEM.getValue(entry);
				if (first != null && first != Items.AIR) {
					return new ItemStack(first);
				}
			}

			return new ItemStack(Items.CHEST);
		}

		return new ItemStack(item);
	}

	/** The tabs every new system starts with. */
	public static List<StorageTab> defaults() {
		List<StorageTab> tabs = new ArrayList<>();
		tabs.add(create(0, "All Items", Kind.ALL, Items.CHEST, List.of()));
		tabs.add(create(1, "Unsorted", Kind.UNSORTED, Items.BUNDLE, List.of()));
		tabs.add(create(2, "Building Blocks", Kind.CATEGORY, Items.BRICKS, List.of(
			TabRule.tag("minecraft:planks"),
			TabRule.tag("minecraft:logs"),
			TabRule.tag("minecraft:stone_bricks"),
			TabRule.tag("minecraft:wool"),
			TabRule.tag("minecraft:terracotta"),
			TabRule.tag("minecraft:walls"),
			TabRule.tag("minecraft:wooden_stairs"),
			TabRule.tag("minecraft:wooden_slabs"),
			TabRule.tag("c:stones"),
			TabRule.tag("c:cobblestones"),
			TabRule.tag("c:sandstone_blocks"),
			TabRule.tag("c:glass_blocks"),
			TabRule.tag("c:concretes"),
			TabRule.tag("c:glazed_terracottas"),
			TabRule.tag("c:storage_blocks")
		)));
		tabs.add(create(3, "Tools", Kind.CATEGORY, Items.IRON_PICKAXE, List.of(
			TabRule.tag("c:tools"),
			TabRule.tag("minecraft:pickaxes"),
			TabRule.tag("minecraft:axes"),
			TabRule.tag("minecraft:shovels"),
			TabRule.tag("minecraft:hoes"),
			TabRule.tag("minecraft:swords")
		)));
		tabs.add(create(4, "Food", Kind.CATEGORY, Items.APPLE, List.of(
			TabRule.tag("c:foods")
		)));
		tabs.add(create(5, "Ores", Kind.CATEGORY, Items.IRON_ORE, List.of(
			TabRule.tag("c:ores")
		)));
		tabs.add(create(6, "Redstone", Kind.CATEGORY, Items.REDSTONE, List.of(
			TabRule.tag("c:dusts/redstone"),
			TabRule.tag("c:storage_blocks/redstone"),
			TabRule.tag("minecraft:wooden_buttons"),
			TabRule.tag("minecraft:wooden_pressure_plates"),
			TabRule.tag("minecraft:rails")
		)));
		return tabs;
	}

	public static final int FIRST_CUSTOM_ID = 7;
}
