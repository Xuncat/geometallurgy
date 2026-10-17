package com.geometallurgy.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * 全模组共享的<b>客户端文字助手</b>（bug#46）。
 *
 * <h2>为什么要有这一层</h2>
 * 26.x 的 {@code GuiGraphicsExtractor} 有 5 参与 6 参两套 {@code text(...)}：
 * 5 参重载内部以 {@code dropShadow = true} 调 6 参重载（javap 已核：5 参重载压
 * {@code iconst_1} 再调 6 参）。于是「用 5 参」就等于「每行都带投影描边」——
 * 在小字号 / 深色底上看着就是糊成一团的「花体」。
 *
 * <p>原版的书与笔走 {@code ActiveTextCollector}，没有这层描边。本模组没有自定义
 * 字体资源，所以「花」的唯一来源就是这层默认描边 —— 修法只有一处：<b>所有自绘界面
 * 的文字一律显式 {@code false}</b>。
 *
 * <h2>只统一描边，不动别的</h2>
 * 这里刻意只做「转发 + 补 {@code false}」：字体（调用方传的 {@link Font}）、
 * 颜色、坐标、折行（{@link net.minecraft.client.gui.Font#split}）与裁剪
 * （{@link #clipped}）都保持在调用方，一个字、一个像素都不改。
 *
 * <h2>用法</h2>
 * <pre>{@code
 * // 旧：extractor.text(this.font, text, x, y, color);        // 5 参 = 带描边
 * // 新：
 * UiText.text(extractor, this.font, text, x, y, color);       // 不描边
 * }</pre>
 * 三个重载对应 26.x 的三种文本载体：{@link Component}、{@link String}、
 * {@link FormattedCharSequence}；都只调 6 参重载并显式传 {@code false}。
 */
public final class UiText {

	private UiText() {
	}

	/** 画一行 {@link Component}，<b>不描边</b>。 */
	public static void text(GuiGraphicsExtractor extractor, Font font, Component text, int x, int y, int color) {
		extractor.text(font, text, x, y, color, false);
	}

	/** 画一行字符串，<b>不描边</b>。 */
	public static void text(GuiGraphicsExtractor extractor, Font font, String text, int x, int y, int color) {
		extractor.text(font, text, x, y, color, false);
	}

	/** 画一行已经排好版序的文本，<b>不描边</b>。 */
	public static void text(GuiGraphicsExtractor extractor, Font font, FormattedCharSequence text, int x, int y,
			int color) {
		extractor.text(font, text, x, y, color, false);
	}

	/**
	 * 水平居中画一行：{@code centerX} 是这一行的中心线。
	 *
	 * <p>与 {@link #text} 同样不描边 —— 居中只是把 {@code font.width} 的算术收进一处，
	 * 免得每个界面各写一遍。
	 */
	public static void centered(GuiGraphicsExtractor extractor, Font font, Component text, int centerX, int y,
			int color) {
		text(extractor, font, text, centerX - font.width(text) / 2, y, color);
	}

	/**
	 * 画一行、按最大宽度裁到画得下为止（<b>保留原有能力</b>：裁剪，不折行）。
	 *
	 * <p>等同于调用方自己写 {@code font.plainSubstrByWidth(text.getString(), width)}
	 * 再 {@code Component.literal(...)}；抽出来只为了「裁剪」只有一套写法。
	 * 返回实际画出的字符数（0 = 一个字都放不下、什么都没画）。
	 */
	public static int clipped(GuiGraphicsExtractor extractor, Font font, Component text, int x, int y, int color,
			int width) {
		String clipped = font.plainSubstrByWidth(text.getString(), Math.max(0, width));
		if (clipped.isEmpty()) {
			return 0;
		}
		text(extractor, font, Component.literal(clipped), x, y, color);
		return clipped.length();
	}
}
