package com.eareyereading.ui.components.category

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.eareyereading.ui.theme.CategoryPalette

/**
 * 分类自定义的数据模型与预设（SPEC §4.9）
 *
 * 设计判断：把「图标 + 颜色」做成两套预设枚举，
 * 调用方只持有 name + CategoryIcon + Color 三元组。
 * 元数据（用户自定义的图标/颜色）由 CategoryPrefs（DataStore）持久化，
 * 无元数据的分类按 name hash 稳定派生默认值。
 */

/** 用户可选的 12 个预设分类图标（SPEC §4.9.3） */
enum class CategoryIcon(val imageVector: ImageVector) {
    BOOK(Icons.Default.AutoStories),
    STAR(Icons.Default.Star),
    HEART(Icons.Default.Favorite),
    BOLT(Icons.Default.Bolt),
    CROWN(Icons.Default.Workspaces),
    GLOBE(Icons.Default.Public),
    NOTE(Icons.Default.NoteAlt),
    TROPHY(Icons.Default.Star),         // 用 Star 兜底（M3 无 Crown/Trophy，扩展包有；保持轻量）
    COFFEE(Icons.Default.Coffee),
    COMPASS(Icons.Default.Explore),
    FLAME(Icons.Default.LocalFireDepartment),
    LEAF(Icons.Default.Park);

    companion object {
        /** 按 name 稳定派生图标：同分类名永远拿到同图标 */
        fun forName(name: String): CategoryIcon {
            val idx = stableHash(name, values().size)
            return values()[idx]
        }
    }
}

/** 稳定字符串哈希：同输入永远同输出，跨进程/重启一致（勿用 String.hashCode 负值场景） */
private fun stableHash(input: String, mod: Int): Int {
    var acc = 0
    input.lowercase().forEach { acc = (acc * 31 + it.code) % mod }
    return (acc + mod) % mod
}

/** 无元数据时按名称派生稳定颜色 */
fun derivedColorFor(name: String): Color =
    CategoryPalette[stableHash(name, CategoryPalette.size)]

/** 无元数据时按名称派生稳定图标 */
fun derivedIconFor(name: String): CategoryIcon = CategoryIcon.forName(name)

/**
 * 分类数据载体（UI 层）
 *
 * name 来自 Book.category（String，派生）或用户自建（customCategories）；
 * icon/color 来自 CategoryPrefs 元数据，缺省时按 name hash 派生。
 */
data class Category(
    val name: String,
    val bookCount: Int = 0,
    val icon: CategoryIcon = derivedIconFor(name),
    val color: Color = derivedColorFor(name),
)

/** 默认预设分类（无书籍时的冷启动演示） */
val DefaultCategories = listOf(
    Category("英语学习", 8),
    Category("文学小说", 6),
    Category("商业思维", 5),
    Category("科学认知", 3),
    Category("科技前沿", 2),
)
