"""为 geometallurgy 的冶金三炉 + 单一锭生成资源文件与数据文件。

纯 Python 写 PNG（zlib + struct），不依赖 Pillow。
"""
import json
import os
import struct
import zlib

# 资源根目录按脚本自身位置推导（tools/ 的上一级），不写死本机路径。
ROOT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "src", "main", "resources",
)
ASSETS = os.path.join(ROOT, "assets/geometallurgy")
DATA = os.path.join(ROOT, "data/geometallurgy")


def ensure(path):
    os.makedirs(os.path.dirname(path), exist_ok=True)


def write_json(path, obj):
    ensure(path)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(obj, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def write_png(path, pixels, w=16, h=16):
    ensure(path)
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            r, g, b, a = pixels[y][x]
            raw += bytes((r, g, b, a))

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))
    with open(path, "wb") as handle:
        handle.write(png)


# --------------------------------------------------------------------- 画布工具

class Canvas:
    def __init__(self, w=16, h=16, fill=(0, 0, 0, 0)):
        self.w = w
        self.h = h
        self.px = [[fill for _ in range(w)] for _ in range(h)]

    def set(self, x, y, color):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = color

    def rect(self, x0, y0, x1, y1, color):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, color)

    def frame(self, x0, y0, x1, y1, color):
        for x in range(x0, x1 + 1):
            self.set(x, y0, color)
            self.set(x, y1, color)
        for y in range(y0, y1 + 1):
            self.set(x0, y, color)
            self.set(x1, y, color)

    def disc(self, cx, cy, r, color):
        for y in range(self.h):
            for x in range(self.w):
                if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                    self.set(x, y, color)

    def line(self, x0, y0, x1, y1, color):
        steps = max(abs(x1 - x0), abs(y1 - y0), 1)
        for i in range(steps + 1):
            x = round(x0 + (x1 - x0) * i / steps)
            y = round(y0 + (y1 - y0) * i / steps)
            self.set(x, y, color)


def rgb(value, alpha=255):
    return ((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF, alpha)


def shade(color, factor):
    r, g, b, a = color
    return (min(255, int(r * factor)), min(255, int(g * factor)), min(255, int(b * factor)), a)


# --------------------------------------------------------------------- 方块贴图
#
# 三台炉子要一眼能分清：还原炉是砖砌炉膛（红），电解炉是两根电极插进熔盐（青白），
# 置换炉是带观察窗的湿法槽（铜绿）。三张都按「壳体 + 特征件」两层画，
# 点亮态只把「热的那一块」换成发光色，这样机器开没开在墙上一眼就看得见。

def furnace_texture(kind, lit):
    base = {
        "reduction": rgb(0x51515A),
        "electrolysis": rgb(0x3E4652),
        "displacement": rgb(0x46524B),
    }[kind]
    canvas = Canvas()
    canvas.rect(0, 0, 15, 15, base)
    # 统一的金属壳体：顶面提亮、底面压暗、两侧各一条铆钉线
    canvas.rect(0, 0, 15, 1, shade(base, 1.28))
    canvas.rect(0, 14, 15, 15, shade(base, 0.72))
    for x in (1, 14):
        for y in range(2, 14, 3):
            canvas.set(x, y, shade(base, 0.62))
    canvas.frame(0, 0, 15, 15, shade(base, 0.55))

    if kind == "reduction":
        # 耐火砖炉膛：下三分之二砌红砖，中间开一个炉口
        brick = rgb(0x8E3B32)
        canvas.rect(2, 6, 13, 13, brick)
        for y in range(6, 14, 2):
            canvas.line(2, y, 13, y, shade(brick, 0.7))
        for y in range(7, 14, 4):
            canvas.set(7, y, shade(brick, 0.7))
            canvas.set(8, y, shade(brick, 0.7))
        mouth = rgb(0x1A1414)
        canvas.rect(5, 8, 10, 12, mouth)
        canvas.frame(4, 7, 11, 12, shade(brick, 0.55))
        if lit:
            canvas.rect(5, 10, 10, 12, rgb(0xFF8A18))
            canvas.rect(6, 11, 9, 12, rgb(0xFFD26A))
            canvas.set(7, 12, rgb(0xFFF2C4))
            canvas.set(8, 12, rgb(0xFFF2C4))
        # 顶部一根烟囱
        canvas.rect(6, 2, 9, 5, shade(base, 0.8))
        canvas.rect(7, 3, 8, 4, rgb(0x201C1C))

    elif kind == "electrolysis":
        # 熔盐槽：底部一层熔体，两根电极插进去
        bath = rgb(0xC9C9D4) if not lit else rgb(0xBFF2EA)
        canvas.rect(2, 3, 13, 13, shade(base, 0.78))
        canvas.rect(2, 10, 13, 13, bath)
        canvas.rect(2, 10, 13, 10, shade(bath, 1.15) if lit else shade(bath, 1.1))
        for x in (5, 10):
            canvas.rect(x, 1, x + 1, 11, rgb(0xC8CFD4))
            canvas.set(x, 1, rgb(0xE8EEF2))
            canvas.set(x + 1, 11, shade(rgb(0xC8CFD4), 0.7))
        if lit:
            for x in (5, 10):
                canvas.set(x, 12, rgb(0xFFFFFF))
                canvas.set(x + 1, 12, rgb(0xFFFFFF))
            canvas.set(7, 12, rgb(0xEFFFFB))
            canvas.set(8, 12, rgb(0xEFFFFB))
        canvas.frame(1, 2, 14, 14, shade(base, 0.58))

    else:
        # 湿法槽：一个圆形观察窗 + 顶上的进液管
        canvas.rect(2, 3, 13, 13, shade(base, 0.8))
        canvas.disc(7, 8, 4, rgb(0x1C2420))
        canvas.disc(7, 8, 4, rgb(0x1C2420))
        window = rgb(0x3E7A6E) if not lit else rgb(0x6FD8C0)
        canvas.disc(7, 8, 3, window)
        canvas.disc(6, 7, 1, shade(window, 1.3))
        canvas.frame(2, 2, 13, 14, shade(base, 0.58))
        canvas.rect(6, 0, 9, 3, shade(base, 0.9))
        canvas.rect(7, 1, 8, 2, shade(window, 0.8))
    return canvas


for name in ("reduction", "electrolysis", "displacement"):
    for lit, suffix in ((False, ""), (True, "_on")):
        canvas = furnace_texture(name, lit)
        write_png(os.path.join(ASSETS, "textures/block", f"{name}_furnace{suffix}.png"), canvas.px)


# --------------------------------------------------------------------- 物品贴图
#
# 锭的形状照原版铁锭的轮廓来（斜投影的一块锭），这是唯一值得借的东西：自己拿矩形拼
# 梯形无论如何都画不出那个「摆着的一块金属」的感觉。
#
# 但原版图不能直接当染色底图 —— 它是一张连续渐变的图，均值偏暗，而 minecraft:constant
# 染色是<b>乘法</b>，拿它染色会得到「金属色 × 原版灰」，整体脏掉。所以这里把原版按亮度
# <b>量化成四档</b>，再换上自己的四档近白灰阶：顶面给到 0xFF（染出来就是这个金属的本色），
# 立面逐档压暗。形状是原版的，颜色是自己的。

INGOT_BANDS = (
    "................",
    "................",
    "..........11....",
    ".......111331...",
    "....1113333331..",
    ".11133333333331.",
    "1333333333333331",
    "1333333333333130",
    "1333333333211230",
    "1333333211113330",
    "123332111133200.",
    ".123321122000...",
    "..12321000......",
    "...1100.........",
    "................",
    "................",
)

# 档位 0（最深）～3（最亮）。3 必须是 0xFFFFFF，否则染色后达不到金属自己的色值。
BAND_GRAY = {"0": 0x8C8C8C, "1": 0xB4B4B4, "2": 0xE0E0E0, "3": 0xFFFFFF}


def band_shape(values, knock_out=(), mottle=()):
    canvas = Canvas()
    for y, row in enumerate(INGOT_BANDS):
        for x, ch in enumerate(row):
            if ch == "." or (x, y) in knock_out:
                continue
            canvas.set(x, y, rgb(values.get(ch, values["1"])))
    for x, y, color in mottle:
        canvas.set(x, y, rgb(color))
    return canvas


write_png(os.path.join(ASSETS, "textures/item/ingot.png"), band_shape(BAND_GRAY).px)

# 合金锭：同一副轮廓，但整体压暗一档（不纯的金属没有那种干净的顶面反光），
# 再敲掉几个边角像素、点几处杂色斑 —— 「好几种金属混在一起」看得见。
ALLOY_GRAY = {"0": 0x5E5E66, "1": 0x848489, "2": 0xA6A6A9, "3": 0xC2C2C4}
ALLOY_KNOCK_OUT = ((10, 2), (11, 2), (5, 13), (6, 13), (15, 6))
ALLOY_MOTTLE = (
    (3, 10, 0xB98A5A),   # 暖色夹渣：有点铜
    (9, 9, 0xB98A5A),
    (7, 9, 0x5E6670),    # 深色夹渣：有点铁
    (5, 11, 0x5E6670),
    (10, 11, 0xE8EEF4),  # 亮斑：某一种金属的晶面
    (4, 12, 0xE8EEF4),
    (12, 10, 0xB98A5A),
    (8, 11, 0x5E6670),
)
write_png(os.path.join(ASSETS, "textures/item/ingot_crude.png"),
          band_shape(ALLOY_GRAY, ALLOY_KNOCK_OUT, ALLOY_MOTTLE).px)


def powder_texture(color):
    canvas = Canvas()
    for (x, y) in [(4, 6), (5, 6), (6, 7), (7, 6), (8, 6), (9, 7), (10, 6),
                   (4, 8), (5, 9), (6, 9), (7, 10), (8, 9), (9, 9), (10, 10),
                   (3, 9), (11, 8), (7, 8), (9, 11), (6, 11), (5, 5), (10, 5)]:
        canvas.set(x, y, color)
        canvas.set(x, y + 1, shade(color, 0.78))
    for (x, y) in [(6, 6), (8, 8), (5, 10), (10, 9)]:
        canvas.set(x, y, shade(color, 1.25))
    return canvas


write_png(os.path.join(ASSETS, "textures/item/carbon_powder.png"),
          powder_texture(rgb(0x33333A)).px)


def rod_texture(color, cap):
    canvas = Canvas()
    canvas.rect(6, 3, 10, 13, color)
    for y in range(3, 14):
        canvas.set(6, y, shade(color, 0.78))
        canvas.set(7, y, shade(color, 1.18))
        canvas.set(9, y, shade(color, 0.88))
        canvas.set(10, y, shade(color, 0.68))
    canvas.rect(6, 2, 10, 3, cap)
    canvas.rect(6, 13, 10, 13, shade(color, 0.6))
    return canvas


write_png(os.path.join(ASSETS, "textures/item/carbon_rod.png"),
          rod_texture(rgb(0x3A3A42), rgb(0x5A5A64)).px)
write_png(os.path.join(ASSETS, "textures/item/tungsten_rod.png"),
          rod_texture(rgb(0x6E7378), rgb(0x9AA2AA)).px)
write_png(os.path.join(ASSETS, "textures/item/molybdenum_rod.png"),
          rod_texture(rgb(0x8A939C), rgb(0xB4BCC4)).px)


# 任务书：**形状照原版书**（`assets/minecraft/textures/item/book.png`），
# 只把配色换成自家的皮革与羊皮纸。原版那张图一共只有 10 种不透明色，干净地分成
# 皮革（饱和度 0.77~0.92）与纸口（饱和度 0）两组，所以直接做一张显式映射表即可，
# 不必搞量化或按亮度重排。
#
# 网格里的字符是原版调色板：B..G = 皮革（暗→亮），h..k = 纸口（暗→亮），. = 透明。
# 网格是用 Pillow 从原版 jar 里读出来之后**硬编码在这里**的 ——
# 生成脚本本身仍然不依赖原版 jar，也不依赖 Pillow。
MANUAL_GRID = (
    "................",
    "........CCC.....",
    "......CCEGFC....",
    "....CCEGGGGGC...",
    "..CCEGGGDGGGGC..",
    "CCEFGGGGGDGGGFC.",
    "CEFGGDGGGGDGGGDC",
    "CCEGGGDGGGGGDDh.",
    "CCjEGGGGGGDDiji.",
    "BEkjEFGGDDijjiCD",
    ".BEkjEDDijjiCDBB",
    "..BEkjijjiCDBB..",
    "...BEkjiDDBB....",
    "....BEDDBB......",
    ".....BBB........",
    "................",
)

# 与原版一一对应。皮革保持「暗→亮」的次序；纸口换成偏暖的羊皮纸，
# 与界面里那块内页（#E9DFC4）是同一套色。
MANUAL_COLORS = {
    ".": None,
    "B": 0x1B0F07, "C": 0x2A1809, "D": 0x3A2211,
    "E": 0x472917, "F": 0x4F3019, "G": 0x6B4522,
    "h": 0x8E7F5C, "i": 0xBFAF88, "j": 0xD5C7A2, "k": 0xEBDFC0,
}


def manual_texture():
    canvas = Canvas()
    for y, row in enumerate(MANUAL_GRID):
        for x, ch in enumerate(row):
            color = MANUAL_COLORS[ch]
            if color is not None:
                canvas.set(x, y, rgb(color))
    return canvas


write_png(os.path.join(ASSETS, "textures/item/field_manual.png"),
          manual_texture().px)


def configurator_texture():
    """工程配置器：一块可搬走的面配置面板。

    沿用电池那套灰蓝壳体色（一样的面板分层与四角紧固件），把特征件换成
    「铜色面盘 + 十字调节槽 + 底部状态灯」—— 铜色是模组里「可配置 / 接口」的既定信号色
    （无线感应器的天线帽也是这个色）。
    """
    canvas = Canvas()
    canvas.rect(0, 0, 15, 15, rgb(0x4A5158))
    canvas.frame(0, 0, 15, 15, rgb(0x353B41))
    canvas.rect(1, 1, 14, 14, rgb(0x565E66))
    canvas.rect(2, 2, 13, 13, rgb(0x505860))
    canvas.frame(2, 2, 13, 13, rgb(0x69737B))

    # 中央面盘：深色外环 → 铜面 → 上部高光
    canvas.disc(8, 6, 4, rgb(0x2A2F35))
    canvas.disc(8, 6, 3, rgb(0xB07C31))
    canvas.disc(8, 5, 2, rgb(0xD3A052))
    canvas.set(6, 5, rgb(0xE8C077))
    # 十字调节槽：一眼看出这是个「拧一下就能改」的东西
    canvas.rect(7, 4, 9, 8, rgb(0x2A2F35))
    canvas.rect(5, 6, 11, 6, rgb(0x2A2F35))

    # 底部状态灯：一格亮、一格灭
    canvas.rect(4, 11, 11, 12, rgb(0x3B4248))
    canvas.rect(5, 11, 7, 12, rgb(0xF2C94C))
    canvas.rect(9, 11, 10, 12, rgb(0x2A2F35))

    # 四角紧固件
    for x, y in ((2, 2), (12, 2), (2, 12), (12, 12)):
        canvas.set(x, y, rgb(0xA8B0B6))
    return canvas


write_png(os.path.join(ASSETS, "textures/item/engineering_configurator.png"),
          configurator_texture().px)


# --------------------------------------------------------------------- 枪械
#
# 步枪/枪管/撞针/枪机/弹头的贴图、模型与配方原本在这里生成。
# 2026-10-14 枪械整块搬到了附属模组 geometallurgy_guns，这些资源随之从本模组删掉 ——
# 生成脚本里对应的那一段也一并删了，免得跑一次脚本又把删掉的文件写回来。
# 枪那边的贴图在 geometallurgy_guns 自己的生成脚本里。

# --------------------------------------------------------------------- 机器顶面与底面
#
# 六面共用一张贴图时，从高处往下看就是一块平板 —— 看不出这是台机器。
# 所以给每台机器另配「顶」与「底」两张：朝上的一面画它的功能件
# （料斗、排气口、电极、驱动轴、磁极），朝下的一面画基座件（支脚、排料口、散热槽）。
#
# 四个侧面仍然只用一张：矿脉抽取机和流体挖掘机带 facing 旋转，
# 侧面若画成四张不同的，转个方向就露馅了。顶/底不受水平旋转影响，可以独立。

MACHINE_NAMES = (
    "vein_extractor",
    "coal_generator",
    "fluid_vein_extractor",
    "magnetic_separator",
    "crusher",
    "centrifuge",
    "reduction_furnace",
    "electrolysis_furnace",
    "displacement_furnace",
)

# 底色取自各自现有侧面贴图的主体色，明度对齐 —— 顶/底拼上去不能看出接缝。
MACHINE_BASE = {
    "vein_extractor": 0x70767F,
    "coal_generator": 0x60646C,
    "fluid_vein_extractor": 0x6A7078,
    "magnetic_separator": 0x3B424C,
    "crusher": 0x4A4A52,
    "centrifuge": 0x3F4E5A,
    "reduction_furnace": 0x51515A,
    "electrolysis_furnace": 0x3E4652,
    "displacement_furnace": 0x46524B,
}

BOLT = rgb(0xA8B0B6)
DARK = rgb(0x1A1D21)


def machine_case(base):
    """顶面/底面共用的金属壳体：外描边、亮面板、四角紧固件。"""
    canvas = Canvas()
    canvas.rect(0, 0, 15, 15, base)
    canvas.frame(0, 0, 15, 15, shade(base, 0.55))
    canvas.rect(1, 1, 14, 14, shade(base, 1.10))
    canvas.frame(2, 2, 13, 13, shade(base, 0.74))
    for x, y in ((3, 3), (12, 3), (3, 12), (12, 12)):
        canvas.set(x, y, BOLT)
    return canvas


def machine_side_texture(kind, lit):
    """侧面：与顶/底同一套语法 —— 外描边、顶部受光条、底部压暗条、中间一块功能面板。

    <p>这几台的侧面原本是更早手绘的，矿脉抽取机整面就是几条横杠、离心机中间是个
    菱形花斑，跟后来那套「壳体 + 特征件」根本不是一个语言。这里按同一套重画。
    """
    base = rgb(MACHINE_BASE[kind])
    canvas = Canvas()
    canvas.rect(0, 0, 15, 15, base)
    canvas.frame(0, 0, 15, 15, shade(base, 0.50))
    canvas.rect(1, 1, 14, 2, shade(base, 1.20))     # 顶部受光
    canvas.rect(1, 3, 14, 12, shade(base, 1.00))    # 主面板
    canvas.rect(1, 13, 14, 14, shade(base, 0.76))   # 底部压暗

    if kind == "vein_extractor":
        # 上排：铭牌压条 + 两盏状态灯；中间：井口观察窗，里面是钻杆；下排：传动箱。
        canvas.rect(2, 3, 13, 3, shade(base, 0.60))
        canvas.set(3, 4, rgb(0xF2C94C) if lit else rgb(0x6E6650))
        canvas.set(4, 4, rgb(0xF2C94C) if lit else rgb(0x6E6650))
        canvas.set(6, 4, rgb(0x7FD86A) if lit else rgb(0x566151))
        canvas.rect(9, 4, 12, 4, shade(base, 0.84))
        canvas.frame(3, 6, 12, 11, shade(base, 0.56))
        # 井口：开机后整面透出钻进的青白光，熄灭时只是一块暗玻璃。
        canvas.rect(4, 7, 11, 10, rgb(0x2F5C66) if lit else rgb(0x24292F))
        canvas.rect(7, 7, 8, 9, rgb(0xEFF6FA) if lit else rgb(0xC8CFD4))   # 钻杆
        canvas.rect(6, 10, 9, 10, rgb(0xD0D8DE) if lit else rgb(0x9AA0A8))  # 钻头
        canvas.set(5, 8, rgb(0x9FE8F6) if lit else rgb(0x3A444C))
        canvas.set(10, 9, rgb(0x9FE8F6) if lit else rgb(0x3A444C))
        canvas.set(7, 11, rgb(0xD0D8DE) if lit else rgb(0x9AA0A8))
        canvas.set(8, 11, rgb(0xD0D8DE) if lit else rgb(0x9AA0A8))
        canvas.set(2, 13, BOLT)
        canvas.set(13, 13, BOLT)

    elif kind == "crusher":
        # 两个破碎辊对着转：上面进料斗，中间带齿的一对辊，下面出料口。
        # 原先侧面上是一块 3×3 的齿点阵，看不出「辊」这件事。
        canvas.rect(2, 3, 13, 4, shade(base, 0.60))
        canvas.rect(4, 4, 11, 4, rgb(0x1A1D21))
        for x0 in (2, 9):
            canvas.rect(x0, 6, x0 + 4, 8, rgb(0x8A9096))
            canvas.rect(x0, 6, x0 + 4, 6, rgb(0xB0B6BC))
            canvas.rect(x0, 8, x0 + 4, 8, rgb(0x646A70))
            canvas.set(x0 + 2, 7, rgb(0x53595F))
            for x in range(x0, x0 + 5, 2):
                canvas.set(x, 5, rgb(0xB0B6BC))
                canvas.set(x, 9, rgb(0xB0B6BC))
        canvas.rect(5, 10, 10, 12, rgb(0x1A1D21))
        canvas.rect(6, 11, 9, 12, rgb(0x101215))
        canvas.set(2, 13, BOLT)
        canvas.set(13, 13, BOLT)

    elif kind == "centrifuge":
        # 中部一台转鼓：内圈鼓腔，四片叶片从中心甩开；开机后整台鼓腔泛亮。
        canvas.rect(2, 3, 13, 3, shade(base, 0.64))
        canvas.frame(2, 4, 13, 12, shade(base, 0.58))
        canvas.rect(3, 5, 12, 11, rgb(0x2A4650) if lit else rgb(0x222A31))
        blade = rgb(0xD8F0FA) if lit else rgb(0x8FB8C8)
        for dx, dy in ((1, 0), (0, 1)):
            canvas.line(8, 8, 8 + dx * 4, 8 + dy * 4, blade)
            canvas.line(8, 8, 8 - dx * 4, 8 - dy * 4, blade)
        canvas.rect(7, 7, 8, 8, rgb(0xE8FAFF) if lit else rgb(0x6E8794))
        canvas.set(2, 3, BOLT)
        canvas.set(13, 3, BOLT)
        canvas.set(2, 12, BOLT)
        canvas.set(13, 12, BOLT)

    elif kind == "electrolysis_furnace":
        # 侧面：一座电解槽的剖视，整幅由**横向分层**构成 ——
        # 顶梁 / 槽沿 / 液面 / 液下 / 积渣 / 槽底 / 底座，一共七条横线。
        #
        # 前两版都像背带裤：两根电极从顶贯到底、下面压一大块熔盐，就是
        # 「两条带子 + 一块布」。关键在于**竖线不能贯穿**。这版电极只保留
        # 顶部两截短接头（2 像素），下面立刻被槽沿和液面横切，竖线就断掉了。
        canvas.rect(0, 0, 15, 15, shade(base, 0.74))
        canvas.frame(0, 0, 15, 15, shade(base, 0.50))
        # 顶梁（横向母线）
        canvas.rect(2, 1, 13, 2, rgb(0xB0763A))
        canvas.rect(2, 1, 13, 1, rgb(0xD89A56))
        canvas.rect(2, 2, 13, 2, rgb(0x8A5C28))
        for x in (3, 8, 12):
            canvas.set(x, 2, rgb(0x6E4620))
        # 两个短接头：只占第 3 行，位置靠外（不是居中两根）
        for x in (4, 11):
            canvas.rect(x, 3, x, 3, rgb(0xC8CFD4))
            canvas.set(x, 3, rgb(0xE8EEF2))
        # 槽沿（横向，压住接头）
        canvas.rect(1, 4, 14, 5, shade(base, 1.18))
        canvas.rect(1, 5, 14, 5, shade(base, 0.60))
        # 液面上方的空腔
        canvas.rect(2, 6, 13, 6, shade(base, 0.66))
        # 熔盐：液面高光 → 液下 → 积渣
        bath = rgb(0xBFF2EA) if lit else rgb(0x9FBDC6)
        canvas.rect(2, 7, 13, 11, bath)
        canvas.rect(2, 7, 13, 7, shade(bath, 1.24))
        canvas.rect(2, 11, 13, 11, shade(bath, 0.70))
        # 槽底与底座
        canvas.rect(1, 12, 14, 12, shade(base, 0.56))
        canvas.rect(1, 13, 14, 14, shade(base, 0.90))
        canvas.rect(0, 15, 15, 15, shade(base, 0.46))
        # 排液阀偏在一侧，破掉左右对称
        canvas.rect(3, 13, 4, 14, rgb(0xB0763A))
        canvas.set(3, 13, rgb(0xD89A56))
        canvas.set(12, 13, BOLT)
        if lit:
            for x, y in ((4, 9), (7, 8), (10, 10), (12, 9), (6, 10)):
                canvas.set(x, y, rgb(0xEFFFFB))
        else:
            for x, y in ((4, 9), (7, 10), (11, 9)):
                canvas.set(x, y, shade(bath, 0.86))

    else:
        # 湿法槽：中部一个方形观察窗，能看到里面铜绿色的浸出液。
        canvas.frame(3, 3, 12, 12, shade(base, 0.58))
        canvas.rect(4, 4, 11, 11, rgb(0x1C2420))
        window = rgb(0x6FD8C0) if lit else rgb(0x5FA594)
        canvas.rect(4, 6, 11, 11, window)
        canvas.rect(4, 6, 11, 6, shade(window, 1.20))
        canvas.rect(6, 0, 9, 3, shade(base, 0.92))
        canvas.rect(7, 0, 8, 2, rgb(0x2A2F35))
        for x, y in ((4, 4), (11, 4), (4, 11), (11, 11)):
            canvas.set(x, y, BOLT)
    return canvas


def machine_top_texture(kind):
    base = rgb(MACHINE_BASE[kind])
    canvas = machine_case(base)

    if kind == "vein_extractor":
        # 井口：方形法兰 + 十字加强筋，正中是朝下的钻杆接口。
        # （原先用同心圆画，16×16 下圆边全是锯齿，看着像一块饼。）
        canvas.frame(3, 3, 12, 12, shade(base, 0.62))
        canvas.rect(4, 4, 11, 11, shade(base, 1.14))
        canvas.frame(5, 5, 10, 10, rgb(0x2A6F80))
        canvas.rect(6, 6, 9, 9, rgb(0x1C4A57))
        canvas.rect(7, 7, 8, 8, rgb(0x6FD0E4))
        canvas.rect(4, 7, 4, 8, shade(base, 0.70))
        canvas.rect(11, 7, 11, 8, shade(base, 0.70))
        canvas.rect(7, 4, 8, 4, shade(base, 0.70))
        canvas.rect(7, 11, 8, 11, shade(base, 0.70))
        for x, y in ((5, 5), (10, 5), (5, 10), (10, 10)):
            canvas.set(x, y, BOLT)

    elif kind == "coal_generator":
        # 顶部排气：一个黑烟口，两侧压出散热鳍
        for y in (5, 7, 9, 11):
            canvas.rect(4, y, 4, y, shade(base, 0.68))
            canvas.rect(11, y, 11, y, shade(base, 0.68))
        canvas.frame(5, 4, 10, 11, shade(base, 0.60))
        canvas.rect(6, 5, 9, 10, DARK)
        canvas.rect(7, 6, 8, 9, rgb(0x33261C))

    elif kind == "fluid_vein_extractor":
        # 泵接口：方形法兰 + 黄铜接头，四角各引一根浆管
        canvas.frame(4, 4, 11, 11, shade(base, 0.62))
        canvas.rect(5, 5, 10, 10, shade(base, 1.18))
        canvas.rect(6, 6, 9, 9, rgb(0xC8B45A))
        canvas.rect(7, 7, 8, 8, rgb(0x8A6A2A))
        for x, y in ((6, 3), (9, 3), (6, 12), (9, 12)):
            canvas.set(x, y, rgb(0x2A5F8A))

    elif kind == "magnetic_separator":
        # 一对磁极：左红右蓝，各带三道白条纹；中间是铜线圈。
        # （原先只是两块纯色，俯视看就是两个色块，认不出是磁铁。）
        canvas.rect(2, 4, 6, 11, rgb(0x9E4238))
        canvas.rect(2, 4, 6, 4, rgb(0xC2554A))
        canvas.rect(2, 11, 6, 11, rgb(0x632923))
        canvas.rect(9, 4, 13, 11, rgb(0x3F5F9F))
        canvas.rect(9, 4, 13, 4, rgb(0x5678BE))
        canvas.rect(9, 11, 13, 11, rgb(0x2A3F6E))
        for y in (6, 8, 10):
            canvas.rect(4, y, 6, y, rgb(0xE8E2DA))
            canvas.rect(9, y, 11, y, rgb(0xE8E2DA))
        canvas.rect(7, 4, 8, 11, shade(base, 0.66))
        for y in (5, 7, 9, 11):
            canvas.rect(7, y, 8, y, rgb(0xB0763A))

    elif kind == "crusher":
        # 进料斗：一层层收下去的回字，越往里越暗，四边各嵌两枚破碎齿。
        canvas.frame(3, 3, 12, 12, shade(base, 0.62))
        canvas.frame(4, 4, 11, 11, shade(base, 0.88))
        canvas.frame(5, 5, 10, 10, shade(base, 0.70))
        canvas.rect(6, 6, 9, 9, shade(base, 0.56))
        canvas.rect(7, 7, 8, 8, DARK)
        for x in (6, 9):
            canvas.set(x, 5, rgb(0x9AA0A8))
            canvas.set(x, 10, rgb(0x9AA0A8))
        for y in (6, 9):
            canvas.set(5, y, rgb(0x9AA0A8))
            canvas.set(10, y, rgb(0x9AA0A8))

    elif kind == "centrifuge":
        # 驱动端：十字轮的轮毂，四根辐条从中心连到壳体上。
        # （同心圆那版在 16×16 下边缘一圈锯齿，改成十字轮毂后干净了。）
        canvas.frame(4, 4, 11, 11, shade(base, 0.64))
        canvas.rect(5, 5, 10, 10, shade(base, 1.12))
        canvas.rect(5, 7, 10, 8, shade(base, 0.78))
        canvas.rect(7, 5, 8, 10, shade(base, 0.78))
        canvas.rect(7, 7, 8, 8, rgb(0x8FB8C8))
        canvas.set(7, 7, rgb(0xC8E6F2))
        canvas.set(8, 8, rgb(0xC8E6F2))
        canvas.set(4, 4, BOLT)
        canvas.set(11, 11, BOLT)

    elif kind == "reduction_furnace":
        # 炉顶：耐火砖围着一圈，中间是烟囱口
        canvas.frame(3, 3, 12, 12, rgb(0x8E3B32))
        canvas.frame(4, 4, 11, 11, rgb(0x632923))
        canvas.rect(5, 5, 10, 10, rgb(0x201C1C))
        canvas.rect(6, 6, 9, 9, rgb(0x121010))
        canvas.set(5, 5, rgb(0xA8503F))
        canvas.set(10, 5, rgb(0xA8503F))

    elif kind == "electrolysis_furnace":
        # 俯视：一个方炉口，口里看得见两根电极的**截面**和熔盐液面。
        # 上一版画的是两根纵贯整面的长条 —— 那是侧视的画法；从上往下看，
        # 电极只该是两个小点。
        canvas.frame(3, 3, 12, 12, shade(base, 0.56))
        canvas.rect(4, 4, 11, 11, shade(base, 0.92))
        canvas.rect(4, 4, 11, 4, shade(base, 1.14))
        bath = rgb(0xBFF2EA) if lit else rgb(0x9FBDC6)
        canvas.rect(5, 5, 10, 10, bath)
        canvas.rect(5, 5, 10, 5, shade(bath, 1.16))
        canvas.rect(6, 6, 6, 7, rgb(0xC8CFD4))
        canvas.rect(9, 8, 9, 9, rgb(0xC8CFD4))
        canvas.set(6, 6, rgb(0xE8EEF2))
        canvas.set(9, 8, rgb(0xE8EEF2))
        for x, y in ((4, 4), (11, 4), (4, 11), (11, 11)):
            canvas.set(x, y, BOLT)

    else:
        # 置换炉炉顶：一个方形投料口 + 一根排气短管。
        canvas.frame(4, 5, 11, 12, shade(base, 0.62))
        canvas.rect(5, 6, 10, 11, rgb(0x1C2420))
        canvas.rect(6, 7, 9, 10, rgb(0x3E7A6E))
        canvas.rect(6, 7, 9, 7, rgb(0x6FD8C0))
        canvas.rect(6, 2, 9, 4, shade(base, 0.90))
        canvas.rect(7, 2, 8, 3, rgb(0x2A2F35))
    return canvas


def machine_bottom_texture(kind):
    base = rgb(MACHINE_BASE[kind])
    canvas = Canvas()
    # 「底面照不到光」那件事游戏的光照引擎已经在做了 —— 贴图里再压一档就黑成一团，
    # 深色机器尤其糊。所以这里只比顶面暗一成，明度交给渲染去分。
    canvas.rect(0, 0, 15, 15, shade(base, 0.88))
    canvas.frame(0, 0, 15, 15, shade(base, 0.58))
    canvas.rect(2, 2, 13, 13, shade(base, 0.78))
    canvas.frame(2, 2, 13, 13, shade(base, 0.95))
    # 四个支脚：机器是「站」在地上的，不是平贴在地面。
    for x0, y0 in ((2, 2), (11, 2), (2, 11), (11, 11)):
        canvas.rect(x0, y0, x0 + 2, y0 + 2, shade(base, 1.12))
        canvas.frame(x0, y0, x0 + 2, y0 + 2, shade(base, 0.66))
        canvas.set(x0 + 1, y0 + 1, rgb(0x7C848C))

    if kind in ("vein_extractor", "crusher"):
        # 正下方的排料口
        canvas.rect(6, 6, 9, 9, DARK)
        canvas.rect(7, 7, 8, 8, rgb(0x101215))
        for x, y in ((5, 5), (10, 5), (5, 10), (10, 10)):
            canvas.set(x, y, BOLT)

    elif kind == "coal_generator":
        # 除灰格栅
        for y in (6, 8, 10):
            canvas.rect(5, y, 10, y, DARK)
        canvas.frame(4, 5, 11, 11, shade(base, 0.70))
        for x, y in ((5, 5), (10, 5), (5, 11), (10, 11)):
            canvas.set(x, y, BOLT)

    elif kind in ("fluid_vein_extractor", "displacement_furnace"):
        # 排浆/排液法兰：方口 + 四角螺栓，中心残留液色对应各自工艺
        residue = rgb(0x2A5F8A) if kind == "fluid_vein_extractor" else rgb(0x3E7A6E)
        canvas.frame(4, 4, 11, 11, shade(base, 0.60))
        canvas.rect(5, 5, 10, 10, residue)
        canvas.rect(6, 6, 9, 9, shade(residue, 0.62))
        for x, y in ((5, 5), (10, 5), (5, 10), (10, 10)):
            canvas.set(x, y, BOLT)

    elif kind == "magnetic_separator":
        # 三条尾矿排出槽
        for x in (5, 7, 9):
            canvas.rect(x, 5, x, 10, DARK)
        canvas.frame(4, 4, 11, 11, shade(base, 0.70))

    elif kind == "centrifuge":
        # 底座法兰 + 中央排液方口
        canvas.frame(4, 4, 11, 11, shade(base, 0.72))
        canvas.rect(6, 6, 9, 9, shade(base, 0.50))
        canvas.rect(7, 7, 8, 8, DARK)
        for x, y in ((4, 4), (11, 4), (4, 11), (11, 11)):
            canvas.set(x, y, BOLT)

    elif kind == "reduction_furnace":
        # 出渣口：炉基上开一个暗红的口，残渣还没冷
        canvas.rect(5, 5, 10, 10, rgb(0x2A1A16))
        canvas.rect(6, 6, 9, 9, rgb(0x632923))
        canvas.rect(7, 8, 8, 9, rgb(0x8E3B32))

    else:
        # 电解炉炉底：中央放液口，口沿结着一圈熔盐壳。
        canvas.frame(5, 5, 10, 10, shade(base, 0.86))
        canvas.rect(6, 6, 9, 9, rgb(0x7E8890))
        canvas.rect(7, 7, 8, 8, rgb(0xC9C9D4))
        canvas.set(7, 7, rgb(0xE8EEF2))
    return canvas


for _name in MACHINE_NAMES:
    write_png(os.path.join(ASSETS, "textures/block", f"{_name}_top.png"),
              machine_top_texture(_name).px)
    write_png(os.path.join(ASSETS, "textures/block", f"{_name}_bottom.png"),
              machine_bottom_texture(_name).px)

# 这四台的侧面一并重画（含点亮态）：煤/流体那几台的侧图本来就还行，
# 这四台是早期手绘的横杠与花斑，和现在的语法对不上。
MACHINE_SIDE_REDRAWN = (
    "vein_extractor", "centrifuge", "crusher",
    "electrolysis_furnace", "displacement_furnace",
)
for _name in MACHINE_SIDE_REDRAWN:
    for _lit, _suffix in ((False, ""), (True, "_on")):
        write_png(os.path.join(ASSETS, "textures/block", f"{_name}{_suffix}.png"),
                  machine_side_texture(_name, _lit).px)


# --------------------------------------------------------------------- 方块模型与状态

for name in ("reduction_furnace", "electrolysis_furnace", "displacement_furnace"):
    write_json(os.path.join(ASSETS, "blockstates", f"{name}.json"), {
        "variants": {
            "lit=false": {"model": f"geometallurgy:block/{name}"},
            "lit=true": {"model": f"geometallurgy:block/{name}_on"},
        }
    })


# 九台机器的立方体模型：四个侧面共用一张，顶面与底面各自独立。
# 熄火态用 `{name}` / `{name}_on` 作侧面，顶/底不随开关变化 ——
# 顶上的料斗口和底下的支脚是结构件，不是工作状态。
for name in MACHINE_NAMES:
    for suffix in ("", "_on"):
        side = f"geometallurgy:block/{name}{suffix}"
        write_json(os.path.join(ASSETS, "models/block", f"{name}{suffix}.json"), {
            "parent": "minecraft:block/cube",
            "textures": {
                "up": f"geometallurgy:block/{name}_top",
                "down": f"geometallurgy:block/{name}_bottom",
                "north": side,
                "south": side,
                "east": side,
                "west": side,
                "particle": side,
            },
        })
    write_json(os.path.join(ASSETS, "models/item", f"{name}.json"),
               {"parent": f"geometallurgy:block/{name}"})
    write_json(os.path.join(ASSETS, "items", f"{name}.json"),
               {"model": {"type": "minecraft:model", "model": f"geometallurgy:item/{name}"}})



# --------------------------------------------------------------------- 单一锭的模型
#
# 这里是「一个物品 ID、按成分换贴图」的客户端那一半：
# minecraft:select 按 minecraft:component 属性读 geometallurgy:ingot_metal 这个字符串组件，
# 每个取值走一个 case；case 里引用同一张灰阶底图，用 minecraft:constant 染色成那种金属的颜色。
# 主金属不够纯时组件值是 alloy，落到 fallback —— 换成另一张「粗锭」贴图（未染色），
# 所以「贴图随成分变」在这一层是字面意义上的换贴图，而不是只换个色调。

METAL_COLORS = {
    "potassium": 0xB9A6C9, "calcium": 0xD6D2C4, "sodium": 0xC9C9D4,
    "magnesium": 0xC7CCC9, "aluminum": 0xC4CBD2, "manganese": 0x9E8FA8,
    "zinc": 0x9FB0BC, "chromium": 0xB6C2CC, "iron": 0xD8D8D8,
    "cobalt": 0x6E8FD0, "nickel": 0xC8CFC4, "tin": 0xC6C8CC,
    "lead": 0x8C8C99, "tungsten": 0x6E7378, "molybdenum": 0x8A939C,
    "copper": 0xE07A46, "bismuth": 0xC9BCCF, "silver": 0xE3E8EF,
    "gold": 0xF2C94C, "mercury": 0xB9C3CC,
    # 非金属路线也会产出「锭」（石英还原出工业硅、重晶石电解出钡、熔盐电解出混合稀土），
    # 漏在表外的话它们会落到 fallback 上，看起来跟合金锭一样 —— 得给它们各自的颜色。
    "silicon": 0x9FA6AE, "barium": 0xD6C7A8, "rare_earth": 0xC0A8C8,
    # 铁族分级：碳热还原掺碳之后，同一块铁按含碳量与纯度分四级命名（见 Ingots），
    # 主金属键分别是 wrought_iron / pig_iron / steel / alloy_steel —— 每级一个染色。
    "wrought_iron": 0xE8E9EC,   # 锻铁：几乎无碳，顶面接近纯白
    "steel": 0xAEB4BC,          # 钢：含碳，比铁偏冷偏深
    "alloy_steel": 0x8E979E,    # 合金钢：再深一档，掺了别的金属
}

# 生铁不用标准锭模型：它用粗锭那张（带缺角与杂斑）染个暗褐灰 —— 「这锭不对劲」一眼可见。
CRUDE_CASES = {"pig_iron": 0x8A8078}


def signed(argb):
    """把 0xRRGGBB 变成 minecraft:constant 那种带 alpha 的有符号 int。"""
    value = 0xFF000000 | argb
    return value - 0x100000000 if value >= 0x80000000 else value


ingot_cases = [
    {
        "when": metal,
        "model": {
            "type": "minecraft:model",
            "model": "geometallurgy:item/ingot",
            "tints": [{"type": "minecraft:constant", "value": signed(color)}],
        },
    }
    for metal, color in METAL_COLORS.items()
] + [
    {
        "when": metal,
        "model": {
            "type": "minecraft:model",
            "model": "geometallurgy:item/ingot_crude",
            "tints": [{"type": "minecraft:constant", "value": signed(color)}],
        },
    }
    for metal, color in CRUDE_CASES.items()
]

write_json(os.path.join(ASSETS, "items/ingot.json"), {
    "model": {
        "type": "minecraft:select",
        "property": "minecraft:component",
        "component": "geometallurgy:ingot_metal",
        "cases": ingot_cases,
        "fallback": {
            "type": "minecraft:model",
            "model": "geometallurgy:item/ingot_crude",
        },
    }
})

write_json(os.path.join(ASSETS, "models/item/ingot.json"),
           {"parent": "minecraft:item/generated",
            "textures": {"layer0": "geometallurgy:item/ingot"}})
write_json(os.path.join(ASSETS, "models/item/ingot_crude.json"),
           {"parent": "minecraft:item/generated",
            "textures": {"layer0": "geometallurgy:item/ingot_crude"}})

for name in ("carbon_powder", "carbon_rod", "tungsten_rod", "molybdenum_rod",
             "field_manual", "engineering_configurator"):
    write_json(os.path.join(ASSETS, "models/item", f"{name}.json"),
               {"parent": "minecraft:item/generated",
                "textures": {"layer0": f"geometallurgy:item/{name}"}})
    write_json(os.path.join(ASSETS, "items", f"{name}.json"),
               {"model": {"type": "minecraft:model", "model": f"geometallurgy:item/{name}"}})


# --------------------------------------------------------------------- 蓄电池与无线感应器

def battery_side_texture():
    canvas = Canvas()
    # 保留原灰蓝底色，改成分层金属面板而不是单色方块。
    canvas.rect(0, 0, 15, 15, rgb(0x4A5158))
    canvas.frame(0, 0, 15, 15, rgb(0x353B41))
    canvas.rect(1, 1, 14, 14, rgb(0x565E66))
    canvas.rect(2, 2, 13, 13, rgb(0x505860))
    canvas.frame(2, 2, 13, 13, rgb(0x69737B))
    # 上下加强筋和左右暗缝，强调这是一个电池壳体。
    canvas.rect(3, 3, 12, 3, rgb(0x626C74))
    canvas.rect(3, 11, 12, 11, rgb(0x41484F))
    canvas.rect(3, 4, 3, 10, rgb(0x606A72))
    canvas.rect(12, 4, 12, 10, rgb(0x41484F))
    # 中央电量窗：三格电量、外框与底部状态灯。
    canvas.frame(4, 4, 11, 10, rgb(0x2A2F35))
    canvas.rect(5, 5, 10, 9, rgb(0x3B4248))
    for x, lit in ((6, True), (8, True), (10, False)):
        color = rgb(0xF2C94C) if lit else rgb(0x2A2F35)
        canvas.rect(x, 6, x + 1, 8, color)
    canvas.rect(5, 12, 10, 12, rgb(0xB04A3A))
    # 四角紧固件。
    for x, y in ((2, 2), (12, 2), (2, 12), (12, 12)):
        canvas.set(x, y, rgb(0xA8B0B6))
    return canvas


def battery_top_texture():
    canvas = Canvas()
    canvas.rect(0, 0, 15, 15, rgb(0x565E66))
    canvas.frame(0, 0, 15, 15, rgb(0x3A4046))
    canvas.rect(1, 1, 14, 14, rgb(0x505860))
    canvas.frame(1, 1, 14, 14, rgb(0x69737B))
    # 顶部维护盖板和四角紧固件。
    canvas.frame(2, 7, 13, 13, rgb(0x3A4046))
    canvas.rect(3, 8, 12, 12, rgb(0x4A5158))
    canvas.rect(4, 9, 11, 9, rgb(0x69737B))
    # 两个接线端子：红色输出、黑色输入，底色仍沿用原设计。
    canvas.rect(3, 3, 5, 5, rgb(0xB04A3A))
    canvas.rect(10, 3, 12, 5, rgb(0x23262B))
    canvas.frame(2, 2, 6, 6, rgb(0x2A2F35))
    canvas.frame(9, 2, 13, 6, rgb(0x2A2F35))
    canvas.set(4, 4, rgb(0xF07A5E))
    canvas.set(11, 4, rgb(0x7C8790))
    return canvas


def battery_bottom_texture():
    canvas = Canvas()
    canvas.rect(0, 0, 15, 15, rgb(0x3A4046))
    canvas.frame(0, 0, 15, 15, rgb(0x2A2F35))
    canvas.rect(2, 2, 13, 13, rgb(0x333A40))
    canvas.frame(2, 2, 13, 13, rgb(0x505860))
    # 底部散热槽，保持深灰底色并增加可辨识结构。
    for y in (4, 6, 8, 10):
        canvas.rect(4, y, 11, y, rgb(0x20252A))
    for x, y in ((2, 2), (12, 2), (2, 12), (12, 12)):
        canvas.set(x, y, rgb(0x7A858C))
    return canvas


def sensor_texture():
    # 三个部件共用一张图：顶上 0~4 行是天线帽（铜色），5~9 是杆（深灰），
    # 10~15 是底座（暗色机壳）。模型里按 UV 区间各取所需。
    canvas = Canvas()
    canvas.rect(0, 0, 15, 4, rgb(0xE0A060))
    canvas.frame(0, 0, 15, 4, rgb(0x9B5D35))
    canvas.rect(2, 1, 3, 2, rgb(0xF6CB8E))
    canvas.rect(5, 1, 10, 1, rgb(0xC87C43))
    canvas.rect(0, 5, 15, 9, rgb(0x50565C))
    canvas.frame(0, 5, 15, 9, rgb(0x343A40))
    for y in range(6, 9):
        canvas.set(3, y, rgb(0x767E86))
        canvas.set(11, y, rgb(0x3A4148))
    canvas.rect(0, 10, 15, 15, rgb(0x2E3238))
    canvas.frame(0, 10, 15, 15, rgb(0x1C2024))
    canvas.rect(1, 11, 14, 11, rgb(0x454D54))
    canvas.rect(2, 12, 13, 12, rgb(0xF2C94C))
    canvas.rect(4, 13, 11, 13, rgb(0x252A2F))
    canvas.rect(4, 14, 11, 14, rgb(0x454D54))
    return canvas


write_png(os.path.join(ASSETS, "textures/block/battery_side.png"), battery_side_texture().px)
write_png(os.path.join(ASSETS, "textures/block/battery_top.png"), battery_top_texture().px)
write_png(os.path.join(ASSETS, "textures/block/battery_bottom.png"), battery_bottom_texture().px)
write_png(os.path.join(ASSETS, "textures/block/wireless_sensor.png"), sensor_texture().px)

write_json(os.path.join(ASSETS, "blockstates/battery.json"), {
    "variants": {"": {"model": "geometallurgy:block/battery"}},
})
write_json(os.path.join(ASSETS, "models/block/battery.json"), {
    "parent": "minecraft:block/cube_bottom_top",
    "textures": {
        "top": "geometallurgy:block/battery_top",
        "bottom": "geometallurgy:block/battery_bottom",
        "side": "geometallurgy:block/battery_side",
    },
})
write_json(os.path.join(ASSETS, "models/item/battery.json"),
           {"parent": "geometallurgy:block/battery"})
write_json(os.path.join(ASSETS, "items/battery.json"),
           {"model": {"type": "minecraft:model", "model": "geometallurgy:item/battery"}})

# 感应器是异形件：底座一片 + 天线杆 + 铜帽，自绘元素模型而不是整块立方体。
write_json(os.path.join(ASSETS, "blockstates/wireless_sensor.json"), {
    "variants": {"": {"model": "geometallurgy:block/wireless_sensor"}},
})
write_json(os.path.join(ASSETS, "models/block/wireless_sensor.json"), {
    "textures": {
        "0": "geometallurgy:block/wireless_sensor",
        "particle": "geometallurgy:block/wireless_sensor",
    },
    "elements": [
        # 底座：贴在宿主机顶面的一片
        {"from": [3, 0, 3], "to": [13, 3, 13],
         "faces": {
             "down": {"uv": [3, 13, 13, 16], "texture": "#0"},
             "up": {"uv": [3, 10, 13, 13], "texture": "#0"},
             "north": {"uv": [3, 13, 13, 16], "texture": "#0"},
             "south": {"uv": [3, 13, 13, 16], "texture": "#0"},
             "west": {"uv": [3, 13, 13, 16], "texture": "#0"},
             "east": {"uv": [3, 13, 13, 16], "texture": "#0"},
         }},
        # 天线杆
        {"from": [6.5, 3, 6.5], "to": [9.5, 12, 9.5],
         "faces": {
             "up": {"uv": [6, 5, 10, 9], "texture": "#0"},
             "north": {"uv": [0, 5, 3, 10], "texture": "#0"},
             "south": {"uv": [0, 5, 3, 10], "texture": "#0"},
             "west": {"uv": [0, 5, 3, 10], "texture": "#0"},
             "east": {"uv": [0, 5, 3, 10], "texture": "#0"},
         }},
        # 铜帽
        {"from": [6, 12, 6], "to": [10, 14, 10],
         "faces": {
             "up": {"uv": [0, 0, 4, 4], "texture": "#0"},
             "north": {"uv": [0, 0, 4, 2], "texture": "#0"},
             "south": {"uv": [0, 0, 4, 2], "texture": "#0"},
             "west": {"uv": [0, 0, 4, 2], "texture": "#0"},
             "east": {"uv": [0, 0, 4, 2], "texture": "#0"},
         }},
    ],
})
write_json(os.path.join(ASSETS, "models/item/wireless_sensor.json"),
           {"parent": "geometallurgy:block/wireless_sensor"})
write_json(os.path.join(ASSETS, "items/wireless_sensor.json"),
           {"model": {"type": "minecraft:model", "model": "geometallurgy:item/wireless_sensor"}})


# --------------------------------------------------------------------- 配方

MACHINE_RECIPES = {
    "reduction_furnace": {
        "pattern": ["IBI", "BFB", "ICI"],
        "key": {
            "I": "minecraft:iron_block",
            "B": "minecraft:bricks",
            "F": "minecraft:furnace",
            "C": "minecraft:coal_block",
        },
    },
    "electrolysis_furnace": {
        "pattern": ["ICI", "BLB", "ICI"],
        "key": {
            "I": "minecraft:copper_block",
            "C": "minecraft:redstone_block",
            "B": "minecraft:iron_block",
            "L": "minecraft:lightning_rod",
        },
    },
    "displacement_furnace": {
        "pattern": ["IGI", "BFB", "IGI"],
        "key": {
            "I": "minecraft:copper_block",
            "G": "minecraft:glass",
            "B": "minecraft:iron_block",
            "F": "minecraft:furnace",
        },
    },
}

for name, spec in MACHINE_RECIPES.items():
    write_json(os.path.join(DATA, "recipe", f"{name}.json"), {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": spec["pattern"],
        "key": spec["key"],
        "result": {"id": f"geometallurgy:{name}", "count": 1},
    })

# 碳粉：把煤/木炭焙烧成较纯的碳。2 换 1 —— 当量上是亏的（煤 0.8、碳粉 1.0），
# 所以这只是「没有石墨时的应急来源」，正经做法还是石墨矿粉进电解炉。
write_json(os.path.join(DATA, "recipe", "carbon_powder.json"), {
    "type": "minecraft:crafting_shapeless",
    "category": "misc",
    "ingredients": ["#minecraft:coals", "#minecraft:coals"],
    "result": {"id": "geometallurgy:carbon_powder", "count": 1},
})

# 任务书：开局会自动发一本，这条配方只是「丢了能再造」的保险。
write_json(os.path.join(DATA, "recipe", "field_manual.json"), {
    "type": "minecraft:crafting_shapeless",
    "category": "misc",
    "ingredients": ["minecraft:book", "minecraft:iron_ingot"],
    "result": {"id": "geometallurgy:field_manual", "count": 1},
})

# 碳棒：碳粉加黏土压制成型。便宜、好做，代价是 8 个炉次就烧完。
write_json(os.path.join(DATA, "recipe", "carbon_rod.json"), {
    "type": "minecraft:crafting_shapeless",
    "category": "misc",
    "ingredients": [
        "geometallurgy:carbon_powder",
        "geometallurgy:carbon_powder",
        "minecraft:clay_ball",
    ],
    "result": {"id": "geometallurgy:carbon_rod", "count": 1},
})

# 铸造特殊配方：不写任何材料 —— 匹配逻辑全在 IngotCastingRecipe 里（它读成分组件，
# 而原版 Ingredient 读不到组件，所以只能这么写）。
write_json(os.path.join(DATA, "recipe", "ingot_casting.json"),
           {"type": "geometallurgy:ingot_casting"})

# 电极棒特殊配方：同样是成分驱动的（钨锭/钼锭才拉得成棒）。
write_json(os.path.join(DATA, "recipe", "electrode_rod.json"),
           {"type": "geometallurgy:electrode_rod"})


# 蓄电池：铜壳包能量芯。红石提供储能介质，铁做骨架。
write_json(os.path.join(DATA, "recipe", "battery.json"), {
    "type": "minecraft:crafting_shaped",
    "category": "misc",
    "pattern": ["CIC", "IRI", "CCC"],
    "key": {
        "C": "minecraft:copper_ingot",
        "I": "minecraft:iron_ingot",
        "R": "minecraft:redstone",
    },
    "result": {"id": "geometallurgy:battery", "count": 1},
})

# 无线感应器：铁架 + 铜天线 + 红石感应端。
write_json(os.path.join(DATA, "recipe", "wireless_sensor.json"), {
    "type": "minecraft:crafting_shaped",
    "category": "misc",
    "pattern": [" I ", "ICI", " R "],
    "key": {
        "I": "minecraft:iron_ingot",
        "C": "minecraft:copper_ingot",
        "R": "minecraft:redstone",
    },
    "result": {"id": "geometallurgy:wireless_sensor", "count": 1},
})


# --------------------------------------------------------------------- 战利品表与解锁成就

for name in MACHINE_RECIPES:
    write_json(os.path.join(DATA, "loot_table/blocks", f"{name}.json"), {
        "type": "minecraft:block",
        "pools": [{
            "condition": {"type": "minecraft:survives_explosion"},
            "entries": [{
                "type": "minecraft:item",
                "modifier": {
                    "type": "minecraft:copy_components",
                    "include": ["minecraft:custom_name"],
                    "source": "block_entity",
                },
                "name": f"geometallurgy:{name}",
            }],
            "rolls": 1,
        }],
        "random_sequence": f"geometallurgy:blocks/{name}",
    })

    write_json(os.path.join(DATA, "advancement/recipes", f"{name}.json"), {
        "parent": "minecraft:recipes/root",
        "criteria": {
            "has_unlock": {
                "conditions": {"items": [{"items": "minecraft:iron_block"}]},
                "trigger": "minecraft:inventory_changed",
            },
            "has_the_recipe": {
                "conditions": {"recipes": f"geometallurgy:{name}"},
                "trigger": "minecraft:recipe_unlocked",
            },
        },
        "requirements": [["has_the_recipe", "has_unlock"]],
        "rewards": {"recipes": [f"geometallurgy:{name}"]},
    })

# 蓄电池（带方块实体，抄组件）与感应器（纯方块）的战利品表
for name, copy in (("battery", True), ("wireless_sensor", False)):
    entry = {"type": "minecraft:item", "name": f"geometallurgy:{name}"}
    if copy:
        entry["modifier"] = {
            "type": "minecraft:copy_components",
            "include": ["minecraft:custom_name"],
            "source": "block_entity",
        }
    write_json(os.path.join(DATA, "loot_table/blocks", f"{name}.json"), {
        "type": "minecraft:block",
        "pools": [{
            "condition": {"type": "minecraft:survives_explosion"},
            "entries": [entry],
            "rolls": 1,
        }],
        "random_sequence": f"geometallurgy:blocks/{name}",
    })

for name, unlock in (("carbon_powder", "minecraft:coal"),
                     ("carbon_rod", "minecraft:clay_ball"),
                     ("battery", "minecraft:copper_ingot"),
                     ("wireless_sensor", "minecraft:redstone"),
                     ("engineering_configurator", "minecraft:redstone"),
                     ("field_manual", "minecraft:book")):
    write_json(os.path.join(DATA, "advancement/recipes", f"{name}.json"), {
        "parent": "minecraft:recipes/root",
        "criteria": {
            "has_unlock": {
                "conditions": {"items": [{"items": unlock}]},
                "trigger": "minecraft:inventory_changed",
            },
            "has_the_recipe": {
                "conditions": {"recipes": f"geometallurgy:{name}"},
                "trigger": "minecraft:recipe_unlocked",
            },
        },
        "requirements": [["has_the_recipe", "has_unlock"]],
        "rewards": {"recipes": [f"geometallurgy:{name}"]},
    })

print("assets + data written")
