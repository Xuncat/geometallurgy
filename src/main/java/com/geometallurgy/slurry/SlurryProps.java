package com.geometallurgy.slurry;

import com.geometallurgy.geology.RockType;
import com.geometallurgy.proto.OreMineral;

/**
 * 从「一个相」身上读物理量。
 *
 * <p>{@link SlurryPart} 有矿物与原岩两个实现，两者的密度表、硬度表各自独立
 * （{@link OreMineral} 与 {@link RockType} 是不同的枚举，互不相识）。分选机器只关心
 * 「这颗颗粒多重、多硬」，不关心它是矿物还是石头，所以这里做一层转发，
 * 免得每台机器里都写一遍 {@code instanceof}。
 */
public final class SlurryProps {

	private SlurryProps() {
	}

	/** 颗粒的密度（g/cm³）。离心机按它分层。 */
	public static double density(SlurryPart part) {
		if (part instanceof OreMineral mineral) {
			return mineral.density();
		}
		if (part instanceof RockType rock) {
			return rock.density();
		}
		return 2.5;
	}

	/** 颗粒的莫氏硬度。粉碎机按它算磨矿能耗，钻头按它定挖掘门槛。 */
	public static double mohs(SlurryPart part) {
		if (part instanceof OreMineral mineral) {
			return mineral.mohs();
		}
		if (part instanceof RockType rock) {
			return rock.mohs();
		}
		return 3.0;
	}
}
