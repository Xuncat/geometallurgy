package com.geometallurgy.blockentity;

import com.geometallurgy.block.ReactionVesselBlock;
import com.geometallurgy.chemical.Chemical;
import com.geometallurgy.chemical.MolStock;
import java.util.Map;

import com.geometallurgy.menu.ReactionVesselMenu;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * 反应釜（版本 B）。
 *
 * <p>它只是「一本混合 mol 槽 + 六个可配面」的一台机器：
 * <ul>
 *   <li>三个输入面（{@link PortRole#INPUT}）把相邻容器里装了料的桶倒进混合槽；</li>
 *   <li>输出面按<b>输出相态</b>把槽里的产物灌进空桶，再放回相邻容器；</li>
 *   <li>每 tick 把全部能跑的反应都跑一遍，产物留在槽里等下一 tick 继续反应。</li>
 * </ul>
 *
 * <p>{@link PortRole#OUTPUT} 与 {@link PortRole#BYPRODUCT} 在反应釜上没有区别 ——
 * 相态由「输出相态」这一个旋钮决定，不再是两个固定的槽位。分成两个角色只是为了允许
 * 玩家把「液体出」和「沉淀出」分到两个面上；实际出什么，看的是当时选的相态。
 *
 * <p><b>不需要电。</b>反应釜是化学的，不是电的。
 *
 * <p>{@link #stirred()} 恒为 false：需要搅拌的反应会在这里报
 * {@link ReactorStatus#NEEDS_STIRRING}，提醒玩家换搅拌器。
 */
public class ReactionVesselBlockEntity extends AbstractReactorBlockEntity {

	/** 输入缓冲的容量（mol）：面上收进来的料先存这里，再匀速兑进混合槽。 */
	public static final double INPUT_CAPACITY = 60.0;

	/** 第一本输入缓冲（A 面）。 */
	private final MolStock input = MolStock.empty();

	/** 第二本输入缓冲（B 面）；两本各自计量，界面画两根条。 */
	private final MolStock inputB = MolStock.empty();

	public ReactionVesselBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.REACTION_VESSEL, pos, state);
	}

	@Override
	protected int roleCount() {
		return VesselRole.values().length;
	}

	@Override
	protected boolean isInputRole(int ordinal) {
		return ordinal == VesselRole.INPUT.ordinal() || ordinal == VesselRole.INPUT_B.ordinal();
	}

	@Override
	protected boolean isOutputRole(int ordinal) {
		return outputPhaseOf(ordinal) != null;
	}

	@Override
	protected Chemical.Phase outputPhaseOf(int ordinal) {
		return VesselRole.values()[Math.floorMod(ordinal, VesselRole.values().length)].phase();
	}

	@Override
	protected MolStock inputBuffer(int ordinal) {
		// 输入先进缓冲，再由 mix() 兑进混合槽 —— 这样界面能同时看到「进料」与「槽内」两根条。
		return ordinal == VesselRole.INPUT_B.ordinal() ? this.inputB : this.input;
	}

	@Override
	protected double inputBufferCapacity(int ordinal) {
		return INPUT_CAPACITY;
	}

	@Override
	public double bufferMol(int index) {
		return (index == 0 ? this.input : this.inputB).totalMol();
	}

	/** 进料饼图：A、B 两本缓冲按 mol 合并成一本次本。 */
	@Override
	protected void addFeed(MolStock out) {
		for (Map.Entry<String, Double> e : this.input.view().entrySet()) {
			out.add(e.getKey(), e.getValue());
		}
		for (Map.Entry<String, Double> e : this.inputB.view().entrySet()) {
			out.add(e.getKey(), e.getValue());
		}
	}

	/** 把输入缓冲匀速兑进混合槽（反应釜没有比率，来多少兑多少）。 */
	@Override
	protected void mix() {
		double room = TANK_CAPACITY - this.tank.totalMol();
		if (room <= MIN_MOL) {
			return;
		}
		if (!this.input.isEmpty()) {
			pour(this.input, this.tank, Math.min(room, this.input.totalMol()));
			room = TANK_CAPACITY - this.tank.totalMol();
		}
		if (room > MIN_MOL && !this.inputB.isEmpty()) {
			pour(this.inputB, this.tank, Math.min(room, this.inputB.totalMol()));
		}
		this.setChanged();
	}

	@Override
	protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput out) {
		super.saveAdditional(out);
		out.putString("input", encode(this.input));
		out.putString("input_b", encode(this.inputB));
	}

	@Override
	protected void loadAdditional(net.minecraft.world.level.storage.ValueInput in) {
		super.loadAdditional(in);
		decode(this.input, in.getStringOr("input", ""));
		decode(this.inputB, in.getStringOr("input_b", ""));
	}

	@Override
	protected int nextRole(int ordinal) {
		return VesselRole.values()[(ordinal + 1) % VesselRole.values().length].ordinal();
	}

	@Override
	public String roleKey(int ordinal) {
		return VesselRole.values()[ordinal].key();
	}

	@Override
	protected boolean stirred() {
		return false;
	}

	@Override
	protected BooleanProperty litProperty() {
		return ReactionVesselBlock.LIT;
	}

	@Override
	public AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player player) {
		return new ReactionVesselMenu(syncId, inventory, this);
	}
}
