package com.eareyereading.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eareyereading.ui.compliance.ComplianceDoc
import com.eareyereading.ui.compliance.PRIVACY_POLICY_URL
import com.eareyereading.ui.compliance.USER_AGREEMENT_URL
import com.eareyereading.ui.theme.Primary

/**
 * 「关于与合规」分区：隐私政策、用户协议、开源许可、备案信息。
 *
 * 上架合规要求：应用内必须提供隐私政策/用户协议的查看入口（与首启同意弹窗
 * 呈现同一份内置全文）；开源组件许可集中披露（sherpa-onnx 等为 Apache-2.0）。
 * 备案号常量为占位，App 备案核准后替换 [APP_RECORD_NUMBER] 即可。
 */

/** 工信部 App 备案核准号：备案通过后替换为真实号码（如 "京ICP备XXXXXXXX号-X号"）。 */
const val APP_RECORD_NUMBER = "备案中"

@Composable
internal fun SettingsAboutSection(
    onViewDoc: (ComplianceDoc) -> Unit,
    onShowLicenses: () -> Unit,
) {
    SettingsSectionTitle("关于与合规")
    SettingsListCard {
        SettingRowClickable(
            icon = Icons.Default.PrivacyTip,
            iconBg = Primary.copy(alpha = 0.12f),
            iconColor = Primary,
            title = "隐私政策",
            subtitle = "我们如何处理你的数据（全文）",
            onClick = { onViewDoc(ComplianceDoc.PRIVACY_POLICY) },
        )
        SettingRowClickable(
            icon = Icons.Default.Description,
            iconBg = Primary.copy(alpha = 0.12f),
            iconColor = Primary,
            title = "用户服务协议",
            subtitle = "使用本应用前请知悉（全文）",
            onClick = { onViewDoc(ComplianceDoc.USER_AGREEMENT) },
        )
        SettingRowClickable(
            icon = Icons.Default.Code,
            iconBg = Primary.copy(alpha = 0.12f),
            iconColor = Primary,
            title = "开源许可",
            subtitle = "本应用使用的开源组件",
            onClick = onShowLicenses,
        )
        SettingRow(
            icon = Icons.Default.PrivacyTip,
            iconBg = MaterialTheme.colorScheme.surfaceVariant,
            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = "APP 备案号",
            subtitle = APP_RECORD_NUMBER,
        )
    }
}

/** 开源组件许可对话框：只列打进发行包的组件，许可与官网信息可在对应页面核实。 */
@Composable
internal fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = {
            Text("开源许可", fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "本应用使用了以下开源组件，感谢开源社区：",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LicenseEntry(
                    "sherpa-onnx + Kokoro TTS 模型",
                    "Apache License 2.0",
                    "https://github.com/k2-fsa/sherpa-onnx",
                )
                LicenseEntry(
                    "ML Kit On-device Translation",
                    "Apache License 2.0（Google API ToS 附加条款适用）",
                    "https://developers.google.com/ml-kit",
                )
                LicenseEntry(
                    "Jetpack（Compose / Room / DataStore / Hilt 等）",
                    "Apache License 2.0",
                    "https://developer.android.com/jetpack",
                )
                LicenseEntry("Coil", "Apache License 2.0", "https://coil-kt.github.io/coil/")
                LicenseEntry("Gson", "Apache License 2.0", "https://github.com/google/gson")
                LicenseEntry(
                    "Apache Commons Compress",
                    "Apache License 2.0",
                    "https://commons.apache.org/proper/commons-compress/",
                )
                LicenseEntry("Kotlin Coroutines", "Apache License 2.0", "https://github.com/Kotlin/kotlinx.coroutines")
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
private fun LicenseEntry(name: String, license: String, url: String) {
    Column {
        Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            "$license · $url",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 供商店文案/文档引用的在线政策地址常量集合（避免各处散落字符串）。 */
object ComplianceLinks {
    const val PRIVACY: String = PRIVACY_POLICY_URL
    const val AGREEMENT: String = USER_AGREEMENT_URL
}
