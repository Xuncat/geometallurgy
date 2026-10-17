package com.geometallurgy.client;

import java.io.BufferedReader;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import com.geometallurgy.Geometallurgy;
import com.geometallurgy.manual.Manual;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * 手册正文的「玩家当前语言」视图 —— 段数与文字都从<b>当前语言的语言文件</b>里读。
 *
 * <h2>为什么不再问游戏要翻译（2026-10-14 改）</h2>
 * 原先段数是这么数出来的：从 {@code .l1} 开始问
 * {@code Component.translatable(key).getString()}，直到「翻译结果 == 键名」为止 ——
 * 也就是拿「翻不出来」当「没有这一段」。在 26.4-snapshot-3 上这两条都不成立：
 *
 * <ol>
 *   <li><b>缺的键先落到 {@code en_us}</b>。中文文案比英文短时（改稿后很常见），
 *       中文书里就会接着冒出英文段 —— 页面上看着就是中文讲一半、突然切英文。</li>
 *   <li><b>两边都没有的键，解出来不保证是键名本身</b>。一旦不是，
 *       {@code == 键名} 这个判据就永远为假，循环一路数到
 *       {@link Manual#MAX_PARAGRAPHS}：3 段的条目凭空多出七十几个空段，
 *       页码从 2 页涨到 8 页，后面几页全空。</li>
 * </ol>
 *
 * <p>改成直接读文件之后，「谁在文件里，谁就是这一段」：段数与正文同源，
 * 既不串语言，也不会凭空多段。中文少一段就是少一段，英文多一段也只影响英文书。
 *
 * <h2>读不到怎么办</h2>
 * 语言文件被资源包改坏、命名空间对不上（附属模组注册章时键前缀写错）时，
 * 退回老的探测法 —— 至少书还能翻，不至于整本空白。退路只写在
 * {@link #probeCount} 里，一处。
 *
 * <h2>什么时候读</h2>
 * 每次打开书（构造 {@link ManualScreen}）读一次，不做缓存、不挂资源重载监听：
 * 开书是低频动作，两个 JSON 加起来几十 KB，而「语言变了/资源包换了」之后
 * 下次开书自然就是新的 —— 少一套失效逻辑，就少一类「改了但没生效」。
 */
public final class ManualText {

	/** 语言文件在命名空间下的路径。 */
	private static final String LANG_DIR = "lang/";
	private static final String LANG_SUFFIX = ".json";

	/** 当前语言的语言文件里实际存在的键；{@code null} = 没读到，走探测法。 */
	private final Set<String> keys;

	private ManualText(Set<String> keys) {
		this.keys = keys;
	}

	/** 读当前语言下、手册用到的所有命名空间的语言文件。 */
	public static ManualText load() {
		Set<String> keys = new HashSet<>();
		try {
			Minecraft minecraft = Minecraft.getInstance();
			ResourceManager resources = minecraft.getResourceManager();
			String language = minecraft.getLanguageManager().getSelected();
			for (String namespace : Manual.namespaces()) {
				Identifier id = Identifier.fromNamespaceAndPath(namespace,
						LANG_DIR + language + LANG_SUFFIX);
				Optional<Resource> resource = resources.getResource(id);
				if (resource.isEmpty()) {
					continue;
				}
				try (BufferedReader reader = resource.get().openAsReader()) {
					JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
					keys.addAll(json.keySet());
				}
			}
		} catch (Exception e) {
			Geometallurgy.LOGGER.warn("[手册] 读不出当前语言的语言文件（{}），段数退回探测法",
					e.toString());
			return new ManualText(null);
		}
		if (keys.isEmpty()) {
			Geometallurgy.LOGGER.warn("[手册] 当前语言的语言文件里一个手册键都没有，段数退回探测法");
			return new ManualText(null);
		}
		return new ManualText(keys);
	}

	/** 这一条在当前语言下有几句。 */
	public int paragraphCount(Manual.Chapter chapter, Manual.Entry entry) {
		if (this.keys == null) {
			return probeCount(chapter, entry);
		}
		int count = 0;
		while (count < Manual.MAX_PARAGRAPHS
				&& this.keys.contains(Manual.paragraphKey(chapter, entry, count + 1))) {
			count++;
		}
		return count;
	}

	/**
	 * 第 {@code index} 段（从 1 起）。
	 *
	 * <p>当前语言没写这一段就返回空 —— <b>不回退到别的语言</b>。
	 * 空段仍然占一行，折行后就是一个空行（作者用空串留空行也是这个效果）。
	 */
	public Component paragraph(Manual.Chapter chapter, Manual.Entry entry, int index) {
		String key = Manual.paragraphKey(chapter, entry, index);
		if (this.keys == null) {
			return Component.translatable(key);
		}
		return this.keys.contains(key) ? Component.translatable(key) : Component.empty();
	}

	/**
	 * 退路：老办法，问游戏要翻译。
	 *
	 * <p>留着只为「语言文件读不到」这一种情况 —— 那时宁可多冒几段英文，
	 * 也好过整本书一片空白。
	 */
	private static int probeCount(Manual.Chapter chapter, Manual.Entry entry) {
		int count = 0;
		while (count < Manual.MAX_PARAGRAPHS) {
			String key = Manual.paragraphKey(chapter, entry, count + 1);
			if (Component.translatable(key).getString().equals(key)) {
				break;
			}
			count++;
		}
		return count;
	}
}
