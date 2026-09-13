@file:Suppress("TooGenericExceptionCaught")

package com.eareyereading.ui.screens.reader

import androidx.lifecycle.viewModelScope
import com.eareyereading.domain.model.*
import com.eareyereading.domain.repository.*
import com.eareyereading.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 设置域：字号/主题/字体/翻页方式/RSVP 参数/翻译透明度/词频色彩等
 * 阅读设置的 UI 状态更新与防抖持久化。
 *
 * 滑杆逐像素写 DataStore 的防抖：UI 状态立即更新保证滑杆跟手，
 * 持久化合并到拖停后一次（与 saveProgress 同型）。按设置项分 key，
 * 一个滑杆的拖动不会取消另一项的待写；退出时由 cleanup() 兜底冲刷。
 */
internal class ReaderViewModelSettings(
    private val vm: ReaderViewModel,
    private val settingsRepository: SettingsRepository,
) {
    private val settingsPersistJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val settingsPendingWrites = mutableMapOf<String, suspend () -> Unit>()

    fun persistSettingDebounced(key: String, write: suspend () -> Unit) {
        settingsPersistJobs[key]?.cancel()
        settingsPendingWrites[key] = write
        settingsPersistJobs[key] = vm.viewModelScope.launch {
            delay(SETTINGS_PERSIST_DEBOUNCE_MS)
            write()
            // 按身份移除：只清自己这条，不误删并发排队的同名写入
            if (settingsPendingWrites[key] === write) {
                settingsPendingWrites.remove(key)
            }
        }
    }

    /** cleanup 冲刷防抖窗口内未落盘的设置写入。 */
    fun flushPendingWrites(): List<suspend () -> Unit> {
        settingsPersistJobs.values.forEach { it.cancel() }
        val pending = settingsPendingWrites.values.toList()
        settingsPendingWrites.clear()
        return pending
    }

    fun setFontSize(size: Int) {
        // 持久化收敛后的值：原实现 UI 显示收敛值、存储原始值，
        // 下次启动设置流回填时越界值会重新进入 UI。
        // 写库走防抖：滑杆拖动逐像素回调不再逐像素写 DataStore
        val coerced = size.coerceIn(12, 32)
        vm._uiState.update { it.copy(fontSize = coerced) }
        persistSettingDebounced("fontSize") { settingsRepository.setFontSize(coerced) }
    }

    /** 底部栏快捷字号调节（A- / A+ 按钮）：±1sp 步进，复用 setFontSize 的收敛与防抖。 */
    fun adjustFontSize(delta: Int) {
        setFontSize(vm._uiState.value.fontSize + delta)
    }

    /**
     * 阅读主题循环切换（明亮 → 护眼 → 暗黑 → 明亮），供底部栏快捷胶囊使用。
     * 主题本身是全局设置：写 DataStore 后设置流会回填 uiState.theme。
     */
    fun cycleReadingTheme() {
        val next = when (vm._uiState.value.theme) {
            ReadingTheme.LIGHT -> ReadingTheme.SEPIA
            ReadingTheme.SEPIA -> ReadingTheme.DARK
            ReadingTheme.DARK -> ReadingTheme.LIGHT
        }
        vm._uiState.update { it.copy(theme = next) }
        vm.viewModelScope.launch {
            try {
                settingsRepository.setTheme(next)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "setTheme failed", e)
            }
        }
    }

    /** 衬线字体切换（阅读器正文字体，全局设置持久化）。 */
    fun toggleSerifFont() {
        val next = !vm._uiState.value.serifFont
        vm._uiState.update { it.copy(serifFont = next) }
        vm.viewModelScope.launch {
            try {
                settingsRepository.setSerifFont(next)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "setSerifFont failed", e)
            }
        }
    }

    /** 阅读方式切换：上下滚动 ⇄ 左右翻页（仿书页，全局设置持久化）。 */
    fun togglePageMode() {
        val next = !vm._uiState.value.pageMode
        vm._uiState.update { it.copy(pageMode = next) }
        vm.viewModelScope.launch {
            try {
                settingsRepository.setReadingPageMode(next)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "setReadingPageMode failed", e)
            }
        }
    }

    fun setRsvpSpeed(speed: Int) {
        val coerced = speed.coerceIn(100, 800)
        vm._uiState.update { it.copy(rsvpSpeed = coerced) }
        val bookId = vm.currentBookId
        persistSettingDebounced("rsvpSpeed") {
            settingsRepository.setRsvpSpeed(coerced)
            bookId?.let { vm.readingRepository.updateRsvpSpeed(it, coerced) }
        }
    }

    fun setRsvpStrength(strength: Int) {
        val coerced = strength.coerceIn(1, 5)
        vm._uiState.update { it.copy(rsvpStrength = coerced) }
        persistSettingDebounced("rsvpStrength") { settingsRepository.setRsvpStrength(coerced) }
    }

    fun setTranslationAlpha(alpha: Float) {
        val coerced = alpha.coerceIn(TRANSLATION_ALPHA_MIN, TRANSLATION_ALPHA_MAX)
        vm._uiState.update { it.copy(translationAlpha = coerced) }
        persistSettingDebounced("translationAlpha") { settingsRepository.setTranslationAlpha(coerced) }
    }

    fun toggleWordLevelColors() {
        // 持久化到 DataStore（复用 COLLINS_HIGHLIGHT），再次进入阅读详情页时由 init 的
        // settings combine 恢复，不再每次默认退回关闭
        val newValue = !vm._uiState.value.showWordLevelColors
        vm._uiState.update { it.copy(showWordLevelColors = newValue) }
        vm.viewModelScope.launch { settingsRepository.setCollinsHighlight(newValue) }
    }

    fun toggleKnownWordsHighlight() {
        // 与 Collins 词频色同理：持久化到 DataStore，重进阅读页由 init 的
        // settings combine 恢复；此前只改内存 uiState，退出即回默认开
        val newValue = !vm._uiState.value.showKnownWordsHighlight
        vm._uiState.update { it.copy(showKnownWordsHighlight = newValue) }
        vm.viewModelScope.launch {
            try {
                settingsRepository.setKnownWordsHighlight(newValue)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "setKnownWordsHighlight failed", e)
            }
        }
    }

    fun toggleChapterNav() {
        vm._uiState.update { it.copy(showChapterNav = !it.showChapterNav) }
    }

    /** 当前模式说明（顶栏溢出菜单）。开与关都由同一个入口驱动。 */
    fun toggleModeHelp() {
        // 打开说明时收起模式选择器：两个抽屉叠着会让用户不知道该看哪个
        vm._uiState.update {
            it.copy(showModeHelp = !it.showModeHelp, showModeSelector = false)
        }
    }

    fun dismissModeSelector() {
        vm._uiState.update { it.copy(showModeSelector = false) }
    }

    fun showModeSelector() {
        // 与 toggleModeHelp 对称：两个抽屉互斥，避免叠在一起
        vm._uiState.update { it.copy(showModeSelector = true, showModeHelp = false) }
    }

    fun toggleSettings() {
        vm._uiState.update { it.copy(showSettings = !it.showSettings) }
    }

    companion object {
        private const val SETTINGS_PERSIST_DEBOUNCE_MS = 300L
        private const val TRANSLATION_ALPHA_MIN = 0.3f
        private const val TRANSLATION_ALPHA_MAX = 1f
    }
}
