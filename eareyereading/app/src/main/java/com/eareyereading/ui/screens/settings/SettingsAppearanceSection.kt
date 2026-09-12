package com.eareyereading.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.Accent
import com.eareyereading.ui.theme.SurfaceHover

/**
 * 设置页「外观」分区：Material You 动态取色开关。
 *
 * 从 [SettingsScreen] 按 SRP 抽出：本分区只负责外观偏好展示与开关回调，
 * 不持有状态，状态由 [SettingsViewModel] 统一管理。
 */
@Composable
internal fun SettingsAppearanceSection(
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
) {
    SettingsSectionTitle("外观")
    SettingsListCard {
        SettingRowToggle(
            icon = Icons.Default.Palette,
            iconBg = SurfaceHover,
            iconColor = Accent,
            title = "动态取色",
            subtitle = "跟随系统主题色",
            checked = dynamicColor,
            onCheckedChange = onDynamicColorChange,
        )
    }
    Spacer(modifier = Modifier.height(20.dp))
}
