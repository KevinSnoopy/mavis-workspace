#!/usr/bin/env python3
"""生成商店图标：app 内 Adaptive Icon → docs/store/icon-512.png（纯 PIL 光栅化）。

商店要求 512×512 直角 PNG，且与应用桌面图标一致。不依赖 rsvg/浏览器：
- 背景：墨绿对角线性渐变 #0E6B5E → #0B473F（与 ic_launcher_background.xml 一致）
- 前景：立体书（多边形）+ 文字行（线段）+ 声波（三次贝塞尔采样连线），
  全部取自 ic_launcher_foreground.xml 的原始 pathData
- 取 108 viewport 中心 90dp 区域映射到画布，2x 超采样后缩小抗锯齿

用法：python3 scripts/gen_store_icon.py
"""

import os
import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SIZE = 512
SS = 2  # 超采样倍数

# 与背景 drawable 一致：108 viewport 对角渐变
C1 = (0x0E, 0x6B, 0x5E)
C2 = (0x0B, 0x47, 0x3F)

# 取中心 90dp（内容安全区），映射到 SIZE 画布
VOFF, VSPAN = 9.0, 90.0
S = SIZE * SS / VSPAN


def P(x: float, y: float) -> tuple[float, float]:
    return ((x - VOFF) * S, (y - VOFF) * S)


def bezier(p0, p1, p2, p3, n=60):
    pts = []
    for i in range(n + 1):
        t = i / n
        mt = 1 - t
        x = mt**3 * p0[0] + 3 * mt**2 * t * p1[0] + 3 * mt * t**2 * p2[0] + t**3 * p3[0]
        y = mt**3 * p0[1] + 3 * mt**2 * t * p1[1] + 3 * mt * t**2 * p2[1] + t**3 * p3[1]
        pts.append((x, y))
    return pts


def stroke_polyline(draw: ImageDraw.ImageDraw, pts, width_dp: float, color):
    """粗曲线：PIL line 的 joint 在高曲率处会出毛刺，改用圆点盖印（步长≈r/2）。"""
    w = width_dp * S
    r = w / 2
    # 按像素步长重采样：累计弦长，每 r/2 落一个圆，覆盖成平滑粗线
    dense = []
    carry = 0.0
    dense.append(pts[0])
    for a, b in zip(pts, pts[1:]):
        dx, dy = b[0] - a[0], b[1] - a[1]
        seg = (dx * dx + dy * dy) ** 0.5
        if seg == 0:
            continue
        step = max(r / 2, 0.5)
        travelled = step - carry
        d = travelled
        while d < seg:
            t = d / seg
            dense.append((a[0] + dx * t, a[1] + dy * t))
            d += step
        carry = (carry + seg) % step
    for x, y in dense:
        draw.ellipse([x - r, y - r, x + r, y + r], fill=color)


def main() -> None:
    W = H = SIZE * SS
    canvas = np.zeros((H, W, 3), dtype=np.float32)

    # 1) 对角线性渐变（108 viewport 上 (0,0)→(108,108)，画布裁取中心 90 区域）
    xs = np.linspace(VOFF, VOFF + VSPAN, W, dtype=np.float32)
    ys = np.linspace(VOFF, VOFF + VSPAN, H, dtype=np.float32)
    t = (xs[None, :] + ys[:, None]) / 216.0  # (0,0)→(108,108) 投影
    c1 = np.array(C1, dtype=np.float32)
    c2 = np.array(C2, dtype=np.float32)
    canvas = c1[None, None, :] * (1 - t[..., None]) + c2[None, None, :] * t[..., None]
    img = Image.fromarray(canvas.astype(np.uint8), "RGB")
    draw = ImageDraw.Draw(img)

    # 2) 书页与厚度（多边形，取自 foreground.xml）
    poly = lambda pts: [P(x, y) for x, y in pts]
    draw.polygon(poly([(54, 47.5), (19, 53), (16.2, 58.5), (16.2, 85.5), (54, 92)]),
                 fill=(0xF1, 0xFC, 0xFA))  # 左页
    draw.polygon(poly([(54, 47.5), (89, 53), (91.8, 58.5), (91.8, 85.5), (54, 92)]),
                 fill=(0xF1, 0xFC, 0xFA))  # 右页
    side = (0xA6, 0xF2, 0xD8)
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    od.polygon(poly([(16.2, 85.5), (19, 90.7), (54, 97.2), (54, 92)]), fill=side + (140,))  # 左厚度 0.55
    od.polygon(poly([(91.8, 85.5), (89, 90.7), (54, 97.2), (54, 92)]), fill=side + (140,))  # 右厚度
    img = Image.alpha_composite(img.convert("RGBA"), overlay).convert("RGB")
    draw = ImageDraw.Draw(img)
    draw.polygon(poly([(52.8, 47.5), (55.2, 47.5), (55.2, 92), (52.8, 92)]),
                 fill=(0x0B, 0x47, 0x3F))  # 书脊

    # 3) 文字行（stroke 1.1dp，#0E6B5E）
    ink = (0x0E, 0x6B, 0x5E)
    lines = [
        [(22.7, 63.7), (47.5, 62.1)], [(22.7, 71.3), (43.2, 69.7)], [(22.7, 79), (46.4, 77.4)],
        [(60.5, 62.1), (85.3, 63.7)], [(64.8, 69.7), (85.3, 71.3)], [(61.6, 77.4), (85.3, 79)],
    ]
    for ln in lines:
        stroke_polyline(draw, poly(ln), 1.1, ink)

    # 4) 声波：4 周期正弦（M10.8,33.5 + 8 段 C 曲线，stroke 2.7dp，#F1FCFA）
    wave_pts = [(10.8, 33.5)]
    cur = (10.8, 33.5)
    controls = [(14, 22.7), (18.4, 22.7), (21.6, 33.5)]
    for k in range(8):
        c1p = (cur[0] + 3.2, cur[1] - 10.8 if k % 2 == 0 else cur[1] + 10.8)
        c2p = (cur[0] + 7.6, cur[1] - 10.8 if k % 2 == 0 else cur[1] + 10.8)
        end = (cur[0] + 10.8, cur[1])
        wave_pts += bezier(cur, c1p, c2p, end)[1:]
        cur = end
    stroke_polyline(draw, poly(wave_pts), 2.7, (0xF1, 0xFC, 0xFA))

    img = img.resize((SIZE, SIZE), Image.LANCZOS)
    out = os.path.join(ROOT, "docs", "store", "icon-512.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.save(out, "PNG")
    print(f"generated: {out}")


if __name__ == "__main__":
    main()
