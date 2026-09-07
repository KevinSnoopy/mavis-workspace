package com.eareyereading.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.Error
import com.eareyereading.ui.theme.ErrorBg
import com.eareyereading.ui.theme.OnSurfaceTertiary
import com.eareyereading.ui.theme.Primary
import com.eareyereading.ui.theme.SurfaceHover
import java.util.Locale

/**
 * 设置页「数据」分区：导出/导入/清除缓存。
 *
 * 从 [SettingsScreen] 按 SRP 抽出：本分区只负责数据管理展示与回调，
 * 状态由 [SettingsViewModel] 统一管理。
 */
@Composable
internal fun SettingsDataSection(
    isExporting: Boolean,
    isImporting: Boolean,
    isClearing: Boolean,
    cacheSizeMb: Double,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onClearCache: () -> Unit,
) {
    SettingsSectionTitle("数据")
    SettingsListCard {
        SettingRowClickable(
            icon = Icons.Default.Download,
            iconBg = SurfaceHover,
            iconColor = Primary,
            title = "导出数据",
            subtitle = if (isExporting) "导出中..." else "导出词汇和阅读数据",
            onClick = onExport,
        )
        Divider(modifier = Modifier.padding(horizontal = 20.dp))
        SettingRowClickable(
            icon = Icons.Default.Upload,
            iconBg = SurfaceHover,
            iconColor = Primary,
            title = "导入数据",
            subtitle = if (isImporting) "导入中..." else "从备份文件导入词汇",
            onClick = onImport,
        )
        Divider(modifier = Modifier.padding(horizontal = 20.dp))
        SettingRowClickable(
            icon = Icons.Default.Delete,
            iconBg = SurfaceHover,
            iconColor = OnSurfaceTertiary,
            title = "清除缓存",
            subtitle = if (isClearing) "清除中..." else {
                String.format(Locale.getDefault(), "%.1f MB", cacheSizeMb)
            },
            onClick = onClearCache,
        )
    }
    Spacer(modifier = Modifier.height(20.dp))
}

/**
 * 设置页「危险区域」分区：恢复默认设置。
 *
 * 从 [SettingsScreen] 按 SRP 抽出。
 */
@Composable
internal fun SettingsDangerZoneSection(
    onResetToDefaults: () -> Unit,
) {
    SettingsSectionTitle("危险区域")
    SettingsListCard {
        SettingRowClickable(
            icon = Icons.Default.Refresh,
            iconBg = ErrorBg,
            iconColor = Error,
            title = "恢复默认设置",
            subtitle = "清除所有设置（不影响数据）",
            titleColor = Error,
            onClick = onResetToDefaults,
        )
    }
    Spacer(modifier = Modifier.height(20.dp))
}

/**
 * 设置页页脚版本号。
 *
 * 从 [SettingsScreen] 按 SRP 抽出。
 */
@Composable
internal fun SettingsVersionFooter(versionName: String) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "听阅 EareyeReading · v$versionName",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 16.dp),
        )
    }
    Spacer(modifier = Modifier.height(40.dp))
}
