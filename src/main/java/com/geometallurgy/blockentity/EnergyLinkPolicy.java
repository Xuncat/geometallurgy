package com.geometallurgy.blockentity;

/**
 * 蓄电池与邻居之间「这一格能不能取电 / 能不能送电」的纯决策。
 *
 * <p>刻意不碰 Minecraft：只吃 {@code supportsInsertion} / {@code supportsExtraction}
 * 两个布尔，所以可以脱离游戏用一段 plain-Java harness 直接验证。
 *
 * <p><b>行为与界面档位无关。</b>蓄电池没有方向 —— 它任何时刻都同时是输入与输出：
 * 邻居有电就抽、邻居要电就给，读的是邻居自己的能力，而不是本机被标成了哪一档
 * （见 bug#43：原来由 {@code Mode} 二选一，切换档位会让一条本来能通的连接变成不通）。
 */
public final class EnergyLinkPolicy {

	private EnergyLinkPolicy() {
	}

	/** 邻居能不能被抽电 —— 能，蓄电池这一格就同时可以取电。 */
	public static boolean canDraw(boolean neighbourSupportsExtraction) {
		return neighbourSupportsExtraction;
	}

	/** 邻居能不能收电 —— 能，蓄电池这一格就同时可以送电。 */
	public static boolean canFeed(boolean neighbourSupportsInsertion) {
		return neighbourSupportsInsertion;
	}

	/** 邻居是否进设备列表：能收或能给任一即可 —— 与档位无关。 */
	public static boolean visible(boolean neighbourSupportsInsertion,
			boolean neighbourSupportsExtraction) {
		return neighbourSupportsInsertion || neighbourSupportsExtraction;
	}
}
