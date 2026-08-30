// 本文件是 OEM TTS 引擎探测代码：NameNotFound 等异常是预期信号
// （"该包未安装/该组件不存在"），不是需要上报的错误，按文件级抑制
@file:Suppress("SwallowedException")

package com.eareyereading.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.util.Log

/**
 * TTS 引擎检测与安装引导工具。
 *
 * 国产手机（小米/华为/OPPO/Vivo/魅族）通常不内置 Google TTS，而是使用各家自带的语音引擎。
 * - 小米: com.xiaomi.mibrain.speech / com.xiaomi.speech.tts
 * - 华为: com.huawei.hitouch.tts / com.huawei.tts
 * - OPPO: com.coloros.oppopush / oppo tts
 * - vivo: com.vivo.tts
 * - 百度: com.baidu.duer.tts
 * - 讯飞: com.iflytek.tts
 *
 * 这些引擎大多数情况下已预装但需要在系统设置中启用，或者需要单独下载数据包。
 */
object TtsEngineHelper {
    private const val TAG = "TtsEngineHelper"

    /**
     * 已知的国内 TTS 引擎包名列表（按优先级排序）
     */
    val KNOWN_CHINESE_ENGINE_PACKAGES = listOf(
        "com.google.android.tts",     // Google TTS（兼容）
        "com.xiaomi.mibrain.speech",  // 小爱语音
        "com.xiaomi.speech.tts",      // 小米 TTS（部分机型）
        "com.huawei.hitouch.tts",     // 华为 TTS（HiTouch）
        "com.huawei.tts",             // 华为 TTS（旧版）
        "com.hicloud.android.tts",    // 华为云 TTS
        "com.coloros.tts",            // OPPO TTS
        "com.oppo.tts",               // OPPO TTS（旧）
        "com.vivo.tts",               // vivo TTS
        "com.baidu.duer.tts",         // 百度小度 TTS
        "com.baidu.tts",              // 百度 TTS
        "com.iflytek.tts",            // 讯飞 TTS
        "com.iflytek.cloudtts",       // 讯飞云 TTS
        "com.tencent.tts",            // 腾讯 TTS
        "com.sogou.tts",              // 搜狗 TTS
    )

    data class TtsEngineInfo(
        val packageName: String,
        val displayName: String,
        val isInstalled: Boolean,
        val isEnabled: Boolean,
    )

    /**
     * 列出设备上所有可用的 TTS 引擎（按优先级排序）。
     *
     * 返回的列表包含：
     * - 已知国内引擎（已安装的）
     * - 通过 PackageManager 查询到的所有 ACTION_TTS_SERVICE 引擎
     * - Google TTS（如果已安装但未启用也会列出）
     */
    fun listAvailableEngines(context: Context): List<TtsEngineInfo> {
        val result = mutableListOf<TtsEngineInfo>()
        val pm = context.packageManager

        // 1. 通过 Intent 查询所有实现了 TTS 服务的引擎
        // TextToSpeech.Engine.ACTION_TTS_SERVICE 是常量 "android.intent.action.TTS_SERVICE"，但 API 21+ 才公开
        val ttsIntent = Intent("android.intent.action.TTS_SERVICE")
        val resolvedEngines = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(ttsIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(ttsIntent, 0)
        }
        Log.i(TAG, "listAvailableEngines: Intent query returned ${resolvedEngines.size} engines: ${resolvedEngines.map { it.serviceInfo.packageName }}")

        val enginePackageNames = mutableSetOf<String>()
        for (info in resolvedEngines) {
            val pkg = info.serviceInfo.packageName
            val appInfo = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(pkg, 0)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
            val displayName = appInfo?.loadLabel(pm)?.toString() ?: pkg
            val isEnabled = appInfo?.enabled == true
            result.add(TtsEngineInfo(pkg, displayName, true, isEnabled))
            enginePackageNames.add(pkg)
        }

        // 2. 补充已知的国内引擎包名（即使未通过 Intent 查询到，也列出来供用户查看）
        val knownInstalled = mutableListOf<String>()
        for (pkg in KNOWN_CHINESE_ENGINE_PACKAGES) {
            if (pkg in enginePackageNames) continue
            val isInstalled = try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
            if (isInstalled) {
                knownInstalled.add(pkg)
                val appInfo = try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getApplicationInfo(pkg, 0)
                    }
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
                val displayName = appInfo?.loadLabel(pm)?.toString() ?: pkg
                val isEnabled = appInfo?.enabled == true
                result.add(TtsEngineInfo(pkg, displayName, true, isEnabled))
            }
        }
        Log.i(TAG, "listAvailableEngines: known Chinese engines installed: $knownInstalled")

        // 3. 按优先级排序：Google TTS > 华为 > 小米 > OPPO > vivo > 其他
        result.sortBy { info ->
            KNOWN_CHINESE_ENGINE_PACKAGES.indexOf(info.packageName).let {
                if (it == -1) Int.MAX_VALUE else it
            }
        }

        return result
    }

    /**
     * 检查设备是否完全没有任何 TTS 引擎（包括 Google TTS 和国内厂商引擎）。
     */
    fun hasAnyEngine(context: Context): Boolean {
        return listAvailableEngines(context).isNotEmpty()
    }

    /**
     * 获取当前系统的默认 TTS 引擎包名。
     *
     * 通过 Settings.Secure.TTS_DEFAULT_SYNTH 直接读取系统设置中的默认引擎包名。
     * 注意：某些国产手机虽然"在设置里选了"小爱/华为语音，但系统设置项仍然是 Google TTS
     * （因为 OEM 引擎没有注册到标准 TTS 配置里），所以这里需要兜底检查。
     */
    fun getSystemDefaultEnginePackage(context: Context): String? {
        return try {
            val pkg = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.TTS_DEFAULT_SYNTH,
            )
            Log.i(TAG, "Settings.Secure.TTS_DEFAULT_SYNTH=$pkg")
            pkg
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read TTS_DEFAULT_SYNTH", e)
            null
        }
    }

    /**
     * 找到第一个已启用且已知可用的 OEM 引擎（用于自动回退）。
     *
     * 返回的引擎（优先级从高到低）：
     * 1. 用户在系统设置里选的实际引擎包名（Settings.Secure.TTS_DEFAULT_SYNTH）——
     *    这是最权威的来源，即使用户没装 Google TTS 也能拿到。
     * 2. listAvailableEngines 中第一个已启用的引擎。
     *
     * 注意：即使包名不在 KNOWN_CHINESE_ENGINE_PACKAGES 中，只要系统设置里选了它，
     * 我们也直接信任并返回。OEM 厂商可能有我们没列入列表的引擎包名。
     */
    fun findFallbackEngine(context: Context): TtsEngineInfo? {
        val systemDefault = getSystemDefaultEnginePackage(context)
        Log.i(TAG, "findFallbackEngine: systemDefault=$systemDefault")

        // 1. 最高优先级：用户实际在系统设置里选的引擎
        if (systemDefault != null && systemDefault != "com.google.android.tts") {
            val info = checkPackage(context, systemDefault)
            if (info != null) {
                Log.i(TAG, "findFallbackEngine: using system default $systemDefault (isEnabled=${info.isEnabled})")
                return info
            } else {
                Log.w(TAG, "findFallbackEngine: system default $systemDefault not installed")
            }
        }

        // 2. 兜底：listAvailableEngines 中第一个已启用的
        val engines = listAvailableEngines(context)
        Log.i(TAG, "findFallbackEngine: listAvailableEngines returned ${engines.size} engines: ${engines.map { "${it.packageName}(enabled=${it.isEnabled})" }}")
        return engines.firstOrNull { it.isEnabled }
            ?: engines.firstOrNull()
    }

    /**
     * 检查一个包是否已安装，并返回其 TtsEngineInfo。
     * 即使包不在已知列表里，也返回（用户系统设置里选的可能就是未知包名）。
     */
    fun checkPackage(context: Context, packageName: String): TtsEngineInfo? {
        val pm = context.packageManager
        return try {
            // 先尝试 getPackageInfo 确认包存在
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            val appInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            val displayName = appInfo.loadLabel(pm).toString()
            TtsEngineInfo(
                packageName = packageName,
                displayName = displayName,
                isInstalled = true,
                isEnabled = appInfo.enabled,
            )
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: Exception) {
            Log.w(TAG, "checkPackage failed for $packageName", e)
            null
        }
    }

    /**
     * 检测是否是"幽灵默认"场景：系统设置指向一个未安装的引擎包名，
     * 且设备上没有其他可用的 TTS 引擎。
     *
     * 这是国产手机（特别是 MIUI/HyperOS）的典型问题：系统设置里"选了"某个
     * 引擎，但该引擎的 TTS 服务只对系统内部开放，第三方 app 无法 bind。
     */
    fun isPhantomDefaultState(context: Context): Boolean {
        val systemDefault = getSystemDefaultEnginePackage(context)
        if (systemDefault == null) return false
        // 系统默认引擎指向不存在的包
        if (checkPackage(context, systemDefault) == null) {
            // 且确实没有任何真实可用的引擎
            val realEngines = listAvailableEngines(context)
            return realEngines.isEmpty() ||
                realEngines.all { !it.isEnabled }
        }
        return false
    }

    /**
     * 检查设备是否安装了 Google Play 商店。
     */
    fun hasGooglePlay(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.android.vending", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 构造跳转到 Google Play 商店下载 Google TTS 的 Intent。
     */
    fun buildGooglePlayIntentForGoogleTts(): Intent {
        return Intent(
            Intent.ACTION_VIEW,
            android.net.Uri.parse("market://details?id=com.google.android.tts")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * 构造跳转到浏览器下载 Google TTS APK 的 Intent（当 Google Play 不可用时）。
     */
    fun buildApkDownloadIntentForGoogleTts(): Intent {
        return Intent(
            Intent.ACTION_VIEW,
            android.net.Uri.parse("https://www.apkmirror.com/apk/google-inc/google-text-to-speech/")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * 跳转到"未知来源应用"设置页面，让用户允许安装第三方 APK。
     * 这是国产手机安装 Google TTS 的必要步骤。
     */
    fun buildUnknownSourcesSettingsIntent(context: Context): Intent {
        return Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * 构造一键安装引导序列：
     * 1. 先打开"允许安装未知来源"设置
     * 2. 用户打开后回到 app，再点"下载"按钮
     */
    fun getInstallGuideSteps(context: Context): List<String> {
        val isPlay = hasGooglePlay(context)
        return if (isPlay) {
            listOf(
                "1. 点击下方「下载 Google 文字转语音」按钮，会跳转到 Play 商店",
                "2. 在 Play 商店点击「安装」",
                "3. 安装完成后回到本 app，再次点击朗读按钮",
                "4. 如果弹窗再次出现，点击「使用 Play 商店发现的引擎」即可",
            )
        } else {
            listOf(
                "1. 点击下方「前往系统 TTS 设置」，或在浏览器访问 Google TTS 官网下载",
                "2. 下载完成后点击 APK 文件，系统会提示「不允许安装未知来源」",
                "3. 点击「设置」，在「未知来源应用」列表中找到浏览器或文件管理器并允许",
                "4. 返回继续安装 APK",
                "5. 安装完成后回到本 app，再次点击朗读按钮",
                "6. 如果还有问题：建议安装带 TTS 的第三方 app（如讯飞语记、百度翻译）",
            )
        }
    }

    /**
     * 通过 Intent 扫描所有已安装应用中注册了 TTS_SERVICE 的引擎。
     *
     * 这是最可靠的发现方法 — 不依赖任何固定包名列表。
     * 任何应用只要在 AndroidManifest 里声明了 `<service android:intent-filter action="android.intent.action.TTS_SERVICE">`，
     * 就会被发现。这能捕获讯飞/百度/Google 翻译等任何带 TTS 引擎的应用。
     *
     * 与 queryIntentServices 不同，这个方法还会尝试 `queryIntentActivities` 来兜底
     * （某些 OEM ROM 把 TTS 引擎注册为 Activity 而非 Service）。
     */
    fun discoverTtsEnginesViaIntent(context: Context): List<TtsEngineInfo> {
        val pm = context.packageManager
        val result = mutableListOf<TtsEngineInfo>()
        val seenPackages = mutableSetOf<String>()

        // 1. 通过 Service Intent 扫描
        val ttsIntent = Intent("android.intent.action.TTS_SERVICE")
        val resolvedServices = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(ttsIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(ttsIntent, 0)
        }
        Log.i(TAG, "discoverTtsEnginesViaIntent: queryIntentServices returned ${resolvedServices.size}")
        for (info in resolvedServices) {
            val pkg = info.serviceInfo.packageName
            if (pkg in seenPackages) continue
            seenPackages.add(pkg)
            val engine = checkPackage(context, pkg)
            if (engine != null) result.add(engine)
        }

        // 2. 备用：通过 Activity Intent 扫描（某些 ROM 把 TTS 注册为 Activity）
        try {
            val resolvedActivities = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(ttsIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(ttsIntent, 0)
            }
            for (info in resolvedActivities) {
                val pkg = info.activityInfo.packageName
                if (pkg in seenPackages) continue
                seenPackages.add(pkg)
                val engine = checkPackage(context, pkg)
                if (engine != null) result.add(engine)
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryIntentActivities for TTS failed", e)
        }

        Log.i(TAG, "discoverTtsEnginesViaIntent: found ${result.size} engines: ${result.map { it.packageName }}")
        return result
    }

    /**
     * 通过 TextToSpeech.getEngines() API 获取所有已安装的 TTS 引擎。
     *
     * 这是 Android 官方推荐的 API，比直接用 PackageManager.queryIntentServices 更可靠，
     * 因为系统服务 TTS 会做额外的过滤和验证（屏蔽 OEM 内部不可用的引擎）。
     *
     * 实现：创建一个临时的 TextToSpeech 实例，调用 getEngines()（不等待 init 回调），
     * 然后立即 shutdown。这个 API 即使在 TTS 未初始化成功时也能返回引擎列表。
     *
     * 注意：这个方法会创建并销毁一个 TextToSpeech 实例，有一定开销，所以只在必要时调用。
     */
    fun discoverEnginesViaGetEngines(context: Context): List<TtsEngineInfo> {
        var tts: TextToSpeech? = null
        return try {
            tts = TextToSpeech(context) { /* status ignored */ }
            // getEngines() 是同步方法，会通过 Binder 调用系统服务，返回引擎列表
            val engines = tts.engines
            Log.i(TAG, "discoverEnginesViaGetEngines: getEngines() returned ${engines.size} engines")
            engines.map { engineInfo ->
                val pkg = engineInfo.name
                val displayName = engineInfo.label
                val isEnabled = try {
                    val appInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        context.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        context.packageManager.getApplicationInfo(pkg, 0)
                    }
                    appInfo.enabled
                } catch (e: PackageManager.NameNotFoundException) {
                    false
                } catch (e: Exception) {
                    true
                }
                TtsEngineInfo(
                    packageName = pkg,
                    displayName = displayName,
                    isInstalled = true,
                    isEnabled = isEnabled,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "discoverEnginesViaGetEngines: failed to call getEngines()", e)
            emptyList()
        } finally {
            try { tts?.shutdown() } catch (_: Exception) {}
        }
    }

    /**
     * 综合发现所有可能的 TTS 引擎。
     *
     * 调用顺序（每个都是独立 try，任何一个失败不影响其他）：
     * 1. TextToSpeech.getEngines() — 最可靠，但需要创建临时 TTS 实例
     * 2. PackageManager.queryIntentServices(TTS_SERVICE) — 兜底
     * 3. KNOWN_CHINESE_ENGINE_PACKAGES — 已知的 OEM 包名
     *
     * 返回去重后的列表。
     */
    fun discoverAllTtsEngines(context: Context): List<TtsEngineInfo> {
        val result = mutableListOf<TtsEngineInfo>()
        val seen = mutableSetOf<String>()

        // 1. getEngines() — 最可靠
        try {
            val engines = discoverEnginesViaGetEngines(context)
            for (engine in engines) {
                if (engine.packageName !in seen) {
                    seen.add(engine.packageName)
                    result.add(engine)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "discoverAllTtsEngines: getEngines failed", e)
        }

        // 2. queryIntentServices — 兜底
        try {
            val engines = discoverTtsEnginesViaIntent(context)
            for (engine in engines) {
                if (engine.packageName !in seen) {
                    seen.add(engine.packageName)
                    result.add(engine)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "discoverAllTtsEngines: queryIntentServices failed", e)
        }

        // 3. KNOWN_CHINESE_ENGINE_PACKAGES — 兜底
        try {
            val pm = context.packageManager
            for (pkg in KNOWN_CHINESE_ENGINE_PACKAGES) {
                if (pkg in seen) continue
                try {
                    pm.getPackageInfo(pkg, 0)
                    val info = checkPackage(context, pkg)
                    if (info != null) {
                        seen.add(pkg)
                        result.add(info)
                    }
                } catch (_: PackageManager.NameNotFoundException) {
                    // not installed
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "discoverAllTtsEngines: KNOWN_CHINESE_ENGINE_PACKAGES failed", e)
        }

        Log.i(TAG, "discoverAllTtsEngines: total found ${result.size} engines: ${result.map { it.packageName }}")
        return result
    }

    /**
     * 国内常见的第三方 TTS 应用 — 安装后这些 app 会注册一个 bindable 的 TTS 服务，
     * 即使 OEM 系统不开放内置 TTS，第三方 app 仍能使用它们的 TTS。
     *
     * 这些包名对应 app 的"主应用"，它们的 TTS 服务组件包名通常等于主包名或子包。
     */
    data class ThirdPartyTtsApp(
        val packageName: String,
        val displayName: String,
        val description: String,
        val playStoreUrl: String,
    )

    val THIRD_PARTY_TTS_APPS = listOf(
        ThirdPartyTtsApp(
            packageName = "com.google.android.apps.translate",
            displayName = "Google 翻译",
            description = "内置 Google TTS 服务，可作为第三方 TTS 引擎使用",
            playStoreUrl = "https://play.google.com/store/apps/details?id=com.google.android.apps.translate",
        ),
        // 讯飞系列 — 不同应用商店的讯飞输入法包名不同，全部列出
        ThirdPartyTtsApp(
            packageName = "com.iflytek.inputmethod.voiceassist",
            displayName = "讯飞输入法（华为/小米商店）",
            description = "讯飞自带 TTS 服务，中文语音合成质量高",
            playStoreUrl = "https://play.google.com/store/apps/details?id=com.iflytek.inputmethod.voiceassist",
        ),
        ThirdPartyTtsApp(
            packageName = "com.iflytek.inputmethod",
            displayName = "讯飞输入法（官方）",
            description = "讯飞官方版输入法，自带 TTS 服务",
            playStoreUrl = "https://play.google.com/store/apps/details?id=com.iflytek.inputmethod",
        ),
        ThirdPartyTtsApp(
            packageName = "com.iflytek.tts",
            displayName = "讯飞语音合成",
            description = "讯飞专门的 TTS 引擎（独立组件）",
            playStoreUrl = "https://play.google.com/store/apps/details?id=com.iflytek.tts",
        ),
        ThirdPartyTtsApp(
            packageName = "com.baidu.baidutranslate",
            displayName = "百度翻译",
            description = "百度自带 TTS 引擎，支持中英日韩等多语言",
            playStoreUrl = "https://play.google.com/store/apps/details?id=com.baidu.baidutranslate",
        ),
        ThirdPartyTtsApp(
            packageName = "com.youdao.hind",
            displayName = "有道翻译官",
            description = "网易有道自带 TTS，英文发音清晰",
            playStoreUrl = "https://play.google.com/store/apps/details?id=com.youdao.hind",
        ),
    )

    /**
     * 列出未安装的第三方 TTS app（用于引导用户安装）。
     */
    fun listUninstalledThirdPartyTtsApps(context: Context): List<ThirdPartyTtsApp> {
        val pm = context.packageManager
        return THIRD_PARTY_TTS_APPS.filter { app ->
            try {
                pm.getPackageInfo(app.packageName, 0)
                false
            } catch (e: PackageManager.NameNotFoundException) {
                true
            }
        }
    }

    /**
     * 构造跳转到应用宝/华为应用市场/小米应用商店等下载页面的 Intent。
     * 优先尝试 Play Store，回退到浏览器。
     */
    fun buildInstallAppIntent(thirdPartyApp: ThirdPartyTtsApp, hasPlay: Boolean): Intent {
        val primary = if (hasPlay) {
            Intent(
                Intent.ACTION_VIEW,
                android.net.Uri.parse("market://details?id=${thirdPartyApp.packageName}")
            )
        } else {
            Intent(
                Intent.ACTION_VIEW,
                android.net.Uri.parse(thirdPartyApp.playStoreUrl)
            )
        }
        primary.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return primary
    }

    /**
     * 构建跳转到系统 TTS 设置的 Intent。
     *
     * 不同 Android 版本的入口不同，我们尝试多个可能的 Intent。
     */
    fun buildTtsSettingsIntent(packageName: String? = null): Intent {
        // 如果指定了引擎包名，跳转到引擎自己的设置页面
        if (packageName != null) {
            return Intent().apply {
                setClassName(
                    packageName,
                    "$packageName.TtsSettingsActivity"
                )
            }
        }

        // 通用 TTS 设置入口
        return Intent().apply {
            action = "com.android.settings.TTS_SETTINGS"
        }
    }

    /**
     * 根据引擎包名推测厂商和显示名称。
     */
    fun getEngineDisplayInfo(packageName: String): Pair<String, String> {
        // 返回 (vendor, displayHint)
        return when {
            packageName.startsWith("com.google") -> "Google" to "Google 文字转语音引擎"
            packageName.startsWith("com.xiaomi") -> "小米" to "小米小爱语音引擎"
            packageName.startsWith("com.huawei") || packageName.startsWith("com.hicloud") -> "华为" to "华为语音引擎"
            packageName.startsWith("com.coloros") || packageName.startsWith("com.oppo") -> "OPPO" to "OPPO 语音引擎"
            packageName.startsWith("com.vivo") -> "vivo" to "vivo 语音引擎"
            packageName.startsWith("com.baidu") -> "百度" to "百度语音引擎"
            packageName.startsWith("com.iflytek") -> "讯飞" to "讯飞语音引擎"
            packageName.startsWith("com.tencent") -> "腾讯" to "腾讯语音引擎"
            packageName.startsWith("com.sogou") -> "搜狗" to "搜狗语音引擎"
            else -> "其他" to "文字转语音引擎"
        }
    }

    /**
     * 检测当前设备是否为国产手机品牌。
     */
    fun isChineseDevice(): Boolean {
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        return manufacturer in setOf(
            "xiaomi", "redmi", "huawei", "honor", "oppo", "realme",
            "vivo", "oneplus", "meizu", "lenovo", "zuk", "nubia",
            "smartisan", "360", "letv", "tecno", "itel", "infinix"
        )
    }

    /**
     * 判断包名是否是已知的 TTS 引擎包名。
     * 主要用于判断 Settings.Secure.TTS_DEFAULT_SYNTH 指向的包是否是"真的 TTS 引擎"。
     */
    fun isKnownTtsEnginePackage(packageName: String): Boolean {
        return packageName == "com.google.android.tts" ||
            packageName in KNOWN_CHINESE_ENGINE_PACKAGES
    }

    /**
     * 根据引擎包名返回友好的显示名（用于 UI）。
     */
    fun friendlyEngineName(packageName: String): String {
        return when (packageName) {
            "com.google.android.tts" -> "Google 文字转语音"
            else -> getEngineDisplayInfo(packageName).second
        }
    }

    /**
     * 获取适合国产手机的友好提示信息。
     */
    fun getFriendlyHint(context: Context): String {
        val manufacturer = android.os.Build.MANUFACTURER
        val brand = android.os.Build.BRAND
        return when {
            manufacturer.startsWith("Xiaomi", ignoreCase = true) || brand.startsWith("Redmi", ignoreCase = true) ->
                "检测到小米/Redmi 设备。请在「设置 → 更多设置 → 无障碍 → 文本转语音(TTS)」中启用「小爱语音」或「Google 文字转语音」。"
            manufacturer.startsWith("Huawei", ignoreCase = true) || brand.startsWith("Honor", ignoreCase = true) ->
                "检测到华为/荣耀设备。请在「设置 → 辅助功能 → 文本转语音(TTS)输出」中启用「华为语音引擎」。"
            manufacturer.startsWith("OPPO", ignoreCase = true) || brand.startsWith("realme", ignoreCase = true) ->
                "检测到 OPPO/realme 设备。请在「设置 → 系统设置 → 无障碍 → 文本转语音(TTS)」中启用 TTS 引擎。"
            manufacturer.startsWith("vivo", ignoreCase = true) ->
                "检测到 vivo 设备。请在「设置 → 快捷与辅助 → 无障碍 → 文本转语音(TTS)」中启用 TTS 引擎。"
            else ->
                "未检测到可用的 TTS 引擎。请前往系统设置 → 无障碍 → 文本转语音(TTS)输出，选择并启用一个语音引擎。"
        }
    }
}