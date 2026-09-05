# 听阅 EareyeReading · UI 设计改造任务总览

> **任务**：全新设计「听阅」应用 UI（AppIcon / 启动图 / 图标 / 颜色 / 亮暗色 / 字体 / 大小 / 主页面 / 子页面 / 弹窗）
> **交付**：设计令牌 · 品牌图标 · AppIcon + Splash + Themed Icon · HTML 高保真可交互原型 · 设计规范文档
> **状态**：✅ 全部完成（设计阶段）

---

## 核心判断

不堆砌效果、不酷炫、不妥协无障碍。每一步都有**实测数据**支撑。

### 1 · 主色方向 · 保留墨绿 `#0E6B5E`，深化为完整色阶

理由：用户认可品牌已有认知，且墨绿与「阅读」气质天然吻合（沉稳、纸感、低饱和）。

**关键修正**：原 AppIcon 用暖棕 `#8B7355`，与主色墨绿严重割裂。新设计 **AppIcon 与主题同色系**，品牌识别统一。

### 2 · 设计令牌 · 55/55 项 WCAG 对比度实测通过

不只是写色值 —— 用 Python 脚本按目标对比度**反解**明度，全部通过实测。

**关键修正**：旧版"次级文字" `#727860` 在暖白底上仅 4.34:1（需 ≥4.5）。新色阶按 `#FBF8F3`（暖白主背景）反解，确保最暗背景也达标。

### 3 · 字体 · UI 无衬线 + 阅读衬线，双轨制

- **UI**：Inter（多语言、屏幕优化、x-height 大）
- **阅读正文**：Literata（Google 专为屏幕阅读设计的长文衬线体，提升 20-30% 速度）
- **中文**：系统字体栈（PingFang SC / Microsoft YaHei）

**关键修正**：旧版所有文本都用 `FontFamily.Default`，没有针对阅读场景定制字体。一款英语阅读 App 的核心体验就是字体，这是必须修的痛点。

### 4 · AppIcon · 「眸中之页」概念，远看是眼近看是书

- **三层融合**（不是堆叠）：书页构成杏仁眼廓 + 中缝是书脊 + 瞳孔点破「阅」语义
- **小尺寸退化策略**：16px 仍能识别主轮廓（实测），48dp 起全部细节清晰
- **Themed Icon**：提供 monochrome 路径，Android 13+ 规范
- **Splash**：独立 240dp 设计（不再放大 launcher）

### 5 · 图标语言 · 8 枚核心图标（统一规范）

- 24dp 网格 / 1.75dp 描边 / 圆角端点
- 与 AppIcon 几何呼应（SpeedRead 眼睛轮廓 = AppIcon 杏仁形）
- 详情预览图见 `icons/preview/icons-overview.png`

---

## 交付清单

```
design-system/
├── SPEC.md                                 ← 设计规范文档（必读）
├── overview.md                             ← 本文件
│
├── tokens/
│   ├── generate.py                         ← 令牌生成脚本（可重跑）
│   ├── tokens.json                         ← 机器可读令牌
│   └── tokens.css                          ←  Web/HTML 可用 CSS 变量
│
├── launcher/
│   ├── app-icon.svg                        ← AppIcon 主源
│   ├── ic_launcher_background.xml          ← Android adaptive 背景
│   ├── ic_launcher_foreground.xml          ← Android adaptive 前景
│   ├── ic_launcher_monochrome.xml          ← Android 13+ Themed Icon
│   ├── ic_splash.xml / .svg                ← Splash 240dp
│   ├── render.js                           ← PNG 导出脚本
│   ├── render_splash.js
│   └── png/
│       ├── ic_launcher_*px.png             ← 各密度 PNG（6 档）
│       ├── ic_splash_*px.png               ← Splash 各密度 PNG（5 档）
│       ├── degradation-test.png            ← 16–144px 退化测试条
│       ├── mask-preview.png                ← 5 种厂商遮罩裁切预览
│       └── themed-icon-preview.png         ← 5 种主题背景适配预览
│
├── icons/
│   ├── ic_reader.svg                       ← 阅读（与 AppIcon 几何呼应）
│   ├── ic_vocabulary.svg                   ← 生词
│   ├── ic_review.svg                       ← 复习
│   ├── ic_listen.svg                       ← 听读
│   ├── ic_speed_read.svg                   ← 速读
│   ├── ic_cloze.svg                        ← 挖空
│   ├── ic_frequency.svg                    ← 词频
│   ├── ic_settings.svg                     ← 设置
│   └── preview/icons-overview.png          ← 8 图标 × 2 主题预览
│
└── preview/
    ├── index.html                          ← ⭐ 主原型入口（高保真）
    ├── prototype.css                       ← 原型样式
    ├── tokens.css                          ← 设计令牌（与上面共用）
    ├── fonts-local.css                     ← 本地字体 CSS
    ├── fonts.css                           ← Google Fonts 原始（备用）
    ├── fetch_fonts.py                      ← 字体下载脚本
    └── fonts/                              ← Inter + Literata woff2（524KB）
```

---

## 关键数字

| 项 | 数值 |
|---|---|
| 设计令牌对比度通过率 | **55/55 (100%)** |
| AppIcon 颜色一致性 | 5 层 → **3 层融合**（48dp 主轮廓清晰） |
| 图标语言描边精度 | 2.0dp → **1.75dp**（更精致） |
| 字体本地化体积 | 524KB / 8 文件（**离线可用**） |
| 原型代码规模 | 1057 行 HTML + 1355 行 CSS + 167 行令牌 |
| 原型覆盖页面 | **5 主 + 3 子 + 3 弹窗 = 11 屏** |
| 主题覆盖 | **亮 / Sepia / 暗** 三套 |

---

## 下一步建议（待开发者执行）

### 立即可做（1-2 小时）

1. **替换 AppIcon**：把 `launcher/ic_launcher_*.xml` 复制到 Android `app/src/main/res/`，更新 `mipmap-anydpi-v26/`
2. **替换 Splash theme**：更新 `app/src/main/res/values/themes.xml` 中的 Splash 颜色与图标
3. **替换 Color.kt**：对照 `tokens/tokens.json` 重写或反生成

### 中期（1-2 天）

4. **集成字体**：woff2 → ttf，放入 `app/src/main/res/font/`，更新 Type.kt
5. **集成 8 枚品牌图标**：Vector Asset，替换现有 Material Icons 在功能页内的使用
6. **Dimens 令牌**：新建 `ui/theme/Dimens.kt`，封装间距/圆角
7. **Shape 令牌**：新建 `ui/theme/Shape.kt`，封装 Shapes

### 长期（1 周）

8. **三主题持久化**：扩展现有 readingTheme 实现，新增 Sepia 主题，DataStore 持久化
9. **设计 QA**：用新令牌重构各屏幕，逐页验收一致性
10. **A/B 测试**：对比旧版与新版的用户行为指标（停留时间、阅读字数）

---

## 验证入口

- **预览原型**：`design-system/preview/index.html`（已 present_files，可点击交互）
- **阅读规范**：`design-system/SPEC.md`（设计原理 + 令牌表 + 集成步骤）
- **对比度报告**：执行 `python tokens/generate.py` 重新生成实测报告

---

**设计交付完成 · 2026-09-05**