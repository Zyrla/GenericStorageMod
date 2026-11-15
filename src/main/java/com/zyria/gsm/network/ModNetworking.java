package com.zyria.gsm.network;

import com.zyria.gsm.menu.StorageMenu;
import com.zyria.gsm.registry.ModContent;
import com.zyria.gsm.storage.StorageTab;
import com.zyria.gsm.storage.StoredEntry;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class ModNetworking {
	private static final int MAX_VIEW_PACKET_SIZE = 32 * 1024 * 1024;

	private ModNetworking() {
	}

	// ---------------------------------------------------------------- server -> client

	/**
	 * An update for an open menu. With {@code full}, {@code changes} is every item; otherwise only items
	 * whose count changed (count 0 = gone). Tabs are included only when they changed.
	 */
	public record SyncView(int containerId, boolean full, List<StoredEntry> changes, Optional<List<StorageTab>> tabs,
						   int modules, int totalSlots, float percentFull) implements CustomPacketPayload {
		public static final Type<SyncView> TYPE = new Type<>(ModContent.id("sync_view"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SyncView> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, SyncView::containerId,
			ByteBufCodecs.BOOL, SyncView::full,
			StoredEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), SyncView::changes,
			ByteBufCodecs.optional(StorageTab.STREAM_CODEC.apply(ByteBufCodecs.list())), SyncView::tabs,
			ByteBufCodecs.VAR_INT, SyncView::modules,
			ByteBufCodecs.VAR_INT, SyncView::totalSlots,
			ByteBufCodecs.FLOAT, SyncView::percentFull,
			SyncView::new
		);

		@Override
		public Type<SyncView> type() {
			return TYPE;
		}
	}

	// ---------------------------------------------------------------- client -> server

	public static final int CLICK_LEFT = 0;
	public static final int CLICK_RIGHT = 1;
	public static final int CLICK_SHIFT = 2;

	/** A click in the item area. {@code stack} is the clicked item, or empty for an empty cell. */
	public record ClickEntry(int containerId, ItemStack stack, int action) implements CustomPacketPayload {
		public static final Type<ClickEntry> TYPE = new Type<>(ModContent.id("click_entry"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ClickEntry> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ClickEntry::containerId,
			ItemStack.OPTIONAL_UNTRUSTED_STREAM_CODEC, ClickEntry::stack,
			ByteBufCodecs.VAR_INT, ClickEntry::action,
			ClickEntry::new
		);

		@Override
		public Type<ClickEntry> type() {
			return TYPE;
		}
	}

	/** Manually assigns an item to a tab, or removes it from the tab. */
	public record AssignItem(int containerId, int tabId, ItemStack stack, boolean add) implements CustomPacketPayload {
		public static final Type<AssignItem> TYPE = new Type<>(ModContent.id("assign_item"));
		public static final StreamCodec<RegistryFriendlyByteBuf, AssignItem> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, AssignItem::containerId,
			ByteBufCodecs.VAR_INT, AssignItem::tabId,
			ItemStack.OPTIONAL_UNTRUSTED_STREAM_CODEC, AssignItem::stack,
			ByteBufCodecs.BOOL, AssignItem::add,
			AssignItem::new
		);

		@Override
		public Type<AssignItem> type() {
			return TYPE;
		}
	}

	/** Creates (tabId = -1) or edits a tab. */
	public record EditTab(int containerId, int tabId, String name, String icon, boolean sorting,
						  List<String> rules) implements CustomPacketPayload {
		public static final Type<EditTab> TYPE = new Type<>(ModContent.id("edit_tab"));
		public static final StreamCodec<RegistryFriendlyByteBuf, EditTab> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, EditTab::containerId,
			ByteBufCodecs.VAR_INT, EditTab::tabId,
			ByteBufCodecs.stringUtf8(64), EditTab::name,
			ByteBufCodecs.stringUtf8(256), EditTab::icon,
			ByteBufCodecs.BOOL, EditTab::sorting,
			ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list(128)), EditTab::rules,
			EditTab::new
		);

		@Override
		public Type<EditTab> type() {
			return TYPE;
		}
	}

	public record RemoveTab(int containerId, int tabId) implements CustomPacketPayload {
		public static final Type<RemoveTab> TYPE = new Type<>(ModContent.id("remove_tab"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RemoveTab> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, RemoveTab::containerId,
			ByteBufCodecs.VAR_INT, RemoveTab::tabId,
			RemoveTab::new
		);

		@Override
		public Type<RemoveTab> type() {
			return TYPE;
		}
	}

	public record MoveTab(int containerId, int tabId, int newIndex) implements CustomPacketPayload {
		public static final Type<MoveTab> TYPE = new Type<>(ModContent.id("move_tab"));
		public static final StreamCodec<RegistryFriendlyByteBuf, MoveTab> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, MoveTab::containerId,
			ByteBufCodecs.VAR_INT, MoveTab::tabId,
			ByteBufCodecs.VAR_INT, MoveTab::newIndex,
			MoveTab::new
		);

		@Override
		public Type<MoveTab> type() {
			return TYPE;
		}
	}

	public record SetSortMode(int containerId, int tabId, int mode) implements CustomPacketPayload {
		public static final Type<SetSortMode> TYPE = new Type<>(ModContent.id("set_sort_mode"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SetSortMode> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, SetSortMode::containerId,
			ByteBufCodecs.VAR_INT, SetSortMode::tabId,
			ByteBufCodecs.VAR_INT, SetSortMode::mode,
			SetSortMode::new
		);

		@Override
		public Type<SetSortMode> type() {
			return TYPE;
		}
	}

	/** Setting ids for {@link ChangeSetting}. */
	public static final int SETTING_CRAFTING_AUTO_FILL = 0;

	/** Changes one of the system's settings (from the Settings panel). */
	public record ChangeSetting(int containerId, int setting, int value) implements CustomPacketPayload {
		public static final Type<ChangeSetting> TYPE = new Type<>(ModContent.id("change_setting"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ChangeSetting> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ChangeSetting::containerId,
			ByteBufCodecs.VAR_INT, ChangeSetting::setting,
			ByteBufCodecs.VAR_INT, ChangeSetting::value,
			ChangeSetting::new
		);

		@Override
		public Type<ChangeSetting> type() {
			return TYPE;
		}
	}

	public record RotateDisguise(int containerId) implements CustomPacketPayload {
		public static final Type<RotateDisguise> TYPE = new Type<>(ModContent.id("rotate_disguise"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RotateDisguise> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, RotateDisguise::containerId,
			RotateDisguise::new
		);

		@Override
		public Type<RotateDisguise> type() {
			return TYPE;
		}
	}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().registerLarge(SyncView.TYPE, SyncView.CODEC, MAX_VIEW_PACKET_SIZE);
		PayloadTypeRegistry.serverboundPlay().register(ClickEntry.TYPE, ClickEntry.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(AssignItem.TYPE, AssignItem.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(EditTab.TYPE, EditTab.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RemoveTab.TYPE, RemoveTab.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(MoveTab.TYPE, MoveTab.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SetSortMode.TYPE, SetSortMode.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RotateDisguise.TYPE, RotateDisguise.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ChangeSetting.TYPE, ChangeSetting.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(ChangeSetting.TYPE, (payload, context) -> {
			StorageMenu menu = openMenu(context.player(), payload.containerId());
			if (menu != null) {
				menu.handleSetting(payload.setting(), payload.value());
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(MoveTab.TYPE, (payload, context) -> {
			StorageMenu menu = openMenu(context.player(), payload.containerId());
			if (menu != null) {
				menu.handleMoveTab(payload.tabId(), payload.newIndex());
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(SetSortMode.TYPE, (payload, context) -> {
			StorageMenu menu = openMenu(context.player(), payload.containerId());
			if (menu != null) {
				menu.handleSortMode(payload.tabId(), payload.mode());
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(RotateDisguise.TYPE, (payload, context) -> {
			StorageMenu menu = openMenu(context.player(), payload.containerId());
			if (menu != null) {
				menu.handleRotateDisguise();
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(ClickEntry.TYPE, (payload, context) -> {
			StorageMenu menu = openMenu(context.player(), payload.containerId());
			if (menu != null) {
				menu.handleEntryClick(context.player(), payload.stack(), payload.action());
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(AssignItem.TYPE, (payload, context) -> {
			StorageMenu menu = openMenu(context.player(), payload.containerId());
			if (menu != null) {
				menu.handleAssign(payload.tabId(), payload.stack(), payload.add());
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(EditTab.TYPE, (payload, context) -> {
			StorageMenu menu = openMenu(context.player(), payload.containerId());
			if (menu != null) {
				menu.handleEditTab(payload.tabId(), payload.name(), payload.icon(), payload.sorting(), payload.rules());
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(RemoveTab.TYPE, (payload, context) -> {
			StorageMenu menu = openMenu(context.player(), payload.containerId());
			if (menu != null) {
				menu.handleRemoveTab(payload.tabId());
			}
		});
	}

	private static StorageMenu openMenu(ServerPlayer player, int containerId) {
		if (player.containerMenu instanceof StorageMenu menu && menu.containerId == containerId && menu.stillValid(player)) {
			return menu;
		}

		return null;
	}
}
