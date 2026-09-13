package com.eareyereading.ui.screens.settings

import com.eareyereading.ui.components.EareyeBottomSheet
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eareyereading.ui.theme.EareyeShapes

/**
 * 「如何拿到 API Key / 云凭证」分步引导。
 *
 * 背景：AI 翻译与腾讯云 TTS 都要求用户自备凭据，而这两处的申请流程
 * 藏在各自控制台的深处（智谱的 API Keys 页、腾讯云的访问管理 CAPI 页），
 * 普通用户卡在第一步就放弃了。此前只在输入框下面留了一行 helper 文字，
 * 等于把「去找 Key」这件事整个丢给用户。
 *
 * 本组件把这条路拆成「打开页面 → 注册 → 创建 → 粘贴」四到五步，
 * 每步一个编号圆点 + 标题 + 说明，并提供一键跳转对应控制台的按钮。
 * LLM（智谱 / DeepSeek）与腾讯云共用这一套壳子，内容由 [KeyGuide] 提供。
 */

/** 引导中的一个步骤。 */
internal data class KeyGuideStep(
    val title: String,
    val detail: String,
)

/** 一份完整的取 Key 引导。 */
internal data class KeyGuide(
    /** 引导标题 */
    val title: String,
    /** 顶部概述：告诉用户要花多久、是否免费 */
    val intro: String,
    /** 安全提示（可为空） */
    val notice: String = "",
    /** 分步说明 */
    val steps: List<KeyGuideStep>,
    /** 一键跳转的控制台地址 */
    val actionUrl: String,
    /** 跳转按钮文案 */
    val actionLabel: String,
)

/** 智谱开放平台（GLM-4-Flash 免费）取 Key 引导。 */
internal val GlmKeyGuide = KeyGuide(
    title = "获取智谱 API Key",
    intro = "智谱的 glm-4-flash 模型目前免费，注册后即可调用。整个过程大约 2 分钟。",
    steps = listOf(
        KeyGuideStep(
            "打开开放平台",
            "点下方按钮，会用浏览器打开智谱开放平台。没账号的话，这个页面也能直接注册。",
        ),
        KeyGuideStep(
            "注册并登录",
            "手机号就能注册。注意：调用接口前需要完成实名认证，否则 Key 建了也不能用。",
        ),
        KeyGuideStep(
            "创建 API Key",
            "登录后进入「API Keys」页面，点「新建 API Key」，把生成的那串字符整段复制下来。",
        ),
        KeyGuideStep(
            "回到本页粘贴",
            "切回听阅，回到这里把复制的内容粘进「API Key」输入框，保存后再把上方的开关打开。",
        ),
    ),
    actionUrl = "https://open.bigmodel.cn/usercenter/apikeys",
    actionLabel = "打开智谱开放平台",
)

/** 腾讯云 TTS 取凭证引导。 */
internal val TencentKeyGuide = KeyGuide(
    title = "获取腾讯云凭证",
    intro = "腾讯云语音合成每月有免费额度。它需要的是一对 SecretId / SecretKey，" +
        "不在语音合成控制台，而在「访问管理」里 —— 这一步最容易找错地方。",
    notice = "SecretKey 的权限等同于账号密码：不要截图、不要发给别人。" +
        "建议在腾讯云「访问管理 → 策略」里只授予语音合成（TTS）权限。",
    steps = listOf(
        KeyGuideStep(
            "打开访问管理控制台",
            "点下方按钮，会直接跳到「API 密钥管理」页，不用在控制台里一层层找。",
        ),
        KeyGuideStep(
            "登录并完成实名",
            "腾讯云账号需要实名认证后才能创建密钥；未实名的账号在这一步会没有「新建」按钮。",
        ),
        KeyGuideStep(
            "新建密钥",
            "点「新建密钥」，腾讯云会同时给你 SecretId 和 SecretKey 两个值。",
        ),
        KeyGuideStep(
            "立刻复制保存",
            "SecretKey 只在创建时显示这一次，关掉页面就再也看不到了。两个值都先存到备忘录里。",
        ),
        KeyGuideStep(
            "回到本页填写",
            "切回听阅，把两个值分别粘进「腾讯云凭证」弹窗的两个输入框。",
        ),
    ),
    actionUrl = "https://console.cloud.tencent.com/cam/capi",
    actionLabel = "打开腾讯云访问管理",
)

/** 用系统浏览器打开地址；无浏览器/被拦截时静默失败，不打断当前流程。 */
private fun openInBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}

/**
 * 取 Key 引导底部抽屉。
 *
 * @param guide 引导内容
 * @param onDismiss 关闭
 * @param onDone 用户表示"我已经拿到 Key 了"，非空时展示确认按钮（通常直接打开输入框）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeyGuideSheet(
    guide: KeyGuide,
    onDismiss: () -> Unit,
    onDone: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    // 引导内容比半屏高：默认的半展开会把跳转按钮压到屏幕外，
    // 而"打开控制台"正是这个抽屉最重要的一步，必须开屏可见
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    EareyeBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = EareyeShapes.bottomSheet,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                guide.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                guide.intro,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 跳转按钮紧随概述：它对应的就是第 1 步「打开页面」，
            // 放在抽屉底部会被长内容顶出屏幕
            Button(
                onClick = { openInBrowser(context, guide.actionUrl) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(guide.actionLabel)
            }

            if (guide.notice.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    guide.notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            guide.steps.forEachIndexed { index, step ->
                GuideStepRow(number = index + 1, step = step)
                if (index < guide.steps.lastIndex) {
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (onDone != null) {
                OutlinedButton(
                    onClick = {
                        onDone()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("我已经拿到 Key 了")
                }
            } else {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("知道了")
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

/** 单步：编号圆点 + 标题 + 说明。编号而非图标 —— 顺序本身就是这一步的信息。 */
@Composable
private fun GuideStepRow(number: Int, step: KeyGuideStep) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                step.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                step.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
