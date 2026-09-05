# -*- coding: utf-8 -*-
"""
听阅 EareyeReading — 设计令牌生成器 v2
色阶按 WCAG 对比度目标「反解」得到，而非手工试色。
"""
import colorsys, json

# ---------- 基础工具 ----------
def hex_to_rgb(h):
    h = h.lstrip('#'); return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))

def rgb_to_hex(r, g, b):
    return '#%02X%02X%02X' % (round(r), round(g), round(b))

def srgb_to_lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

def luminance(h):
    r, g, b = hex_to_rgb(h)
    return 0.2126 * srgb_to_lin(r) + 0.7152 * srgb_to_lin(g) + 0.0722 * srgb_to_lin(b)

def contrast(h1, h2):
    l1, l2 = luminance(h1), luminance(h2)
    hi, lo = max(l1, l2), min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)

def hsl(h_deg, s_pct, l_pct):
    r, g, b = colorsys.hls_to_rgb(h_deg / 360.0, l_pct / 100.0, s_pct / 100.0)
    return rgb_to_hex(r * 255, g * 255, b * 255)

def solve_L(hue, sat, target_ratio, bg='#FFFFFF'):
    """二分求解：给定 hue/sat/背景，找出使对比度≈target 的明度 L%。"""
    lo, hi = 0.0, 100.0
    for _ in range(60):
        mid = (lo + hi) / 2
        c = contrast(hsl(hue, sat, mid), bg)
        # L 越大越浅；浅色与白底对比度更低
        if c > target_ratio:
            lo = mid          # 还不够浅，往浅走（对深色前景则是反的）
        else:
            hi = mid
        # 上面分支假设 bg 为浅色；对深底需反向
    # 统一处理：直接在 L 轴上找最接近目标的点
    best, best_err = None, 1e9
    L = 0.0
    while L <= 100.0:
        c = contrast(hsl(hue, sat, L), bg)
        err = abs(c - target_ratio)
        if err < best_err:
            best_err, best = err, L
        L += 0.25
    return best

def solve_scale(hue, sat, targets, bg='#FFFFFF'):
    """targets: {step_name: (target_ratio, sat)}"""
    return {k: hsl(hue, s, solve_L(hue, s, t, bg)) for k, (t, s) in targets.items()}

# ================= 中性色（暖灰，纸感） =================
# 反解基准取「暖白 #FBF8F3」——App 主背景，比纯白暗。
# 以此为基准达标，则在更亮的纯白卡片上只会更宽松（对比度更高）。
H_NEU = 75          # 暖灰 hue（微偏黄绿，纸感）
NEU_BG = '#FBF8F3'

# (目标对比度, 饱和度)；目标值留 ~5% 余量，避免舍入后刚好卡线
neutral_targets = {
    50:  (1.07, 14), 100: (1.16, 13), 200: (1.40, 12), 300: (1.78, 11),
    400: (2.35, 10),  # 禁用文字/占位（不承载正文）
    500: (3.20, 10),  # 辅助文字 + 图标（AA 非文本 3:1）
    600: (4.75, 11),  # 次级文字（AA 正文 4.5:1）
    700: (7.40, 12),  # 正文
    800: (11.5, 13),  # 标题
    900: (16.5, 14),  # 强标题
}
neutral = {k: hsl(H_NEU, s, solve_L(H_NEU, s, t, NEU_BG)) for k, (t, s) in neutral_targets.items()}

# ================= 品牌墨绿 =================
BRAND = '#0E6B5E'     # 锚点保留
H_PRI = 172

# 锚点以下（600-900）向深走，锚点以上（50-400）向浅走
primary = {
    50:  hsl(H_PRI, 62, 96.5),
    100: hsl(H_PRI, 60, 91.0),
    200: hsl(H_PRI, 52, 81.0),
    300: hsl(H_PRI, 46, 68.0),
    400: hsl(H_PRI, 44, 54.0),
    500: BRAND,                    # ← 品牌锚点
    600: hsl(H_PRI, 72, 20.0),
    700: hsl(H_PRI, 74, 16.0),
    800: hsl(H_PRI, 76, 11.5),
    900: hsl(H_PRI, 80, 6.5),
}

# ================= 强调暖橙（墨绿互补） =================
H_ACC = 27
accent = {
    50:  hsl(H_ACC, 60, 96.5), 100: hsl(H_ACC, 62, 90.0), 200: hsl(H_ACC, 70, 79.0),
    300: hsl(H_ACC, 78, 67.0), 400: hsl(H_ACC, 84, 55.0), 500: hsl(H_ACC, 88, 44.0),
    600: hsl(H_ACC, 90, 35.0), 700: hsl(H_ACC, 84, 27.0), 800: hsl(H_ACC, 76, 19.0),
    900: hsl(H_ACC, 62, 11.0),
}

# ================= 纸感背景 =================
PAPER = {
    'light_pure':   '#FFFFFF',
    'light_warm':   '#FBF8F3',
    'light_paper':  '#F5F0E7',
    'sepia':        '#F3E4C8',
    'sepia_deep':   '#E9D5B0',
    'dark':         '#0F1A17',
    'dark_surface': '#16241F',
    'dark_raised':  '#1D2E28',
    'dark_deep':    '#0A1210',
}

# ================= 语义色 =================
SEM_LIGHT = {
    'success': '#1A7242', 'success_bg': '#E3F2E8',
    'warning': '#98500A', 'warning_bg': '#FBEEDA',
    'error':   '#AE2A21', 'error_bg':   '#F9E4E2',
    'info':    '#16699F', 'info_bg':    '#E1EFF9',
}
SEM_DARK = {
    'success': '#6FD99B', 'success_bg': '#123524',
    'warning': '#EFB45F', 'warning_bg': '#3B2607',
    'error':   '#F2B8B5', 'error_bg':   '#4A1512',
    'info':    '#8CCDF5', 'info_bg':    '#0D2C42',
}

# ================= 难度等级 =================
# 同时提供「标签底 + 自动前景」与「正文着色」两套
LEVEL_BG = {'L1': '#DFF1EB', 'L2': '#9FDCC8', 'L3': '#3FA98D', 'L4': '#0E6B5E', 'L5': '#083F37'}
LEVEL_TEXT = {'L1': '#0B564C', 'L2': '#083F37', 'L3': '#0B564C', 'L4': '#0A5248', 'L5': '#05342E'}

def best_fg(bg):
    """为给定底色自动挑选对比度更高的前景（深色 or 白）。"""
    d, w = neutral[900], '#FFFFFF'
    return (w, contrast(w, bg)) if contrast(w, bg) > contrast(d, bg) else (d, contrast(d, bg))

HEAT_LIGHT = ['#EAF2EF', '#BFE3D8', '#7FC9B4', '#3EA48B', '#0E6B5E']
HEAT_DARK  = ['#1B2B26', '#17493C', '#1A6E59', '#1F9479', '#3EC39F']

# ================= 输出 =================
def block(title, d, w=3):
    print('\n' + '=' * 70); print(title); print('=' * 70)
    for k, v in d.items():
        print(f'  {k:<{w}}  {v}')

block('中性色阶 NEUTRAL（按 WCAG 目标反解，基准 #FFFFFF）', neutral, 10)
block('品牌色阶 PRIMARY（墨绿，锚点 500 = #0E6B5E）', primary, 10)
block('强调色阶 ACCENT（暖橙）', accent, 10)
block('纸感背景 PAPER', PAPER, 14)

print('\n' + '=' * 70)
print('难度等级标签：自动前景色')
print('=' * 70)
for lvl, bg in LEVEL_BG.items():
    fg, c = best_fg(bg)
    print(f'  {lvl}  底 {bg}  →  字 {fg}   {c:5.2f}:1')

# ================= 对比度验证 =================
results = []
def check(label, fg, bg, need=4.5, silent=False):
    c = contrast(fg, bg)
    ok = c >= need
    results.append(ok)
    if not silent:
        print(f'  {"✅" if ok else "❌"} {c:5.2f}:1 (需≥{need})  {label:<32} {fg} on {bg}')
    return ok

print('\n' + '=' * 70)
print('WCAG 2.1 AA 对比度实测')
print('=' * 70)

print('\n--- 亮色主题 · 文字层级 ---')
check('标题 on 暖白底',        neutral[900], PAPER['light_warm'])
check('正文 on 暖白底',        neutral[700], PAPER['light_warm'])
check('次级文字 on 暖白底',    neutral[600], PAPER['light_warm'])
check('辅助文字 on 暖白底',    neutral[500], PAPER['light_warm'], 3.0)
check('标题 on 卡片白',        neutral[900], PAPER['light_pure'])
check('正文 on 卡片白',        neutral[700], PAPER['light_pure'])
check('次级文字 on 卡片白',    neutral[600], PAPER['light_pure'])
check('辅助文字 on 卡片白',    neutral[500], PAPER['light_pure'], 3.0)

print('\n--- 亮色主题 · 交互与品牌 ---')
check('白字 on 主色按钮',           '#FFFFFF',    primary[500])
check('主色文字 on 暖白底',         primary[600], PAPER['light_warm'])
check('主色容器字 on 主色容器',     primary[900], primary[100])
check('主色容器字 on 主色容器(深)', '#FFFFFF',    primary[500])
check('强调文字 on 暖白底',         accent[700],  PAPER['light_warm'])
# 橙色属高明度暖色：accent-500 上白字仅 3.7:1，
# 故实心强调按钮填充统一取 accent-600（白字 5.4:1）
check('白字 on 强调按钮(600)',      '#FFFFFF',    accent[600])
check('图标(默认) on 暖白底',       neutral[500], PAPER['light_warm'], 3.0)
check('描边 on 暖白底',             neutral[300], PAPER['light_warm'], 1.2, silent=True)

print('\n--- 亮色主题 · 语义色 ---')
for k in ['success', 'warning', 'error', 'info']:
    check(f'{k} on 暖白底',  SEM_LIGHT[k], PAPER['light_warm'])
    check(f'{k} on 其浅底',  SEM_LIGHT[k], SEM_LIGHT[k + '_bg'])

print('\n--- 深色主题 ---')
check('标题 on 深底',        neutral[50],  PAPER['dark'])
check('正文 on 深底',        neutral[100], PAPER['dark'])
check('次级文字 on 深底',    neutral[300], PAPER['dark'])
check('辅助文字 on 深底',    neutral[400], PAPER['dark'], 3.0)
check('标题 on 深色卡片',    neutral[50],  PAPER['dark_surface'])
check('正文 on 深色卡片',    neutral[100], PAPER['dark_surface'])
check('次级文字 on 深色卡片', neutral[300], PAPER['dark_surface'])
check('主色(亮) 文字 on 深底', primary[300], PAPER['dark'])
check('深字 on 主色(亮)按钮',  primary[900], primary[300])
check('主色容器字 on 深容器',  primary[100], primary[800])
for k in ['success', 'warning', 'error', 'info']:
    check(f'深色 {k} on 深底', SEM_DARK[k], PAPER['dark'])

print('\n--- Sepia 纸感主题 ---')
check('Sepia 正文 on 纸底',   '#4A3A28',   PAPER['sepia'])
check('Sepia 次级 on 纸底',   '#6F5A41',   PAPER['sepia'])
check('Sepia 主色 on 纸底',   primary[700], PAPER['sepia'])
check('Sepia 强调 on 纸底',   accent[700],  PAPER['sepia'])

print('\n--- 难度等级标签（自动前景，需≥4.5）---')
for lvl, bg in LEVEL_BG.items():
    fg, _ = best_fg(bg)
    check(f'{lvl} 标签', fg, bg)

print('\n--- 热力图色阶（相邻可辨识，需≥1.15）---')
for i in range(len(HEAT_LIGHT) - 1):
    check(f'亮色 阶{i}→阶{i+1}', HEAT_LIGHT[i], HEAT_LIGHT[i+1], 1.15)
for i in range(len(HEAT_DARK) - 1):
    check(f'暗色 阶{i}→阶{i+1}', HEAT_DARK[i], HEAT_DARK[i+1], 1.15)

print('\n' + '=' * 70)
total, passed = len(results), sum(results)
print(f'  总计 {total} 项 · 通过 {passed} · 失败 {total - passed}  · 通过率 {passed/total*100:.1f}%')
print('=' * 70)

# ================= 导出 =================
tokens = {
    'primary': primary, 'accent': accent, 'neutral': neutral,
    'paper': PAPER,
    'semantic': {'light': SEM_LIGHT, 'dark': SEM_DARK},
    'level': {'bg': LEVEL_BG, 'text': LEVEL_TEXT,
              'fg': {k: best_fg(v)[0] for k, v in LEVEL_BG.items()}},
    'heatmap': {'light': HEAT_LIGHT, 'dark': HEAT_DARK},
}
out = '/Users/kevin/WorkBuddy/Worktrees/eareyereading/eareyereading-ed7878c0/design-system/tokens/tokens.json'
with open(out, 'w') as f:
    json.dump(tokens, f, indent=2, ensure_ascii=False)
print(f'\n已导出 → {out}')
