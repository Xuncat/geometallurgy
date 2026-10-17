package com.geometallurgy.item;

import java.util.HashMap;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.geometallurgy.Geometallurgy;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;

/**
 * 物品自定义数据组件。
 *
 * <p>26.x 的物品数据一律走 {@link DataComponentType}，不再有 NBT 式的自由标签。
 *
 * <p><b>存的是矿物 id 而不是序号</b>：序号会随矿物表的增删而整体位移，
 * 存档里的探测仪会突然指向另一种矿物；id 是稳定的，找不到就退回默认值。
 */
public final class ModComponents {

	private ModComponents() {
	}

	/** 探测仪当前锁定的矿物 id（{@link com.geometallurgy.proto.OreMineral#id()}）。 */
	public static final DataComponentType<String> TARGET_MINERAL = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			Geometallurgy.id("target_mineral"),
			DataComponentType.<String>builder()
					.persistent(Codec.STRING)
					.networkSynchronized(ByteBufCodecs.STRING_UTF8)
					.build());

	/**
	 * 锭的主金属键：金属 id（{@code iron}），或者 {@link com.geometallurgy.metal.Ingots#ALLOY}。
	 *
	 * <p>它和成分表是两件事：成分表是连续量（可以是 83.5% 铁），这个键是<b>离散标签</b>，
	 * 只回答「够不够纯到叫这个名字」。物品模型要靠它选贴图 —— {@code minecraft:select}
	 * 需要一个可枚举的选择器，连续量当不了选择器。
	 */
	public static final DataComponentType<String> INGOT_METAL = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			Geometallurgy.id("ingot_metal"),
			DataComponentType.<String>builder()
					.persistent(Codec.STRING)
					.networkSynchronized(ByteBufCodecs.STRING_UTF8)
					.build());

	/**
	 * 锭的完整成分表：物质 id → 质量分数（总和 1.000，对齐到百分位）。
	 *
	 * <p>用真正的 {@code Map} 而不是拼成字符串，是为了拿到<b>值相等即堆叠</b>这个免费的好处 ——
	 * 同一炉同一种料出来的锭成分完全一致，就能堆在一起；若是字符串，还得自己保证键序稳定。
	 *
	 * <p>对齐到百分位也是同一个目的：不量化的话每一锭都独一无二，背包会炸。
	 */
	public static final DataComponentType<Map<String, Double>> INGOT_COMPOSITION = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			Geometallurgy.id("ingot_composition"),
			DataComponentType.<Map<String, Double>>builder()
					.persistent(Codec.unboundedMap(Codec.STRING, Codec.DOUBLE))
					.networkSynchronized(ByteBufCodecs
							.<io.netty.buffer.ByteBuf, String, Double, Map<String, Double>>map(
									HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.DOUBLE))
					.build());

	/**
	 * 工程配置器记下的<b>来源机器类型</b>：方块注册名（{@code geometallurgy:crusher}）。
	 *
	 * <p>与 {@link #FACE_COPY_FACES} 是<b>一对</b>，要么都有、要么都没有 —— 只记面配置、不记类型的话，
	 * 「把手上的配置贴到另一种机器上」会静默成功，而那正是要防的事
	 * （见 {@link com.geometallurgy.blockentity.FaceConfig}：两套角色枚举里同一个序号含义不同）。
	 */
	public static final DataComponentType<String> FACE_COPY_KIND = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			Geometallurgy.id("face_copy_kind"),
			DataComponentType.<String>builder()
					.persistent(Codec.STRING)
					.networkSynchronized(ByteBufCodecs.STRING_UTF8)
					.build());

	/**
	 * 工程配置器记下的<b>六面配置</b>：每面 2 bit、六个面共 12 bit 的一个 int。
	 *
	 * <p>整数是故意这样压的 —— 它同时也是界面同步和存档用的同一份表示，
	 * 搬运时不必再做一次转换，也就没有「两边编码不一致」这类错。
	 */
	public static final DataComponentType<Integer> FACE_COPY_FACES = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			Geometallurgy.id("face_copy_faces"),
			DataComponentType.<Integer>builder()
					.persistent(Codec.INT)
					.networkSynchronized(ByteBufCodecs.VAR_INT)
					.build());

	/**
	 * 化学桶/容器的内容：物质 id → mol 数（绝对 mol，不是归一化占比）。
	 *
	 * <p>与 {@link #INGOT_COMPOSITION} 同构（{@code Map<String,Double>}），含义不同：
	 * 那一个是质量分数（总和 1.000），这个是<b>绝对 mol</b>（一个 {@link com.geometallurgy.chemical.MolStock} 的视图）。
	 *
	 * <p>存绝对 mol 而不是占比，是因为 mol 是桶的本征记账量（设计稿 §一）：15 mol 的桶
	 * 装 3 mol NaCl + 12 mol H₂O，成分分离地记着，不归一。量化到千分位，与
	 * {@code MolStock.add} 同口径，保证同成分的桶能堆叠。
	 */
	public static final DataComponentType<Map<String, Double>> BUCKET_STOCK = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			Geometallurgy.id("bucket_stock"),
			DataComponentType.<Map<String, Double>>builder()
					.persistent(Codec.unboundedMap(Codec.STRING, Codec.DOUBLE))
					.networkSynchronized(ByteBufCodecs
							.<io.netty.buffer.ByteBuf, String, Double, Map<String, Double>>map(
									HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.DOUBLE))
					.build());

	/**
	 * 储罐方块<b>物品形态</b>里的同一本 mol 账。
	 *
	 * <p>与 {@link #BUCKET_STOCK} 同构，但挂在储罐方块物品上：储罐方块实体把罐内账
	 * 作为「隐式组件」暴露出去（{@code collectImplicitComponents}），掉落表与创造模式取方块
	 * 都会把它抄到物品上；放下时 {@code BlockItem} 再通过 {@code applyImplicitComponents} 灌回方块实体。
	 * 这样一个储罐被挖掉时掉的还是<b>储罐本身</b>，内容跟着走，不会散成一地桶。
	 */
	public static final DataComponentType<Map<String, Double>> TANK_STOCK = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			Geometallurgy.id("tank_stock"),
			DataComponentType.<Map<String, Double>>builder()
					.persistent(Codec.unboundedMap(Codec.STRING, Codec.DOUBLE))
					.networkSynchronized(ByteBufCodecs
							.<io.netty.buffer.ByteBuf, String, Double, Map<String, Double>>map(
									HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.DOUBLE))
					.build());

	/**
	 * 载具的<b>计量单位标签</b>（{@link Measure}）—— 单位路由机制（设计稿 §六.7）。
	 *
	 * <p>回答「这个载具装的是哪一套单位」：{@code mol} / {@code mb} / {@code b}。
	 * 机器读它决定接收 / 拒收，不再硬编码认某一种物品 —— 任何打了 {@code mol} 标签的
	 * 载具都能进反应釜输入槽，哪怕它不是金属桶。
	 *
	 * <p>存的是 {@link Measure#key()}（{@code "mol"} 等），不是枚举名 —— 键稳定，
	 * 枚举改名不影响已存存档。
	 */
	public static final DataComponentType<String> MEASURE = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			Geometallurgy.id("measure"),
			DataComponentType.<String>builder()
					.persistent(Codec.STRING)
					.networkSynchronized(ByteBufCodecs.STRING_UTF8)
					.build());

	/** 读载具的单位标签；没打标签返回 null。 */
	public static Measure measureOf(net.minecraft.world.item.ItemStack stack) {
		String key = stack.get(MEASURE);
		return key == null ? null : Measure.fromKey(key);
	}

	// 枪械那几个组件（部件槽 / 瞄准 / 磨损 / 连射计数 / 上一发时刻）随枪械一起搬到了
	// 附属模组 geometallurgy_guns —— 那边有自己的 GunComponents。本模组只留
	// 「金属成分」这一类与冶金有关的东西。

	public static void init() {
		// 触发类加载。
	}
}
