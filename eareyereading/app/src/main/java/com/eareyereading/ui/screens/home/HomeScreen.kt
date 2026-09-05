package com.eareyereading.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eareyereading.domain.model.Book
import com.eareyereading.ui.components.BookCover
import com.eareyereading.ui.components.ReadingHeatmap
import com.eareyereading.ui.components.StatCard
import com.eareyereading.ui.components.rememberPlayOnceAnimation
import com.eareyereading.ui.theme.*
import com.eareyereading.util.notificationPermissionGranted
import com.eareyereading.util.rememberNotificationPermissionRequester
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToLibrary: () -> Unit,
    onNavigateToReview: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onBookClick: (Long) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // issue 5.1：主页有到期卡时也作为通知权限申请入口（此前只有设置页能申请）。
    // 已授权则不显示该入口。
    val context = androidx.compose.ui.platform.LocalContext.current
    val requestNotifications = rememberNotificationPermissionRequester()
    val notificationsGranted = notificationPermissionGranted(context)

    // issue 11.15：此前 remember 一次后跨午夜不更新；每分钟 tick 刷新
    var dateText by remember { mutableStateOf(SimpleDateFormat("MM月dd日 E", Locale.CHINA).format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            dateText = SimpleDateFormat("MM月dd日 E", Locale.CHINA).format(Date())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            uiState.greeting,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            dateText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, "设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding),
            // Scaffold 的 padding 已包含底部导航栏高度，不再叠加 100dp 魔法值
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            // 今日统计卡片
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatCard(
                        icon = Icons.Default.Timer,
                        value = uiState.todayMinutes,
                        unit = "min",
                        label = "今日阅读",
                        color = Primary,
                        modifier = Modifier.weight(1f),
                    )
                    // 连续打卡：火焰呼吸脉动 + Count-up（多邻国式的存活暗示）；
                    // 底色升为 primary-container（绿=活跃，情感焦点高亮）
                    StatCard(
                        icon = Icons.Outlined.LocalFireDepartment,
                        value = uiState.streakDays,
                        unit = "天",
                        label = "连续打卡",
                        color = Warning,
                        pulse = uiState.streakDays > 0,
                        highlight = true,
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        icon = Icons.Default.MenuBook,
                        value = uiState.totalVocabulary,
                        unit = "词",
                        label = "生词本",
                        color = Accent,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 待复习提醒
            if (uiState.dueReviewCount > 0) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    ReviewReminderBanner(
                        count = uiState.dueReviewCount,
                        onReview = onNavigateToReview,
                        // issue 5.1：未授权时给"开启通知"入口
                        showEnableNotifications = !notificationsGranted,
                        onEnableNotifications = requestNotifications,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            // 本周阅读趋势
            item {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    "本周阅读",
                    style = SectionTitle,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(modifier = Modifier.height(10.dp))
                WeeklyChart(
                    data = uiState.weeklyData,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .padding(horizontal = 16.dp),
                )
            }

            // 学习热力图（GitHub contributions 式打卡墙）
            if (uiState.heatmapData.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    ReadingHeatmap(
                        dailyMinutes = uiState.heatmapData,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                }
            }

            // 最近阅读
            if (uiState.recentBooks.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("最近阅读", style = SectionTitle)
                        TextButton(onClick = onNavigateToLibrary) {
                            Text("查看全部", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(uiState.recentBooks, key = { it.id }) { book ->
                            RecentBookCard(
                                book = book,
                                onClick = { onBookClick(book.id) },
                            )
                        }
                    }
                }
            }

            // 新用户空状态引导：没有最近阅读时给一条明确的"导入第一本书"
            // CTA——旧实现此时"最近阅读"/热力图整段隐藏，新用户面对的
            // 是全 0 统计卡 + 空柱状图，不知道下一步该做什么
            if (uiState.recentBooks.isEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(28.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Primary.copy(alpha = 0.10f),
                            ) {
                                Icon(
                                    Icons.Default.MenuBook,
                                    null,
                                    tint = Primary,
                                    modifier = Modifier
                                        .padding(18.dp)
                                        .size(28.dp),
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "开始你的第一本书",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "导入 EPUB 或用 URL 添加文章，边读边攒生词",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedButton(onClick = onNavigateToLibrary) {
                                Text("去书库导入")
                            }
                        }
                    }
                }
            }

            // 快捷入口已移除：与底部导航完全重复的 web 仪表盘式链接区。
            // 书库/生词本/复习都在底部导航栏一步直达
            item { Spacer(modifier = Modifier.height(20.dp)) }
        }
    }
}

// ── 复习提醒 ─────────────────────────────────────
@Composable
private fun ReviewReminderBanner(
    count: Int,
    onReview: () -> Unit,
    showEnableNotifications: Boolean,
    onEnableNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Warning.copy(alpha = 0.12f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Warning.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Replay, null, tint = Warning, modifier = Modifier.size(22.dp))
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "待复习 $count 个生词",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Warning,
                    )
                    Text(
                        "基于 SM-2 遗忘曲线，科学安排复习时间",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Warning.copy(alpha = 0.15f),
                    onClick = onReview,
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("去复习", style = MaterialTheme.typography.labelMedium, color = Warning.copy(alpha = 0.9f))
                    }
                }
            }
            // issue 5.1：到期卡存在且未授权时，提供"开启通知"入口
            if (showEnableNotifications) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onEnableNotifications,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        Icons.Default.Notifications,
                        contentDescription = null,
                        tint = Warning,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("开启每日复习提醒", color = Warning, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

// ── 周阅读图表 ───────────────────────────────────
/** 日均目标（分钟）：达标判定阈值，与热力图第一档对齐。 */
private const val DAILY_GOAL_MINUTES = 15

@Composable
private fun WeeklyChart(
    data: List<DayReadingData>,
    modifier: Modifier = Modifier,
) {
    val maxMinutes = data.maxOfOrNull { it.minutes }?.coerceAtLeast(1) ?: 1

    // 进入页面时柱状图从 0 生长到目标高度（图表生长动画，0f→1f 单次推进）
    val growProgress = rememberPlayOnceAnimation(durationMillis = 750)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,

    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            data.forEachIndexed { index, day ->
                val isToday = index == data.lastIndex
                val heightRatio = day.minutes.toFloat() / maxMinutes
                // 改版C：今日柱即使未达标也保持"进行中"的视觉高度
                //（不低于昨日/前日较高者的 60%），不再是一根贴地的短柱
                val effectiveRatio = if (isToday) {
                    val recentMax = data.dropLast(1).takeLast(2)
                        .maxOfOrNull { it.minutes }?.toFloat() ?: 0f
                    heightRatio.coerceAtLeast((recentMax / maxMinutes) * 0.6f)
                } else {
                    heightRatio
                }
                // 颜色规则（§4.5.1）：今日 primary-container 高亮；
                // 已发生日达标=primary 深绿、未达标=outline 灰
                val barColor = when {
                    isToday -> PrimaryLight
                    day.minutes >= DAILY_GOAL_MINUTES -> Primary
                    else -> BorderStrong
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isToday) {
                        // 今日柱顶给带语义的文案，替代孤立数字（原图"34"含义不明）
                        Text(
                            "今日 +${day.minutes} min",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    } else if (day.minutes >= DAILY_GOAL_MINUTES) {
                        // 仅达标日显示数值，未达标日不显示（降低负反馈噪音）
                        Text(
                            "${day.minutes}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .fillMaxHeight(
                                (effectiveRatio * growProgress).coerceAtLeast(0.03f),
                            )
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(barColor),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        day.dayLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── 最近阅读书籍卡片 ────────────────────────────────
@Composable
private fun RecentBookCard(
    book: Book,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            // 改版C：横向卡 144dp 宽（§4.5.3 horizontal-card 规格）；
            // 首卡 16dp 对齐 + 末卡右露 16dp 由 LazyRow contentPadding 保证
            .width(144.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),

    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // 生成式插图封面自带书名/作者，卡片内不再重复标题文字
            BookCover(
                title = book.title,
                coverPath = book.coverPath,
                author = book.author,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(128.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = book.readProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Primary,
                trackColor = Primary.copy(alpha = 0.15f),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${(book.readProgress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
