# 修复：阅读页翻译过程中布局持续被刷新

## 症状

打开全文翻译后，正在阅读的页面会持续自我刷新：

- **滚动模式**：每翻译完一段就把译文插进该段下方，同屏靠后段落高度不断增长，下方内容被一路往下顶；
- **翻页模式**：译文是分页的输入，译文一变就整书重新分页 → 当前页内容错位、页数跳变；更严重的是分页结果一变，`LaunchedEffect(currentIndex, pages)` 就按"目标页 ≠ 当前页"发起翻页，用户正在读的页面会被硬生生翻走。

翻译顺序是"从当前阅读位置向两侧扩散"，也就是说**用户正看着的那几段最先被翻译**，恰好是抖动最剧烈的区域。

## 方案：数据层 / 上屏层分离，按"视线内外"区别对待

### 1. 两层译文

| 字段 | 角色 |
| --- | --- |
| `paragraphTranslations` | 数据层。渐进更新，落库来源；分栏、回译、挖空等"就是要看译文逐段浮现"的模式继续用它 |
| `readerTranslations` | 上屏层。滚动/翻页两种正文视图的**唯一**渲染源，也是翻页分页的**唯一**译文输入 |

### 2. 上屏规则

- **视口内**（用户正看着的这一屏）：先攒进 `held`，等这一屏涉及的待翻段落全部翻完，**一次性整屏上屏**。因为翻译顺序是从当前阅读位置向两侧扩散，这一屏通常几秒内就齐了——用户只看到"这一屏刷新了一次"，不会觉得卡。
- **视口外**：随时上屏。滚动模式下这些高度变化发生在屏幕之外，LazyColumn 以首个可见项为锚，不会推动当前屏；翻页模式下不做及时上屏（任何一次译文变化都会触发整书重新分页），只在下一次翻页或翻译结束时放行。
- **整体提交点**：打开翻译时先把 Room 缓存铺上屏；整本翻译结束时把补齐的译文整体提交；失败路径清空。

### 3. 配套改动

- `ReaderViewModel.visibleParaRange` + `onVisibleRangeChanged(first, last)`：滚动视图上报可见项区间，翻页视图上报当前页段落区间。故意放普通字段而非 `uiState`——滚动时高频变化，进状态流会驱动整屏重组。
- `PagedReadingView` 锚点机制：`anchorParaIndex` 记录当前页首段落，**唯一**的自动翻页入口是 `LaunchedEffect(pages, anchorParaIndex)`，按"段落"而不是"页码"对齐视口。`currentIndex` 只做锚点跟随——旧实现把 `pages` 当触发条件，任何一次重新分页都会把用户翻走。
- 翻页模式去重：`flushedRange` 保证每次进入新页只整屏刷新一次，否则本页翻完后每一批完成都会立刻放行 → 又回到持续重排。
- `TranslationProgressPill`：正文顶部叠加层（不参与正文测量）显示 `翻译中 xx%` + 细进度槽。顶栏的 spinner 只在 chrome 显示时可见，而 chrome 会随滚动自动收起，进度必须常驻。

## 取舍说明

"可见区稳定"和"即时逐段上屏"在流式布局里本质冲突——插入译文必然撑高段落。折中方案是把零散的多次位移**合并成一次**，且发生在翻译开始后几秒内；此后该屏完全静止。用户滚动或翻页时会立刻看到最新译文（译文已按中心向外扩散排在前面），因此不会产生"卡住"的误判。

## 改动文件

```text
app/src/main/java/com/eareyereading/ui/screens/reader/
├── ReaderUiState.kt                 + readerTranslations / 翻译进度字段
├── ReaderViewModel.kt               + visibleParaRange / onVisibleRangeChanged
├── ReaderViewModelTranslation.kt    + 上屏策略（视口内整屏、视口外渐进）
├── ReaderViewModelBookLoader.kt     + 换书时同步上屏层与视口区间
├── ReaderContentDispatcher.kt       + 正文视图改读上屏层 / 进度浮标组件
├── ReaderScreen.kt                  + 翻译进度浮标叠加层
├── NormalReadingView.kt             + 可见区间上报
└── PagedReadingView.kt              + 当前页区间上报 / 锚点定位（去掉强制翻页）
```

## 验证

- `./gradlew :app:compileDebugKotlin` 通过
- `./gradlew detekt` 通过
