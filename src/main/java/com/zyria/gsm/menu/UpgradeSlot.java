package com.zyria.gsm.menu;

import com.zyria.gsm.item.UpgradeItem;
import com.zyria.gsm.storage.StorageSystem;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** One of the system's upgrade slots. Holds a single upgrade item. */
public class UpgradeSlot extends Slot {
	public UpgradeSlot(Container container, int index, int x, int y) {
		super(container, index, x, y);
	}

	@Override
	public boolean mayPlace(ItemStack stack) {
		return stack.getItem() instanceof UpgradeItem;
	}

	@Override
	public int getMaxStackSize() {
		return 1;
	}

	/** A Capacity upgrade can't come out while the slots it adds still hold items that wouldn't fit elsewhere. */
	@Override
	public boolean mayPickup(Player player) {
		if (this.container instanceof UpgradeContainer upgrades) {
			StorageSystem system = upgrades.system();
			return system == null || system.canRemoveUpgrade(this.getItem());
		}

		return true;
	}
}
