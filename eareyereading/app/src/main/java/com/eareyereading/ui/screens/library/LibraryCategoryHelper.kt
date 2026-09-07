package com.eareyereading.ui.screens.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.eareyereading.data.repository.CategoryPrefs
import com.eareyereading.domain.model.Book
import com.eareyereading.ui.components.category.Category
import com.eareyereading.ui.components.category.CategoryIcon
import com.eareyereading.ui.components.category.derivedColorFor
import com.eareyereading.ui.components.category.derivedIconFor

/**
 * 分类合成工具：将派生分类（来自书籍 category 字段）与用户自建分类
 * （meta 有但书无）合并为统一的 [Category] 列表，并按拖动排序元数据排列。
 */
internal object LibraryCategoryHelper {

    /**
     * 合成全部分类列表。
     *
     * 图标/颜色取用户元数据，缺省按名称派生稳定默认（同分类永远同色同图标）。
     * 排序规则：有 order 元数据的按 order 升序；无元数据的排最后按名称稳定排列。
     */
    fun mergeCategories(
        derivedCategories: List<String>,
        customCategories: List<String>,
        categoryMeta: Map<String, CategoryPrefs.Meta>,
        books: List<Book>,
    ): List<Category> {
        val custom = customCategories.filter { it !in derivedCategories }
        return (derivedCategories + custom).map { name ->
            val meta = categoryMeta[name]
            Category(
                name = name,
                bookCount = books.count { it.category == name },
                icon = meta?.let { m ->
                    runCatching { CategoryIcon.valueOf(m.icon) }.getOrDefault(derivedIconFor(name))
                } ?: derivedIconFor(name),
                color = meta?.let { m -> Color(m.color) } ?: derivedColorFor(name),
            )
        }.sortedWith(
            compareBy(
                { categoryMeta[it.name]?.order ?: Int.MAX_VALUE },
                { it.name },
            ),
        )
    }

    /** 分类名 → 书籍数（「移至分类」对话框卡片上显示计数用）。 */
    fun bookCountByName(books: List<Book>): Map<String, Int> =
        books.groupingBy { it.category.ifBlank { "未分类" } }.eachCount()
}

/**
 * remember 包装的分类合成，避免调用方直接写 remember key 列表。
 */
@Composable
internal fun rememberMergedCategories(
    derivedCategories: List<String>,
    customCategories: List<String>,
    categoryMeta: Map<String, CategoryPrefs.Meta>,
    books: List<Book>,
): List<Category> = remember(derivedCategories, customCategories, categoryMeta, books) {
    LibraryCategoryHelper.mergeCategories(derivedCategories, customCategories, categoryMeta, books)
}

/**
 * remember 包装的书籍计数映射。
 */
@Composable
internal fun rememberBookCountByName(books: List<Book>): Map<String, Int> =
    remember(books) { LibraryCategoryHelper.bookCountByName(books) }
