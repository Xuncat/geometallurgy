package com.geometallurgy.block;

import com.geometallurgy.blockentity.SmelterBlockEntity;
import com.geometallurgy.blockentity.SmelterProfile;

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
 * 冶炼炉方块。
 *
 * <p>三台炉子（还原 / 电解 / 置换）共用这一个类，差别只在构造时传进来的 {@link SmelterProfile}
 * —— 方块实体也是同一个类，从方块身上把规格读回去。六个面各接什么由方块实体上的
 * {@link com.geometallurgy.blockentity.PortRole} 决定，所以方块本身不带朝向。
 */
public class SmelterBlock extends BaseEntityBlock {

	public static final BooleanProperty LIT = BlockStateProperties.LIT;

	private final SmelterProfile profile;

	public SmelterBlock(SmelterProfile profile, Properties properties) {
		super(properties);
		this.profile = profile;
		registerDefaultState(stateDefinition.any().setValue(LIT, false));
	}

	public SmelterProfile profile() {
		return this.profile;
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
		return new SmelterBlockEntity(pos, state, this.profile);
	}

	/**
	 * 拆掉方块时把槽里的东西弹出来。
	 *
	 * <p>产物是内部的虚拟矿浆，拆了就是拆了；但试剂槽里那根钨棒是实打实的，
	 * 不能跟着方块一起蒸发。
	 */
	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof SmelterBlockEntity smelter) {
			smelter.dropContents();
		}
		return super.playerWillDestroy(level, pos, state, player);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (level.isClientSide()) {
			return null;
		}
		return (tickLevel, pos, tickState, blockEntity) -> {
			if (blockEntity instanceof SmelterBlockEntity smelter
					&& tickLevel instanceof ServerLevel serverLevel) {
				SmelterBlockEntity.serverTick(serverLevel, pos, tickState, smelter);
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
