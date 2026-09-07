@file:Suppress("SwallowedException", "TooGenericExceptionCaught")

package com.eareyereading.ui.screens.library

import com.eareyereading.domain.model.ArticleSource
import com.eareyereading.domain.repository.BookRepository
import com.eareyereading.util.ArticleParser
import com.eareyereading.util.ArticleResult
import com.eareyereading.util.BookImages
import com.eareyereading.util.RssParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文章广场的单一职责控制器，涵盖：
 *
 * - Tab 切换（[setTab]，切回书库时触发统计刷新回调）
 * - 文章源选择与 RSS/非 RSS 抓取（[selectSource]/[clearSelectedSource]）
 * - 文章加入书库（[addArticleToLibrary]，含双击防护与已添加去重）
 *
 * 已添加文章链接集合以 [StateFlow] 暴露供 UI 观察。
 */
internal class ArticleSquareManager(
    private val stateController: LibraryStateController,
    private val bookRepository: BookRepository,
    private val articleParser: ArticleParser,
    private val rssParser: RssParser,
    private val onTabSwitchToLibrary: () -> Unit,
    private val onDueTimestampRefresh: () -> Unit,
) {

    /**
     * 已成功加入书库的文章链接集合。
     * 旧实现在点击瞬间乐观置"已添加"，异步抓取失败后卡片永远卡在已添加态
     * 且无法重试；现在只有导入真正成功才标记
     */
    private val _addedArticleLinks = MutableStateFlow<Set<String>>(emptySet())
    val addedArticleLinks: StateFlow<Set<String>> = _addedArticleLinks.asStateFlow()

    /** 抓取中的文章链接集合：双击时两个点击事件都会先于任一成功通过"已成功"检查，
     * 需在点击瞬间同步占位去重（Main 单线程，读写无竞态） */
    private val inFlightArticleLinks = mutableSetOf<String>()

    /** 文章源抓取任务：切换源时取消旧抓取，防陈旧结果覆盖新选择 */
    private var articlesFetchJob: Job? = null

    fun setTab(index: Int) {
        stateController.update { it.copy(selectedTab = index) }
        if (index == 0) {
            // 回到书库：刷新到期数基准时间与今日统计，避免长停留后数据陈旧
            onTabSwitchToLibrary()
        }
    }

    fun selectSource(source: ArticleSource, scope: CoroutineScope) {
        stateController.update { it.copy(
            selectedSource = source,
            articles = emptyList(),
            articlesLoading = true,
            articlesError = null,
        ) }
        // 取消上一个源的抓取：快速切换源时旧结果不得覆盖新选择
        articlesFetchJob?.cancel()
        articlesFetchJob = scope.launch {
            try {
                val feed = if (source.isRss) {
                    withContext(Dispatchers.IO) { rssParser.parse(source.url) }
                } else {
                    // 非 RSS 源：抓取首页，尝试从中提取文章链接
                    fetchArticleLinks(source)
                }
                // 结果落地前再核对当前选择：极端时序下仍可能有陈旧写入
                if (stateController.current.selectedSource?.id != source.id) return@launch
                if (feed != null && feed.items.isNotEmpty()) {
                    // 去重：同一篇 feed 中可能出现重复的 (link, title) 组合，
                    // LazyColumn 的 key 必须唯一，否则触发 IllegalArgumentException 崩溃。
                    val uniqueArticles = feed.items.distinctBy { it.link to it.title }
                    stateController.update { it.copy(
                        articles = uniqueArticles,
                        articlesLoading = false,
                    ) }
                } else if (feed == null) {
                    stateController.update { it.copy(
                        articlesLoading = false,
                        articlesError = "加载失败：无法读取该源（网络异常或 RSS 格式异常）",
                    ) }
                } else {
                    stateController.update { it.copy(
                        articlesLoading = false,
                        articlesError = "该源暂无文章",
                    ) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                stateController.update { it.copy(
                    articlesLoading = false,
                    articlesError = "加载失败: 网络错误",
                ) }
            } catch (e: org.xmlpull.v1.XmlPullParserException) {
                stateController.update { it.copy(
                    articlesLoading = false,
                    articlesError = "加载失败: RSS 格式错误",
                ) }
            } catch (e: java.lang.RuntimeException) {
                val msg = e.message ?: e.javaClass.simpleName
                stateController.update { it.copy(
                    articlesLoading = false,
                    articlesError = "加载失败: $msg",
                ) }
            } catch (e: Exception) {
                stateController.update { it.copy(
                    articlesLoading = false,
                    articlesError = "加载失败: ${e.javaClass.simpleName}",
                ) }
            }
        }
    }

    private suspend fun fetchArticleLinks(source: ArticleSource): RssParser.RssFeed? {
        // 非 RSS 源：先尝试提取真实文章链接
        val linkResult = articleParser.parseArticleLinks(source.url)
        if (linkResult != null && linkResult.links.isNotEmpty()) {
            val articles = linkResult.links.map { link ->
                RssParser.RssArticle(
                    title = link.title,
                    link = resolveUrl(source.url, link.url),
                    description = null,
                    pubDate = null,
                    pubTimestamp = System.currentTimeMillis(),
                )
            }
            return RssParser.RssFeed(
                title = linkResult.title,
                description = null,
                link = source.url,
                items = articles,
            )
        }
        // 回退：从首页提取段落内容
        val result = articleParser.parseFromUrl(source.url) ?: return null
        if (result.paragraphs.isEmpty()) return null
        val articles = result.paragraphs.take(10).map { p ->
            RssParser.RssArticle(
                title = p.take(80),
                link = source.url,
                description = p.take(200),
                pubDate = null,
                pubTimestamp = System.currentTimeMillis(),
            )
        }
        return RssParser.RssFeed(
            title = result.title,
            description = null,
            link = source.url,
            items = articles,
        )
    }

    /** 相对链接解析统一交给 URI.resolve：正确处理 ./、../、协议相对
     * （//host/...）与带查询串的 base，手写拼接在这些形态上会产出坏链 */
    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        return try {
            URI(base).resolve(relative).toString()
        } catch (_: Exception) {
            relative
        }
    }

    fun clearSelectedSource() {
        stateController.update { it.copy(
            selectedSource = null,
            articles = emptyList(),
            articlesError = null,
        ) }
    }

    fun addArticleToLibrary(article: RssParser.RssArticle, scope: CoroutineScope) {
        if (article.link.isBlank()) {
            stateController.setResultMessage("文章链接无效，无法导入")
            return
        }
        // 已成功的导入不重复抓取入库
        if (article.link in _addedArticleLinks.value) return
        // 双击防护：成功集合要等抓取结束才写入，两次快速点击都会通过上面的检查。
        // 点击瞬间同步占位（Main 单线程无竞态），成功或失败后释放
        if (!inFlightArticleLinks.add(article.link)) return
        scope.launch {
            stateController.beginImportOp("正在导入文章...")
            try {
                // issue 7.3：优先用 feed 自带的完整正文（content:encoded），
                // 仅在为空时才回退去抓 link——NPR 等源的 <link> 指向 SPA 渲染页，
                // HttpURLConnection 拿到的是空壳 HTML，抓取路径基本必失败
                // expandInlineMarkers：正文里的 [[IMG:url]] 插图标记统一规整为
                // 独立段落（feed 正文图片由 RssParser 转成标记，见 BookImages）
                val feedParagraphs = article.content
                    ?.split(Regex("\n\\s*\n"))
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.let { BookImages.expandInlineMarkers(it) }
                    .orEmpty()
                val result = if (feedParagraphs.isNotEmpty()) {
                    ArticleResult(
                        title = article.title.ifBlank { "Web Article" },
                        paragraphs = feedParagraphs,
                    )
                } else {
                    articleParser.parseFromUrl(article.link)
                }
                if (result != null && result.paragraphs.isNotEmpty()) {
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    val book = com.eareyereading.domain.model.Book(
                        title = article.title.ifBlank { "Web Article" },
                        author = extractDomain(article.link),
                        filePath = "",
                        content = result.paragraphs.joinToString("\n\n"),
                        category = "文章",
                        addedAt = dateFormat.format(Date()),
                    )
                    bookRepository.addBook(book)
                    // 成功后才标记"已添加"：失败时卡片保持可重试状态
                    _addedArticleLinks.update { it + article.link }
                    onDueTimestampRefresh()
                    stateController.setResultMessage("「${article.title.take(20)}...」已加入书库！")
                    stateController.update { it.copy(selectedTab = 0) }
                } else {
                    stateController.setResultMessage("该源文章内容为空，无法导入")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                stateController.setResultMessage("导入失败: 网络错误")
            } catch (e: java.lang.RuntimeException) {
                val msg = e.message ?: e.javaClass.simpleName
                stateController.setResultMessage("导入失败: $msg")
            } finally {
                inFlightArticleLinks.remove(article.link)
                stateController.endImportOp()
            }
        }
    }

    /** 从 URL 提取域名（去 www. 前缀），失败回退通用名 */
    private fun extractDomain(url: String): String {
        return try {
            val u = java.net.URL(url)
            u.host.removePrefix("www.")
        } catch (e: java.net.MalformedURLException) {
            "Web Article"
        }
    }
}
