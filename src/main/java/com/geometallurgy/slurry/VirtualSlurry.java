package com.geometallurgy.slurry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 虚拟矿浆 —— 本模组的「混合流体」。
 *
 * <p>它不注册为原版流体，也不在世界里流动：纯粹是一份数据 —— 一罐液体里各悬浮着多少
 * 体积的颗粒。之所以叫「流体」而不是「物品」，是因为后续的磁筛机与离心机处理的是
 * <b>液相里的颗粒</b>：离心机按密度分层、磁筛机按磁性分选，都要求颗粒处于可自由重排的
 * 悬浊状态，固体物品做不到这一点。
 *
 * <p>罐内同时装着<b>原岩相</b>与<b>矿物相</b>（见 {@link SlurryPart}），而且原岩占绝对多数 ——
 * 碎矿机啃下来的是整块岩石，目标矿物只是其中很小的一部分。这一点直接决定了界面上那条
 * 液位条几乎全是原岩色。
 *
 * <p>计量单位是 mB（毫桶），容量 {@value #CAPACITY} mB。
 */
public final class VirtualSlurry {

	/** 储罐容量（mB）。 */
	public static final double CAPACITY = 16000.0;

	private final Map<SlurryPart, Double> contents = new HashMap<>();
	private double amount;

	/**
	 * 本罐里装的是矿浆还是矿粉（见 {@link SlurryForm}）。
	 *
	 * <p>形态是<b>整罐</b>的属性而不是单颗粒的属性：同一罐里的颗粒是一起被磨出来、一起被搬走的，
	 * 不会出现「半罐粗颗粒半罐细粉」。搬运时两罐形态必须一致，罐空时则跟随来源。
	 */
	private SlurryForm form = SlurryForm.SLURRY;

	/**
	 * 本罐<b>允许</b>装哪种形态；{@code null} 表示不挑。
	 *
	 * <p>这是形态约束的<b>唯一权威</b>，必须挡在搬运层上而不是各机器的出料口上：
	 * 搬运有两个方向 —— 上游推、下游抽 —— 只在下游的进料方法里判形态，
	 * 上游主动推的那条路会把它绕过去（空罐会「跟随来源」照单全收）。
	 */
	private Set<SlurryForm> allowed;

	public double capacity() {
		return CAPACITY;
	}

	/** 限定本罐只收这几种形态。不调用（或传空）即不挑。 */
	public VirtualSlurry allow(SlurryForm... forms) {
		this.allowed = forms.length == 0 ? null : EnumSet.copyOf(Arrays.asList(forms));
		return this;
	}

	/** 本罐收不收这种形态。 */
	public boolean allows(SlurryForm incoming) {
		return this.allowed == null || this.allowed.contains(incoming);
	}

	public SlurryForm form() {
		return this.form;
	}

	public void setForm(SlurryForm form) {
		this.form = form;
	}

	public boolean isPowder() {
		return this.form == SlurryForm.POWDER;
	}

	/** 当前总液量（mB）。 */
	public double amount() {
		return amount;
	}

	public boolean isEmpty() {
		return amount <= 0.0;
	}

	public boolean isFull() {
		return amount >= CAPACITY - 1.0e-6;
	}

	/** 还能装多少。 */
	public double room() {
		return Math.max(0.0, CAPACITY - amount);
	}

	/**
	 * 注入一种相。
	 *
	 * @return 实际注入的量（罐满时会小于请求量）
	 */
	public double insert(SlurryPart part, double millibuckets) {
		if (millibuckets <= 0.0) {
			return 0.0;
		}
		double accepted = Math.min(millibuckets, room());
		if (accepted <= 0.0) {
			return 0.0;
		}
		contents.merge(part, accepted, Double::sum);
		amount += accepted;
		return accepted;
	}

	/**
	 * 抽出某一种相。
	 *
	 * @return 实际抽出量
	 */
	public double extract(SlurryPart part, double millibuckets) {
		if (millibuckets <= 0.0) {
			return 0.0;
		}
		Double have = contents.get(part);
		if (have == null || have <= 0.0) {
			return 0.0;
		}
		double taken = Math.min(have, millibuckets);
		double left = have - taken;
		if (left <= 1.0e-9) {
			contents.remove(part);
		} else {
			contents.put(part, left);
		}
		amount -= taken;
		return taken;
	}

	/** 按比例抽走一部分液相（整体减量），用于下游机器「抽走一罐」。 */
	public void drain(double millibuckets) {
		if (millibuckets <= 0.0 || amount <= 0.0) {
			return;
		}
		double ratio = Math.min(1.0, millibuckets / amount);
		if (ratio >= 1.0) {
			clear();
			return;
		}
		contents.replaceAll((part, value) -> value * (1.0 - ratio));
		contents.entrySet().removeIf(entry -> entry.getValue() <= 1.0e-9);
		amount *= (1.0 - ratio);
	}

	/**
	 * 按比例抽走一部分液相，并把抽走的成分表返回。
	 *
	 * <p>矿浆是<b>混</b>的：抽走多少体积，就按当前各相的比例把每一相都带走一份。
	 * 若只挑某一相抽，下游拿到的成分就与罐内实际不符了。
	 *
	 * <p>返回的 map 是<b>已经扣除</b>之后的那一份，调用方可以直接把它塞进另一个罐，
	 * 或者按需丢弃（例如磁筛机排尾矿）。
	 *
	 * @return 抽出的成分表；罐空或请求量非正时返回空表
	 */
	public Map<SlurryPart, Double> drainProportional(double millibuckets) {
		Map<SlurryPart, Double> taken = new HashMap<>();
		if (millibuckets <= 0.0 || amount <= 0.0) {
			return taken;
		}
		double total = Math.min(millibuckets, amount);
		double ratio = total / amount;
		double grabbed = 0.0;
		SlurryPart last = null;
		for (Map.Entry<SlurryPart, Double> entry : contents.entrySet()) {
			double piece = entry.getValue() * ratio;
			if (piece > 0.0) {
				taken.put(entry.getKey(), piece);
				grabbed += piece;
				last = entry.getKey();
			}
		}
		// 逐相取整会有零头，把差额并到最后一相上，保证「抽走的体积」与请求量一致
		if (last != null && grabbed < total) {
			taken.merge(last, total - grabbed, Double::sum);
		}
		taken.forEach(this::extract);
		return taken;
	}

	/** 把一份成分表并入本罐。 */
	public double insertMap(Map<SlurryPart, Double> parts) {
		double moved = 0.0;
		for (Map.Entry<SlurryPart, Double> entry : parts.entrySet()) {
			moved += insert(entry.getKey(), entry.getValue());
		}
		return moved;
	}

	public void clear() {
		contents.clear();
		amount = 0.0;
		// 罐空了就退回默认形态：下一次装什么由来源决定，不留着上一次的形态误导下游。
		this.form = SlurryForm.SLURRY;
	}

	public Map<SlurryPart, Double> contents() {
		return contents;
	}

	/** 某种相占液相的比例（0~1）。 */
	public double fraction(SlurryPart part) {
		if (amount <= 0.0) {
			return 0.0;
		}
		return contents.getOrDefault(part, 0.0) / amount;
	}

	/** 按含量从高到低排序的成分列表，供界面显示。 */
	public List<Map.Entry<SlurryPart, Double>> sortedContents() {
		List<Map.Entry<SlurryPart, Double>> list = new ArrayList<>(contents.entrySet());
		list.removeIf(entry -> entry.getValue() <= 0.0);
		list.sort(Comparator.comparingDouble((Map.Entry<SlurryPart, Double> entry) -> entry.getValue()).reversed());
		return list;
	}

	/** 把另一罐的量并入本罐。 */
	public double insertAll(VirtualSlurry other) {
		double moved = 0.0;
		for (Map.Entry<SlurryPart, Double> entry : other.contents.entrySet()) {
			moved += insert(entry.getKey(), entry.getValue());
		}
		return moved;
	}
}
