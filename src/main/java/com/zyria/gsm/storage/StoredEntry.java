package com.zyria.gsm.storage;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/** One distinct item in a system: a single-count prototype stack plus the total stored. */
public record StoredEntry(ItemStack stack, long count) {
	public static final StreamCodec<RegistryFriendlyByteBuf, StoredEntry> STREAM_CODEC = StreamCodec.composite(
		ItemStack.STREAM_CODEC, StoredEntry::stack,
		ByteBufCodecs.VAR_LONG, StoredEntry::count,
		StoredEntry::new
	);
}
