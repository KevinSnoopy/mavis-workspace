package com.eareyereading.ui.compliance

import android.content.Intent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 隐私政策版本号：政策内容发生实质性变更（新增权限/SDK、变更数据用途）时 +1，
 * 已同意用户在下次启动时会重新看到同意弹窗。与应用内置的 assets 全文同步维护。
 */
const val PRIVACY_VERSION = 1

/** 政策全文的在线地址（jsDelivr CDN，随公共仓库发布；商店后台填写的链接也用这一对）。 */
const val PRIVACY_POLICY_URL =
    "https://cdn.jsdelivr.net/gh/KevinSnoopy/mavis-workspace@eareyereading/eareyereading/docs/store/privacy-policy.html"
const val USER_AGREEMENT_URL =
    "https://cdn.jsdelivr.net/gh/KevinSnoopy/mavis-workspace@eareyereading/eareyereading/docs/store/user-agreement.html"

/**
 * 首次启动（及政策版本升级后）的隐私政策同意弹窗。
 *
 * 合规要求（工信部 164 号文 / 各商店审核准则）：
 * - 同意前不进入任何主功能、不申请任何权限、不发起任何联网请求；
 * - 必须提供"不同意"选项，选择后退出应用（不能默默退出或反复打扰）；
 * - 弹窗内必须可查看政策全文，且**断网也要能看**（审核会做离线测试）——
 *   因此全文走内置 assets，在线 URL 仅在"联系/备案"信息里展示。
 *
 * @param onAgreed 用户点击同意（持久化在调用方完成）
 * @param onDecline 用户拒绝（调用方应退出应用）
 */
@Composable
fun PrivacyConsentGate(
    onAgreed: () -> Unit,
    onDecline: () -> Unit,
) {
    // 全文查看层：null=弹窗本体，否则显示对应文档
    var viewingDoc by remember { mutableStateOf<ComplianceDoc?>(null) }

    viewingDoc?.let { doc ->
        ComplianceDocViewer(doc = doc, onBack = { viewingDoc = null })
        return
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp),
        ) {
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                "欢迎使用「听阅」",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "在开始使用前，请阅读并同意以下条款",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))

            // 摘要区（可滚动）：只放最关键的合规信息，全文点链接看
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(14.dp),
                    )
                    .padding(16.dp),
            ) {
                ConsentBullet(
                    "本应用不要求注册账号，不收集手机号、位置等个人身份信息；" +
                        "你的书籍、生词与学习记录只保存在手机本地。",
                )
                ConsentBullet("核心功能（本地朗读、本地词典、本地翻译兜底）完全离线可用。")
                ConsentBullet(
                    "词典更新、在线翻译、RSS 抓取等联网功能仅在你主动使用时触发，" +
                        "具体见《隐私政策》的网络功能与权限说明。",
                )
                ConsentBullet(
                    "复习提醒需要通知与闹钟权限，均可在系统设置中随时关闭；" +
                        "你也可以随时卸载应用以彻底删除全部数据。",
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    DocLink("《隐私政策》") { viewingDoc = ComplianceDoc.PRIVACY_POLICY }
                    Spacer(modifier = Modifier.width(20.dp))
                    DocLink("《用户服务协议》") { viewingDoc = ComplianceDoc.USER_AGREEMENT }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "点击「同意并继续」即表示你已阅读并同意上述全部条款。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onAgreed,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("同意并继续", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onDecline,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("不同意并退出", fontSize = 16.sp)
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

/** 合规文档（全文内置 assets，离线可看；在线地址供浏览器/商店使用）。 */
enum class ComplianceDoc(val title: String, val assetFile: String, val onlineUrl: String) {
    PRIVACY_POLICY(
        "隐私政策",
        "file:///android_asset/privacy-policy.html",
        PRIVACY_POLICY_URL,
    ),
    USER_AGREEMENT(
        "用户服务协议",
        "file:///android_asset/user-agreement.html",
        USER_AGREEMENT_URL,
    ),
}

/**
 * 政策全文查看页：WebView 渲染内置 assets HTML（离线可用），
 * 顶部提供"在浏览器打开最新版"跳转在线地址。
 */
@Composable
fun ComplianceDocViewer(doc: ComplianceDoc, onBack: () -> Unit) {
    val context = LocalContext.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onBack) { Text("返回") }
                Text(
                    doc.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(
                    onClick = {
                        // 在线最新版交给系统浏览器（应用内不额外发起联网）
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(doc.onlineUrl)))
                        } catch (_: Exception) {
                        }
                    },
                ) { Text("在线版") }
            }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = false // 纯静态文档，禁 JS
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        webViewClient = WebViewClient() // 本地 asset，不外跳
                    }
                },
                update = { webView -> webView.loadUrl(doc.assetFile) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
            )
        }
    }
}

/** 供设置页复用的全文查看入口（不经过同意弹窗）。 */
@Composable
fun ComplianceDocEntry(doc: ComplianceDoc, onBack: () -> Unit) {
    ComplianceDocViewer(doc = doc, onBack = onBack)
}

@Composable
private fun ConsentBullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 5.dp)) {
        Text(
            "·",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun DocLink(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
