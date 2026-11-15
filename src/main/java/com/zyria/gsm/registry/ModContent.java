package com.zyria.gsm.registry;

import com.zyria.gsm.GenericStorageMod;
import com.zyria.gsm.block.StorageModuleBlock;
import com.zyria.gsm.block.StorageModuleBlockEntity;
import com.zyria.gsm.item.UpgradeItem;
import com.zyria.gsm.menu.StorageMenu;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import com.zyria.gsm.storage.StorageSystem;
import com.zyria.gsm.storage.StorageSystemManager;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

public final class ModContent {
	public static final ResourceKey<Block> STORAGE_MODULE_KEY = ResourceKey.create(Registries.BLOCK, id("storage_module"));
	public static final ResourceKey<Item> STORAGE_MODULE_ITEM_KEY = ResourceKey.create(Registries.ITEM, id("storage_module"));

	public static final Block STORAGE_MODULE = Registry.register(
		BuiltInRegistries.BLOCK,
		STORAGE_MODULE_KEY,
		new StorageModuleBlock(BlockBehaviour.Properties.of()
			.setId(STORAGE_MODULE_KEY)
			.mapColor(MapColor.WOOD)
			.strength(2.5F)
			.sound(SoundType.WOOD)
			//? if >=26.3 {
			/*.pushReaction(PushReaction.IMMOVEABLE))
			*///?} else
			.pushReaction(PushReaction.BLOCK))
	);

	public static final Item STORAGE_MODULE_ITEM = Registry.register(
		BuiltInRegistries.ITEM,
		STORAGE_MODULE_ITEM_KEY,
		new BlockItem(STORAGE_MODULE, new Item.Properties().setId(STORAGE_MODULE_ITEM_KEY).useBlockDescriptionPrefix())
	);

	public static final Item CRAFTING_UPGRADE = registerUpgrade("crafting_upgrade", UpgradeItem.Type.CRAFTING);
	public static final Item CAPACITY_UPGRADE = registerUpgrade("capacity_upgrade", UpgradeItem.Type.CAPACITY_1);
	public static final Item CAPACITY_UPGRADE_2 = registerUpgrade("capacity_upgrade_2", UpgradeItem.Type.CAPACITY_2);

	private static Item registerUpgrade(String name, UpgradeItem.Type type) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(name));
		return Registry.register(BuiltInRegistries.ITEM, key, new UpgradeItem(type, new Item.Properties().setId(key).stacksTo(16)));
	}

	public static final BlockEntityType<StorageModuleBlockEntity> STORAGE_MODULE_BLOCK_ENTITY = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE,
		id("storage_module"),
		FabricBlockEntityTypeBuilder.create(StorageModuleBlockEntity::new, STORAGE_MODULE).build()
	);

	public static final MenuType<StorageMenu> STORAGE_MENU = Registry.register(
		BuiltInRegistries.MENU,
		id("storage"),
		new ExtendedMenuType<>(StorageMenu::new, BlockPos.STREAM_CODEC)
	);

	private ModContent() {
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(GenericStorageMod.MOD_ID, path);
	}

	public static void init() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(output -> {
			output.accept(STORAGE_MODULE_ITEM);
			output.accept(CRAFTING_UPGRADE);
			output.accept(CAPACITY_UPGRADE);
			output.accept(CAPACITY_UPGRADE_2);
		});

		// Hoppers and pipes on any side of any module reach the whole system.
		ItemStorage.SIDED.registerForBlockEntity((module, direction) -> {
			if (module.getLevel() instanceof ServerLevel serverLevel) {
				StorageSystem system = StorageSystemManager.get(serverLevel).systemAt(module.getBlockPos());
				return system != null ? system.itemStorage() : null;
			}

			return null;
		}, STORAGE_MODULE_BLOCK_ENTITY);
	}
}
