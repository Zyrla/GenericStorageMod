package com.zyria.gsm.storage;

import com.mojang.serialization.Codec;
import com.zyria.gsm.ModConfig;
import com.zyria.gsm.registry.ModContent;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * All storage systems in one dimension. Module contents live here, keyed by module position,
 * so a system can be opened, merged or split without loading every module's chunk.
 */
public final class StorageSystemManager extends SavedData {
	private static final double EPSILON = 1.0E-9;

	public static final Codec<StorageSystemManager> CODEC = StorageSystem.CODEC.listOf()
		.xmap(StorageSystemManager::new, manager -> new ArrayList<>(manager.systems.values()));

	public static final SavedDataType<StorageSystemManager> TYPE = new SavedDataType<>(
		ModContent.id("storage_systems"), StorageSystemManager::new, CODEC, null
	);

	private final Map<UUID, StorageSystem> systems = new LinkedHashMap<>();
	private final Long2ObjectOpenHashMap<StorageSystem> byPos = new Long2ObjectOpenHashMap<>();

	public StorageSystemManager() {
	}

	private StorageSystemManager(List<StorageSystem> loaded) {
		for (StorageSystem system : loaded) {
			this.add(system);
		}
	}

	public static StorageSystemManager get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	private void add(StorageSystem system) {
		this.systems.put(system.id, system);
		system.dirtyListener = this::setDirty;
		for (long pos : system.modules.keySet()) {
			this.byPos.put(pos, system);
		}
	}

	private void remove(StorageSystem system) {
		this.systems.remove(system.id);
		system.dirtyListener = () -> {
		};
	}

	public StorageSystem systemAt(BlockPos pos) {
		return this.byPos.get(pos.asLong());
	}

	/**
	 * Cleans up "ghost" modules: positions the system still tracks although the block there is no longer
	 * a storage module. This happens when a module is removed without the usual block-removed callbacks,
	 * for example by /setblock, /fill, structure blocks or world editors. Each ghost is handled exactly as
	 * if it had been broken (its items move into the system or drop, and the system splits if needed).
	 * Only checks loaded chunks, so it never loads chunks by itself.
	 */
	public void removeGhosts(ServerLevel level, StorageSystem system) {
		if (system == null) {
			return;
		}

		for (long key : system.modules.keySet().toLongArray()) {
			BlockPos pos = BlockPos.of(key);
			if (this.byPos.containsKey(key) && level.isLoaded(pos) && !level.getBlockState(pos).is(ModContent.STORAGE_MODULE)) {
				this.onModuleRemoved(level, pos);
			}
		}
	}

	// ---------------------------------------------------------------- placing and merging

	public void onModulePlaced(ServerLevel level, BlockPos pos) {
		long key = pos.asLong();
		if (this.byPos.containsKey(key)) {
			return;
		}

		// Clear out ghost modules first, so the new module can't join a system through a block that's gone.
		Set<StorageSystem> nearby = new LinkedHashSet<>();
		forEachNeighbor(key, neighbor -> {
			StorageSystem system = this.byPos.get(neighbor);
			if (system != null) {
				nearby.add(system);
			}
		});
		for (StorageSystem system : nearby) {
			this.removeGhosts(level, system);
		}

		Set<StorageSystem> touching = new LinkedHashSet<>();
		forEachNeighbor(key, neighbor -> {
			StorageSystem system = this.byPos.get(neighbor);
			if (system != null) {
				touching.add(system);
			}
		});

		StorageSystem target;
		if (touching.isEmpty()) {
			target = new StorageSystem(UUID.randomUUID(), StorageTab.defaults(), StorageTab.FIRST_CUSTOM_ID);
			this.add(target);
		} else if (touching.size() == 1) {
			target = touching.iterator().next();
		} else if (ModConfig.get().allow_merging) {
			target = this.merge(new ArrayList<>(touching), level, pos);
		} else {
			// Merging is off in the settings: join just one of the touching systems.
			target = pickWinner(new ArrayList<>(touching), level.getRandom());
		}

		target.modules.put(key, new StorageSystem.Module());
		target.modulesChanged();
		this.byPos.put(key, target);
		target.markChanged();
		this.setDirty();
	}

	/**
	 * Joins systems into one. The kept tabs and rules come from the system with the most modules,
	 * then the most free space, then a random pick. The others' tabs are dropped and their items go to Unsorted.
	 */
	private static StorageSystem pickWinner(List<StorageSystem> systems, RandomSource random) {
		int maxModules = systems.stream().mapToInt(StorageSystem::moduleCount).max().orElse(0);
		List<StorageSystem> candidates = new ArrayList<>(systems.stream().filter(s -> s.moduleCount() == maxModules).toList());
		if (candidates.size() > 1) {
			double maxFree = candidates.stream().mapToDouble(StorageSystem::freeSpace).max().orElse(0);
			candidates.removeIf(s -> s.freeSpace() < maxFree - EPSILON);
		}

		return candidates.get(random.nextInt(candidates.size()));
	}

	private StorageSystem merge(List<StorageSystem> systems, ServerLevel level, BlockPos placedAt) {
		StorageSystem winner = pickWinner(systems, level.getRandom());
		Set<Identifier> claimed = winner.claimedByCategories();
		List<ItemStack> spareUpgrades = new ArrayList<>();

		for (StorageSystem other : systems) {
			if (other == winner) {
				continue;
			}

			List<Identifier> orphaned = new ArrayList<>();
			for (StorageTab tab : other.tabs) {
				if (tab.kind == StorageTab.Kind.CATEGORY) {
					for (Identifier entry : tab.entries) {
						if (!claimed.contains(entry)) {
							orphaned.add(entry);
						}
					}
				}
			}

			// Merging only adds capacity: every module keeps its own contents, so nothing can overflow.
			for (var entry : other.modules.long2ObjectEntrySet()) {
				winner.modules.put(entry.getLongKey(), entry.getValue());
				this.byPos.put(entry.getLongKey(), winner);
			}

			winner.modulesChanged(); // the index must include the absorbed items before they move to Unsorted

			this.remove(other);
			other.markChanged();
			winner.moveToUnsorted(orphaned);

			// The merged system keeps one set of upgrades: the others' upgrades fill its empty upgrade
			// slots, and any that don't fit drop where the new module was placed.
			for (ItemStack upgrade : other.takeUpgrades()) {
				if (!winner.addUpgrade(upgrade)) {
					spareUpgrades.add(upgrade);
				}
			}
		}

		winner.resort();
		winner.applyUpgrades(); // every module now matches the merged system's capacity; publishes the change
		drop(level, placedAt, spareUpgrades);
		return winner;
	}

	// ---------------------------------------------------------------- breaking and splitting

	public void onModuleRemoved(ServerLevel level, BlockPos pos) {
		long key = pos.asLong();
		StorageSystem system = this.byPos.remove(key);
		if (system == null) {
			return;
		}

		StorageSystem.Module removed = system.modules.remove(key);
		system.modulesChanged();
		if (removed == null) {
			removed = new StorageSystem.Module();
		}

		this.setDirty();

		if (system.modules.isEmpty()) {
			// The last module drops everything, plus the system's upgrades.
			List<ItemStack> leftovers = new ArrayList<>(system.takeUpgrades());
			for (var entry : removed.items.object2LongEntrySet()) {
				StorageSystem.toStacks(entry.getKey(), entry.getLongValue(), leftovers);
			}

			this.remove(system);
			system.markChanged();
			drop(level, pos, leftovers);
			return;
		}

		List<LongSet> groups = connectedGroups(system.modules.keySet());
		if (groups.size() == 1) {
			// No split: the broken module's items move into the free space, the rest drops.
			drop(level, pos, system.absorb(removed));
			return;
		}

		List<ItemStack> leftovers = new ArrayList<>();
		for (var entry : removed.items.object2LongEntrySet()) {
			StorageSystem.toStacks(entry.getKey(), entry.getLongValue(), leftovers);
		}

		// Split: the largest group keeps the original system, every other group becomes a copy.
		groups.sort(Comparator.comparingInt((LongSet g) -> g.size()).reversed());
		List<StorageSystem> parts = new ArrayList<>();
		parts.add(system);
		for (int i = 1; i < groups.size(); i++) {
			StorageSystem part = new StorageSystem(UUID.randomUUID(), system.copyTabs(), system.nextTabId);
			part.craftingAutoFill = system.craftingAutoFill; // each new system keeps a copy of the settings
			for (long modulePos : groups.get(i)) {
				part.modules.put(modulePos, system.modules.remove(modulePos));
			}

			this.add(part);
			parts.add(part);
		}

		for (StorageSystem part : parts) {
			part.modulesChanged();
			// Upgrades stay with the largest part; the other parts lose the extra room (modules that end up
			// over-full keep their items, see applyUpgrades).
			part.applyUpgrades();
		}

		drop(level, pos, distribute(leftovers, parts));
	}

	/**
	 * Hands the broken module's items to the new systems: most free space first, then the next most.
	 * Systems that tie on free space split the items evenly. Returns what is still left.
	 */
	private static List<ItemStack> distribute(List<ItemStack> items, List<StorageSystem> systems) {
		List<StorageSystem> order = new ArrayList<>(systems);
		Map<StorageSystem, Double> free = new LinkedHashMap<>();
		for (StorageSystem system : order) {
			free.put(system, system.freeSpace());
		}

		order.sort(Comparator.comparingDouble((StorageSystem s) -> free.get(s)).reversed());

		List<ItemStack> pending = new ArrayList<>(items);
		int index = 0;
		while (index < order.size() && !pending.isEmpty()) {
			// Collect the tier of systems tied on free space.
			List<StorageSystem> tier = new ArrayList<>();
			double tierFree = free.get(order.get(index));
			while (index < order.size() && Math.abs(free.get(order.get(index)) - tierFree) < EPSILON) {
				tier.add(order.get(index++));
			}

			pending = insertEvenly(pending, tier);
		}

		return pending;
	}

	private static List<ItemStack> insertEvenly(List<ItemStack> items, List<StorageSystem> tier) {
		List<ItemStack> rest = new ArrayList<>();
		int k = tier.size();
		int rotation = 0;
		for (ItemStack stack : items) {
			int base = stack.getCount() / k;
			int extra = stack.getCount() % k;
			List<ItemStack> unplaced = new ArrayList<>();
			for (int i = 0; i < k; i++) {
				int share = base + (i < extra ? 1 : 0);
				if (share == 0) {
					continue;
				}

				StorageSystem target = tier.get((i + rotation) % k);
				ItemStack left = target.insert(stack.copyWithCount(share));
				if (!left.isEmpty()) {
					unplaced.add(left);
				}
			}

			rotation++;
			// Anything one tied system couldn't take may still fit in another.
			for (ItemStack left : unplaced) {
				ItemStack current = left;
				for (StorageSystem target : tier) {
					current = target.insert(current);
					if (current.isEmpty()) {
						break;
					}
				}

				if (!current.isEmpty()) {
					rest.add(current);
				}
			}
		}

		return rest;
	}

	private static void drop(ServerLevel level, BlockPos pos, List<ItemStack> stacks) {
		for (ItemStack stack : stacks) {
			Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
		}
	}

	// ---------------------------------------------------------------- connectivity

	/** Calls the consumer for all 26 positions sharing a face, edge or corner. */
	private static void forEachNeighbor(long pos, java.util.function.LongConsumer consumer) {
		int x = BlockPos.getX(pos);
		int y = BlockPos.getY(pos);
		int z = BlockPos.getZ(pos);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx != 0 || dy != 0 || dz != 0) {
						consumer.accept(BlockPos.asLong(x + dx, y + dy, z + dz));
					}
				}
			}
		}
	}

	private static List<LongSet> connectedGroups(LongSet positions) {
		List<LongSet> groups = new ArrayList<>();
		LongOpenHashSet unvisited = new LongOpenHashSet(positions);
		LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
		for (long start : positions) {
			if (!unvisited.remove(start)) {
				continue;
			}

			LongSet group = new LongLinkedOpenHashSet();
			group.add(start);
			queue.enqueue(start);
			while (!queue.isEmpty()) {
				forEachNeighbor(queue.dequeueLong(), neighbor -> {
					if (unvisited.remove(neighbor)) {
						group.add(neighbor);
						queue.enqueue(neighbor);
					}
				});
			}

			groups.add(group);
		}

		return groups;
	}
}
