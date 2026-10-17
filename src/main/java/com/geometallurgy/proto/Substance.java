package com.geometallurgy.proto;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.network.chat.Component;

/**
 * 物质名表。
 *
 * <p>成分体系里的键全都是字符串 id（如 {@code "iron"}、{@code "quartz"}、{@code "sulfur"}），
 * 这样成分可以自由演化为任意组合，不必为每一种组合注册一个枚举。
 * 这个类只负责把 id 翻译成中文名与显示用色，并说明它是不是金属
 * —— 后续湿法冶金（电极电势、溶解性）只对金属有意义。
 */
public final class Substance {

	private Substance() {
	}

	/** 一条物质记录。 */
	public record Info(String id, String cnName, int color, boolean metal) {
	}

	private static final Map<String, Info> ALL = new LinkedHashMap<>();

	// ------------------------------------------------------------------ 金属
	private static void metal(String id, String cn, int color) {
		ALL.put(id, new Info(id, cn, color, true));
	}

	// ------------------------------------------------------------------ 非金属
	private static void nonMetal(String id, String cn, int color) {
		ALL.put(id, new Info(id, cn, color, false));
	}

	static {
		// 主族与过渡金属（按活动性序排列，方便与冶金模块对齐）
		metal("potassium", "钾", 0xB9A6C9);
		metal("calcium", "钙", 0xD6D2C4);
		metal("sodium", "钠", 0xC9C9D4);
		metal("magnesium", "镁", 0xC7CCC9);
		metal("aluminum", "铝", 0xC4CBD2);
		metal("manganese", "锰", 0x9E8FA8);
		metal("zinc", "锌", 0x9FB0BC);
		metal("chromium", "铬", 0xB6C2CC);
		metal("iron", "铁", 0xD8D8D8);
		metal("cobalt", "钴", 0x6E8FD0);
		metal("nickel", "镍", 0xC8CFC4);
		metal("tin", "锡", 0xC6C8CC);
		metal("lead", "铅", 0x8C8C99);
		metal("tungsten", "钨", 0x6E7378);
		metal("molybdenum", "钼", 0x8A939C);
		metal("copper", "铜", 0xE07A46);
		metal("bismuth", "铋", 0xC9BCCF);
		metal("silver", "银", 0xE3E8EF);
		metal("gold", "金", 0xF2C94C);
		metal("mercury", "汞", 0xB9C3CC);

		// 非金属主成分
		nonMetal("silicon", "硅", 0x9FA6AE);
		nonMetal("carbon", "碳", 0x4A4A50);
		nonMetal("sulfur", "硫", 0xE0D24A);
		nonMetal("phosphorus", "磷", 0xE0A0A8);
		nonMetal("fluorine", "氟", 0x9BE0C8);
		nonMetal("barium", "钡", 0xD6C7A8);
		nonMetal("arsenic", "砷", 0xA9B6A0);
		nonMetal("antimony", "锑", 0xB0B6C0);
		nonMetal("titanium", "钛", 0xBAC0C8);
		nonMetal("vanadium", "钒", 0xA8B2C4);
		nonMetal("rare_earth", "稀土", 0xC0A8C8);
		// 电解时从阳极落下来的不溶残渣 —— 里面富集了贵金属与稀土
		nonMetal("anode_mud", "阳极泥", 0x4A4038);

		// 稀土元素：十六种一起登记。它们之所以要单独成项，是因为一炉稀土拿到的是
		// 一份「配分」而不是一种东西，浮层里要逐项列百分比（见 RareEarth）。
		for (RareEarth.Def element : RareEarth.all()) {
			nonMetal(element.id(), element.cnName(), element.color());
		}

		// 脉石矿物（岩石的无用组成部分）
		nonMetal("quartz", "石英", 0xE6E2D8);
		nonMetal("feldspar", "长石", 0xDCD3C4);
		nonMetal("plagioclase", "斜长石", 0xD2D6D8);
		nonMetal("pyroxene", "辉石", 0x6A7078);
		nonMetal("hornblende", "角闪石", 0x4E5A50);
		nonMetal("olivine", "橄榄石", 0x8FA65A);
		nonMetal("biotite", "黑云母", 0x4A4448);
		nonMetal("mica", "云母", 0xB8B0A0);
		nonMetal("calcite", "方解石", 0xE4E4DC);
		nonMetal("dolomite", "白云石", 0xD8D4C4);
		nonMetal("clay", "黏土", 0xA89A88);
		nonMetal("chalcedony", "玉髓", 0xCFD4D8);
		nonMetal("halite", "岩盐", 0xF0EEE8);
		nonMetal("gypsum", "石膏", 0xE8E4DA);
		nonMetal("rock_fragments", "岩屑", 0x8E8A80);
		// 钾盐 KCl：岩盐（rock_salt）矿脉里与石盐共生的钾盐矿物，是钾源的起点。
		// 追加在表尾，序号不变（与 Chemical 的 APPEND ONLY 同一规矩）。
		nonMetal("sylvite", "钾盐", 0xC9C2D6);
		// 氮族矿源（本轮）：硝石（钠硝石 NaNO3 / 钾硝石 KNO3）与硇砂（NH4Cl）。
		// 与 sylvite 同一规矩：必须登记成 Substance 并挂进某岩体的脉石，否则矿根就是假的。
		nonMetal("natratine", "钠硝石", 0xE8E6DA);
		nonMetal("niter", "钾硝石", 0xEDEBE0);
		nonMetal("sal_ammoniac", "硇砂", 0xE4E6E0);
	}

	public static Info of(String id) {
		return ALL.get(id);
	}

	// ------------------------------------------------------------------ 整型编解码
	//
	// 界面同步走 ContainerData，只能传整数；而物质是字符串 id。这里把 id 压成它在
	// {@link #ALL} 里的序号 —— 表是静态的 LinkedHashMap，同一份代码两端跑出来的序号一致。
	// 与 SlurryParts 一样，这条通道只服务「同一份代码的客户端与服务端」，不必考虑跨版本兼容。
	// 编码时 +1，好让 0 空出来表示「没有这一项」。

	private static final java.util.List<String> ORDER = java.util.List.copyOf(ALL.keySet());

	/** 物质的稳定序号（从 1 开始）；不认识返回 0。 */
	public static int indexOf(String id) {
		if (id == null) {
			return 0;
		}
		int index = ORDER.indexOf(id);
		return index < 0 ? 0 : index + 1;
	}

	/** {@link #indexOf} 的逆运算；越界返回 null。 */
	public static String byIndex(int index) {
		int slot = index - 1;
		return slot >= 0 && slot < ORDER.size() ? ORDER.get(slot) : null;
	}

	/** 表里有几条物质，供界面校验用。 */
	public static int count() {
		return ORDER.size();
	}

	public static boolean isKnown(String id) {
		return ALL.containsKey(id);
	}

	public static boolean isMetal(String id) {
		Info info = ALL.get(id);
		return info != null && info.metal();
	}

	public static String nameOf(String id) {
		Info info = ALL.get(id);
		return info == null ? id : info.cnName();
	}

	public static int colorOf(String id) {
		Info info = ALL.get(id);
		return info == null ? 0x9A9A9A : info.color();
	}

	public static String translationKey(String id) {
		return "substance.geometallurgy." + id;
	}

	public static Component displayName(String id) {
		return Component.translatable(translationKey(id));
	}
}
