package com.geometallurgy.block;

import com.geometallurgy.blockentity.MagneticSeparatorBlockEntity;

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
 * 磁力筛机：把上游送来的混合矿浆按磁性拆成精矿与尾矿。
 *
 * <p>方块本身不带朝向：六个面各自接什么，是方块实体上的配置（{@link com.geometallurgy.blockentity.FaceRole}），
 * 与方块怎么摆无关。玩家不必为了「把出口转到管道那一侧」而拆了重放。
 *
 * <p>只有 {@code lit} 一个状态 —— 磁场偏出窗口时灯照样亮着，因为机器确实在干活，只是干得不好。
 */
public class MagneticSeparatorBlock extends BaseEntityBlock {

	public static final BooleanProperty LIT = BlockStateProperties.LIT;

	public MagneticSeparatorBlock(Properties properties) {
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
		return new MagneticSeparatorBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (level.isClientSide()) {
			return null;
		}
		return (tickLevel, pos, tickState, blockEntity) -> {
			if (blockEntity instanceof MagneticSeparatorBlockEntity separator
					&& tickLevel instanceof ServerLevel serverLevel) {
				MagneticSeparatorBlockEntity.serverTick(serverLevel, pos, tickState, separator);
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
