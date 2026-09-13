# 听阅 EareyeReading

英语阅读辅助 Android 应用，基于 **听阅** (eareyereading.com) 功能设计。

## 功能特性

| 功能 | 说明 |
|------|------|
| 📚 **全文翻译** | 段落自动译成中文，显示在原文下方 |
| 📊 **词频统计** | 统计全书词频，识别高频词汇 |
| 🔤 **生词提示** | 难词下自动显示词典释义 |
| 👁 **仿生阅读** | RSVP 模式，部分字母加粗引导视线 |
| ⚡ **快速阅读** | 逐字/逐句快速闪现训练 |
| ✏️ **挖空练习** | 隐藏词汇做填空练习 |
| 🎧 **模糊听读** | 模糊文字练听力复述 |
| 🔊 **真人朗读** | Android TTS 跟读 |
| 🤖 **自动全文朗读** | 一键自动逐段 TTS 播报 |
| 📝 **生词本** | 查过的词自动收集复习 |
| 🌐 **在线文章** | 输入网址自动抓取英文文章 |
| 📖 **导入书籍** | 支持 EPUB / TXT 格式 |

## 技术栈

- **Kotlin** + **Jetpack Compose** (Material 3)
- **MVVM** 架构 + **Clean Architecture**
- **Hilt** 依赖注入
- **Room** 本地数据库
- **DataStore** 设置持久化
- **Coroutines** + **Flow**

## 项目结构

```
app/src/main/java/com/eareyereading/
├── data/
│   ├── local/
│   │   ├── dao/          # Room DAOs
│   │   ├── entity/       # 数据库实体
│   │   └── database/      # AppDatabase
│   └── repository/       # Repository 实现
├── di/                   # Hilt DI 模块
├── domain/
│   ├── model/            # 领域模型
│   └── repository/       # Repository 接口
├── ui/
│   ├── theme/           # Compose 主题
│   ├── screens/
│   │   ├── library/      # 书架页面
│   │   ├── reader/       # 阅读器页面
│   │   ├── vocabulary/   # 生词本页面
│   │   └── settings/     # 设置页面
│   └── Navigation.kt
└── util/                 # 工具类 (EpubParser, WordAnalyzer, TtsHelper)
```

## 项目文档

项目文档统一放在 [`docs/`](docs/) 目录，根目录只保留本 README。

**当前有效文档**（`docs/`）：

| 文档 | 内容 |
|------|------|
| [REFACTOR_13_PRINCIPLES.md](docs/REFACTOR_13_PRINCIPLES.md) | 第一轮重构报告（13 条设计原则，职责拆分） |
| [REFACTOR_13_PRINCIPLES_R2.md](docs/REFACTOR_13_PRINCIPLES_R2.md) | 第二轮重构报告（DRY 专题，重复消除） |
| [COMPLETION_R3.md](docs/COMPLETION_R3.md) | 第三轮重构报告（功能补全与遗留清理） |
| [FIX_TRANSLATION_LAYOUT.md](docs/FIX_TRANSLATION_LAYOUT.md) | 修复：阅读页翻译导致布局持续刷新 |
| [TOC_FEATURE_RESEARCH.md](docs/TOC_FEATURE_RESEARCH.md) | 阅读页「目录」功能调研与实现方案 |
| [PERF_AUDIT_2026-09-13.md](docs/PERF_AUDIT_2026-09-13.md) | 全项目性能与死代码审计（2026-09-13） |
| [UI_GUIDANCE_REVIEW_2026-09-13.md](docs/UI_GUIDANCE_REVIEW_2026-09-13.md) | UI 设计评审与功能引导缺口报告（2026-09-13） |

**历史归档**（`docs/archive/`，阶段性评审/修复过程记录，结论已吸收进代码）：

- [REVIEW_CYCLE1-10.md](docs/archive/) — Round 1~10 循环评审报告
- [FIX_REPORT.md](docs/archive/FIX_REPORT.md) — P0/P1 静态评审与修复报告
- [ISSUES_2026-08-31.md](docs/archive/ISSUES_2026-08-31.md) — 问题清单（按状态重组版）

## 构建

1. **克隆项目**
   ```bash
   git clone <repo-url>
   cd eareyereading
   ```

2. **配置 Android SDK**
   确保本地已安装 Android SDK (API 34)，并配置 `local.properties`：
   ```properties
   sdk.dir=/path/to/android/sdk
   ```

3. **使用 Android Studio 打开**
   - File → Open → 选择 `eareyereading` 文件夹
   - 等待 Gradle sync 完成
   - Run → Run 'app'

4. **命令行构建**
   ```bash
   ./gradlew assembleDebug    # Debug APK
   ./gradlew assembleRelease   # Release APK
   ```

## 导入书籍

支持 **EPUB** 和 **TXT** 格式的英文书籍。点击右下角「导入书籍」按钮，选择文件即可。

## 注意事项

- **翻译方案（自动选择最优）**：
  1. Android 14+ 系统翻译（华为/小米/OPPO等国产机，无需联网）
  2. Google ML Kit（需要 GMS，境外机型）
  3. 内置本地词典（1000+ 高频词，完全离线）
- 词频统计基于本地词汇分析，高频词来自停用词过滤
- TTS 使用系统内置语音引擎，支持语言取决于系统设置
