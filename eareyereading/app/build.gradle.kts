import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("io.gitlab.arturbosch.detekt")
}

android {
    namespace = "com.eareyereading"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.eareyereading"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // 仅打包主流 ABI，减小 APK 体积（sherpa-onnx 包含多个 .so 文件）
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // issue 12.3：词典 manifest 地址通过 BuildConfig 注入，
        // 不再硬编码作者私有仓库路径（jsDelivr 对私有仓返回 404，刷新必败）。
        // 锁 tag@eareyereading 而非 branch，避免弱指针型漂移。
        buildConfigField(
            "String",
            "DICTIONARY_MANIFEST_URL",
            "\"https://cdn.jsdelivr.net/gh/KevinSnoopy/mavis-workspace@eareyereading/eareyereading/scripts/out/dictionaries/manifest.json\"",
        )
    }

    // 按 ABI 拆分 APK：每个架构独立 APK，减少用户下载体积
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true  // 保留 universal apk 兜底
        }
    }

    // ── release 正式签名（上架商店必备）──
    // 凭据来源优先级：keystore/signing.properties（本地，gitignore）> 环境变量 > 不签名。
    // 未配置时 release 构建退回 unsigned，CI/他人克隆不会因缺签名文件而挂掉。
    signingConfigs {
        create("release") {
            val propFile = rootProject.file("keystore/signing.properties")
            if (propFile.exists()) {
                val props = Properties().apply {
                    propFile.inputStream().use { load(it) }
                }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            } else if (System.getenv("RELEASE_STORE_FILE") != null) {
                storeFile = rootProject.file(System.getenv("RELEASE_STORE_FILE"))
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 裁剪 + 资源缩减：未用到的类/方法/资源全部裁掉。
            // 此前 minify=false 使 proguard-rules.pro 整个文件是死配置，
            // material-icons-extended（2 万+ 图标类）等大体积依赖全量进包
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 仅在凭据齐全时挂签名（storeFile 为 null 说明本地/CI 无签名配置）
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile?.exists() == true) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // ── Compose 强跳过模式（strong skipping）──
        // 背景（2026-09-13 编译器报告实测）：ReaderParagraphBlock / ReaderSliceParagraphBlock /
        // NormalReadingView / SplitReadingView / PagedReadingView / ReaderContentDispatcher
        // 全部是 restartable 但**不可跳过**——因为参数含 List/Set/Map 等不稳定类型，
        // 导致自动朗读（每句 currentSentenceIndex 变化）、全文翻译（译文 map 持续增长）、
        // 模型下载进度回调 都会让视口内所有段落一起重组。
        //
        // 强跳过把不稳定参数的比较从"不可跳过"改为"实例相等（===）即跳过"，
        // 并自动 remember 组件内的 lambda。前提：状态对象不得就地修改——
        // 本工程全部走 `_uiState.update { it.copy(...) }` + 新建集合，满足前提。
        //
        // 验证方式：重新生成 app/build/compose-reports 确认上述组件变为 skippable，
        // 并在真机跑 朗读 / 全文翻译 / 模型下载 三条路径确认 UI 正常刷新。
        freeCompilerArgs += listOf(
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:experimentalStrongSkipping=true",
        )
        // Compose 编译器报告：输出每个 @Composable 的可跳过性（skippable / restartable），
        // 落到 app/build/compose-reports/。用于性能审计时定位"参数不稳定导致整棵子树重组"。
        freeCompilerArgs += listOf(
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=" +
                project.layout.buildDirectory.dir("compose-reports").get().asFile.absolutePath,
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // sherpa-onnx 的 .so 文件本身不带调试符号，无需 strip
        jniLibs {
            useLegacyPackaging = false
        }
    }

    testOptions {
        // RssParser 等工具类调用 android.util.Log；JVM 单测需要返回默认值而不是抛 "not mocked"
        unitTests.isReturnDefaultValues = true
    }
}

// Room schema 导出：配合 AppDatabase.exportSchema = true，
// 每个版本的表结构落到 app/schemas/ 并随仓库提交，供迁移链静态比对
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.5")

    // Coil：书籍封面图加载（EPUB 内嵌封面渲染）
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.48.1")
    ksp("com.google.dagger:hilt-android-compiler:2.48.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // SplashScreen (Android 12+)
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Apache Commons Compress：用于解压 sherpa-onnx 模型 tarball（.tar.bz2）
    implementation("org.apache.commons:commons-compress:1.26.1")

    // ML Kit Translate (on-device, free, offline)
    implementation("com.google.mlkit:translate:17.0.2")

    // sherpa-onnx: 自包含离线 TTS（用于在 MIUI 等无法用系统 TTS 的设备上提供兜底）
    // 不依赖系统 TTS 服务，模型直接打包在 app 内
    //
    // 集成方式：sherpa-onnx 不发布 Maven/JitPack AAR。我们采用官方推荐方式：
    //   1. Kotlin 源码（com.k2fsa.sherpa.onnx.Tts 等）直接拷贝到 app/src/main/java 下
    //   2. 预编译 .so（libsherpa-onnx-jni.so + libonnxruntime.so）放到 app/src/main/jniLibs
    //   3. .so 来自 https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.7/sherpa-onnx-v1.13.7-android.tar.bz2
    //      （v1.13.7 起支持 Kokoro 多音色模型：OfflineTtsKokoroModelConfig + generate(sid)）
    // 升级时更新脚本版本号并重新下载 .so + 源码。详见 scripts/download-sherpa-onnx.sh

    // Testing
    testImplementation("junit:junit:4.13.2")
    // XmlPullParser 实现：与 Android 的 KXmlParser 同源，保证单测解析行为一致
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("xmlpull:xmlpull:1.1.3.1")
    // org.json 实现：android.jar 在 JVM 单测里是桩（返回默认值不真解析），
    // LlmTranslator 的请求体构造/响应解析需要真 JSON
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

detekt {
    toolVersion = "1.23.4"
    // Round 5 灰度：改为叠加官方默认规则集，detekt.yml 作为覆盖层
    // （style/complexity/performance/naming 仍按 ruleset 级关闭，
    // potential-bugs/coroutines/exceptions/empty-blocks/comments 生效）。
    // 此前 =false 时全仓实际只跑 2 条规则
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(file("$rootDir/detekt.yml"))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    // 不再 ignoreFailures：detekt.yml 里 build.maxIssues: 0 必须真正生效，
    // 否则 CI 唯一的静态门禁永远不会失败（此前所有 finding 都被静默放过）
    reports {
        html.required.set(true)
        html.outputLocation.set(file("$projectDir/build/reports/detekt.html"))
        sarif.required.set(true)
        sarif.outputLocation.set(file("$projectDir/build/reports/detekt.sarif"))
    }
}
