package com.zyria.gsm.item;

import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/** An upgrade that goes in a storage system's upgrade slots. Upgrades apply to the whole system. */
public class UpgradeItem extends Item {
	public enum Type {
		/** Adds a crafting panel to the storage menu. */
		CRAFTING(0),
		/** Capacity Upgrade I: 4 more stacks of room in every module. */
		CAPACITY_1(4),
		/** Capacity Upgrade II: 9 more stacks of room in every module. */
		CAPACITY_2(9);

		private final int extraSlots;

		Type(int extraSlots) {
			this.extraSlots = extraSlots;
		}

		/** Slots this upgrade adds to every module. */
		public int extraSlots() {
			return this.extraSlots;
		}
	}

	private final Type type;

	public UpgradeItem(Type type, Properties properties) {
		super(properties);
		this.type = type;
	}

	public Type type() {
		return this.type;
	}

	/** Explains what the upgrade does, right on the item. */
	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		String key = "item.gsm.upgrade.tooltip." + this.type.name().toLowerCase(Locale.ROOT);
		for (String line : Component.translatable(key).getString().split("\n")) {
			builder.accept(Component.literal(line).withStyle(ChatFormatting.GRAY));
		}

		builder.accept(Component.translatable("item.gsm.upgrade.tooltip.whole_system").withStyle(ChatFormatting.DARK_GRAY));
	}

	public static boolean is(ItemStack stack, Type type) {
		return stack.getItem() instanceof UpgradeItem upgrade && upgrade.type == type;
	}

	/** Slots the upgrade in this stack adds to every module (0 for non-capacity upgrades and other items). */
	public static int extraSlots(ItemStack stack) {
		return stack.getItem() instanceof UpgradeItem upgrade ? upgrade.type.extraSlots : 0;
	}
}
