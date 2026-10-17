package com.geometallurgy.debug;

import java.util.List;

import com.geometallurgy.Geometallurgy;
import com.geometallurgy.client.ManualText;
import com.geometallurgy.manual.Manual;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * 手册正文段数探针（验完可删）。
 *
 * <p>验的是「段数与正文都来自玩家当前语言的语言文件」这件事：
 *
 * <pre>JAVA_TOOL_OPTIONS=-Dgeometallurgy.manualTextProbe=true ./gradlew runClient</pre>
 *
 * <p>日志里每一行是「条目 → 段数」。中文环境下这些数应当正好等于
 * {@code zh_cn.json} 里 {@code .l1..lN} 的个数；如果某条打出 0，说明那个命名空间的
 * {@code lang/zh_cn.json} 没被读到；如果打出 80（{@link Manual#MAX_PARAGRAPHS}），
 * 说明读文件失败、退回了老的探测法。
 *
 * <p>不需要进世界：客户端主菜单上资源管理器与语言管理器就已经就绪，读一次就够。
 */
public final class ManualTextProbe {

	private static final String SWITCH = "geometallurgy.manualTextProbe";

	private static boolean done;

	private ManualTextProbe() {
	}

	public static void init() {
		Geometallurgy.LOGGER.warn("[手册段数探针] 已启用（{}）", SWITCH);
		ClientTickEvents.END_CLIENT_TICK.register(ManualTextProbe::tick);
	}

	private static void tick(Minecraft client) {
		if (done || client.getResourceManager() == null) {
			return;
		}
		done = true;

		String language = client.getLanguageManager().getSelected();
		ManualText text = ManualText.load();
		List<Manual.Chapter> chapters = Manual.chapters();
		Geometallurgy.LOGGER.info("[手册段数探针] 语言={}｜章数={}｜命名空间={}",
				language, chapters.size(), Manual.namespaces());

		for (Manual.Chapter chapter : chapters) {
			for (Manual.Entry entry : chapter.entries()) {
				Geometallurgy.LOGGER.info("[手册段数探针]   {}.{} → 段数 {}",
						chapter.id(), entry.id(), text.paragraphCount(chapter, entry));
			}
		}
	}
}
