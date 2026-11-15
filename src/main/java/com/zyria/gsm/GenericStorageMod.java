package com.zyria.gsm;

import com.zyria.gsm.network.ModNetworking;
import com.zyria.gsm.registry.ModContent;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GenericStorageMod implements ModInitializer {
	public static final String MOD_ID = "gsm";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModConfig.load();
		ModContent.init();
		ModNetworking.init();
	}
}
