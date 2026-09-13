package com.eareyereading.ui.screens.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eareyereading.util.notificationPermissionGranted
import com.eareyereading.util.rememberNotificationPermissionRequester
import kotlinx.coroutines.launch

/**
 * 首启引导（3 页可跳过）。
 *
 * ── 为什么要改 ──
 * 旧实现（issue 5.1）叫 FirstLaunchOnboarding，实际只是一个通知权限申请页：
 * 96 行里只有「开通知 / 跳过」。App 只有一次教育用户的窗口，被花在了
 * 一件用户本来就会在设置里做的事上 —— 结果用户既不知道「点单词可以查词」，
 * 也不知道有 9 种阅读模式，更不知道生词和复习是怎么连起来的。
 *
 * 现在拆成三页（通知授权挪到最后一页，不再单独占一屏）：
 *   1. 核心闭环 —— 导入 → 点词 → 生词 → 复习，四步讲清这个 App 靠什么成立
 *   2. 模式速览 —— 三组九种读法，各配一句「适合什么时候用」
 *   3. 通知授权 —— 复用原有权限申请逻辑
 *
 * 每页都可跳过；已授权通知时第 3 页的主按钮直接完成引导，不重复弹框。
 * 完成标记沿用 `has_seen_onboarding`（MainActivity 的 SharedPreferences），
 * 不引入新的持久化机制。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FirstLaunchOnboarding(
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    // 已授权（或旧版本无需权限）时按下主按钮直接进入主界面，不再重复弹框
    val requestNotifications = rememberNotificationPermissionRequester(onGranted = onDone)
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState { ONBOARDING_PAGE_COUNT }
    val isLastPage = pagerState.currentPage == ONBOARDING_PAGE_COUNT - 1

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 28.dp),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            when (page) {
                0 -> OnboardingCoreLoopPage()
                1 -> OnboardingModesPage()
                else -> OnboardingNotificationPage()
            }
        }

        // 页指示点：当前页放大 + 主色，其余小点
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(ONBOARDING_PAGE_COUNT) { index ->
                val isCurrent = index == pagerState.currentPage
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (isCurrent) 10.dp else 7.dp)
                        .clip(CircleShape)
                        .background(
                            if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (isLastPage) {
                    if (notificationPermissionGranted(context)) onDone() else requestNotifications()
                } else {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            },
            contentPadding = PaddingValues(vertical = 14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (isLastPage) "开启通知，开始使用" else "下一步",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        TextButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("暂时跳过")
        }

        if (isLastPage) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "稍后可在「设置 → 通知」中随时开启",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

private const val ONBOARDING_PAGE_COUNT = 3

// ── 第 1 页：核心闭环 ─────────────────────────────
@Composable
private fun OnboardingCoreLoopPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(0.8f))
        OnboardingIconCircle(Icons.Outlined.MenuBook)
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "边读边攒，越读越顺",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "生词不是抄在本子上的，是读出来的。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(28.dp))
        OnboardingStep(1, "导入一本书", "EPUB / TXT，或粘贴网址直接抓文章")
        OnboardingStep(2, "点任意单词", "弹出释义，一键收进生词本")
        OnboardingStep(3, "生词自动排队", "按遗忘曲线排期，什么时候该复习它说了算")
        OnboardingStep(4, "到点回来巩固", "到点提醒你回炉，读完的书不留死角")
        Spacer(modifier = Modifier.weight(1f))
    }
}

// ── 第 2 页：模式速览 ─────────────────────────────
@Composable
private fun OnboardingModesPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(0.8f))
        OnboardingIconCircle(Icons.Outlined.Speed)
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "九种读法，随需切换",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "同一段文字，可以换着方式读。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        ModeGroupCard(
            group = "阅读",
            modes = "普通阅读 · 分栏对照",
            note = "安安静静把书读完，长难句对照着看",
            icon = Icons.Outlined.MenuBook,
        )
        ModeGroupCard(
            group = "提速",
            modes = "仿生阅读 · 快速阅读",
            note = "让眼睛跑起来，先泛读再决定要不要精读",
            icon = Icons.Outlined.Speed,
        )
        ModeGroupCard(
            group = "训练",
            modes = "挖空 · 听写 · 模糊听读 · 回译 · 词性",
            note = "检验你是真会还是假会",
            icon = Icons.Outlined.FitnessCenter,
        )
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            "书内点顶栏的书本图标即可切换",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.weight(1f))
    }
}

// ── 第 3 页：通知授权（复用旧实现的文案与逻辑）─────
@Composable
private fun OnboardingNotificationPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OnboardingIconCircle(Icons.Outlined.Notifications)
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            "开启通知，不错过每日复习",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "生词攒够了却一直不回炉，等于白读。" +
                "到点提醒你回来巩固，比凭感觉复习有效得多。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ── 共用组件 ─────────────────────────────────────
@Composable
private fun OnboardingIconCircle(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
        )
    }
}

/** 编号步骤行：编号圆点 + 标题 + 一句说明。编号本身就是顺序信息，不用图标。 */
@Composable
private fun OnboardingStep(number: Int, title: String, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 模式分组卡：一句话讲清这一组是干什么的，不罗列参数。 */
@Composable
private fun ModeGroupCard(group: String, modes: String, note: String, icon: ImageVector) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            0.5.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "$group · $modes",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
