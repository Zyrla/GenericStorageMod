package com.zyria.gsm.block;

import com.mojang.serialization.MapCodec;
import com.zyria.gsm.menu.StorageMenu;
import com.zyria.gsm.storage.StorageSystemManager;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class StorageModuleBlock extends BaseEntityBlock {
	public static final Component TITLE = Component.translatable("container.gsm.storage");

	public StorageModuleBlock(Properties properties) {
		super(properties);
	}

	// Blocks needed a codec before 26.3.
	//? if <26.3 {
	public static final MapCodec<StorageModuleBlock> CODEC = simpleCodec(StorageModuleBlock::new);

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}
	//?}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new StorageModuleBlockEntity(pos, state);
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		super.onPlace(state, level, pos, oldState, movedByPiston);
		if (!oldState.is(this) && level instanceof ServerLevel serverLevel) {
			StorageSystemManager.get(serverLevel).onModulePlaced(serverLevel, pos);
		}
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
			StorageSystemManager manager = StorageSystemManager.get(serverLevel);
			manager.removeGhosts(serverLevel, manager.systemAt(pos)); // modules removed by /setblock etc.
			if (manager.systemAt(pos) == null) {
				// A module with no system (for example, placed by a tool that skips block callbacks): join it now.
				manager.onModulePlaced(serverLevel, pos);
			}

			serverPlayer.openMenu(new ExtendedMenuProvider<BlockPos>() {
				@Override
				public BlockPos getScreenOpeningData(ServerPlayer p) {
					return pos;
				}

				@Override
				public Component getDisplayName() {
					return TITLE;
				}

				@Override
				public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player p) {
					if (level.getBlockEntity(pos) instanceof StorageModuleBlockEntity module) {
						return new StorageMenu(containerId, inventory, pos, module.getDisguiseSlot());
					}

					return new StorageMenu(containerId, inventory, pos);
				}
			});
		}

		return InteractionResult.SUCCESS;
	}
}
