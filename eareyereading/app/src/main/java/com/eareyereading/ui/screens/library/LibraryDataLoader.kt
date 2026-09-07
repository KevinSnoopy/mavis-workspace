@file:Suppress("SwallowedException", "TooGenericExceptionCaught")

package com.eareyereading.ui.screens.library

import android.content.Context
import com.eareyereading.data.repository.CategoryPrefs
import com.eareyereading.domain.model.Book
import com.eareyereading.domain.model.ClassicBooks
import com.eareyereading.domain.repository.BookRepository
import com.eareyereading.domain.repository.VocabularyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/**
 * 书库数据加载与搜索筛选的单一职责控制器。
 *
 * 组合 searchQuery / books / 词汇统计 / 分类元数据 五路 Flow，
 * 衍生筛选后书列表、分类列表、自定义分类、已拥有经典书集合等派生状态，
 * 写入 [LibraryStateController]。搜索查询亦由此控制器持有。
 */
internal class LibraryDataLoader(
    private val stateController: LibraryStateController,
    private val bookRepository: BookRepository,
    private val vocabularyRepository: VocabularyRepository,
    private val categoryPrefs: CategoryPrefs,
    private val context: Context,
) {

    private val searchQuery = MutableStateFlow("")

    /** 搜索查询变更入口 */
    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    /** 启动书库数据组合流收集 */
    fun startCollection(scope: CoroutineScope) {
        scope.launch {
            try {
                combine(
                    searchQuery,
                    bookRepository.getAllBooks(),
                    vocabularyRepository.getTotalCount(),
                    vocabularyRepository.getLearnedCount(),
                    categoryPrefs.metaFlow,
                ) { query, books, total, learned, catMeta ->
                    val classicDir = File(context.filesDir, "books/classics").absolutePath
                    val ownedClassics = ClassicBooks.list
                        .filter { c -> books.any { it.filePath.startsWith("$classicDir/${c.id}.") } }
                        .map { it.id }
                        .toSet()
                    // 分类列表：按首现顺序去重，"未分类"固定垫底
                    val cats = books.map { it.category.ifBlank { "未分类" } }
                        .distinct()
                        .sortedBy { it == "未分类" }
                    // v2：用户自建但还没有书挂上的分类（meta 存在即分类存在）
                    val customCats = catMeta.keys.filter { it !in cats }.sorted()
                    stateController.current.copy(
                        books = filterBooks(query, books),
                        searchQuery = query,
                        totalWordCount = total,
                        learnedWordCount = learned,
                        ownedClassicIds = ownedClassics,
                        categories = cats,
                        categoryMeta = catMeta,
                        customCategories = customCats,
                    )
                }.collect { state ->
                    stateController.update { it.copy(
                        books = state.books,
                        searchQuery = state.searchQuery,
                        totalWordCount = state.totalWordCount,
                        learnedWordCount = state.learnedWordCount,
                        ownedClassicIds = state.ownedClassicIds,
                        categories = state.categories,
                        categoryMeta = state.categoryMeta,
                        customCategories = state.customCategories,
                    ) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 数据层异常不再让整个 App 崩溃：书库保持空态可用
                android.util.Log.e("LibraryViewModel", "library combine failed", e)
            }
        }
    }

    /** 按标题/作者模糊筛选（大小写不敏感） */
    private fun filterBooks(query: String, books: List<Book>): List<Book> {
        return if (query.isBlank()) books
        else books.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.author.contains(query, ignoreCase = true)
        }
    }
}
