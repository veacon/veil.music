# -*- coding: utf-8 -*-
"""
薄音 · 全屏歌词胶囊图标 —— 重绘稿（最终版）

产出：5 个独立 SVG（24×24 网格，单色 currentColor，现代扁平）
用法：直接内联进 HTML / 插到设计稿；后续要落到 Compose 时按同一套坐标改 Canvas 绘制即可。

设计语言
  - 24×24 网格；圆头圆角；主笔画 1.7~2.1，次级 1.0~1.3
  - 单色 currentColor；层次靠 opacity（0.42~0.55），不用渐变/阴影
  - 几何化剪影：小尺寸（≥19px）仍能读懂
"""
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "svg")
os.makedirs(OUT, exist_ok=True)


def n(v):
    s = f"{v:.2f}"
    if s.endswith(".00"):
        s = s[:-3]
    if s.startswith("0."):
        s = s[1:]
    elif s.startswith("-0."):
        s = "-" + s[2:]
    return s


def head(tip, ang_deg, length=3.0, half=1.95):
    """实心三角箭头：尖端落在 tip，指向 ang_deg（屏幕角：0=右，90=下，-90=上）。"""
    a = math.radians(ang_deg)
    tx, ty = math.cos(a), math.sin(a)
    px, py = -ty, tx
    bx, by = tip[0] - tx * length, tip[1] - ty * length
    p1 = (bx + px * half, by + py * half)
    p2 = (bx - px * half, by - py * half)
    return (
        f'  <path d="M {n(tip[0])} {n(tip[1])} L {n(p1[0])} {n(p1[1])} '
        f'L {n(p2[0])} {n(p2[1])} Z" fill="currentColor"/>'
    )


def svg(body, name):
    doc = (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="24" height="24" '
        f'fill="none" color="#1A1F27">\n'
        f'  <title>{name}</title>\n'
        f'{body}\n'
        f'</svg>\n'
    )
    with open(os.path.join(OUT, f"{name}.svg"), "w", encoding="utf-8", newline="\n") as f:
        f.write(doc)
    return doc


# ───────────── 字形 A（几何无衬线）：局部宽 11、高 13，顶点 (5.5, 0) ─────────────
def glyph_a(x, y, s, sw, opacity=None):
    """在 (x, y) 画一个字母 A。s 为缩放（1.0 → 高 13、宽 11）。"""
    op = f' opacity="{opacity}"' if opacity is not None else ""
    d = (
        f"M {n(x)} {n(y + 13 * s)} L {n(x + 5.5 * s)} {n(y)} "
        f"L {n(x + 11 * s)} {n(y + 13 * s)} "
        f"M {n(x + 2.7 * s)} {n(y + 9.6 * s)} L {n(x + 8.3 * s)} {n(y + 9.6 * s)}"
    )
    return (
        f'  <path d="{d}" stroke="currentColor" stroke-width="{sw}" '
        f'stroke-linecap="round" stroke-linejoin="round"{op}/>'
    )


# ═════════════ 1. 换效 ═════════════
# 语义：上下反向的双向箭头（⇅）＝ 上下换一套；外圈旋转弧 ＝ 循环轮转。
def icon_cycle():
    body = [
        # 竖直双向箭头：上下两端反向
        '  <path d="M 12 7.6 L 12 16.4" stroke="currentColor" stroke-width="2" '
        'stroke-linecap="round"/>',
        head((12.0, 5.7), -90, length=3.0, half=2.5),
        head((12.0, 18.3), 90, length=3.0, half=2.5),
    ]
    # 外圈旋转弧：左下 → 顺时针 → 左上，缺口在左侧，末端切线带箭头
    cx = cy = 12.0
    r = 9.0
    a0, a1 = 205.0, 140.0 + 360.0
    p0 = (cx + r * math.cos(math.radians(a0)), cy + r * math.sin(math.radians(a0)))
    p1 = (cx + r * math.cos(math.radians(a1)), cy + r * math.sin(math.radians(a1)))
    body.append(
        f'  <path d="M {n(p0[0])} {n(p0[1])} A {r} {r} 0 1 1 {n(p1[0])} {n(p1[1])}" '
        f'stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/>'
    )
    # 末端箭头：尖端越过弧端点，底边压住弧线 → 严丝合缝的切线方向
    # （弧的圆头端帽必须完全落在三角形内部，否则会鼓出一个疙瘩）
    tang = math.degrees(math.atan2(math.cos(math.radians(a1)), -math.sin(math.radians(a1))))
    tip = (p1[0] + math.cos(math.radians(tang)) * 1.9,
           p1[1] + math.sin(math.radians(tang)) * 1.9)
    body.append(head(tip, tang, length=4.0, half=2.5))
    return svg("\n".join(body), "icon-cycle")


# ═════════════ 2. 字体 ═════════════
# 语义：同一个「A」的两副字形 —— 粗体在左下、细体在右上，彼此错位叠出，
#      表示「字形在变 / 可换字体」，而不是简单的 Aa。
def icon_font():
    body = [
        glyph_a(10.9, 2.6, 0.60, 1.25, opacity="0.5"),   # 细体（后）
        glyph_a(3.0, 6.8, 0.88, 2.5),                    # 粗体（前）
    ]
    return svg("\n".join(body), "icon-font")


# ═════════════ 3. 大小 ═════════════
# 语义：中心方框 ＝ 取景范围；四角对角双向箭头 ＝ 缩放；
#      框内大 A 与小 A 交叠 ＝ 字号在变。
def icon_size():
    body = [
        # 中心方框 ＝ 取景范围（放大到 9.6，给框内两个字号都留出内边距）
        '  <rect x="7.2" y="7.2" width="9.6" height="9.6" rx="1.9" '
        'stroke="currentColor" stroke-width="1.6"/>',
        # 框内：同一个「A」的两种字号 —— 大 A 在左下、小 A 缩到右上角，
        # 沿对角分居、互不接触，小尺寸下也分得清「大 / 小」。
        glyph_a(13.4, 8.5, 0.175, 0.85, opacity="0.5"),
        glyph_a(8.3, 10.9, 0.34, 1.8),
    ]
    # 四角对角双向箭头：短杆 + 小箭头，不与中心抢视觉
    for a, b, tip, ang in [
        ((6.5, 6.5), (4.9, 4.9), (4.3, 4.3), -135),
        ((17.5, 6.5), (19.1, 4.9), (19.7, 4.3), -45),
        ((6.5, 17.5), (4.9, 19.1), (4.3, 19.7), 135),
        ((17.5, 17.5), (19.1, 19.1), (19.7, 19.7), 45),
    ]:
        body.append(
            f'  <path d="M {n(a[0])} {n(a[1])} L {n(b[0])} {n(b[1])}" stroke="currentColor" '
            f'stroke-width="1.8" stroke-linecap="round"/>'
        )
        body.append(head(tip, ang, length=2.3, half=1.5))
    return svg("\n".join(body), "icon-size")


# ═════════════ 4. 背景 ═════════════
# 语义：画框 ＝ 背景图层；远山脊线在后（被近山遮住尾部）、近山剪影在前、太阳点缀。
def icon_bg():
    body = [
        '  <rect x="3" y="4.4" width="18" height="15.2" rx="2.4" '
        'stroke="currentColor" stroke-width="1.8"/>',
        # 太阳（左上，抬高一点，与远山留出空隙）
        '  <circle cx="7.6" cy="8.8" r="1.5" fill="currentColor"/>',
        # 远山（脊线，尾巴被近山盖住 → 层次感）
        '  <path d="M 4.8 17.2 L 8.9 11.9 L 13 17.2" stroke="currentColor" '
        'stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" opacity="0.55"/>',
        # 近山（实心剪影）
        '  <path d="M 9.8 17.8 L 14.4 12.4 L 19 17.8 Z" fill="currentColor"/>',
    ]
    return svg("\n".join(body), "icon-background")


# ═════════════ 5. 上传 ═════════════
# 语义：标准二维码 —— 三个定位角（左上/右上/左下）+ 右下数据点阵。
def icon_qr():
    body = []

    def finder(x, y):
        body.append(
            f'  <rect x="{x}" y="{y}" width="6.2" height="6.2" rx="1.5" '
            f'stroke="currentColor" stroke-width="1.7"/>'
        )
        body.append(
            f'  <rect x="{x + 1.85:.2f}" y="{y + 1.85:.2f}" width="2.5" height="2.5" '
            f'rx="0.6" fill="currentColor"/>'
        )

    def mod(x, y, s=1.6):
        body.append(
            f'  <rect x="{x}" y="{y}" width="{s}" height="{s}" rx="0.42" fill="currentColor"/>'
        )

    finder(3.0, 3.0)
    finder(14.8, 3.0)
    finder(3.0, 14.8)

    for gx, gy in [
        (14.6, 14.6), (16.8, 14.6), (19.0, 14.6),
        (14.6, 16.8), (19.0, 16.8),
        (14.6, 19.0), (16.8, 19.0), (19.0, 19.0),
        (10.4, 10.4), (12.6, 10.4), (10.4, 12.6), (12.6, 12.6),
    ]:
        mod(gx, gy)
    return svg("\n".join(body), "icon-upload")


ICONS = [
    ("icon-cycle", "换效"),
    ("icon-font", "字体"),
    ("icon-size", "大小"),
    ("icon-background", "背景"),
    ("icon-upload", "上传"),
]


def build_preview_html():
    big, small, tiny, light = [], [], [], []
    for fname, label in ICONS:
        with open(os.path.join(OUT, f"{fname}.svg"), encoding="utf-8") as f:
            body = f.read()
        s = body[body.index("<svg"):]
        big.append(
            f'<div class="cell"><div class="box">{s}</div>'
            f'<div class="nm">{label}</div><div class="en">{fname}.svg</div></div>'
        )
        small.append(
            f'<div class="sc"><div class="b">{s}</div><div class="nm">{label}</div></div>'
        )
        tiny.append(
            f'<div class="ti"><div class="b">{s}</div><div class="nm">{label}</div></div>'
        )
        light.append(
            f'<div class="cell lt"><div class="box">{s}</div>'
            f'<div class="nm">{label}</div></div>'
        )
    html = f"""<!DOCTYPE html>
<html><head><meta charset="utf-8">
<style>
 body {{ margin:0; background:#0E1116; font-family:"Segoe UI","Microsoft YaHei",sans-serif; color:#E8E4DA; }}
 .wrap {{ padding:26px 30px 34px; }}
 h1 {{ font-size:19px; font-weight:600; margin:0 0 4px; letter-spacing:.4px; }}
 .sub {{ font-size:12.5px; color:#8A9099; margin:0 0 6px; }}
 h2 {{ font-size:13px; font-weight:600; color:#B9BEC6; margin:22px 0 12px; }}
 .row {{ display:flex; gap:20px; align-items:flex-end; margin-bottom:8px; }}
 .cell {{ text-align:center; }}
 .box {{ width:176px; height:176px; border-radius:24px; background:#1A1F27;
        display:flex; align-items:center; justify-content:center; margin-bottom:9px; }}
 .box svg {{ width:126px; height:126px; color:#EDE7DA; }}
 .nm {{ font-size:12.5px; color:#C9CDD4; }}
 .en {{ font-size:10.5px; color:#6E757E; margin-top:2px; }}
 .small {{ display:flex; gap:24px; align-items:flex-end; }}
 .sc .b {{ width:62px; height:62px; border-radius:50%; background:#1A1F27;
        display:flex; align-items:center; justify-content:center; margin:0 auto 7px; }}
 .sc .b svg {{ width:26px; height:26px; color:#EDE7DA; }}
 .sc .nm {{ font-size:11px; color:#8A9099; }}
 .tiny {{ display:flex; gap:22px; align-items:flex-end; margin-top:12px; }}
 .ti .b {{ width:32px; height:32px; border-radius:50%; background:#1A1F27;
        display:flex; align-items:center; justify-content:center; margin:0 auto 5px; }}
 .ti .b svg {{ width:19px; height:19px; color:#EDE7DA; }}
 .ti .nm {{ font-size:10px; color:#6E757E; }}
 .light {{ display:flex; gap:20px; margin-top:4px; }}
 .lt .box {{ background:#F2EFE8; }}
 .lt .box svg {{ color:#1A1F27; }}
</style></head>
<body><div class="wrap">
 <h1>薄音 · 全屏歌词胶囊图标（重绘稿）</h1>
 <p class="sub">24×24 网格 · 圆头线宽 1.6–2.0 · 单色 currentColor · 现代扁平</p>
 <h2>大图 126px</h2>
 <div class="row">{''.join(big)}</div>
 <h2>实际尺寸 26px / 19px</h2>
 <div class="small">{''.join(small)}</div>
 <div class="tiny">{''.join(tiny)}</div>
 <h2>浅色底</h2>
 <div class="light">{''.join(light)}</div>
</div></body></html>"""
    with open(os.path.join(HERE, "preview.html"), "w", encoding="utf-8", newline="\n") as f:
        f.write(html)


if __name__ == "__main__":
    icon_cycle()
    icon_font()
    icon_size()
    icon_bg()
    icon_qr()
    build_preview_html()
    print("done ->", OUT)
