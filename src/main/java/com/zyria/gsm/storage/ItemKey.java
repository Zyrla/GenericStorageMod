package com.zyria.gsm.storage;

import net.minecraft.world.item.ItemStack;

/** A map key that treats stacks of the same item with the same components as equal, ignoring count. */
public record ItemKey(ItemStack stack) {
	@Override
	public boolean equals(Object other) {
		return other instanceof ItemKey key && ItemStack.isSameItemSameComponents(this.stack, key.stack);
	}

	@Override
	public int hashCode() {
		return ItemStack.hashItemAndComponents(this.stack);
	}
}
