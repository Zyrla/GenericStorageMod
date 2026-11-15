package com.zyria.gsm.block;

import com.zyria.gsm.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Disguise rules. A module is disguised by putting a block in the disguise slot of its storage menu.
 */
public final class Camouflage {
	private Camouflage() {
	}

	/** The look a disguise item gives, or null for an empty slot or a non-block item. */
	public static BlockState stateFor(ItemStack stack) {
		if (stack.getItem() instanceof BlockItem blockItem) {
			return blockItem.getBlock().defaultBlockState();
		}

		return null;
	}

	/** Whether this item may go in a disguise slot. */
	public static boolean isAllowedItem(ItemStack stack, Level level, BlockPos pos) {
		BlockState state = stateFor(stack);
		return state != null && isAllowed(state, level, pos);
	}

	private static final Set<Property<?>> DIRECTION_PROPERTIES = Set.of(
		BlockStateProperties.AXIS,
		BlockStateProperties.HORIZONTAL_AXIS,
		BlockStateProperties.FACING,
		BlockStateProperties.HORIZONTAL_FACING
	);

	/**
	 * Every direction the disguise block can face, keeping all its other properties as they are.
	 * Directions that would break the full-cube rule are left out.
	 */
	public static List<BlockState> orientations(BlockState current, Level level, BlockPos pos) {
		List<BlockState> result = new ArrayList<>();
		for (BlockState state : current.getBlock().getStateDefinition().getPossibleStates()) {
			boolean sameOtherwise = true;
			for (Property<?> property : state.getProperties()) {
				if (!DIRECTION_PROPERTIES.contains(property) && !state.getValue(property).equals(current.getValue(property))) {
					sameOtherwise = false;
					break;
				}
			}

			if (sameOtherwise && isAllowed(state, level, pos)) {
				result.add(state);
			}
		}

		return result;
	}

	/** Only full, solid, opaque cubes without block entities. */
	public static boolean isAllowed(BlockState state, Level level, BlockPos pos) {
		Block block = state.getBlock();
		if (block instanceof EntityBlock || state.hasBlockEntity() || state.is(ModContent.STORAGE_MODULE)) {
			return false;
		}

		return state.getRenderShape() == RenderShape.MODEL
			&& state.isSolidRender()
			&& state.canOcclude()
			&& Block.isShapeFullBlock(state.getShape(level, pos))
			&& state.isCollisionShapeFullBlock(level, pos);
	}
}
