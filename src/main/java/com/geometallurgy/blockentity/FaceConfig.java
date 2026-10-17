package com.geometallurgy.blockentity;

/**
 * 六面角色配置的打包与解包。
 *
 * <p>每面占 {@value #BITS} 位、共 {@value #FACES} 个面，所以<b>一份完整的六面配置正好是一个 int</b>。
 * 这么定的直接好处有三条：能塞进 {@code ContainerData} 随界面同步；能整份丢进物品组件
 * （工程配置器就是靠这个把配置搬到另一台机器上的）；存档里也只是一行。
 *
 * <h2>为什么必须有 {@code kind} 校验</h2>
 * 本模组有两套面角色枚举：{@link PortRole}（粉碎机 / 离心机 / 冶金炉）与
 * {@link FaceRole}（磁力筛机）。两套都恰好 4 个取值，序号却<b>含义完全不同</b> ——
 * 例如序号 1 在 {@code PortRole} 里是「进料」、在 {@code FaceRole} 里是「磁性出」。
 * 于是「把粉碎机的配置贴到磁筛机上」不会报错、也不会越界，只会得到一台六个面全错的机器。
 * 这类错误在界面上看起来完全合法，是最难查的一种。所以打包数据本身不带类型信息，
 * 类型由 {@link FaceConfigurable#faceConfigKind()} 单独携带、粘贴前必须比对。
 */
public final class FaceConfig {

	/** 面数 —— 就是原版的六个方向。 */
	public static final int FACES = 6;

	/** 每个面占的位数。4 个取值刚好占满。 */
	public static final int BITS = 2;

	/** 单个面的位掩码。 */
	private static final int MASK = (1 << BITS) - 1;

	/** 一份配置占的总位数。 */
	public static final int PACKED_BITS = FACES * BITS;

	private FaceConfig() {
	}

	/**
	 * 把六个面的角色打包成一个 int。
	 *
	 * <p>下标即 {@code Direction.ordinal()} —— 与各机器内部 {@code roles[]} 的约定一致。
	 *
	 * <p>取值个数超过 {@code 2^BITS} 时<b>直接抛</b>，而不是悄悄截断：静默截断会让问题拖到很久
	 * 以后、以「某个面自己变了」这种面目出现，那时已经很难追回是哪次改动引入的。
	 */
	public static int pack(Enum<?>[] roles) {
		int packed = 0;
		for (int i = 0; i < roles.length; i++) {
			int ordinal = roles[i].ordinal();
			if (ordinal > MASK) {
				throw new IllegalStateException(roles[i].getDeclaringClass().getSimpleName()
						+ " 的取值多于 " + (1 << BITS) + " 个，塞不进 " + BITS + " 位");
			}
			packed |= ordinal << (i * BITS);
		}
		return packed;
	}

	/**
	 * 把一份打包好的配置摊回六个面。
	 *
	 * @param packed   打包值
	 * @param target   目标机器的 {@code roles[]}，长度必须等于 {@link #FACES}
	 * @param values   目标机器自己的角色枚举（{@code XxxRole.values()}）
	 * @param fallback 某个面解不出合法取值时退到的角色 —— 交给调用方指定成该枚举里的「关闭」。
	 *                 正常路径（已经过 {@code kind} 校验）不会用到它；这是给手改组件、
	 *                 跨版本迁移这类输入留的一条不崩的退路，而不是静默容错。
	 */
	public static <E extends Enum<E>> void unpack(int packed, E[] target, E[] values, E fallback) {
		for (int i = 0; i < target.length; i++) {
			int index = (packed >>> (i * BITS)) & MASK;
			target[i] = index < values.length ? values[index] : fallback;
		}
	}
}
