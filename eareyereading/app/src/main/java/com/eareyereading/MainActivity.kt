package com.eareyereading

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.eareyereading.domain.repository.SettingsRepository
import com.eareyereading.ui.AppNavigation
import com.eareyereading.ui.Screen
import com.eareyereading.ui.navigateToTopLevel
import com.eareyereading.ui.screens.library.LibraryViewModel
import com.eareyereading.ui.screens.onboarding.FirstLaunchOnboarding
import com.eareyereading.ui.theme.EareyeReadingTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var translationHelper: com.eareyereading.util.TranslationHelper

    /** issue 9.10：待跳转书库的外部导入 URI（Compose state，onCreate/onNewIntent 均可驱动导航）。 */
    private var pendingViewUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // issue 9.10：系统"打开方式"选 .epub（ACTION_VIEW）冷启动进入
        handleViewIntent(intent)

        setContent {
            // Flow 实例 remember：getter 每次调用都新建 map 链上的冷 Flow，
            // 顶层作用域任何重组（onboarding 消失/ACTION_VIEW 深链）都会
            // 取消旧收集、重新订阅 DataStore。记住实例后 collectAsState 稳定复用
            val themeFlow = remember { settingsRepository.getTheme() }
            val theme by themeFlow.collectAsState(initial = com.eareyereading.domain.model.ReadingTheme.LIGHT)
            val dynamicColorFlow = remember { settingsRepository.getDynamicColor() }
            val dynamicColor by dynamicColorFlow.collectAsState(initial = false)
            val navController = rememberNavController()

            // issue 5.1：首次启动展示轻量通知引导页（SharedPreferences 记一次，简单不引入 DataStore）
            val prefs = remember { getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE) }
            var showOnboarding by remember {
                mutableStateOf(!prefs.getBoolean("has_seen_onboarding", false))
            }
            val finishOnboarding = {
                prefs.edit().putBoolean("has_seen_onboarding", true).apply()
                showOnboarding = false
            }

            // 收到外部 EPub 后跳到书库展示导入进度（冷启动时 pendingViewUri 已在
            // 首次组合前由 onCreate 写值，onNewIntent 时靠 state 触达重组）
            LaunchedEffect(pendingViewUri) {
                if (pendingViewUri != null) {
                    pendingViewUri = null
                    navController.navigateToTopLevel(Screen.Library.route)
                }
            }

            // 全局深色跟随系统（Android 14+ 用户预期）；阅读主题（LIGHT/DARK/SEPIA）
            // 继续决定阅读页内的纸面配色。动态取色（Material You）可在设置中开启。
            EareyeReadingTheme(
                readingTheme = theme,
                darkTheme = androidx.compose.foundation.isSystemInDarkTheme(),
                dynamicColor = dynamicColor,
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // issue 5.1：首启引导页（单次）在上层，之后进主界面
                    if (showOnboarding) {
                        FirstLaunchOnboarding(onDone = finishOnboarding)
                    } else {
                        AppNavigation(navController = navController)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // issue 9.10：运行中再次选择"打开方式"（singleTop 复用本 Activity）
        handleViewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 桌面小组件数据刷新：回到前台时拉齐最新待复习数/打卡天数
        com.eareyereading.receiver.ReviewWidgetProvider.triggerUpdate(this)
    }

    /** 处理 ACTION_VIEW 的 content:// EPUB URI：持久化读权限 + 投递给书库导入流程。 */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        // 系统"打开方式"随 intent 授予的是临时读权限；尽量持久化，
        // 失败（如某些 provider 不支持）仅告警，当前会话临时授权仍够用
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "takePersistableUriPermission failed", e)
        }
        LibraryViewModel.requestImport(uri)
        pendingViewUri = uri
    }

    @Deprecated("Deprecated in Java")
    override fun onDestroy() {
        // issue 8.2：兜底释放 ML Kit Translator（App.onTerminate 真机很少回调）
        if (isFinishing) {
            try {
                translationHelper.close()
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "close translationHelper failed", e)
            }
        }
        super.onDestroy()
    }
}
