package cn.hys159x.grid.app.ui

import android.content.Intent
import android.provider.Settings as SysSettings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.hys159x.grid.app.service.ServiceState
import cn.hys159x.grid.app.service.Settings

@Composable
fun HomeScreen(
    onOpenLogs: () -> Unit,
    onOpenGuide: () -> Unit = {},
    onOpenCustomRules: () -> Unit = {},
) {
    val context = LocalContext.current
    val connected by ServiceState.serviceConnected.collectAsState()
    val enabled by Settings.enabled.collectAsState()
    val ruleSet by ServiceState.ruleSet.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            Text("网格", style = MaterialTheme.typography.headlineLarge)
            Text(
                "拦截之网",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
            )
        }
        Card {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(if (connected) "服务运行中" else "服务未开启")
                    Text(
                        text = "规则：${ruleSet?.totalRules ?: 0} 条 · 订阅 v${ruleSet?.version ?: "-"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { Settings.setEnabled(it) })
            }
        }
        if (!connected) {
            OutlinedButton(onClick = {
                context.startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
            }) { Text("去开启无障碍权限") }
            Text(
                "路径：设置 → 无障碍 → 已下载的应用 → 网格 → 开启。华为/小米机型请同时允许自启动并关闭电池优化，防止服务被系统回收。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(onClick = onOpenLogs) { Text("拦截日志") }
        OutlinedButton(onClick = onOpenCustomRules) { Text("自定义规则") }
        OutlinedButton(onClick = onOpenGuide) { Text("使用说明") }
        Text(
            "隐私：完全离线运行（无联网权限），无障碍数据仅在本地内存中匹配，不上传任何内容。",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "免责声明：本工具仅供个人学习与技术交流，请在遵守法律法规及各应用用户协议的前提下" +
                "在本人设备上合理使用。工具完全在本地运行，不修改应用、不收集数据、不含任何恶意行为，" +
                "亦不用于商业用途；因使用本工具产生的任何后果由使用者自行承担。若认为内容侵权，请联系删除。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
