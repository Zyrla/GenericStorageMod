package com.zyria.gsm.client;

import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The storage module's model. Without a disguise it draws the normal module; with one it draws the
 * disguise block's model in its place. Only the look changes.
 */
public class CamouflagedModel extends WrapperBlockStateModel {
	public CamouflagedModel(BlockStateModel wrapped) {
		super(wrapped);
	}

	private static BlockState disguise(BlockAndTintGetter level, BlockPos pos) {
		return level.getBlockEntityRenderData(pos) instanceof BlockState state ? state : null;
	}

	private static BlockStateModel modelFor(BlockState state) {
		return Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
	}

	@Override
	public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, Predicate<Direction> cullTest) {
		BlockState disguise = disguise(level, pos);
		if (disguise == null) {
			super.emitQuads(emitter, level, pos, state, random, cullTest);
			return;
		}

		// The renderer would tint using the module's block colors, so apply the disguise's tint here.
		BlockColors colors = Minecraft.getInstance().getBlockColors();
		emitter.pushTransform(quad -> {
			int tintIndex = quad.tintIndex();
			if (tintIndex != -1) {
				BlockTintSource source = colors.getTintSource(disguise, tintIndex);
				if (source != null) {
					quad.multiplyColor(0xFF000000 | source.colorInWorld(disguise, level, pos));
				}

				quad.tintIndex(-1);
			}

			return true;
		});
		modelFor(disguise).emitQuads(emitter, level, pos, disguise, random, cullTest);
		emitter.popTransform();
	}

	@Override
	public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
		return disguise(level, pos) != null ? null : super.createGeometryKey(level, pos, state, random);
	}

	@Override
	public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) {
		BlockState disguise = disguise(level, pos);
		return disguise != null ? modelFor(disguise).particleMaterial(level, pos, disguise) : super.particleMaterial(level, pos, state);
	}

	@Override
	public int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
		BlockState disguise = disguise(level, pos);
		return disguise != null ? modelFor(disguise).materialFlags(level, pos, disguise, random) : super.materialFlags(level, pos, state, random);
	}
}
