package com.geometallurgy.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.sounds.BlockSoundSets;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import team.reborn.energy.api.EnergyStorage;

/**
 * 无线感应器：贴在机器顶面的一小片天线。
 *
 * <h2>它的全部工作</h2>
 * 感应器本身不存电、不传电 —— 它只是一张「许可」：蓄电池在扫描 19³ 范围时，
 * 看到哪台机器头顶装了这个，才把那台机器算进无线供电网（见 {@code BatteryBlockEntity}）。
 * 之所以做成一个实体方块而不是机器身上的升级件，是因为它要<b>看得见</b>：
 * 哪台机器入了网，绕着基地走一圈就一目了然，不用逐台开界面查。
 *
 * <h2>安装规则</h2>
 * 只能立在「有能量接口的方块」顶面 —— {@link #canSurvive} 用
 * {@code EnergyStorage.SIDED.find} 探下面的方块，探不到就立不住。
 * 宿主机被拆掉时，{@link #updateShape} 让感应器自己碎掉（不掉落）：
 * 机器都没了，天线悬在半空没有意义。
 */
public class WirelessSensorBlock extends Block {

	/** 底座一片 + 一根细杆的高瘦轮廓；碰撞只给底座，杆不挡路。 */
	private static final VoxelShape SHAPE = Block.box(3, 0, 3, 13, 5, 13);

	public WirelessSensorBlock(Properties properties) {
		super(properties);
	}

	public static Properties sensorProperties() {
		return Block.Properties.of()
				.mapColor(MapColor.METAL)
				.strength(0.8F)
				.sound(BlockSoundSets.METAL)
				.noOcclusion();
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		// 无状态：朝向由它立在哪决定，不需要数据
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		return hasEnergyHost(level, pos);
	}

	/** 放置时也走同一套判定：宿主机没有能量接口就不让放。 */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return hasEnergyHost(context.getLevel(), context.getClickedPos())
				? this.defaultBlockState()
				: null;
	}

	/**
	 * 宿主机被拆时跟着碎掉。
	 *
	 * <p>返回空气而不是保持原状：canSurvive 只在邻方块变化时被走到，
	 * 这里就是「机器没了」那一刻的入口。
	 */
	@Override
	protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess tickAccess,
			BlockPos pos, Direction direction, BlockPos neighborPos, BlockState neighborState,
			RandomSource random) {
		if (direction == Direction.DOWN && !hasEnergyHost(level, pos)) {
			return Blocks.AIR.defaultBlockState();
		}
		return super.updateShape(state, level, tickAccess, pos, direction, neighborPos, neighborState, random);
	}

	/** 下面的方块有没有能量存储 —— 有，它就是一台可无线的机器。 */
	private static boolean hasEnergyHost(LevelReader level, BlockPos sensorPos) {
		if (!(level instanceof net.minecraft.world.level.Level real)) {
			// 模板/客户端预测阶段拿不到真正的世界：放行，真正的把关在 getStateForPlacement
			return true;
		}
		return EnergyStorage.SIDED.find(real, sensorPos.below(), Direction.UP) != null;
	}
}
