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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.*
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
                        value = "${uiState.todayMinutes}",
                        unit = "min",
                        label = "今日阅读",
                        color = Primary,
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        icon = Icons.Outlined.LocalFireDepartment,
                        value = "${uiState.streakDays}",
                        unit = "天",
                        label = "连续打卡",
                        color = Warning,
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        icon = Icons.Default.MenuBook,
                        value = "${uiState.totalVocabulary}",
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
                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    "本周阅读",
                    style = SectionTitle,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                WeeklyChart(
                    data = uiState.weeklyData,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .padding(horizontal = 16.dp),
                )
            }

            // 最近阅读
            if (uiState.recentBooks.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(28.dp))
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
                    Spacer(modifier = Modifier.height(12.dp))
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
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

            // 快捷入口已移除：与底部导航完全重复的 web 仪表盘式链接区。
            // 书库/生词本/复习都在底部导航栏一步直达
            item { Spacer(modifier = Modifier.height(20.dp)) }
        }
    }
}

// ── 统计卡片 ─────────────────────────────────────
@Composable
private fun StatCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    unit: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.1f),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, label, tint = color, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = color.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
@Composable
private fun WeeklyChart(
    data: List<DayReadingData>,
    modifier: Modifier = Modifier,
) {
    val maxMinutes = data.maxOfOrNull { it.minutes }?.coerceAtLeast(1) ?: 1
    val barColor = Primary

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
            data.forEach { day ->
                val heightRatio = day.minutes.toFloat() / maxMinutes
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                    modifier = Modifier.weight(1f),
                ) {
                    if (day.minutes > 0) {
                        Text(
                            "${day.minutes}",
                            style = MaterialTheme.typography.labelSmall,
                            color = barColor,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .fillMaxHeight(heightRatio.coerceAtLeast(0.03f))
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(barColor.copy(alpha = if (heightRatio > 0) 0.85f else 0.15f)),
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
            .width(140.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),

    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = book.title.take(2).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = book.readProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
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


