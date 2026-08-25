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
