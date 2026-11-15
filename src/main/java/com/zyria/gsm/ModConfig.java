package com.zyria.gsm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Settings, read from config/gsm.json. The file is created with defaults on first launch.
 * These are server settings: in multiplayer, the server's file is the one that counts.
 */
public final class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ModConfig instance = new ModConfig();

	public String _help = "slots_per_module: slots in each module (1-270). Lowering it never removes items from modules that already exist. "
		+ "allow_merging: if false, a module touching two systems joins only one of them (the larger one) instead of merging them. "
		+ "sync_interval_ticks: how often open menus update while items are moving (1-100, 20 ticks = 1 second). "
		+ "Restart the game or server after editing.";
	public int slots_per_module = 27;
	public boolean allow_merging = true;
	public int sync_interval_ticks = 2;

	public static ModConfig get() {
		return instance;
	}

	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(GenericStorageMod.MOD_ID + ".json");
		ModConfig config = new ModConfig();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				ModConfig read = GSON.fromJson(reader, ModConfig.class);
				if (read != null) {
					config = read;
				}
			} catch (IOException | RuntimeException e) {
				GenericStorageMod.LOGGER.error("Could not read {}, using defaults", path, e);
			}
		}

		config.slots_per_module = Math.clamp(config.slots_per_module, 1, 270);
		config.sync_interval_ticks = Math.clamp(config.sync_interval_ticks, 1, 100);
		config._help = new ModConfig()._help;
		instance = config;

		// Write it back so new or missing settings show up in the file.
		try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			GSON.toJson(config, writer);
		} catch (IOException e) {
			GenericStorageMod.LOGGER.error("Could not write {}", path, e);
		}
	}
}
