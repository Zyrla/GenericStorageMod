package com.zyria.gsm.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/** This player's own display settings, saved in config/gsm-client.properties. */
public final class ClientSettings {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("gsm-client.properties");
	private static boolean loaded;
	private static boolean showFullness = true;

	private ClientSettings() {
	}

	public static boolean showFullness() {
		load();
		return showFullness;
	}

	public static void setShowFullness(boolean value) {
		load();
		showFullness = value;
		save();
	}

	private static void load() {
		if (loaded) {
			return;
		}

		loaded = true;
		if (!Files.exists(FILE)) {
			return;
		}

		Properties props = new Properties();
		try (Reader reader = Files.newBufferedReader(FILE)) {
			props.load(reader);
			showFullness = !"false".equals(props.getProperty("show_fullness"));
		} catch (IOException ignored) {
			// Unreadable file: keep the defaults.
		}
	}

	private static void save() {
		Properties props = new Properties();
		props.setProperty("show_fullness", Boolean.toString(showFullness));
		try (Writer writer = Files.newBufferedWriter(FILE)) {
			props.store(writer, "Generic Storage Mod client settings");
		} catch (IOException ignored) {
			// Not saved; the setting still applies until the game closes.
		}
	}
}
