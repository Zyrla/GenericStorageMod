package com.zyria.gsm.storage;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.item.base.SingleStackStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The whole system as one Fabric item storage, so hoppers (and pipes from other mods) can insert into
 * and extract from any module. Each slot is a transactional part; changes reach the system only on commit.
 */
public final class SystemItemStorage extends CombinedStorage<ItemVariant, SystemItemStorage.SlotStorage> {
	private final StorageSystem system;

	private SystemItemStorage(StorageSystem system, List<SlotStorage> parts) {
		super(parts);
		this.system = system;
	}

	static SystemItemStorage create(StorageSystem system) {
		List<SlotStorage> parts = new ArrayList<>(system.totalSlots());
		for (ItemStack[] slots : system.modules.values()) {
			for (int i = 0; i < slots.length; i++) {
				parts.add(new SlotStorage(system, slots, i));
			}
		}

		return new SystemItemStorage(system, parts);
	}

	/** Like a chest: top up matching stacks first, then use empty slots. */
	@Override
	public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
		long inserted = 0;
		boolean stored = this.system.containsItem(resource.getItem());
		for (SlotStorage part : stored ? this.parts : List.<SlotStorage>of()) {
			if (inserted >= maxAmount) {
				return inserted;
			}

			if (!part.isResourceBlank() && part.getResource().equals(resource)) {
				inserted += part.insert(resource, maxAmount - inserted, transaction);
			}
		}

		for (SlotStorage part : this.parts) {
			if (inserted >= maxAmount || this.system.emptySlotCount() == 0) {
				return inserted;
			}

			if (part.isResourceBlank()) {
				inserted += part.insert(resource, maxAmount - inserted, transaction);
			}
		}

		return inserted;
	}

	/** Skips the slot-by-slot search entirely when the item isn't stored. */
	@Override
	public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
		if (!this.system.containsItem(resource.getItem())) {
			return 0;
		}

		return super.extract(resource, maxAmount, transaction);
	}

	static final class SlotStorage extends SingleStackStorage {
		private final StorageSystem system;
		private final ItemStack[] slots;
		private final int index;

		SlotStorage(StorageSystem system, ItemStack[] slots, int index) {
			this.system = system;
			this.slots = slots;
			this.index = index;
		}

		@Override
		protected ItemStack getStack() {
			return this.slots[this.index];
		}

		@Override
		protected void setStack(ItemStack stack) {
			ItemStack old = this.slots[this.index];
			this.slots[this.index] = stack;
			// Keeps the system's index right, including when a transaction is rolled back.
			this.system.onSlotChanged(old.isEmpty() ? null : old.getItem(), stack.isEmpty() ? null : stack.getItem());
		}

		@Override
		public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
			// The base class shrinks the stack in place, so setStack can't see it empty out: record that here.
			ItemStack current = this.getStack();
			Item before = current.isEmpty() ? null : current.getItem();
			long extracted = super.extract(resource, maxAmount, transaction);
			if (before != null && this.getStack().isEmpty()) {
				this.system.onSlotChanged(before, null);
			}

			return extracted;
		}

		@Override
		protected void onFinalCommit() {
			this.system.onExternalChange();
		}
	}

	@Override
	public String toString() {
		return "SystemItemStorage[" + this.system.id + "]";
	}
}
