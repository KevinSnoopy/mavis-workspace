package com.eareyereading.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.Primary
import com.eareyereading.ui.theme.SurfaceHover

/**
 * 设置页「阅读与词典」分区。
 *
 * 从 [SettingsScreen] 按 SRP 抽出：阅读偏好入口（已收敛到阅读页内）与
 * 词典管理跳转。本分区只负责展示与导航回调，不持有状态。
 */
@Composable
internal fun SettingsReadingSection(
    onNavigateToDictionaryManager: () -> Unit,
) {
    // 字号/RSVP 速度/阅读主题/衬线字体等阅读设置已全部收敛到阅读页内
    //（顶栏"更多 → 设置"弹窗 + 底栏快捷设置），本页不再提供重复入口——
    // 两处入口并存时，设置页改的是"默认值"，阅读页改的是"当前值"，
    // 互相覆盖容易让用户困惑"为什么设置了不生效"
    SettingsSectionTitle("阅读与词典")
    SettingsListCard {
        SettingRow(
            icon = Icons.Default.MenuBook,
            iconBg = SurfaceHover,
            iconColor = Primary,
            title = "阅读偏好",
            subtitle = "字号、阅读主题、衬线字体、翻译显示等",
        ) {
            Text(
                text = "已移至阅读页内：进入任意书籍，点击正文唤出菜单 → 设置",
                style = MaterialTheme.typography.bodySmall,
                color = Primary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Divider(modifier = Modifier.padding(horizontal = 20.dp))

        SettingRowClickable(
            icon = Icons.Default.LibraryBooks,
            iconBg = SurfaceHover,
            iconColor = Primary,
            title = "词典管理",
            subtitle = "下载分级词典（四级/六级/考研/托福/GRE/雅思）",
            onClick = onNavigateToDictionaryManager,
        )
    }
    Spacer(modifier = Modifier.height(20.dp))
}
