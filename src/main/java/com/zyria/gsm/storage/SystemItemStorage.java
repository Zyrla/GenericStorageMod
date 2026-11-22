package com.zyria.gsm.storage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.world.item.ItemStack;

/**
 * The whole system as one Fabric item storage, so hoppers (and pipes from other mods) can insert into
 * and extract from it. Changes apply right away and are journaled; a cancelled transaction undoes them.
 */
public final class SystemItemStorage extends SnapshotParticipant<SystemItemStorage.Mark> implements Storage<ItemVariant> {
	private final StorageSystem system;

	/** Where the journal stood when a transaction first touched the system, and whether it was the outermost. */
	record Mark(int size, boolean outermost) {
	}

	SystemItemStorage(StorageSystem system) {
		this.system = system;
	}

	@Override
	public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
		StoragePreconditions.notBlankNotNegative(resource, maxAmount);
		this.updateSnapshots(transaction);
		return this.system.insertAmount(resource.toStack(), maxAmount, null);
	}

	@Override
	public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
		StoragePreconditions.notBlankNotNegative(resource, maxAmount);
		ItemStack prototype = resource.toStack();
		if (!this.system.containsItem(prototype.getItem()) || this.system.count(prototype) == 0) {
			return 0;
		}

		this.updateSnapshots(transaction);
		return this.system.extractAmount(prototype, maxAmount);
	}

	@Override
	public Iterator<StorageView<ItemVariant>> iterator() {
		List<StorageView<ItemVariant>> views = new ArrayList<>(this.system.storedKeys().size());
		for (ItemStack key : this.system.storedKeys()) {
			views.add(new EntryView(ItemVariant.of(key), key.copy()));
		}

		return views.iterator();
	}

	@Override
	protected Mark createSnapshot() {
		boolean outermost = this.system.journal == null;
		if (outermost) {
			this.system.journal = new ArrayList<>();
		}

		return new Mark(this.system.journal.size(), outermost);
	}

	@Override
	protected void readSnapshot(Mark mark) {
		this.system.undoTo(mark.size());
		if (mark.outermost()) {
			this.system.journal = null;
		}
	}

	@Override
	protected void onFinalCommit() {
		this.system.journal = null;
		this.system.onExternalChange();
	}

	/** One stored item, as seen by hoppers and pipes looking for something to take. */
	private final class EntryView implements StorageView<ItemVariant> {
		private final ItemVariant resource;
		private final ItemStack prototype;

		EntryView(ItemVariant resource, ItemStack prototype) {
			this.resource = resource;
			this.prototype = prototype;
		}

		@Override
		public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
			return resource.equals(this.resource) ? SystemItemStorage.this.extract(resource, maxAmount, transaction) : 0;
		}

		@Override
		public boolean isResourceBlank() {
			return this.getAmount() == 0;
		}

		@Override
		public ItemVariant getResource() {
			return this.resource;
		}

		@Override
		public long getAmount() {
			return SystemItemStorage.this.system.count(this.prototype);
		}

		@Override
		public long getCapacity() {
			return Math.max(this.getAmount(), this.prototype.getMaxStackSize());
		}
	}

	@Override
	public String toString() {
		return "SystemItemStorage[" + this.system.id + "]";
	}
}
