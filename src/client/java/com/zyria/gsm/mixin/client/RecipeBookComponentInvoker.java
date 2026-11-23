package com.zyria.gsm.mixin.client;

import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets the storage menu ask the vanilla recipe book to recount what's available. The book only does
 * that by itself when the player's inventory changes, but here storage counts too.
 */
@Mixin(RecipeBookComponent.class)
public interface RecipeBookComponentInvoker {
	@Invoker("updateStackedContents")
	void gsm$updateStackedContents();
}
