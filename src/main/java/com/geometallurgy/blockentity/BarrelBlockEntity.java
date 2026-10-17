package com.geometallurgy.blockentity;

import java.util.ArrayList;
import java.util.Map;

import com.geometallurgy.block.BarrelBlock;
import com.geometallurgy.chemical.Chemical;
import com.geometallurgy.chemical.MolStock;
import com.geometallurgy.chemical.MolTank;
import com.geometallurgy.item.BucketMaterial;
import com.geometallurgy.item.ModComponents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 储罐的方块实体：一本容量 60 mol（= 4 桶）的混合账。
 *
 * <p>四种材质共用这一个类：材质从方块身上读回来（{@link BarrelBlock#material()}），
 * 决定这个罐子耐不耐蚀。{@link MolTank} 让机器可以按面直接和储罐过户 mol，
 * 不必经过物品桶 —— 桶只负责玩家手动搬运。
 *
 * <p>存档沿用反应釜那套「{@code id:mol;id:mol}」的字符串形式，简单、无损、与版本无关。
 */
public class BarrelBlockEntity extends BlockEntity implements MolTank {

	/** 容量（mol）：60 mol = 4 桶。 */
	public static final double CAPACITY = 60.0;

	/** 少于此值就当没有（mol）。 */
	private static final double MIN_MOL = 1.0e-3;

	/** 罐内的混合账 —— 唯一的物料真相。 */
	private final MolStock tank = MolStock.empty();

	/** 罐身材质（耐蚀判定与界面提示用）。 */
	private final BucketMaterial material;

	public BarrelBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.BARREL, pos, state);
		this.material = state.getBlock() instanceof BarrelBlock barrel ? barrel.material() : BucketMaterial.IRON;
	}

	/** 这只罐的材质。 */
	public BucketMaterial material() {
		return this.material;
	}

// ------------------------------------------------------------------ MolTank

	@Override
	public MolStock tank() {
		return this.tank;
	}

	@Override
	public double capacity() {
		return CAPACITY;
	}

	/** 这个罐的材质装得住这本账吗（耐蚀判定）。 */
	@Override
	public boolean holds(MolStock stock) {
		return this.material.holds(stock);
	}

	/**
	 * 收料：最多收 {@code maxMol}，再受罐子余量钳制。
	 *
	 * <p>按占比等比例收，返回的账就是从 {@code amount} 里实际挪走的部分。
	 */
	@Override
	public MolStock pour(MolStock amount, double maxMol) {
		MolStock moved = MolStock.empty();
		if (amount == null || amount.isEmpty() || maxMol <= MIN_MOL) {
			return moved;
		}
		// room() 可能因为浮点误差变负；limit 一旦为负，下面的 take 会把 -负 变成"加料"，
		// 那就会越加越多。这里先钳到非负，再夹一次容量余量。
		double limit = Math.max(0.0, Math.min(maxMol, room()));
		if (limit <= MIN_MOL) {
			return moved;
		}
		double total = amount.totalMol();
		if (total <= MIN_MOL) {
			return moved;
		}
		double ratio = Math.min(limit, total) / total;
		for (Map.Entry<String, Double> e : new ArrayList<>(amount.view().entrySet())) {
			double got = amount.take(e.getKey(), e.getValue() * ratio);
			if (got > 0.0) {
				this.tank.add(e.getKey(), got);
				moved.add(e.getKey(), got);
			}
		}
		// 再夹一次：罐里绝不允许超过容量
		double over = this.tank.totalMol() - CAPACITY;
		if (over > MIN_MOL) {
			this.tank.take(this.tank.view().keySet().iterator().next(), over);
		}
		if (!moved.isEmpty()) {
			this.setChanged();
		}
		return moved;
	}

	/**
	 * 抽料：最多抽 {@code maxMol}，按登记顺序抽；{@code phase} 非空时只抽该相态。
	 */
	@Override
	public MolStock drain(double maxMol, Chemical.Phase phase) {
		MolStock moved = MolStock.empty();
		if (maxMol <= MIN_MOL) {
			return moved;
		}
		// 显式重建：先算出「抽走什么、留下什么」，再整本换掉。
		// 不依赖 take() 的副作用 —— 诊断日志显示过"take 之后账没变"，
		// 先在这一层把扣减钉死，再去查到底是谁把料加回来。
		MolStock kept = MolStock.empty();
		double left = maxMol;
		for (Map.Entry<String, Double> e : new ArrayList<>(this.tank.view().entrySet())) {
			double value = e.getValue();
			if ((phase == null || Chemical.phaseOf(e.getKey()) == phase) && left > MIN_MOL) {
				double take = Math.min(value, left);
				if (take > MIN_MOL) {
					moved.add(e.getKey(), take);
					left -= take;
					value -= take;
				}
			}
			if (value > MIN_MOL) {
				kept.add(e.getKey(), value);
			}
		}
		this.tank.clear();
		for (Map.Entry<String, Double> e : kept.view().entrySet()) {
			this.tank.add(e.getKey(), e.getValue());
		}
		if (!moved.isEmpty()) {
			this.setChanged();
		}
		return moved;
	}

// ------------------------------------------------------------------ 物品形态（内容跟方块走）

	/**
	 * 把罐内账作为<b>隐式组件</b>暴露给物品形态。
	 *
	 * <p>掉落表的 {@code copy_components} 与创造模式取方块都读这一份，所以一个装了料的
	 * 储罐被挖掉时掉的还是储罐本身，内容一起带走。
	 */
	@Override
	protected void collectImplicitComponents(net.minecraft.core.component.DataComponentMap.Builder builder) {
		super.collectImplicitComponents(builder);
		if (!this.tank.isEmpty()) {
			builder.set(ModComponents.TANK_STOCK, Map.copyOf(this.tank.view()));
		}
	}

	/** 物品放回世界时把组件里的账灌回罐里。 */
	@Override
	protected void applyImplicitComponents(net.minecraft.core.component.DataComponentGetter getter) {
		super.applyImplicitComponents(getter);
		Map<String, Double> map = getter.get(ModComponents.TANK_STOCK);
		this.tank.clear();
		if (map == null) {
			return;
		}
		for (Map.Entry<String, Double> e : map.entrySet()) {
			if (e.getValue() != null && e.getValue() > 0.0) {
				this.tank.add(e.getKey(), e.getValue());
			}
		}
	}

// ------------------------------------------------------------------ 客户端同步

	/**
	 * 方块更新包的内容：整份 {@code tank} 账。
	 *
	 * <p>服务端不走 {@code ContainerData}（储罐没有界面），玻璃罐的内嵌流体渲染
	 * 只能靠客户端手上这份账算颜色与液位，所以把存档里那一行 {@code tank} 一并带过去。
	 * 客户端收到后由 {@link #loadAdditional(ValueInput)} 解回来，渲染器只读 {@link #tank()}。
	 */
	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return this.saveWithoutMetadata(registries);
	}

	/** 账一变就把更新包发出去；基类默认返回 null，不覆写客户端永远看不到罐里的内容。 */
	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	/**
	 * 收料 / 抽料后把新内容推给周围客户端。
	 *
	 * <p>{@code setChanged} 只标脏存档、不发包；玻璃罐的内嵌流体是客户端算出来的，
	 * 所以这里补一次 {@code sendBlockUpdated}，走 {@link #getUpdatePacket()}。
	 * 只改服务端本地账（机器按面过户）时也走同一条路。
	 */
	@Override
	public void setChanged() {
		super.setChanged();
		if (this.level instanceof ServerLevel server) {
			server.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(),
					Block.UPDATE_CLIENTS);
		}
	}

// ------------------------------------------------------------------ 存档

	@Override
	protected void saveAdditional(ValueOutput out) {
		super.saveAdditional(out);
		out.putString("tank", encode(this.tank));
	}

	@Override
	protected void loadAdditional(ValueInput in) {
		super.loadAdditional(in);
		decode(this.tank, in.getStringOr("tank", ""));
	}

	/** 「id:mol;id:mol」——与反应釜的 tank 字段同一种写法。 */
	private static String encode(MolStock stock) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Double> e : stock.view().entrySet()) {
			if (sb.length() > 0) {
				sb.append(';');
			}
			sb.append(e.getKey()).append(':').append(e.getValue());
		}
		return sb.toString();
	}

	private static void decode(MolStock stock, String text) {
		stock.clear();
		if (text == null || text.isEmpty()) {
			return;
		}
		for (String part : text.split(";")) {
			int colon = part.indexOf(':');
			if (colon <= 0) {
				continue;
			}
			try {
				stock.add(part.substring(0, colon), Double.parseDouble(part.substring(colon + 1)));
			} catch (NumberFormatException ignored) {
				// 坏数据跳过
			}
		}
	}
}
