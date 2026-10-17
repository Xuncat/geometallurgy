package com.geometallurgy.slurry;

import org.jetbrains.annotations.Nullable;

import com.geometallurgy.geology.RockType;
import com.geometallurgy.proto.OreMineral;
import com.geometallurgy.proto.TracePhase;

/**
 * {@link SlurryPart} 的整型编解码。
 *
 * <p>界面同步走 {@code ContainerData}，只能传整数，所以要把「哪一个相」压成一个 int。
 * 编码方式：矿物相占 {@link #MINERAL_BASE} 号段、微量相占 {@link #TRACE_BASE} 号段，
 * 原岩相用序号本身。序号在同一个 classpath 下稳定，且这条通道两端跑的是同一份代码，
 * 不需要考虑跨版本兼容。
 */
public final class SlurryParts {

	private SlurryParts() {
	}

	/** 矿物相的编号基址，避开原岩序号。 */
	public static final int MINERAL_BASE = 1000;
	/** 微量相（伴生相 / 阳极泥）的编号基址。 */
	public static final int TRACE_BASE = 2000;

	public static int encode(SlurryPart part) {
		if (part instanceof TracePhase trace) {
			return TRACE_BASE + trace.index();
		}
		if (part instanceof OreMineral mineral) {
			return MINERAL_BASE + mineral.ordinal();
		}
		if (part instanceof RockType rock) {
			return rock.ordinal();
		}
		return -1;
	}

	@Nullable
	public static SlurryPart decode(int code) {
		if (code >= TRACE_BASE) {
			return TracePhase.byIndex(code - TRACE_BASE);
		}
		if (code >= MINERAL_BASE) {
			int index = code - MINERAL_BASE;
			OreMineral[] minerals = OreMineral.values();
			return index < minerals.length ? minerals[index] : null;
		}
		RockType[] rocks = RockType.values();
		return code >= 0 && code < rocks.length ? rocks[code] : null;
	}

	// ------------------------------------------------------------------ 存档键

	private static final String MINERAL_PREFIX = "ore:";
	private static final String TRACE_PREFIX = "trace:";
	private static final String ROCK_PREFIX = "rock:";

	/** 存档里用的相名，带前缀以免三类相撞名。 */
	public static String key(SlurryPart part) {
		if (part instanceof TracePhase trace) {
			return TRACE_PREFIX + trace.substance();
		}
		return (part.isMineral() ? MINERAL_PREFIX : ROCK_PREFIX) + part.id();
	}

	/** {@link #key} 的逆运算；也兼容早期不带前缀的写法。 */
	@Nullable
	public static SlurryPart fromKey(String key) {
		if (key.startsWith(TRACE_PREFIX)) {
			return TracePhase.of(key.substring(TRACE_PREFIX.length()));
		}
		if (key.startsWith(MINERAL_PREFIX)) {
			return OreMineral.byId(key.substring(MINERAL_PREFIX.length()));
		}
		if (key.startsWith(ROCK_PREFIX)) {
			return RockType.byId(key.substring(ROCK_PREFIX.length()));
		}
		OreMineral mineral = OreMineral.byId(key);
		return mineral != null ? mineral : RockType.byId(key);
	}
}
