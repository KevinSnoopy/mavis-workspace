package com.eareyereading

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.eareyereading.domain.repository.SettingsRepository
import com.eareyereading.ui.AppNavigation
import com.eareyereading.ui.theme.EareyeReadingTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var translationHelper: com.eareyereading.util.TranslationHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val theme by settingsRepository.getTheme().collectAsState(initial = com.eareyereading.domain.model.ReadingTheme.LIGHT)
            val darkMode by settingsRepository.getDarkMode().collectAsState(initial = false)

            EareyeReadingTheme(readingTheme = theme, darkTheme = darkMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation()
                }
            }
        }
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
