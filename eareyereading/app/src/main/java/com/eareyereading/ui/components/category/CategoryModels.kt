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

/**
 * 预制分类（书库「分类」开箱可用，SPEC §4.9 扩展）
 *
 * 设计判断：分类的图标/颜色**显式指定**而非走 name hash 派生——
 * 预制分类是给用户"一眼认出"的语义标签，必须稳定且符合直觉
 * （英语学习=书本、口语听力=星标…），不能听凭 hash 撞色撞图标。
 *
 * 消费方两处：
 * 1. [CategoryEditSheet] 新建分类顶部快选：一键填充「名称 + 图标 + 颜色」
 * 2. [CategoryManageSheet] 预制分类区：一键把常用分类收进书库
 */
val PresetCategories: List<Category> = listOf(
    Category("英语学习", icon = CategoryIcon.BOOK, color = CategoryPalette[0]),
    Category("单词词汇", icon = CategoryIcon.NOTE, color = CategoryPalette[2]),
    Category("语法精读", icon = CategoryIcon.COMPASS, color = CategoryPalette[3]),
    Category("口语听力", icon = CategoryIcon.STAR, color = CategoryPalette[4]),
    Category("文学小说", icon = CategoryIcon.HEART, color = CategoryPalette[5]),
    Category("经典名著", icon = CategoryIcon.CROWN, color = CategoryPalette[1]),
    Category("商业思维", icon = CategoryIcon.BOLT, color = CategoryPalette[6]),
    Category("科技前沿", icon = CategoryIcon.GLOBE, color = CategoryPalette[7]),
    Category("科学认知", icon = CategoryIcon.LEAF, color = CategoryPalette[8]),
    Category("考试备考", icon = CategoryIcon.TROPHY, color = CategoryPalette[9]),
    Category("休闲阅读", icon = CategoryIcon.COFFEE, color = CategoryPalette[4]),
    Category("新闻时政", icon = CategoryIcon.FLAME, color = CategoryPalette[5]),
)
