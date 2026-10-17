package com.geometallurgy.client;

import java.util.List;

import com.geometallurgy.manual.Manual;
import com.geometallurgy.menu.ManualMenu;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 《地质工业冶金 · 任务书》—— <b>摊开的书本</b>。
 *
 * <h2>一、左页 / 右页 / 书脊</h2>
 * 屏幕正中是一本摊开的书：两块纸色矩形（{@link #PAGE}）夹一条书脊，
 * 外圈深棕封皮（{@link #COVER}）、内沿描边（{@link #PAGE_FRAME} / {@link #SPINE}）、
 * 书脊两侧几道半透明竖条画出纸张向脊里弯的阴影 —— 没有用任何贴图，全是
 * {@code extractor.fill}。这套画法是从 {@code backups/geometallurgy-20261017-123606.jar}
 * 里 {@code ManualScreen} 的旧字节码量回来的（原代码已删，常量见本类顶部）。
 *
 * <h2>二、内容流式分页</h2>
 * 正文块（小标题 / 自然段 / 链接 / 表格 / 合成网格）交给 {@link ManualPager} 按
 * 「一页正文区有多高」切页：左页放不下就顺到右页，右页也满了就翻到下一跨；
 * 自然段按页宽折行、<b>按行切</b>，表格<b>按行切</b>并在续页补表头。
 * 所以文字永远在纸框里，不会像旧的单列浏览器那样一路溢出去。
 * 页尺寸随窗口缩放（scale 2/3、小窗都夹进屏幕），因此分页在每次 {@link #init()} 重算。
 *
 * <h2>三、两级目录、面包屑、页签</h2>
 * 书本第一跨是<b>总目录</b>（只有四行，每部分一行「部分名 · N 页」）；
 * 点一行进该部分的<b>部分目录页</b>（该部分第一个物理页，列小节名与部分内首页码），
 * 从部分目录再点进正文那一条 —— 书签也走同一条路（先到部分目录，再从那里进正文）。
 * 整跨只在<b>左上角印一行面包屑</b>「部分名 → 小节名」（右页顶部不印标题）；
 * 页脚按部分独立编号 {@code 部分名 局部页/部分总页数}，书首总目录印全书的第几页。
 *
 * <h2>四、翻页</h2>
 * 左下 / 右下两个箭头（悬停高亮）、{@code ←/→} 与 {@code PageUp/PageDown} 键、
 * 滚轮都能翻页；底部中间是全书页码（次要参考），两页各自在页脚有本部分页码。
 *
 * <h2>五、搜索框仍然钉在最上面</h2>
 * 输入即过滤 {@link ManualSearch} 的索引：精确标题 &gt; 前缀 &gt; 包含 &gt; id &gt; 正文；
 * {@code ↑/↓} 选择、{@code Enter} 或点击打开；物质类结果直接进
 * {@link ReactionDetailScreen}，其余跳到那一页并高亮那一条。
 * {@code ESC} 先清词、词空了才关书；开书时输入框不聚焦，点一下或直接敲字才进框。
 */
public class ManualScreen extends AbstractContainerScreen<ManualMenu> {

	// ------------------------------------------------------------------ 配色

	private static final int BACKDROP = 0xF018130E;
	private static final int BAR = 0xFF2A1E14;
	private static final int BAR_EDGE = 0xFF4E3320;

	/** 封皮：取自旧字节码 {@code COVER = 0xFF4E3320} 的同类深棕，稍暗一档做封皮面。 */
	private static final int COVER = 0xFF4E3320;
	private static final int COVER_DARK = 0xFF2E1E10;
	private static final int PAGE = 0xFFE9DFC4;
	private static final int PAGE_FRAME = 0xFFCFC09A;
	private static final int SPINE = 0xFFB8A87E;

	private static final int SELECT = 0xFFB08A48;
	private static final int TAB_BG = 0xFFCFC09A;
	private static final int TAB_HOVER = 0xFFDDD0AD;
	private static final int TAB_ACTIVE = 0xFFB08A48;
	private static final int TAB_EDGE = 0xFF8A6A3A;
	private static final int HOVER_PAPER = 0x332F1F0C;
	private static final int FLASH = 0x44B08A48;
	private static final int RESULT_BG = 0xFFF3EAD3;
	private static final int RESULT_EDGE = 0xFF8A6A3A;
	private static final int LABEL = 0xFFF0E6CE;
	private static final int LABEL_DIM = 0xFFB8A88C;

	// ------------------------------------------------------------------ 尺寸

	private static final int TOP_H = 26;
	private static final int BOTTOM_H = 16;
	private static final int MARGIN = 8;
	private static final int SEARCH_H = 16;
	private static final int TECH_W = 64;
	private static final int TECH_H = 16;

	/** 旧书的封面尺寸（{@code BOOK_W / BOOK_H}），作为窗口够大时的上限。 */
	private static final int BOOK_MAX_W = 312;
	private static final int BOOK_MAX_H = 196;
	private static final int COVER_PAD = 4;
	private static final int SPINE_GAP = 10;
	private static final int PAD_X = 7;
	private static final int TAB_GAP = 4;
	private static final int ARROW_W = 18;
	private static final int FLASH_MS = 1600;

	private static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");

	// ------------------------------------------------------------------ 内容

	/** 按 {@link Manual} 的章节骨架 + 当前语言的正文 + 现算的数据表拼出来的书。 */
	private final ManualDoc.Book book;
	/** 全书搜索索引（页 / 小标题 / 链接 / 每个表格行）。 */
	private final List<ManualSearch.Entry> index;

	private ManualPager.Result paged;

	private EditBox searchBox;

	private String query = "";
	private List<ManualSearch.Result> results = List.of();
	private int pick;
	private boolean dismissed;

	/** 当前跨（一跨 = 左右两页）。 */
	private int spread;
	/** 跨窗口变化仍想停在哪一条（逻辑页下标）。 */
	private int cursorLogical = -1;
	/** 搜索跳转后的高亮。 */
	private int flashPage = -1;
	private int flashY;
	private long flashUntil;

	// ------------------------------------------------------------------ 几何（init 里算）

	private int bookW;
	private int bookH;
	private int bookX;
	private int bookY;
	private int innerX;
	private int innerY;
	private int innerW;
	private int innerH;
	private int pageW;
	private int pageLeftX;
	private int pageRightX;
	private int textW;
	private int bodyTop;
	private int bodyH;
	private int tabX;
	private int tabW;
	private int tabTop;
	private int tabH;
	private int arrowH;

	public ManualScreen(ManualMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, 320, 200);
		ManualText text = ManualText.load();
		this.book = ManualDoc.build(text);
		this.index = ManualSearch.index(this.book);
	}

	// ------------------------------------------------------------------ 生命周期

	@Override
	protected void init() {
		super.init();
		this.layoutGeometry();
		this.paged = ManualPager.build(this.font, this.book, this.textW, this.bodyH,
				this::gridFor, Component.translatable("manual.geometallurgy.toc"));

		if (this.cursorLogical >= 0 && this.cursorLogical < this.paged.logicalStart.length) {
			int page = this.paged.logicalStart[this.cursorLogical];
			if (page >= 0) {
				this.spread = page / 2;
			}
		}
		this.spread = Math.clamp(this.spread, 0, this.maxSpread());

		int searchX = this.searchX();
		this.searchBox = new EditBox(this.font, searchX, (TOP_H - SEARCH_H) / 2, this.searchW(), SEARCH_H,
				Component.translatable("manual.geometallurgy.search"));
		this.searchBox.setMaxLength(64);
		this.searchBox.setBordered(true);
		// 书与笔那种平正文：默认字体、不描边（bug#44）。EditBox 是独立控件，要单独关阴影。
		this.searchBox.setTextShadow(false);
		this.searchBox.setHint(Component.translatable("manual.geometallurgy.search.hint"));
		this.searchBox.setValue(this.query);
		this.searchBox.setResponder(this::onQueryChanged);
		this.addRenderableWidget(this.searchBox);

		// 开书时输入框**不聚焦**：只调 clearFocus() 不够，容器里的 focused 字段还留着。
		this.setFocused((GuiEventListener) null);
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		// 本界面没有槽位，原版的「标题 + 物品栏」两条标签一律不要。
	}

	/** 本界面不暂停世界（与科技线一致）。 */
	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ------------------------------------------------------------------ 几何

	/** 把书夹进窗口：宽了缩、矮了缩；左边留标签条的位置。 */
	private void layoutGeometry() {
		int availW = Math.max(80, this.width - MARGIN * 2);
		int availH = Math.max(80, this.height - TOP_H - 4 - BOTTOM_H);

		this.tabW = Math.clamp(availW / 8, 22, 44);
		this.bookW = Math.clamp(availW - TAB_GAP - this.tabW, 120, BOOK_MAX_W);
		this.bookH = Math.clamp(availH, 110, BOOK_MAX_H);

		int totalW = this.bookW + TAB_GAP + this.tabW;
		this.bookX = MARGIN + Math.max(0, (availW - totalW) / 2);
		this.bookY = TOP_H + 4 + Math.max(0, (availH - this.bookH) / 2);

		this.innerX = this.bookX + COVER_PAD;
		this.innerY = this.bookY + COVER_PAD;
		this.innerW = this.bookW - COVER_PAD * 2;
		this.innerH = this.bookH - COVER_PAD * 2;

		this.pageW = Math.max(28, (this.innerW - SPINE_GAP) / 2);
		this.pageLeftX = this.innerX;
		this.pageRightX = this.innerX + this.pageW + SPINE_GAP;

		int padTop = 6;
		int headerLine = this.font.lineHeight + 4;
		int padBottom = this.font.lineHeight + 7;
		this.bodyTop = this.innerY + padTop + headerLine;
		this.bodyH = Math.max(20, this.innerH - padTop - headerLine - padBottom);
		this.textW = Math.max(24, this.pageW - PAD_X * 2);

		this.tabH = this.font.lineHeight + 6;
		this.tabX = this.bookX + this.bookW - 1;
		this.tabTop = this.innerY + 2;
		this.arrowH = this.font.lineHeight + 6;
	}

	private int maxSpread() {
		int count = this.paged == null ? 0 : this.paged.pages.size();
		return Math.max(0, (count - 1) / 2);
	}

	/** 书页左/右：全局页下标偶数在左、奇数在右。 */
	private int pageX(int pageIndex) {
		return (pageIndex & 1) == 0 ? this.pageLeftX : this.pageRightX;
	}

	/** 搜索框的起点：书名右边；窗口太窄时干脆不给书名留位置。 */
	private int searchX() {
		int titleW = this.font.width(Component.translatable("manual.geometallurgy.book_name"));
		int techX = this.width - MARGIN - TECH_W;
		return MARGIN + titleW + 8 + 140 > techX ? MARGIN : MARGIN + titleW + 8;
	}

	private int searchW() {
		int techX = this.width - MARGIN - TECH_W;
		return Math.max(60, techX - 6 - this.searchX());
	}

	/** 当前跨左页算在哪个部分（目录 = -1）。 */
	private int currentPart() {
		if (this.paged == null || this.paged.partStart.length == 0) {
			return -1;
		}
		int page = this.spread * 2;
		for (int i = this.paged.partStart.length - 1; i >= 0; i--) {
			if (this.paged.partStart[i] >= 0 && page >= this.paged.partStart[i]) {
				return i;
			}
		}
		return -1;
	}

	private int currentTab() {
		int part = this.currentPart();
		return part < 0 ? 0 : part + 1;
	}

	private int tabCount() {
		return 1 + this.book.parts.size();
	}

	private int tabY(int tab) {
		return this.tabTop + tab * (this.tabH + 2);
	}

	private void goSpread(int target) {
		this.spread = Math.clamp(target, 0, this.maxSpread());
		this.pageCursorFromSpread();
	}

	private void pageCursorFromSpread() {
		if (this.paged == null) {
			return;
		}
		int page = this.spread * 2;
		for (int i = 0; i < 2 && page + i < this.paged.pages.size(); i++) {
			int logical = this.paged.pages.get(page + i).logical;
			if (logical >= 0) {
				this.cursorLogical = logical;
				return;
			}
		}
	}

	private List<ItemStack> gridFor(int recipeIndex) {
		if (recipeIndex < 0) {
			return null;
		}
		List<ItemStack> items = this.menu.recipeGrid(recipeIndex);
		if (items == null || items.size() < Manual.RECIPE_SLOTS) {
			return null;
		}
		for (ItemStack stack : items) {
			if (!stack.isEmpty()) {
				return items;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ 绘制

	@Override
	public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(extractor, mouseX, mouseY, partialTick);
		extractor.fill(0, 0, this.width, this.height, BACKDROP);

		this.drawBook(extractor);
		this.drawPages(extractor, mouseX, mouseY);
		this.drawTabs(extractor, mouseX, mouseY);
		this.drawNav(extractor, mouseX, mouseY);
		this.drawTopBar(extractor, mouseX, mouseY);
		this.drawResults(extractor, mouseX, mouseY);

		int tab = this.tabAt(mouseX, mouseY);
		if (tab >= 0) {
			Component full = this.tabLabel(tab);
			extractor.setComponentTooltipForNextFrame(this.font, List.of(full), mouseX, mouseY);
			return;
		}
		if (this.arrowAt(mouseX, mouseY, true)) {
			extractor.setComponentTooltipForNextFrame(this.font,
					List.of(Component.translatable("manual.geometallurgy.nav.prev")), mouseX, mouseY);
			return;
		}
		if (this.arrowAt(mouseX, mouseY, false)) {
			extractor.setComponentTooltipForNextFrame(this.font,
					List.of(Component.translatable("manual.geometallurgy.nav.next")), mouseX, mouseY);
			return;
		}
		ManualPager.Hot hovered = this.hotAt(mouseX, mouseY);
		if (hovered != null && hovered.tooltip() != null && !hovered.tooltip().isEmpty()) {
			extractor.setComponentTooltipForNextFrame(this.font, hovered.tooltip(), mouseX, mouseY);
		}
	}

	/** 封皮、两块纸、书脊、纸张向脊里的阴影。全部是 fill，没有贴图。 */
	private void drawBook(GuiGraphicsExtractor extractor) {
		extractor.fill(this.bookX, this.bookY, this.bookX + this.bookW, this.bookY + this.bookH, COVER);
		extractor.fill(this.bookX, this.bookY, this.bookX + this.bookW, this.bookY + 2, COVER_DARK);
		extractor.fill(this.bookX, this.bookY + this.bookH - 2, this.bookX + this.bookW,
				this.bookY + this.bookH, COVER_DARK);
		extractor.fill(this.bookX, this.bookY, this.bookX + 2, this.bookY + this.bookH, COVER_DARK);
		extractor.fill(this.bookX + this.bookW - 2, this.bookY, this.bookX + this.bookW,
				this.bookY + this.bookH, COVER_DARK);

		int top = this.innerY;
		int bottom = this.innerY + this.innerH;
		extractor.fill(this.pageLeftX, top, this.pageLeftX + this.pageW, bottom, PAGE);
		extractor.fill(this.pageRightX, top, this.pageRightX + this.pageW, bottom, PAGE);
		extractor.fill(this.pageLeftX, top, this.pageLeftX + this.pageW, top + 1, PAGE_FRAME);
		extractor.fill(this.pageLeftX, bottom - 1, this.pageLeftX + this.pageW, bottom, PAGE_FRAME);
		extractor.fill(this.pageRightX, top, this.pageRightX + this.pageW, top + 1, PAGE_FRAME);
		extractor.fill(this.pageRightX, bottom - 1, this.pageRightX + this.pageW, bottom, PAGE_FRAME);

		// 书脊：两块纸之间的缝 + 两侧内沿
		extractor.fill(this.pageLeftX + this.pageW, top, this.pageRightX, bottom, COVER_DARK);
		extractor.fill(this.pageLeftX + this.pageW, top, this.pageLeftX + this.pageW + 1, bottom, SPINE);
		extractor.fill(this.pageRightX - 1, top, this.pageRightX, bottom, SPINE);

		// 纸张向脊里弯：几道由深到浅的半透明竖条
		for (int i = 0; i < 3; i++) {
			int shade = (0x18 - i * 6) << 24;
			extractor.fill(this.pageLeftX + this.pageW - 1 - i - 2, top + 1,
					this.pageLeftX + this.pageW - 1 - i, bottom - 1, shade);
			extractor.fill(this.pageRightX + 1 + i, top + 1,
					this.pageRightX + 2 + i, bottom - 1, shade);
		}
	}

	private void drawPages(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		if (this.paged == null) {
			return;
		}
		this.drawBreadcrumb(extractor);
		int first = this.spread * 2;
		for (int i = 0; i < 2; i++) {
			int page = first + i;
			if (page >= 0 && page < this.paged.pages.size()) {
				this.drawOnePage(extractor, page, mouseX, mouseY);
			}
		}
	}

	private void drawOnePage(GuiGraphicsExtractor extractor, int pageIndex, int mouseX, int mouseY) {
		ManualPager.Page page = this.paged.pages.get(pageIndex);
		int px = this.pageX(pageIndex);
		int bottom = this.innerY + this.innerH;

		// 页眉不在这里画：整跨的左上角只印一行面包屑，见 drawBreadcrumb。
		int tx = px + PAD_X;
		extractor.enableScissor(px + 1, this.bodyTop, px + this.pageW - 1, this.bodyTop + this.bodyH);
		for (ManualPager.Fill fill : page.fills) {
			extractor.fill(tx + fill.x0(), this.bodyTop + fill.y0(),
					tx + fill.x1(), this.bodyTop + fill.y1(), fill.color());
		}
		if (pageIndex == this.flashPage && System.currentTimeMillis() < this.flashUntil) {
			extractor.fill(tx, this.bodyTop + this.flashY - 1,
					px + this.pageW - PAD_X, this.bodyTop + this.flashY + this.font.lineHeight, FLASH);
		}
		ManualPager.Hot hovered = this.hotInPage(page, px, mouseX, mouseY);
		if (hovered != null) {
			extractor.fill(tx + hovered.x0(), this.bodyTop + hovered.y0(),
					tx + hovered.x1(), this.bodyTop + hovered.y1(), HOVER_PAPER);
		}
		for (ManualPager.Line line : page.lines) {
			UiText.text(extractor, this.font, line.text(), tx + line.x(), this.bodyTop + line.y(), line.color());
		}
		for (ManualPager.Grid grid : page.grids) {
			this.drawGrid(extractor, tx + grid.x(), this.bodyTop + grid.y(), grid.recipeIndex());
		}
		extractor.disableScissor();

		if (!page.blank) {
			Component folio = this.folio(pageIndex);
			UiText.text(extractor, this.font, folio, px + (this.pageW - this.font.width(folio)) / 2,
					bottom - this.font.lineHeight - 4, ManualPager.INK_DIM);
		}
	}

	/**
	 * 整跨只印一行面包屑，左对齐在左页上沿内 —— 用这一跨里第一张有内容的纸页的
	 * {@code 部分名 → 小节名}（右页顶部不再印任何标题）。
	 */
	private void drawBreadcrumb(GuiGraphicsExtractor extractor) {
		if (this.paged == null) {
			return;
		}
		Component crumb = null;
		int first = this.spread * 2;
		for (int i = 0; i < 2; i++) {
			int index = first + i;
			if (index < 0 || index >= this.paged.pages.size()) {
				continue;
			}
			ManualPager.Page page = this.paged.pages.get(index);
			if (!page.blank && !page.crumb.getString().isEmpty()) {
				crumb = page.crumb;
				break;
			}
		}
		if (crumb == null) {
			return;
		}
		// 允许跨过书脊排到右页：一行的宽度就是整跨的宽度，宁可长一点也不截断小节名。
		String text = this.font.plainSubstrByWidth(crumb.getString(), this.innerW - PAD_X * 2);
		if (text.isEmpty()) {
			return;
		}
		int x = this.pageLeftX + PAD_X;
		int y = this.innerY + 5;
		UiText.text(extractor, this.font, Component.literal(text), x, y, ManualPager.INK_DIM);
		extractor.fill(x, y + this.font.lineHeight + 1, x + this.font.width(text),
				y + this.font.lineHeight + 2, PAGE_FRAME);
	}

	/** 页脚：部分内页码 {@code 部分名 局部/部分总页数}；书首（总目录）印全书的第几页。 */
	private Component folio(int pageIndex) {
		int part = this.paged.partOf(pageIndex);
		int local = this.paged.localPage(pageIndex);
		if (part >= 0 && local > 0) {
			return Component.translatable("manual.geometallurgy.folio.part",
					this.book.parts.get(part).title, local, this.paged.partPages(part));
		}
		return Component.translatable("manual.geometallurgy.page.global", pageIndex + 1);
	}

	private void drawTabs(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		int active = this.currentTab();
		for (int tab = 0; tab < this.tabCount(); tab++) {
			int y = this.tabY(tab);
			boolean hover = this.inside(mouseX, mouseY, this.tabX, y, this.tabW, this.tabH);
			boolean selected = tab == active;
			int face = selected ? TAB_ACTIVE : hover ? TAB_HOVER : TAB_BG;
			extractor.fill(this.tabX, y, this.tabX + this.tabW, y + this.tabH, face);
			extractor.fill(this.tabX, y, this.tabX + 1, y + this.tabH, TAB_EDGE);
			extractor.fill(this.tabX, y, this.tabX + this.tabW, y + 1, TAB_EDGE);
			extractor.fill(this.tabX, y + this.tabH - 1, this.tabX + this.tabW, y + this.tabH, TAB_EDGE);
			Component label = this.tabLabel(tab);
			UiText.clipped(extractor, this.font, label, this.tabX + 3,
					y + (this.tabH - this.font.lineHeight) / 2 + 1, selected ? 0xFFFFFFFF : ManualPager.INK,
					this.tabW - 6);
		}
	}

	private Component tabLabel(int tab) {
		return tab == 0 ? Component.translatable("manual.geometallurgy.toc") : this.book.parts.get(tab - 1).title;
	}

	private void drawNav(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		int y = this.innerY + this.innerH - this.arrowH - 2;
		int leftX = this.pageLeftX + 4;
		int rightX = this.pageRightX + this.pageW - ARROW_W - 4;

		if (this.spread > 0) {
			this.drawArrow(extractor, leftX, y, "<", this.inside(mouseX, mouseY, leftX, y, ARROW_W, this.arrowH));
		}
		if (this.spread < this.maxSpread()) {
			this.drawArrow(extractor, rightX, y, ">",
					this.inside(mouseX, mouseY, rightX, y, ARROW_W, this.arrowH));
		}

		int total = this.paged == null ? 1 : Math.max(1, this.paged.pages.size());
		Component folio = Component.translatable("manual.geometallurgy.page", this.spread * 2 + 1, total);
		UiText.text(extractor, this.font, folio, this.bookX + (this.bookW - this.font.width(folio)) / 2,
				this.height - BOTTOM_H + 3, LABEL_DIM);

		Component hint = Component.translatable("manual.geometallurgy.nav.hint");
		UiText.clipped(extractor, this.font, hint, MARGIN, this.height - BOTTOM_H + 3, LABEL_DIM,
				Math.max(20, this.bookX - MARGIN - 4));
	}

	private void drawArrow(GuiGraphicsExtractor extractor, int x, int y, String glyph, boolean hover) {
		extractor.fill(x, y, x + ARROW_W, y + this.arrowH, hover ? SELECT : 0x30B8A87E);
		extractor.fill(x, y, x + ARROW_W, y + 1, PAGE_FRAME);
		Component text = Component.literal(glyph);
		UiText.text(extractor, this.font, text, x + (ARROW_W - this.font.width(text)) / 2,
				y + (this.arrowH - this.font.lineHeight) / 2 + 1, ManualPager.INK);
	}

	private void drawTopBar(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		extractor.fill(0, 0, this.width, TOP_H, BAR);
		extractor.fill(0, TOP_H - 1, this.width, TOP_H, BAR_EDGE);

		if (this.searchX() > MARGIN) {
			Component name = Component.translatable("manual.geometallurgy.book_name");
			UiText.text(extractor, this.font, name, MARGIN, (TOP_H - this.font.lineHeight) / 2 + 1, LABEL);
		}

		int tx = this.width - MARGIN - TECH_W;
		int ty = (TOP_H - TECH_H) / 2;
		boolean hover = this.inside(mouseX, mouseY, tx, ty, TECH_W, TECH_H);
		extractor.fill(tx, ty, tx + TECH_W, ty + TECH_H, hover ? 0xFF7A5A2A : 0xFF4E3320);
		extractor.fill(tx, ty, tx + TECH_W, ty + 1, BAR_EDGE);
		Component label = Component.translatable("gui.geometallurgy.techtree.button");
		UiText.text(extractor, this.font, label, tx + (TECH_W - this.font.width(label)) / 2,
				ty + (TECH_H - this.font.lineHeight) / 2 + 1, LABEL);
	}

	private void drawGrid(GuiGraphicsExtractor extractor, int x, int y, int recipeIndex) {
		List<ItemStack> items = this.menu.recipeGrid(recipeIndex);
		if (items == null || items.size() < Manual.RECIPE_SLOTS) {
			return;
		}
		for (int slot = 0; slot < 9; slot++) {
			int cx = x + (slot % 3) * ManualPager.CELL;
			int cy = y + (slot / 3) * ManualPager.CELL;
			this.drawSlot(extractor, cx, cy, items.get(slot));
		}
		int ax = x + ManualPager.CELL * 3 + 6;
		int ay = y + ManualPager.CELL * 3 / 2;
		extractor.fill(ax, ay, ax + 6, ay + 1, ManualPager.INK_DIM);
		extractor.fill(ax + 5, ay - 2, ax + 6, ay + 3, ManualPager.INK_DIM);
		int rx = ax + 12;
		this.drawSlot(extractor, rx, y + ManualPager.CELL, items.get(9));
	}

	private void drawSlot(GuiGraphicsExtractor extractor, int x, int y, ItemStack stack) {
		extractor.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, 18, 18, 0, 0, x - 1, y - 1, 18, 18);
		if (!stack.isEmpty()) {
			extractor.item(stack, x, y);
			extractor.itemDecorations(this.font, stack, x, y);
		}
	}

	// ------------------------------------------------------------------ 搜索结果

	private boolean searchOpen() {
		return !this.query.isBlank() && !this.dismissed;
	}

	private int resultHeight() {
		int rows = Math.max(1, this.results.size());
		return rows * (this.font.lineHeight + 3) + 6;
	}

	private void drawResults(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		if (!this.searchOpen()) {
			return;
		}
		int x0 = this.bookX;
		int x1 = this.bookX + this.bookW;
		int y0 = TOP_H + 2;
		int y1 = y0 + this.resultHeight();
		extractor.fill(x0, y0, x1, y1, RESULT_BG);
		extractor.fill(x0, y0, x1, y0 + 1, RESULT_EDGE);
		extractor.fill(x0, y1 - 1, x1, y1, RESULT_EDGE);

		if (this.results.isEmpty()) {
			UiText.text(extractor, this.font, Component.translatable("manual.geometallurgy.search.none"),
					x0 + 6, y0 + 4, ManualPager.INK_DIM);
			return;
		}

		for (int i = 0; i < this.results.size(); i++) {
			ManualSearch.Result result = this.results.get(i);
			int y = y0 + 3 + i * (this.font.lineHeight + 3);
			if (i == this.pick) {
				extractor.fill(x0 + 1, y - 1, x1 - 1, y + this.font.lineHeight + 1, 0x30B08A48);
			}
			ManualSearch.Entry entry = result.entry();
			UiText.text(extractor, this.font, entry.title(), x0 + 6, y, ManualPager.INK);

			int used = 6 + this.font.width(entry.title()) + 8;
			String where = entry.speciesId() != null
					? Component.translatable("manual.geometallurgy.search.species").getString()
					: this.pageTitle(entry.page()).getString();
			UiText.clipped(extractor, this.font, Component.literal("· " + where), x0 + used, y,
					ManualPager.INK_DIM, Math.max(10, x1 - x0 - used - 4));
		}
	}

	private Component pageTitle(int index) {
		if (index < 0 || index >= this.book.flat.size()) {
			return Component.empty();
		}
		return this.book.flat.get(index).title;
	}

	// ------------------------------------------------------------------ 命中

	private ManualPager.Hot hotInPage(ManualPager.Page page, int px, double mouseX, double mouseY) {
		if (mouseX < px || mouseX >= px + this.pageW
				|| mouseY < this.bodyTop || mouseY >= this.bodyTop + this.bodyH) {
			return null;
		}
		double cx = mouseX - (px + PAD_X);
		double cy = mouseY - this.bodyTop;
		for (ManualPager.Hot hot : page.hots) {
			if (cx >= hot.x0() && cx < hot.x1() && cy >= hot.y0() && cy < hot.y1()) {
				return hot;
			}
		}
		return null;
	}

	private ManualPager.Hot hotAt(double mouseX, double mouseY) {
		if (this.paged == null) {
			return null;
		}
		for (int i = 0; i < 2; i++) {
			int pageIndex = this.spread * 2 + i;
			if (pageIndex < 0 || pageIndex >= this.paged.pages.size()) {
				continue;
			}
			ManualPager.Hot hot = this.hotInPage(this.paged.pages.get(pageIndex),
					this.pageX(pageIndex), mouseX, mouseY);
			if (hot != null) {
				return hot;
			}
		}
		return null;
	}

	private int resultAt(double mouseX, double mouseY) {
		if (!this.searchOpen() || this.results.isEmpty()) {
			return -1;
		}
		int x0 = this.bookX;
		int x1 = this.bookX + this.bookW;
		int y0 = TOP_H + 2;
		if (mouseX < x0 || mouseX > x1 || mouseY < y0 || mouseY > y0 + this.resultHeight()) {
			return -1;
		}
		int row = (int) ((mouseY - (y0 + 3)) / (this.font.lineHeight + 3));
		return row >= 0 && row < this.results.size() ? row : -1;
	}

	private int tabAt(double mouseX, double mouseY) {
		for (int tab = 0; tab < this.tabCount(); tab++) {
			if (this.inside(mouseX, mouseY, this.tabX, this.tabY(tab), this.tabW, this.tabH)) {
				return tab;
			}
		}
		return -1;
	}

	private boolean arrowAt(double mouseX, double mouseY, boolean left) {
		int y = this.innerY + this.innerH - this.arrowH - 2;
		if (left) {
			return this.spread > 0 && this.inside(mouseX, mouseY, this.pageLeftX + 4, y, ARROW_W, this.arrowH);
		}
		return this.spread < this.maxSpread() && this.inside(mouseX, mouseY,
				this.pageRightX + this.pageW - ARROW_W - 4, y, ARROW_W, this.arrowH);
	}

	private boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
		return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}

	private boolean insideSearchBox(double mouseX, double mouseY) {
		if (this.searchBox == null) {
			return false;
		}
		return mouseX >= this.searchBox.getX() && mouseX < this.searchBox.getX() + this.searchBox.getWidth()
				&& mouseY >= this.searchBox.getY() && mouseY < this.searchBox.getY() + this.searchBox.getHeight();
	}

	private boolean techButtonAt(double mouseX, double mouseY) {
		int tx = this.width - MARGIN - TECH_W;
		int ty = (TOP_H - TECH_H) / 2;
		return this.inside(mouseX, mouseY, tx, ty, TECH_W, TECH_H);
	}

	// ------------------------------------------------------------------ 交互

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			if (this.insideSearchBox(event.x(), event.y())) {
				this.dismissed = false;
			} else {
				this.setFocused((GuiEventListener) null);
			}
			int result = this.resultAt(event.x(), event.y());
			if (result >= 0) {
				this.activate(this.results.get(result));
				return true;
			}
			if (this.techButtonAt(event.x(), event.y())) {
				this.activate("tech");
				return true;
			}
			if (this.arrowAt(event.x(), event.y(), true)) {
				this.goSpread(this.spread - 1);
				return true;
			}
			if (this.arrowAt(event.x(), event.y(), false)) {
				this.goSpread(this.spread + 1);
				return true;
			}
			int tab = this.tabAt(event.x(), event.y());
			if (tab >= 0) {
				this.jumpTab(tab);
				return true;
			}
			ManualPager.Hot hot = this.hotAt(event.x(), event.y());
			if (hot != null) {
				this.activate(hot.action());
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private void jumpTab(int tab) {
		if (tab <= 0) {
			this.goSpread(0);
			return;
		}
		this.jumpPart(tab - 1);
	}

	/** 跳到某一部分的目录页（部分 = 该部分第一个物理页）。 */
	private void jumpPart(int part) {
		if (this.paged == null || part < 0 || part >= this.paged.partStart.length) {
			return;
		}
		if (this.paged.partStart[part] >= 0) {
			this.goSpread(this.paged.partStart[part] / 2);
		}
	}

	/** 执行一条跳转动作。 */
	private void activate(String action) {
		if (action == null) {
			return;
		}
		if (action.equals("tech")) {
			this.minecraft.gui.setScreen(new TechTreeScreen(this));
			return;
		}
		if (action.startsWith("species:")) {
			this.minecraft.gui.setScreen(new ReactionDetailScreen(this, action.substring("species:".length())));
			return;
		}
		if (action.startsWith("app:")) {
			// 书里自己印的跳转（一条反应 → 它涉及的物质页）：目标只给了页 id，
			// 「谁在第几页」要等整本书排完才知道，所以在这里现查。
			String target = action.substring("app:".length());
			this.jumpTo(this.book.indexOfId(target), target);
			return;
		}
		if (action.startsWith("part:")) {
			// 总目录的一行 -> 该部分自己的目录页（书签走的也是这条）。
			try {
				this.jumpPart(Integer.parseInt(action.substring("part:".length())));
			} catch (NumberFormatException e) {
				// 同上：脏 action 不许炸界面。
			}
			return;
		}
		if (action.startsWith("go:")) {
			String[] parts = action.split(":", 3);
			try {
				this.jumpTo(Integer.parseInt(parts[1]), parts.length > 2 ? parts[2] : null);
			} catch (NumberFormatException e) {
				// 锚点写错就当没点 —— 不许因为一个手滑的 action 把界面炸掉。
			}
		}
	}

	private void activate(ManualSearch.Result result) {
		ManualSearch.Entry entry = result.entry();
		if (entry.speciesId() != null) {
			this.minecraft.gui.setScreen(new ReactionDetailScreen(this, entry.speciesId()));
			return;
		}
		this.dismissed = true;
		this.jumpTo(entry.page(), entry.anchor());
	}

	/** 跳到逻辑页 {@code logical}（{@link ManualDoc.Book#flat} 的下标），并高亮 {@code anchor}。 */
	private void jumpTo(int logical, String anchor) {
		if (this.paged == null) {
			return;
		}
		int page;
		if (anchor != null && this.paged.anchors.containsKey(anchor)) {
			page = this.paged.pageOf(anchor, 0);
			this.flashY = this.paged.yOf(anchor);
		} else if (logical >= 0 && logical < this.paged.logicalStart.length
				&& this.paged.logicalStart[logical] >= 0) {
			page = this.paged.logicalStart[logical];
			this.flashY = 0;
		} else {
			page = 0;
			this.flashY = 0;
		}
		this.flashPage = page;
		this.flashUntil = System.currentTimeMillis() + FLASH_MS;
		this.cursorLogical = logical;
		this.spread = Math.clamp(page / 2, 0, this.maxSpread());
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY > 0) {
			if (this.spread > 0) {
				this.goSpread(this.spread - 1);
				return true;
			}
		} else if (scrollY < 0) {
			if (this.spread < this.maxSpread()) {
				this.goSpread(this.spread + 1);
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();

		// ESC：先清搜索词，搜索词空了才关界面。
		if (key == InputConstants.KEY_ESCAPE) {
			if (!this.query.isEmpty()) {
				this.searchBox.setValue("");
				this.onQueryChanged("");
				return true;
			}
			return super.keyPressed(event);
		}

		if (this.searchOpen()) {
			if (key == InputConstants.KEY_UP) {
				this.pick = Math.floorMod(this.pick - 1, Math.max(1, this.results.size()));
				return true;
			}
			if (key == InputConstants.KEY_DOWN) {
				this.pick = Math.floorMod(this.pick + 1, Math.max(1, this.results.size()));
				return true;
			}
			if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
				if (!this.results.isEmpty()) {
					this.activate(this.results.get(this.pick));
				}
				return true;
			}
		}

		// 敲字就聚焦输入框：焦点一进框，这一下按键之后的 charTyped 就落进框里。
		if (this.searchBox != null && !this.searchBox.isFocused() && isTypingKey(key)) {
			this.setInitialFocus(this.searchBox);
			return true;
		}

		// 翻页键：输入框聚焦时留给 EditBox 走光标。
		boolean boxFocused = this.searchBox != null && this.searchBox.isFocused();
		if (!boxFocused) {
			if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_PAGEUP) {
				if (this.spread > 0) {
					this.goSpread(this.spread - 1);
				}
				return true;
			}
			if (key == InputConstants.KEY_RIGHT || key == InputConstants.KEY_PAGEDOWN) {
				if (this.spread < this.maxSpread()) {
					this.goSpread(this.spread + 1);
				}
				return true;
			}
		}
		return super.keyPressed(event);
	}

	/**
	 * 这一下按键算不算「想打字」。
	 *
	 * <p>只收字母 / 数字 / 空格 / 常用标点（GLFW 的 32~96），控制键、方向键、功能键一律不算 ——
	 * 否则按一下 Tab 或 F1 就把焦点抢进搜索框。
	 */
	private static boolean isTypingKey(int key) {
		return (key >= 32 && key <= 96) || key == 160 || key == 161 || key == 162;
	}

	private void onQueryChanged(String value) {
		this.query = value == null ? "" : value;
		this.results = ManualSearch.query(this.index, this.query, 8);
		this.pick = 0;
		this.dismissed = false;
	}

	@Override
	public void onClose() {
		super.onClose();
	}
}
