package com.zyria.gsm.block;

import com.zyria.gsm.registry.ModContent;
import com.zyria.gsm.storage.StorageSystemManager;
import net.fabricmc.fabric.api.blockgetter.v2.RenderDataBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import java.util.List;

/**
 * A storage module's block entity. It only holds the disguise; items live in the system data.
 * The disguise is the block item sitting in this module's disguise slot.
 */
public class StorageModuleBlockEntity extends BlockEntity implements RenderDataBlockEntity {
	private final SimpleContainer disguiseSlot = new SimpleContainer(1) {
		@Override
		public int getMaxStackSize() {
			return 1;
		}

		@Override
		public void setChanged() {
			super.setChanged();
			StorageModuleBlockEntity.this.onDisguiseChanged();
		}
	};
	private BlockState camouflage;
	private boolean loading;

	public StorageModuleBlockEntity(BlockPos pos, BlockState state) {
		super(ModContent.STORAGE_MODULE_BLOCK_ENTITY, pos, state);
	}

	/** The one-slot container the storage menu shows as this module's disguise slot. */
	public SimpleContainer getDisguiseSlot() {
		return this.disguiseSlot;
	}

	private void onDisguiseChanged() {
		if (this.loading) {
			return;
		}

		BlockState chosen = Camouflage.stateFor(this.disguiseSlot.getItem(0));
		// Keep the current direction if the same block is still in the slot.
		if (chosen != null && this.camouflage != null && this.camouflage.is(chosen.getBlock())) {
			chosen = this.camouflage;
		}

		this.applyCamouflage(chosen);
	}

	/** Turns the disguise to its next direction (for logs, pillars and other blocks that face a way). */
	public void rotateDisguise() {
		if (this.camouflage == null || this.level == null) {
			return;
		}

		List<BlockState> options = Camouflage.orientations(this.camouflage, this.level, this.worldPosition);
		if (options.size() > 1) {
			int index = options.indexOf(this.camouflage);
			this.applyCamouflage(options.get((index + 1) % options.size()));
		}
	}

	private void applyCamouflage(BlockState state) {
		BlockState previous = this.camouflage;
		this.camouflage = state;
		if (this.level == null || previous == this.camouflage) {
			return;
		}

		if (this.level.isClientSide()) {
			// Rebuild the chunk mesh so the new disguise shows.
			this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), Block.UPDATE_IMMEDIATE);
		} else {
			this.setChanged();
			this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), Block.UPDATE_ALL);
		}
	}

	@Override
	public Object getRenderData() {
		return this.camouflage;
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.store("disguise", ItemStack.OPTIONAL_CODEC, this.disguiseSlot.getItem(0));
		output.storeNullable("disguise_state", BlockState.CODEC, this.camouflage);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		ItemStack item = input.read("disguise", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
		BlockState saved = input.read("disguise_state", BlockState.CODEC).orElse(null);
		BlockState base = Camouflage.stateFor(item);
		// Use the saved direction only if it belongs to the block in the slot.
		BlockState look = base != null && saved != null && saved.is(base.getBlock()) ? saved : base;

		this.loading = true;
		this.disguiseSlot.setItem(0, item);
		this.loading = false;
		this.applyCamouflage(look);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return this.saveCustomOnly(registries);
	}

	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		super.preRemoveSideEffects(pos, state);
		if (this.level instanceof ServerLevel serverLevel) {
			// The disguise block comes back when the module is broken.
			Containers.dropContents(serverLevel, pos, this.disguiseSlot);
			StorageSystemManager.get(serverLevel).onModuleRemoved(serverLevel, pos);
		}
	}
}
