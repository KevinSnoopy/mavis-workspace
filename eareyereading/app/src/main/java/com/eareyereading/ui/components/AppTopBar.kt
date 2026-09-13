package com.eareyereading.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

/**
 * 二级页面通用顶部栏：大号加粗标题 + 可选返回按钮 + 背景色跟随主题。
 *
 * ── 重构说明（DRY）──
 * 复习 / 设置 / 词汇本 / 词典管理 四个页面此前各自内联一份结构完全相同的
 * TopAppBar（标题样式、返回按钮、containerColor 三处逐行一致），仅在
 * actions 槽位不同。标题字号或返回图标一旦调整，很容易只改到其中一两个页面。
 * 现统一走本组件，各页只声明自己的标题与动作。
 *
 * ── 返回按钮（修复）──
 * [onBack] 为 null 表示**根页面**（底部导航的 5 个一级 Tab）：不渲染返回箭头。
 * 此前复习 / 设置 / 词汇本三个一级 Tab 也能拿到 onBack 并渲染箭头，但它们
 * 是导航图的顶层目的地，点箭头弹栈等于"从 Tab 返回上一个 Tab"，
 * 与底部导航栏的语义（平行切换、不堆栈）自相矛盾。
 * 真正的二级页（词典管理）传 onBack 即显示箭头。
 *
 * @param title 页面标题
 * @param onBack 返回回调；null = 根页面，不显示返回按钮
 * @param actions 右侧动作槽位，默认无动作
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(
                title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "返回")
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
        actions = actions,
    )
}
