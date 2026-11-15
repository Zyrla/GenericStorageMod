package com.zyria.gsm.client;

import com.zyria.gsm.menu.StorageMenu;
import com.zyria.gsm.network.ModNetworking;
import com.zyria.gsm.registry.ModContent;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.MenuScreens;

public class GenericStorageModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		MenuScreens.register(ModContent.STORAGE_MENU, StorageScreen::new);

		ClientPlayNetworking.registerGlobalReceiver(ModNetworking.SyncView.TYPE, (payload, context) -> {
			if (context.player().containerMenu instanceof StorageMenu menu && menu.containerId == payload.containerId()) {
				menu.applyView(payload);
			}
		});

		// Wrap the module's model so a disguised module draws its disguise block instead.
		ModelLoadingPlugin.register(plugin -> plugin.modifyBlockModelAfterBake().register((model, context) ->
			context.state().is(ModContent.STORAGE_MODULE) ? new CamouflagedModel(model) : model));
	}
}
