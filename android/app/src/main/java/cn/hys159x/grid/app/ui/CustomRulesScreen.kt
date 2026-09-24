package cn.hys159x.grid.app.ui

import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.hys159x.grid.app.service.ServiceState
import cn.hys159x.grid.app.subscription.CustomRulesStore
import cn.hys159x.grid.app.subscription.SubscriptionRepository
import kotlinx.coroutines.launch

private data class AppItem(val pkg: String, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomRulesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val recording by ServiceState.recording.collectAsState()
    val targetPkg by ServiceState.recordingTargetPkg.collectAsState()
    val candidates by ServiceState.recordCandidates.collectAsState()
    var savedRules by remember { mutableStateOf(CustomRulesStore.list(context)) }
    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var editSel by remember { mutableStateOf("") }
    var formMode by remember { mutableStateOf(true) }
    var fProp by remember { mutableStateOf("text") }
    var fOp by remember { mutableStateOf("^=") }
    var fVal by remember { mutableStateOf("") }
    var fAction by remember { mutableStateOf("click") }   // click / back
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var showExport by remember { mutableStateOf(false) }
    var importedSet by remember { mutableStateOf(cn.hys159x.grid.app.subscription.SubscriptionShare.hasImported(context)) }

    // 从文件导入完整规则集（微信等接收 .json 后用系统文件选择器选取）
    val importFileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            }.getOrNull()?.let { text ->
                if (cn.hys159x.grid.app.subscription.SubscriptionShare.saveImported(context, text)) {
                    importedSet = true
                    SubscriptionRepository.get(context).start()   // 规则重载（imported 参与合并）
                    android.widget.Toast.makeText(context, "完整规则集已导入并生效", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(context, "文件格式不对，需为网格导出的规则集文件", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 页面回到前台时重读规则（从目标 App 返回本页时，候选/已存规则立即可见）
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                savedRules = CustomRulesStore.list(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 后台加载已安装的可启动应用
    LaunchedEffect(Unit) {
        kotlin.concurrent.thread {
            val pm = context.packageManager
            val list = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
            ).map {
                AppItem(it.activityInfo.packageName, it.loadLabel(pm).toString())
            }.distinctBy { it.pkg }.sortedBy { it.name }
            apps = list
        }
    }

    fun startRecord(app: AppItem) {
        ServiceState.recordCandidates.value = emptyList()
        ServiceState.recordingTargetPkg.value = app.pkg
        ServiceState.recording.value = true
        runCatching {
            context.packageManager.getLaunchIntentForPackage(app.pkg)?.let { context.startActivity(it) }
        }
    }

    fun reload() {
        savedRules = CustomRulesStore.list(context)
        SubscriptionRepository.get(context).start()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自定义规则") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val filtered = if (query.isBlank()) apps else apps.filter {
            it.name.contains(query, true) || it.pkg.contains(query, true)
        }
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---- 录制状态/候选（置顶显示） ----
            if (recording || candidates.isNotEmpty()) {
                item {
                    Text(
                        if (recording) "第二步：录制中（${targetPkg ?: ""}）… 广告出现 3 秒后回到这里"
                        else "第二步：识别到 ${candidates.size} 个候选（点击添加为规则）",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(candidates) { c ->
                val candAppName = remember(c.packageName) {
                    runCatching {
                        val ai = context.packageManager.getApplicationInfo(c.packageName, 0)
                        context.packageManager.getApplicationLabel(ai).toString()
                    }.getOrDefault(c.packageName)
                }
                ListItem(
                    headlineContent = { Text("$candAppName · ${c.displayName}") },
                    supportingContent = {
                        Text(
                            (if (c.activityId != null) "进入 ${c.activityId.substringAfterLast('.')} 时按返回键"
                            else c.selector),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = {
                        Row {
                            TextButton(onClick = {
                                CustomRulesStore.add(context, c.packageName, c.selector, c.displayName, c.action, c.activityId)
                                ServiceState.recordCandidates.value = emptyList()
                                ServiceState.recording.value = false
                                reload()
                            }) { Text("添加") }
                            TextButton(onClick = {
                                editing = EditTarget(c.packageName, c.displayName, null, c.action, c.activityId)
                                editSel = c.selector
                            }) { Text("编辑") }
                            TextButton(onClick = {
                                ServiceState.recordCandidates.value =
                                    ServiceState.recordCandidates.value.filterNot { it == c }
                            }) { Text("删除") }
                        }
                    },
                )
                HorizontalDivider()
            }

            // ---- 我的规则 ----
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("我的规则（${savedRules.size} 条，最高优先级）", style = MaterialTheme.typography.titleMedium)
                    Row {
                        TextButton(onClick = { showImport = true }) { Text("导入") }
                        TextButton(onClick = { showExport = true }) { Text("导出") }
                    }
                }
            }
            if (importedSet) {
                item {
                    ListItem(
                        headlineContent = { Text("外部规则集已导入") },
                        supportingContent = { Text("来自文件导入的完整规则集，优先于内置规则生效") },
                        trailingContent = {
                            TextButton(onClick = {
                                cn.hys159x.grid.app.subscription.SubscriptionShare.removeImported(context)
                                importedSet = false
                                reload()
                                android.widget.Toast.makeText(context, "已移除外部规则集", android.widget.Toast.LENGTH_SHORT).show()
                            }) { Text("移除") }
                        },
                    )
                    HorizontalDivider()
                }
            }
            if (savedRules.isEmpty()) {
                item { Text("暂无。从上方列表选一个应用开始录制。", style = MaterialTheme.typography.bodySmall) }
            }
            items(savedRules) { r ->
                val appName = remember(r.packageName) {
                    runCatching {
                        val ai = context.packageManager.getApplicationInfo(r.packageName, 0)
                        context.packageManager.getApplicationLabel(ai).toString()
                    }.getOrDefault(r.packageName)
                }
                ListItem(
                    headlineContent = {
                        Text(
                            "$appName · ${r.name}" + when {
                                r.activityId != null -> " ·［返回键·开屏页］"
                                r.action == "back" -> " ·［返回键］"
                                else -> ""
                            },
                        )
                    },
                    supportingContent = {
                        Text(
                            r.activityId ?: r.selector,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = {
                        Row {
                            TextButton(onClick = {
                                editing = EditTarget(r.packageName, r.name, r.selector, r.action, r.activityId)
                                editSel = r.selector
                            }) { Text("编辑") }
                            TextButton(onClick = {
                                CustomRulesStore.remove(context, r.packageName, r.selector)
                                reload()
                            }) { Text("删除") }
                        }
                    },
                )
                HorizontalDivider()
            }

            // ---- 应用列表（在下方，用右侧悬浮按钮置顶/置底快速跳转） ----
            item {
                Text("选择要录制的应用", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Text(
                    "点\"录制\"会打开该应用并自动扫描开屏广告上的\"跳过/关闭\"按钮，" +
                        "稍后回到本页，在上方候选列表中点击添加为规则。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索应用（推荐用搜索，不必翻列表）") },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    singleLine = true,
                )
            }
            items(filtered) { app ->
                val bmp = remember(app.pkg) {
                    runCatching {
                        (context.packageManager.getApplicationIcon(app.pkg) as? BitmapDrawable)?.bitmap
                    }.getOrNull()
                }
                ListItem(
                    leadingContent = {
                        if (bmp != null) {
                            Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.size(36.dp))
                        } else {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(36.dp))
                        }
                    },
                    headlineContent = { Text(app.name, fontSize = 15.sp) },
                    supportingContent = { Text(app.pkg, fontSize = 11.sp) },
                    trailingContent = {
                        TextButton(onClick = { startRecord(app) }) { Text("录制") }
                    },
                )
                HorizontalDivider()
            }
            if (apps.isNotEmpty() && filtered.isEmpty()) {
                item { Text("无匹配应用", style = MaterialTheme.typography.bodySmall) }
            }
        }

        // 右下角悬浮：置顶 / 置底
        Column(
            Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SmallFloatingActionButton(onClick = {
                scope.launch { listState.animateScrollToItem(0) }
            }) { Text("顶") }
            SmallFloatingActionButton(onClick = {
                scope.launch { listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1) }
            }) { Text("底") }
        }
        }
    }

    editing?.let { t ->
        // 从 selector 解析预填：[vid="x"] / [text^="x"] / [desc*="x"]
        LaunchedEffect(t) {
            fAction = t.action ?: "click"
            // 预填反转义（值里的 \" → "）：保存时会重新转义，不反转义会层层叠加
            val m = Regex("\\[(vid|text|desc)(\\^|\\*)?=\"(.*?)\"]").find(editSel)
            if (m != null) {
                fProp = m.groupValues[1]; fOp = m.groupValues[2] + "="
                fVal = m.groupValues[3].replace("\\\"", "\"").replace("\\\\", "\\")
                formMode = true
            } else {
                formMode = false
            }
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (t.oldSelector != null) "修改规则" else "编辑选择器") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${t.displayName}（${t.pkg}）", style = MaterialTheme.typography.bodySmall)
                    if (t.activityId != null) {
                        Text(
                            "Activity 规则：进入开屏页 ${t.activityId.substringAfterLast('.')} 时按返回键关闭广告。" +
                                "（该 App 广告为自绘页面，无按钮可抓，只能整页关闭）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // tap 坐标规则已移除：坐标手势在本机被静默丢弃，统一节点连点
                    if (formMode) {
                        Text("匹配属性", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("text", "vid", "desc").forEach { p ->
                                FilterChip(selected = fProp == p, onClick = { fProp = p }, label = { Text(p) })
                            }
                        }
                        Text("匹配方式", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("=" to "等于", "^=" to "开头", "*=" to "包含").forEach { (op, label) ->
                                FilterChip(selected = fOp == op, onClick = { fOp = op }, label = { Text(label) })
                            }
                        }
                        OutlinedTextField(
                            value = fVal,
                            onValueChange = { fVal = it },
                            singleLine = true,
                            label = { Text("匹配值（只填文字本身，如：跳过）") },
                        )
                        Text("动作", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("click" to "点击按钮", "back" to "按返回键").forEach { (a, label) ->
                                FilterChip(selected = fAction == a, onClick = { fAction = a }, label = { Text(label) })
                            }
                        }
                        Text(
                            "点击无效的 App（广告带反制）可改用返回键：直接关闭整个开屏页",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (fVal.isNotBlank()) {
                            // 预览用中文引号包裹值（手机字体下英文引号与等号几乎同形，易误读为双等号）
                            Text(
                                "预览：$fProp ${chipLabelOf(fOp)} 「$fVal」",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            "text=按钮文字 · vid=控件 id（最稳） · desc=内容描述。多个条件需高级模式。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        OutlinedTextField(
                            value = editSel,
                            onValueChange = { editSel = it },
                            singleLine = true,
                            label = { Text("选择器（原始语法）") },
                        )
                        Text(
                            "语法：[vid=\"id\"] · [text^=\"跳过\"] · [text*=\"跳过\"] · [desc=\"关闭\"]；多条件并列=同时满足。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { formMode = !formMode }) {
                        Text(if (formMode) "切换到高级（手写完整语法）" else "切换到表单模式")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val sel = if (formMode && fVal.isNotBlank()) {
                        // 防呆：匹配值误填成整段属性（如 text="跳过"）时剥壳取内层文字。
                        // fOp 自带等号（"=" / "^=" / "*="），此处不可再拼字面 =（曾致 *==）
                        val inner = Regex("^\\s*(text|vid|desc)\\s*[\\^*]?\\s*=\\s*\"(.*)\"\\s*$")
                            .find(fVal.trim())?.groupValues?.get(2) ?: fVal.trim()
                        val v = inner.replace("\\", "\\\\").replace("\"", "\\\"")
                        "[$fProp$fOp\"$v\"]"
                    } else editSel.trim()
                    if (sel.isNotEmpty()) {
                        if (t.oldSelector != null) {
                            CustomRulesStore.remove(context, t.pkg, t.oldSelector)   // 修改：先移除旧规则
                        } else {
                            ServiceState.recordCandidates.value = emptyList()
                            ServiceState.recording.value = false
                        }
                        CustomRulesStore.add(context, t.pkg, sel, t.displayName, fAction, t.activityId)
                        reload()
                    }
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }

    // 导入他人分享的规则：粘贴文本 → 去重合并
    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("导入规则") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "粘贴别人分享的规则文本（微信里长按消息 → 复制，再点下方\"粘贴\"）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        minLines = 4,
                        label = { Text("规则文本") },
                    )
                    TextButton(onClick = {
                        runCatching {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.primaryClip?.getItemAt(0)?.text?.toString()?.let { importText = it }
                        }
                    }) { Text("粘贴") }
                    HorizontalDivider()
                    Text(
                        "收到完整规则集文件（.json）？从文件导入，优先于内置规则生效",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = {
                        showImport = false
                        importFileLauncher.launch(arrayOf("*/*"))
                    }) { Text("从文件导入完整规则集") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when (val n = CustomRulesStore.importJson(context, importText)) {
                        -1 -> android.widget.Toast.makeText(context, "格式不对，请粘贴完整的分享文本", android.widget.Toast.LENGTH_SHORT).show()
                        0 -> android.widget.Toast.makeText(context, "没有新规则（全部已存在）", android.widget.Toast.LENGTH_SHORT).show()
                        else -> {
                            android.widget.Toast.makeText(context, "已导入 $n 条规则", android.widget.Toast.LENGTH_SHORT).show()
                            reload()
                        }
                    }
                    showImport = false
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text("取消") } },
        )
    }

    // 导出方式选择：文本（仅自定义，微信直发）/ 文件（完整规则集）
    if (showExport) {
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("导出规则") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("分享我的自定义规则", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "仅你自己录制的规则，一段文本消息即可发送，对方粘贴导入",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = {
                        showExport = false
                        if (savedRules.isEmpty()) {
                            android.widget.Toast.makeText(context, "暂无自定义规则可导出", android.widget.Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        runCatching {
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, CustomRulesStore.exportJson(context))
                                    },
                                    "分享自定义规则",
                                ),
                            )
                        }
                    }) { Text("以文本分享") }
                    HorizontalDivider()
                    Text("导出完整规则集（文件）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "包含全部内置规则 + 你的自定义规则，生成 .json 文件发送；" +
                            "对方在导入 → 从文件导入，导入后优先于其内置规则生效",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = {
                        showExport = false
                        runCatching {
                            val f = java.io.File(context.cacheDir, "grid_rules_full.json")
                            f.writeText(cn.hys159x.grid.app.subscription.SubscriptionShare.fullRulesJson(context))
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, context.packageName + ".fileprovider", f,
                            )
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    },
                                    "导出完整规则集",
                                ),
                            )
                        }.onFailure {
                            android.widget.Toast.makeText(context, "导出失败：${it.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("生成文件分享") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showExport = false }) { Text("取消") } },
        )
    }
}

/** 编辑目标：oldSelector 为 null 表示来自候选（新增），非空表示修改已存规则 */
private data class EditTarget(
    val pkg: String, val displayName: String, val oldSelector: String?,
    val action: String? = null, val activityId: String? = null,
)

private fun chipLabelOf(op: String): String = when (op) {
    "=" -> "等于"
    "^=" -> "开头"
    "*=" -> "包含"
    else -> op
}
