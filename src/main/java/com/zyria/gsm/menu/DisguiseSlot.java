package com.zyria.gsm.menu;

import com.zyria.gsm.block.Camouflage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** The menu slot for the opened module's disguise. Takes one full, solid block. */
public class DisguiseSlot extends Slot {
	private final Player player;
	private final BlockPos modulePos;

	public DisguiseSlot(Container container, Player player, BlockPos modulePos, int x, int y) {
		super(container, 0, x, y);
		this.player = player;
		this.modulePos = modulePos;
	}

	@Override
	public boolean mayPlace(ItemStack stack) {
		return Camouflage.isAllowedItem(stack, this.player.level(), this.modulePos);
	}

	@Override
	public int getMaxStackSize() {
		return 1;
	}
}
