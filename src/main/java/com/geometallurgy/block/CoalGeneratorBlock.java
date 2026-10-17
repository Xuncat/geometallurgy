package com.geometallurgy.block;

import com.geometallurgy.blockentity.CoalGeneratorBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
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
 * 火力发电机：烧煤发电。
 *
 * <p>每块煤燃烧 200 刻（10 秒），每刻产 64 单位能量；产出的能量按
 * Team Reborn Energy 的 push 语义主动推给相邻的用能机器。
 */
public class CoalGeneratorBlock extends BaseEntityBlock {

	public static final BooleanProperty LIT = BlockStateProperties.LIT;

	public CoalGeneratorBlock(Properties properties) {
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
		return new CoalGeneratorBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (level.isClientSide()) {
			return null;
		}
		return (tickLevel, pos, tickState, blockEntity) -> {
			if (blockEntity instanceof CoalGeneratorBlockEntity generator && tickLevel instanceof ServerLevel serverLevel) {
				CoalGeneratorBlockEntity.serverTick(serverLevel, pos, tickState, generator);
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

	/**
	 * 破坏时把燃料槽与输出槽里的东西掉落出来，避免被吞掉。
	 *
	 * <p>注意：本方块实体只是持有容器字段，自身不是 {@code Container}，
	 * {@code BlockEntity.preRemoveSideEffects} 的通用掉落不适用。
	 */
	@Override
	public void playerDestroy(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state,
			BlockEntity blockEntity, ItemStack tool) {
		if (blockEntity instanceof CoalGeneratorBlockEntity generator) {
			Containers.dropContents(level, pos, generator.getFuelContainer());
			Containers.dropContents(level, pos, generator.getOutputContainer());
		}
		super.playerDestroy(level, player, pos, state, blockEntity, tool);
	}

	/** 发电机本身不参与红石，方向仅用于能量推送上判断邻面。 */
	public static Direction[] allDirections() {
		return Direction.values();
	}
}
