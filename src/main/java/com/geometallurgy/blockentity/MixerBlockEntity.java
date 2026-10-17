package com.geometallurgy.blockentity;

import com.geometallurgy.block.MixerBlock;
import com.geometallurgy.chemical.Chemical;
import com.geometallurgy.chemical.MolBlend;
import com.geometallurgy.chemical.MolStock;
import java.util.Map;

import com.geometallurgy.menu.MixerMenu;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * 搅拌器。
 *
 * <p>与反应釜共用一个底座，只差三件事：
 * <ol>
 *   <li><b>两个入口</b>：A 面（{@link MixerRole#INPUT_A}）与 B 面（{@link MixerRole#INPUT_B}）
 *       各有自己的预混缓冲，桶倒进来先存着；</li>
 *   <li><b>比率</b>：{@link #ratio()} 是 A 占的百分比，每 tick 按这个比例把两边的缓冲兑进混合槽 ——
 *       这就是「精准配比」；</li>
 *   <li><b>会搅拌</b>：{@link #stirred()} 恒为 true，所以需要搅拌的反应（HCl、SO₃ 合成）
 *       只有在这里才会发生。</li>
 * </ol>
 *
 * <p>兑进去之后的流程与反应釜完全一致：匹配全部可发生的反应 → 产物留在槽里 → 下一 tick 继续反应。
 * 不同之处在于搅拌器能先把组成调好再反应，所以它「防止不需要的产物生成」——
 * 反应釜喂什么就反应什么，搅拌器可以先配准再放行。
 *
 * <p><b>不需要电。</b>
 */
public class MixerBlockEntity extends AbstractReactorBlockEntity {

	/** 每侧预混缓冲的容量（mol）。 */
	public static final double BUFFER_CAPACITY = 90.0;

	/** 默认比率：各一半。 */
	public static final int DEFAULT_RATIO = 50;

	/**
	 * 比率滑轨的按钮 id 起点：界面把 0..100 的整数编码成 {@code 20 + percent} 发过来。
	 *
	 * <p>旧的 {@code 7..11} 五档格子太粗；新的一段与它不重叠，也不进 0..5 的六面格子区间。
	 */
	public static final int SET_RATIO_BUTTON_BASE = 20;

	/** 搅拌开关的按钮 id。 */
	public static final int STIR_BUTTON = 12;

	private final MolStock bufferA = MolStock.empty();
	private final MolStock bufferB = MolStock.empty();

	private int ratio = DEFAULT_RATIO;

	/** 搅拌开关。默认开 —— 关掉之后需要搅拌的反应就不会发生（用来「先配比、后放行」）。 */
	private boolean stirring = true;

	public MixerBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.MIXER, pos, state);
	}

	// ---------------------------------------------------------------- 面角色

	@Override
	protected int roleCount() {
		return MixerRole.values().length;
	}

	@Override
	protected boolean isInputRole(int ordinal) {
		return ordinal == MixerRole.INPUT_A.ordinal() || ordinal == MixerRole.INPUT_B.ordinal();
	}

	@Override
	protected boolean isOutputRole(int ordinal) {
		return outputPhaseOf(ordinal) != null;
	}

	@Override
	protected Chemical.Phase outputPhaseOf(int ordinal) {
		return MixerRole.values()[Math.floorMod(ordinal, MixerRole.values().length)].phase();
	}

	@Override
	protected MolStock inputBuffer(int ordinal) {
		return ordinal == MixerRole.INPUT_B.ordinal() ? this.bufferB : this.bufferA;
	}

	@Override
	protected double inputBufferCapacity(int ordinal) {
		return BUFFER_CAPACITY;
	}

	@Override
	protected int nextRole(int ordinal) {
		return MixerRole.values()[(ordinal + 1) % MixerRole.values().length].ordinal();
	}

	@Override
	public String roleKey(int ordinal) {
		return MixerRole.values()[ordinal].key();
	}

	// ---------------------------------------------------------------- 比率

	@Override
	public int ratio() {
		return this.ratio;
	}

	@Override
	public void setRatio(int percent) {
		this.ratio = Math.max(0, Math.min(100, percent));
		this.setChanged();
	}

	/**
	 * 界面按钮：{@code 20..120} 是滑轨设的比率（0..100），{@code 12} 是搅拌开关，
	 * 其余交给基类（0..5 面）。
	 */
	@Override
	public boolean handleButton(int id) {
		int percent = id - SET_RATIO_BUTTON_BASE;
		if (percent >= 0 && percent <= 100) {
			setRatio(percent);
			return true;
		}
		if (id == STIR_BUTTON) {
			setStirring(!this.stirring);
			return true;
		}
		return super.handleButton(id);
	}

	/** 搅拌开关。 */
	public void setStirring(boolean value) {
		this.stirring = value;
		this.setChanged();
	}

	// ---------------------------------------------------------------- 存档

	@Override
	protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput out) {
		super.saveAdditional(out);
		out.putString("buffer_a", encode(this.bufferA));
		out.putString("buffer_b", encode(this.bufferB));
		out.putBoolean("stirring", this.stirring);
	}

	@Override
	protected void loadAdditional(net.minecraft.world.level.storage.ValueInput in) {
		super.loadAdditional(in);
		decode(this.bufferA, in.getStringOr("buffer_a", ""));
		decode(this.bufferB, in.getStringOr("buffer_b", ""));
		this.stirring = in.getBooleanOr("stirring", true);
	}

	// ---------------------------------------------------------------- 搅拌

	@Override
	protected boolean stirred() {
		return this.stirring;
	}

	@Override
	public boolean stirringOn() {
		return this.stirring;
	}

	@Override
	protected BooleanProperty litProperty() {
		return MixerBlock.LIT;
	}

	@Override
	public double bufferMol(int index) {
		return (index == 0 ? this.bufferA : this.bufferB).totalMol();
	}

	/** 进料饼图：A、B 两本缓冲按 mol 合并成一本次本。 */
	@Override
	protected void addFeed(MolStock out) {
		for (Map.Entry<String, Double> e : this.bufferA.view().entrySet()) {
			out.add(e.getKey(), e.getValue());
		}
		for (Map.Entry<String, Double> e : this.bufferB.view().entrySet()) {
			out.add(e.getKey(), e.getValue());
		}
	}

	/**
	 * 把两边的预混缓冲兑进混合槽：先按比率兑一批，再把余料自由兑完。
	 *
	 * <p>比率仍是「精准配比」的偏好（决定均衡段的 A:B），但<b>不再是把料锁死在缓冲里的闸门</b>：
	 * 只要混合槽还有空间，两侧的料最终都会进槽。旧实现只做「按比率兑一批」，
	 * 一侧缺料（{@code B/bFrac = 0}）或比率不整除时另一侧会永久卡在缓冲里，
	 * 表现为倒进氯气和水也「搅不动」—— 见 {@link MolBlend}。
	 */
	@Override
	protected void mix() {
		if (MolBlend.blend(this.bufferA, this.bufferB, this.tank, this.ratio / 100.0, TANK_CAPACITY)) {
			this.setChanged();
		}
	}

	@Override
	public AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player player) {
		return new MixerMenu(syncId, inventory, this);
	}
}
