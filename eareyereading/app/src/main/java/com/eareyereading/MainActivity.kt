package com.eareyereading

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.eareyereading.domain.repository.SettingsRepository
import com.eareyereading.ui.AppNavigation
import com.eareyereading.ui.Screen
import com.eareyereading.ui.navigateToTopLevel
import com.eareyereading.ui.screens.library.LibraryViewModel
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
            val theme by settingsRepository.getTheme().collectAsState(initial = com.eareyereading.domain.model.ReadingTheme.LIGHT)
            val darkMode by settingsRepository.getDarkMode().collectAsState(initial = false)
            val navController = rememberNavController()

            // 收到外部 EPub 后跳到书库展示导入进度（冷启动时 pendingViewUri 已在
            // 首次组合前由 onCreate 写值，onNewIntent 时靠 state 触达重组）
            LaunchedEffect(pendingViewUri) {
                if (pendingViewUri != null) {
                    pendingViewUri = null
                    navController.navigateToTopLevel(Screen.Library.route)
                }
            }

            EareyeReadingTheme(readingTheme = theme, darkTheme = darkMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation(navController = navController)
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
