package com.zyria.gsm.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * An automatic tab rule. Written as "#namespace:path" for an item tag, or "namespace:path" for one item.
 */
public record TabRule(boolean tag, Identifier id) {
	public static final Codec<TabRule> CODEC = Codec.STRING.comapFlatMap(TabRule::parse, TabRule::toString);

	public static DataResult<TabRule> parse(String text) {
		String s = text.trim();
		boolean isTag = s.startsWith("#");
		if (isTag) {
			s = s.substring(1);
		}

		Identifier parsed = Identifier.tryParse(s);
		if (parsed == null || s.isEmpty()) {
			return DataResult.error(() -> "Invalid rule: " + text);
		}

		return DataResult.success(new TabRule(isTag, parsed));
	}

	public static TabRule tag(String id) {
		return new TabRule(true, Identifier.parse(id));
	}

	public boolean matches(Item item) {
		if (this.tag) {
			return item.builtInRegistryHolder().is(TagKey.create(Registries.ITEM, this.id));
		}

		return BuiltInRegistries.ITEM.getKey(item).equals(this.id);
	}

	@Override
	public String toString() {
		return (this.tag ? "#" : "") + this.id;
	}
}
