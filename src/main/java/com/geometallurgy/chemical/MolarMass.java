package com.geometallurgy.chemical;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 原子量 → 摩尔质量的<b>唯一来源</b>（数据一致性修复）。
 *
 * <p>历史上 {@link Chemical} 的每一条摩尔质量都是手写的，于是同一元素的原子量在不同物质里
 * 各写各的取整：Ca 在 CaO 里当 40、在 CaF2 里当 40.07、在 CaSO4 里当 40.14；Al 在 Al2O3 里
 * 当 27、在 AlCl3 里当 26.8；C 单质 12.01 而 CO / CO2 用 12…… 配平检验只容忍 1 g 的差，
 * 这些亚克级残差混过了启动校验，却在长时间反应里逐条累积（见 bug#39）。
 *
 * <p>现在所有物种的摩尔质量都由公式按本表加和（{@link #of}），整表只有一个原子量来源，
 * 任何配平反应的质量残差都只可能来自浮点，不可能来自「不同地方取了不同的原子量」。
 * 表里的取值刻意与既有「整数感」的口径一致（H=1、C=12、O=16、Cl=35.5、Ca=40…），
 * 因此绝大多数物种的摩尔质量一字未变，只有原先自相矛盾的那几个数值被统一。
 *
 * <p>公式解析支持：元素符号（1~2 字母）、下标、括号、以及「·」水合物（可带组分系数）；
 * 伪元素 {@code RE} / {@code L} / {@code HRE}（稀土占位符）另给原子量。
 * 未知记号<b>直接抛异常</b>——宁可启动就炸，也不要静默算出一个错的摩尔质量。
 */
public final class MolarMass {

	private MolarMass() {
	}

	/** 标准原子量（g/mol）。刻意取常用整数值，与既有表口径一致。 */
	private static final Map<String, Double> ATOMIC_WEIGHTS = new LinkedHashMap<>();

	private static void w(String element, double weight) {
		ATOMIC_WEIGHTS.put(element, weight);
	}

	static {
		w("H", 1.0);
		w("He", 4.0);
		w("Li", 6.94);
		w("Be", 9.01);
		w("B", 10.81);
		w("C", 12.0);
		w("N", 14.0);
		w("O", 16.0);
		w("F", 19.0);
		w("Ne", 20.18);
		w("Na", 23.0);
		w("Mg", 24.0);
		w("Al", 27.0);
		w("Si", 28.0);
		w("P", 31.0);
		w("S", 32.0);
		w("Cl", 35.5);
		w("Ar", 39.95);
		w("K", 39.0);
		w("Ca", 40.0);
		w("Sc", 44.96);
		w("Ti", 47.87);
		w("V", 50.94);
		w("Cr", 52.0);
		w("Mn", 55.0);
		w("Fe", 55.85);
		w("Co", 58.93);
		w("Ni", 58.69);
		w("Cu", 63.55);
		w("Zn", 65.38);
		w("Ga", 69.72);
		w("Ge", 72.63);
		w("As", 74.92);
		w("Se", 78.97);
		w("Br", 79.9);
		w("Kr", 83.8);
		w("Rb", 85.47);
		w("Sr", 87.62);
		w("Y", 88.91);
		w("Zr", 91.22);
		w("Nb", 92.91);
		w("Mo", 95.95);
		w("Ru", 101.07);
		w("Rh", 102.91);
		w("Pd", 106.42);
		w("Ag", 107.87);
		w("Cd", 112.41);
		w("In", 114.82);
		w("Sn", 118.71);
		w("Sb", 121.76);
		w("Te", 127.6);
		w("I", 126.9);
		w("Xe", 131.29);
		w("Cs", 132.91);
		w("Ba", 137.33);
		w("La", 138.91);
		w("Ce", 140.12);
		w("Pr", 140.91);
		w("Nd", 144.24);
		w("Pm", 145.0);
		w("Sm", 150.36);
		w("Eu", 151.96);
		w("Gd", 157.25);
		w("Tb", 158.93);
		w("Dy", 162.5);
		w("Ho", 164.93);
		w("Er", 167.26);
		w("Tm", 168.93);
		w("Yb", 173.05);
		w("Lu", 174.97);
		w("Hf", 178.49);
		w("Ta", 180.95);
		w("W", 183.84);
		w("Re", 186.21);
		w("Os", 190.23);
		w("Ir", 192.22);
		w("Pt", 195.08);
		w("Au", 196.97);
		w("Hg", 200.59);
		w("Tl", 204.38);
		w("Pb", 207.2);
		w("Bi", 208.98);
		w("Th", 232.04);
		w("Pa", 231.04);
		w("U", 238.03);
	}

	/**
	 * 稀土伪元素的原子量。三者的关系约束是 {@code 2·RE = L + HRE} ——
	 * 显式反应 {@code 2 RECl3 → LCl3 + HCl3}（轻重稀土分家）不是原子级配平，
	 * 而是靠这条质量关系成立的；改这三个值时必须同时满足它（见 bug#39）。
	 */
	public static final double WEIGHT_RE = 152.0;
	/** 轻稀土伪元素 L（= Ce 族平均）。 */
	public static final double WEIGHT_L = 144.0;
	/** 重稀土伪元素 HRE（公式里常写作 H）。 */
	public static final double WEIGHT_HRE = 160.0;

	/** 元素 / 伪元素的原子量；未知抛异常。 */
	public static double atomicWeight(String element) {
		Double known = ATOMIC_WEIGHTS.get(element);
		if (known != null) {
			return known;
		}
		switch (element) {
			case "RE":
				return WEIGHT_RE;
			case "L":
				return WEIGHT_L;
			case "HRE":
				return WEIGHT_HRE;
			default:
				throw new IllegalStateException("未知元素 / 伪元素记号: " + element);
		}
	}

	/** 是不是已知元素 / 伪元素。 */
	public static boolean isKnownElement(String element) {
		return ATOMIC_WEIGHTS.containsKey(element)
				|| "RE".equals(element) || "L".equals(element) || "HRE".equals(element);
	}

	/** 原子量表（只读，供审计 / harness 核对）。 */
	public static Map<String, Double> atomicWeights() {
		return java.util.Collections.unmodifiableMap(ATOMIC_WEIGHTS);
	}

	/** 按公式算摩尔质量；公式里的重稀土记作 H（与 {@link ChemicalData} 的别名一致时，用带别名的重载）。 */
	public static double of(String formula) {
		return of(formula, Map.of());
	}

	/**
	 * 按公式算摩尔质量。
	 *
	 * @param formula 化学式（支持括号 / 下标 / {@code ·} 水合物）
	 * @param alias   记号重解释（如重稀土的 {@code H → HRE}）
	 */
	public static double of(String formula, Map<String, String> alias) {
		if (formula == null || formula.isEmpty()) {
			throw new IllegalArgumentException("空公式");
		}
		double total = 0.0;
		for (String part : formula.split("·")) {
			if (part.isEmpty()) {
				continue;
			}
			int digits = 0;
			while (digits < part.length() && part.charAt(digits) >= '0' && part.charAt(digits) <= '9') {
				digits++;
			}
			int lead = 1;
			String body = part;
			if (digits > 0) {
				lead = Integer.parseInt(part.substring(0, digits));
				body = part.substring(digits);
			}
			Cursor cursor = new Cursor(body, alias);
			total += cursor.sequence(false) * lead;
			if (cursor.pos != body.length()) {
				throw new IllegalStateException("公式解析停在位置 " + cursor.pos + ": " + formula);
			}
		}
		return total;
	}

	/** 递归下降解析：括号内 / 括号外。 */
	private static final class Cursor {

		private final String s;
		private final Map<String, String> alias;
		private int pos;

		Cursor(String s, Map<String, String> alias) {
			this.s = s;
			this.alias = alias;
		}

		/** @param inParens true 表示遇到 {@code ')'} 就返回（由调用方吃掉右括号） */
		double sequence(boolean inParens) {
			double sum = 0.0;
			while (pos < s.length()) {
				char c = s.charAt(pos);
				if (c == ')') {
					if (!inParens) {
						throw new IllegalStateException("多余的右括号 @" + pos + ": " + s);
					}
					return sum;
				}
				if (c == '(') {
					pos++;
					double inner = sequence(true);
					if (pos >= s.length() || s.charAt(pos) != ')') {
						throw new IllegalStateException("括号不闭合 @" + pos + ": " + s);
					}
					pos++;
					sum += inner * number();
					continue;
				}
				sum += atomicWeight(symbol()) * number();
			}
			if (inParens) {
				throw new IllegalStateException("括号不闭合: " + s);
			}
			return sum;
		}

		private String symbol() {
			if (s.startsWith("HRE", pos)) {
				pos += 3;
				return alias.getOrDefault("HRE", "HRE");
			}
			if (s.startsWith("RE", pos)) {
				pos += 2;
				return alias.getOrDefault("RE", "RE");
			}
			char first = s.charAt(pos);
			if (first < 'A' || first > 'Z') {
				throw new IllegalStateException("无法识别的记号 '" + first + "' @" + pos + ": " + s);
			}
			int len = 1;
			if (pos + 1 < s.length() && s.charAt(pos + 1) >= 'a' && s.charAt(pos + 1) <= 'z') {
				len = 2;
			}
			String raw = s.substring(pos, pos + len);
			pos += len;
			return alias.getOrDefault(raw, raw);
		}

		private int number() {
			int start = pos;
			while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
				pos++;
			}
			return pos == start ? 1 : Integer.parseInt(s.substring(start, pos));
		}
	}
}
