package com.geometallurgy.block;

import com.geometallurgy.blockentity.AbstractReactorBlockEntity;
import com.geometallurgy.blockentity.MixerBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 搅拌器：与反应釜同一套「混合 mol 槽 + 六面配置」的底座，多了一个比率旋钮、并且会搅拌。
 *
 * <p>它的价值在两处：一是<b>精准配比</b> —— 两个入口的料按设定比例兑进混合槽，
 * 于是组成可控，反应器只会匹配到「想要的那条」；二是<b>搅拌</b> —— 像 HCl、SO₃ 这种
 * 不搅几乎不发生的合成，只有这里做得成。
 */
public class MixerBlock extends BaseEntityBlock {

	public static final BooleanProperty LIT = BlockStateProperties.LIT;

	public MixerBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(LIT, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(LIT);
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new MixerBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (level.isClientSide()) {
			return null;
		}
		return (tickLevel, pos, tickState, blockEntity) -> {
			if (blockEntity instanceof MixerBlockEntity mixer && tickLevel instanceof ServerLevel serverLevel) {
				AbstractReactorBlockEntity.serverTick(serverLevel, pos, tickState, mixer);
			}
		};
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
			BlockHitResult hit) {
		if (level instanceof ServerLevel serverLevel) {
			MenuProvider provider = this.getMenuProvider(state, serverLevel, pos);
			if (provider != null) {
				player.openMenu(provider);
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
			net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
		if (level instanceof ServerLevel serverLevel) {
			MenuProvider provider = this.getMenuProvider(state, serverLevel, pos);
			if (provider != null) {
				player.openMenu(provider);
			}
		}
		return InteractionResult.SUCCESS;
	}
}
