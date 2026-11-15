package com.zyria.gsm.menu;

import com.zyria.gsm.storage.StorageSystem;
import com.zyria.gsm.storage.StorageSystemManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Server side: the upgrade slots of whichever system the opened module currently belongs to.
 * The system is looked up on every access, so merges and splits while the menu is open stay correct.
 */
public class UpgradeContainer implements Container {
	private final ServerLevel level;
	private final BlockPos modulePos;

	public UpgradeContainer(ServerLevel level, BlockPos modulePos) {
		this.level = level;
		this.modulePos = modulePos;
	}

	StorageSystem system() {
		return StorageSystemManager.get(this.level).systemAt(this.modulePos);
	}

	@Override
	public int getContainerSize() {
		return StorageSystem.UPGRADE_SLOTS;
	}

	@Override
	public boolean isEmpty() {
		for (int i = 0; i < StorageSystem.UPGRADE_SLOTS; i++) {
			if (!this.getItem(i).isEmpty()) {
				return false;
			}
		}

		return true;
	}

	@Override
	public ItemStack getItem(int slot) {
		StorageSystem system = this.system();
		return system == null ? ItemStack.EMPTY : system.upgrade(slot);
	}

	@Override
	public ItemStack removeItem(int slot, int count) {
		StorageSystem system = this.system();
		if (system == null || system.upgrade(slot).isEmpty()) {
			return ItemStack.EMPTY;
		}

		ItemStack taken = system.upgrade(slot).split(count);
		if (system.upgrade(slot).isEmpty()) {
			system.setUpgrade(slot, ItemStack.EMPTY);
		}

		this.setChanged();
		return taken;
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		StorageSystem system = this.system();
		if (system == null) {
			return ItemStack.EMPTY;
		}

		ItemStack taken = system.upgrade(slot);
		system.setUpgrade(slot, ItemStack.EMPTY);
		return taken;
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		StorageSystem system = this.system();
		if (system != null) {
			system.setUpgrade(slot, stack);
			this.setChanged();
		}
	}

	@Override
	public int getMaxStackSize() {
		return 1;
	}

	/** Applies the upgrades (for example, resizes modules for Capacity upgrades) and saves. */
	@Override
	public void setChanged() {
		StorageSystem system = this.system();
		if (system != null) {
			system.applyUpgrades();
		}
	}

	@Override
	public boolean stillValid(Player player) {
		return this.system() != null;
	}

	@Override
	public void clearContent() {
	}
}
