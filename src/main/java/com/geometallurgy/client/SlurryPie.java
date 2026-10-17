package com.geometallurgy.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.geometallurgy.proto.Substance;
import com.geometallurgy.slurry.SlurryPart;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 环形成分图：把一罐料的成分画成一圈扇形，鼠标移上去弹出逐项百分比。
 *
 * <p>三台机器（粉碎机 / 磁力筛机 / 离心机）共用这一份实现 —— 所以「形式一致」不是靠约定，
 * 而是同一段代码。粉碎机只有一罐，直接画；另两台各有三个罐，由各自的界面加一排切换格
 * 决定「现在画哪一罐」。
 *
 * <h2>为什么逐像素定扇区</h2>
 * 圆内每个像素只算<b>一次</b> {@code atan2}，落进哪一块就取那块的颜色，同一行的连续同色
 * 像素再合并成一条 {@code fill}。每帧开销是 O(直径²) 次三角运算加几十次填充，
 * 比按扇区各扫一遍省得多。
 */
public final class SlurryPie {

	private static final double TWO_PI = Math.PI * 2.0;

	/** 圆的外圈与罐空着时的底盘。 */
	private static final int RIM = 0xFF404040;
	private static final int EMPTY = 0xFF6E6E6E;

	/** 浮层用色：底、边、正文、数字。 */
	private static final int POPUP_BG = 0xF0101010;
	private static final int POPUP_EDGE = 0xFF202020;
	private static final int POPUP_TEXT = 0xFFE0E0E0;
	private static final int POPUP_NUMBER = 0xFF9FD89F;
	/** 子项（稀土配分）用暗一档的字，一眼能看出它挂在上面那一行底下。 */
	private static final int POPUP_CHILD = 0xFFB0B0B0;
	private static final int POPUP_CHILD_NUMBER = 0xFF87BB87;

	private SlurryPie() {
	}

	/**
	 * 一块扇形。
	 *
	 * <p>刻意不复用各菜单里的 {@code CompositionEntry}：那是服务端同步数据的形状，
	 * 这里是绘图层要的形状，两者各自独立演化反而不容易互相牵扯。
	 *
	 * <p>存的是「颜色 + 名字 + 占比」而不是一个 {@link SlurryPart}：矿浆罐画的是相，
	 * 而冶炼炉的产物饼画的是物质（铁、磷、硫……），后者根本不是相。
	 * 两者在这里统一成同一种扇形，一份渲染代码就够。
	 *
	 * <p>{@code children} 是「挂在它底下的明细」。只有一个用处：稀土。稀土在环上必须
	 * 合成<b>一块</b>（十六种元素各画一小片淡色扇形就是一团糊），但浮层里要能逐项看到
	 * 这一炉的配分 —— 所以扇形归扇形，明细归明细。
	 */
	public record Slice(int color, Component name, double fraction, List<Slice> children) {
	}

	/** 矿浆罐里的一相。 */
	public static Slice of(SlurryPart part, double fraction) {
		return new Slice(part.color(), part.displayName(), fraction, List.of());
	}

	/** 一种物质（冶炼产物饼用）。 */
	public static Slice of(String substanceId, double fraction) {
		return of(substanceId, fraction, List.of());
	}

	/** 一种物质，带底下的明细。 */
	public static Slice of(String substanceId, double fraction, List<Slice> children) {
		return new Slice(Substance.colorOf(substanceId), Substance.displayName(substanceId),
				fraction, children);
	}

	/**
	 * 画一个饼。{@code slices} 为空或占比全零时只留下底盘，表示罐是空的。
	 *
	 * <p>占比来自千分位取整，加起来未必正好 1；这里按实际总和归一，最后一块自然补齐。
	 */
	public static void draw(GuiGraphicsExtractor extractor, int cx, int cy, int r, List<Slice> slices) {
		fillDisc(extractor, cx, cy, r, EMPTY);

		if (!slices.isEmpty()) {
			int n = slices.size();
			double[] bounds = new double[n + 1];
			for (int i = 0; i < n; i++) {
				bounds[i + 1] = bounds[i] + Math.max(0.0, slices.get(i).fraction());
			}
			if (bounds[n] > 0.0) {
				for (int i = 1; i <= n; i++) {
					bounds[i] /= bounds[n];
				}
				bounds[n] = 1.0;
				fillWedges(extractor, cx, cy, r, slices, bounds);
			}
		}

		strokeCircle(extractor, cx, cy, r, RIM);
	}

	/** 鼠标是否落在饼上（多算 1 像素，贴边也算）。 */
	public static boolean hovered(double mouseX, double mouseY, int cx, int cy, int r) {
		double dx = mouseX - cx;
		double dy = mouseY - cy;
		return dx * dx + dy * dy <= (double) (r + 1) * (r + 1);
	}

	/**
	 * 弹出逐项百分比。
	 *
	 * <p>自己画而不是走原版提示框，是为了能配上相的颜色块。
	 *
	 * <p>{@code rightLimit} 是「不许越过」的那条竖线，一般传面板右缘。越过它就改从鼠标
	 * <b>左边</b>弹出来 —— 磁力筛机与离心机的切换格就挂在饼图右侧，浮层默认往右弹的话
	 * 正好把那三格盖住，想点还得先把鼠标挪开。
	 */
	public static void popup(GuiGraphicsExtractor extractor, Font font, Component title,
			List<Slice> slices, double mouseX, double mouseY,
			int screenWidth, int screenHeight, int rightLimit) {
		final int stub = 5;
		final int lineHeight = 11;
		final int padding = 4;
		/** 子项相对父项缩进多少像素 —— 「缩进一行」就靠它。 */
		final int childIndent = 7;

		record Row(Slice slice, int depth) {
		}
		List<Row> rows = new ArrayList<>();
		for (Slice slice : slices) {
			rows.add(new Row(slice, 0));
			for (Slice child : slice.children()) {
				rows.add(new Row(child, 1));
			}
		}

		int widest = font.width(title);
		for (Row entry : rows) {
			String percent = String.format(Locale.ROOT, "%.1f%%", entry.slice().fraction() * 100.0);
			widest = Math.max(widest, entry.depth() * childIndent + stub + 3
					+ font.width(entry.slice().name()) + 8 + font.width(percent));
		}

		int width = widest + padding * 2;
		int height = (rows.size() + 1) * lineHeight + padding * 2;

		int x = (int) mouseX + 10;
		if (x + width > rightLimit) {
			x = (int) mouseX - width - 10;
		}
		int y = (int) mouseY + 8;
		if (x + width > screenWidth - 2) {
			x = screenWidth - 2 - width;
		}
		if (y + height > screenHeight - 2) {
			y = screenHeight - 2 - height;
		}
		x = Math.max(2, x);
		y = Math.max(2, y);

		extractor.fill(x - 1, y - 1, x + width + 1, y + height + 1, POPUP_EDGE);
		extractor.fill(x, y, x + width, y + height, POPUP_BG);

		int row = y + padding;
		UiText.text(extractor, font, title, x + padding, row, POPUP_TEXT);
		row += lineHeight;

		for (Row entry : rows) {
			boolean child = entry.depth() > 0;
			int indent = x + padding + entry.depth() * childIndent;
			extractor.fill(indent, row + 3, indent + stub, row + 3 + stub,
					0xFF000000 | entry.slice().color());
			UiText.text(extractor, font, entry.slice().name(), indent + stub + 3, row,
					child ? POPUP_CHILD : POPUP_TEXT);
			String percent = String.format(Locale.ROOT, "%.1f%%", entry.slice().fraction() * 100.0);
			UiText.text(extractor, font, percent, x + width - padding - font.width(percent), row,
					child ? POPUP_CHILD_NUMBER : POPUP_NUMBER);
			row += lineHeight;
		}
	}

	// ------------------------------------------------------------------ 光栅化

	/** 逐行扫描整个圆，按角度落在哪一段来上色，并把行内连续同色合并成一次填充。 */
	private static void fillWedges(GuiGraphicsExtractor extractor, int cx, int cy, int r,
			List<Slice> slices, double[] bounds) {
		for (int dy = -r; dy <= r; dy++) {
			int half = (int) Math.floor(Math.sqrt((double) r * r - (double) dy * dy));
			int y = cy + dy;
			int runStart = 0;
			int runIndex = -1;

			for (int dx = -half; dx <= half; dx++) {
				int index = wedgeAt(dx, dy, bounds);
				if (index != runIndex) {
					if (runIndex >= 0) {
						extractor.fill(cx + runStart, y, cx + dx, y + 1,
								0xFF000000 | slices.get(runIndex).color());
					}
					runStart = dx;
					runIndex = index;
				}
			}
			if (runIndex >= 0) {
				extractor.fill(cx + runStart, y, cx + half + 1, y + 1,
						0xFF000000 | slices.get(runIndex).color());
			}
		}
	}

	/**
	 * 圆心是 (0,0)，从正上方（-π/2）起顺时针排。{@code atan2} 的值域与起点不同，
	 * 所以先换算成「相对起点的比例」再查表，免得跨界那一段永远匹配不上。
	 */
	private static int wedgeAt(int dx, int dy, double[] bounds) {
		if (dx == 0 && dy == 0) {
			return 0;
		}
		double relative = Math.atan2(dy, dx) + Math.PI / 2.0;
		while (relative < 0.0) {
			relative += TWO_PI;
		}
		while (relative >= TWO_PI) {
			relative -= TWO_PI;
		}
		double t = relative / TWO_PI;
		int index = 0;
		while (index < bounds.length - 2 && t >= bounds[index + 1]) {
			index++;
		}
		return index;
	}

	private static void fillDisc(GuiGraphicsExtractor extractor, int cx, int cy, int r, int color) {
		for (int dy = -r; dy <= r; dy++) {
			int half = (int) Math.floor(Math.sqrt((double) r * r - (double) dy * dy));
			extractor.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
		}
	}

	private static void strokeCircle(GuiGraphicsExtractor extractor, int cx, int cy, int r, int color) {
		for (int dy = -r; dy <= r; dy++) {
			int half = (int) Math.floor(Math.sqrt((double) r * r - (double) dy * dy));
			extractor.fill(cx - half, cy + dy, cx - half + 1, cy + dy + 1, color);
			extractor.fill(cx + half, cy + dy, cx + half + 1, cy + dy + 1, color);
		}
	}
}
