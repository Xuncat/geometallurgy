package com.geometallurgy.slurry;

import com.mojang.serialization.Codec;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 虚拟矿浆的存档编解码。
 *
 * <p>矿浆<b>不是</b>原版流体，也不注册成物品，所以既不能走 {@code FluidStorage} 的序列化，
 * 也不能走物品列表。它就是一张「相 → 体积」表，落盘时铺成
 * {@code ["ore:hematite=1234.5", "rock:granite=9876.5"]} 这样一行一项的字符串列表 ——
 * 用相名而不是枚举序号，这样日后往枚举中间插一项也不会把老存档的矿浆解释成别的东西。
 */
public final class SlurryStore {

	private SlurryStore() {
	}

	public static void store(ValueOutput out, String key, VirtualSlurry slurry) {
		ValueOutput.TypedOutputList<String> list = out.list(key, Codec.STRING);
		slurry.contents().forEach((part, amount) -> list.add(SlurryParts.key(part) + "=" + amount));
	}

	/**
	 * 读回一罐。{@code formOrdinal} 是形态的枚举序，只在罐非空时生效 ——
	 * 空罐没有形态可言，硬塞一个只会误导下游。
	 */
	public static void load(ValueInput in, String key, VirtualSlurry slurry, int formOrdinal) {
		slurry.clear();
		for (String entry : in.listOrEmpty(key, Codec.STRING)) {
			int separator = entry.indexOf('=');
			if (separator <= 0) {
				continue;
			}
			SlurryPart part = SlurryParts.fromKey(entry.substring(0, separator));
			if (part == null) {
				continue;
			}
			try {
				slurry.insert(part, Double.parseDouble(entry.substring(separator + 1)));
			} catch (NumberFormatException ignored) {
				// 坏数据跳过，不让整台机器读不出来
			}
		}
		SlurryForm[] forms = SlurryForm.values();
		if (!slurry.isEmpty() && formOrdinal >= 0 && formOrdinal < forms.length) {
			slurry.setForm(forms[formOrdinal]);
		}
	}
}
