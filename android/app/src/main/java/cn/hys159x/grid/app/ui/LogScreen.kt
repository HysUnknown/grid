package cn.hys159x.grid.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val events by remember { cn.hys159x.grid.app.data.GridDatabase.get(context).interceptLogDao().recent() }
        .collectAsState(initial = emptyList())
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("拦截日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        enabled = events.isNotEmpty(),
                        onClick = {
                            kotlin.concurrent.thread {
                                runCatching {
                                    cn.hys159x.grid.app.data.GridDatabase.get(context).interceptLogDao().clear()
                                }
                            }
                        },
                    ) { Text("清空") }
                },
            )
        },
    ) { padding ->
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp)) { Text("暂无拦截记录") }
            return@Scaffold
        }
        val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            items(events) { e ->
                // 包名 → 应用名（查不到如已卸载则回退显示包名）
                val appName = remember(e.packageName) {
                    runCatching {
                        val ai = context.packageManager.getApplicationInfo(e.packageName, 0)
                        context.packageManager.getApplicationLabel(ai).toString()
                    }.getOrDefault(e.packageName)
                }
                ListItem(
                    headlineContent = {
                        Text(
                            e.action + " " + (e.nodeDesc ?: ""),
                            color = if (e.action.endsWith("✗")) MaterialTheme.colorScheme.error
                            else androidx.compose.ui.graphics.Color.Unspecified,
                        )
                    },
                    supportingContent = { Text("$appName（${e.packageName}）") },
                    trailingContent = { Text(fmt.format(Date(e.time)), style = MaterialTheme.typography.bodySmall) },
                )
                HorizontalDivider()
            }
        }
    }
}
