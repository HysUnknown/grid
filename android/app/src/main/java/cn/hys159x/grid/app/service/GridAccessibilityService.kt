package cn.hys159x.grid.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import cn.hys159x.grid.engine.rule.ActionType
import cn.hys159x.grid.engine.rule.CompiledRule
import cn.hys159x.grid.engine.rule.EngineState
import cn.hys159x.grid.engine.rule.NodeMatcher
import cn.hys159x.grid.engine.rule.ScreenContext
import cn.hys159x.grid.engine.subscription.CompiledRuleSet
import cn.hys159x.grid.app.data.GridDatabase

class GridAccessibilityService : AccessibilityService() {

    companion object {
        /** 悬浮准星等外部组件访问服务的入口（onServiceConnected/onDestroy 维护） */
        @Volatile var instance: GridAccessibilityService? = null
    }

    private val engineState = EngineState { SystemClock.elapsedRealtime() }
    private val matcher = NodeMatcher(engineState)
    private var lastPackageName: String? = null
    private var lastActivity: String? = null
    private var lastMatchAt = 0L
    private var lastNoRulesPkg: String? = null
    private var lastNoRulesAt = 0L

    // 用户真实触摸中：连点/坐标手势须让路——合成 tap 注入输入流会打断用户滑动
    // （表现为"点不动、划不动"），END 事件可能丢失，用起始时间兜底自动复位
    @Volatile private var userTouching = false
    @Volatile private var touchStartAt = 0L
    private fun userTouchActive(): Boolean =
        userTouching && SystemClock.elapsedRealtime() - touchStartAt < 10_000

    private fun dlog(msg: String) = android.util.Log.i("GridSvc", msg)

    private var eventsReceived = false
    private var lastEventAt = 0L
    private var watchdogScheduled = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ServiceState.serviceConnected.value = true
        dlog("connected: ruleSet=${ServiceState._ruleSet.value?.totalRules ?: "null"}")
        // 假活检测（华为等 ROM）：重装/adb 开启的服务可能收不到事件；运行中也可能被系统静默掐断事件流
        eventsReceived = false
        android.os.Handler(mainLooper).postDelayed({
            if (!eventsReceived) {
                dlog("fake-alive detected: no events in 15s")
                notifyFakeAlive()
            }
        }, 15_000)
        scheduleWatchdog()
    }

    /** 心跳看门狗：亮屏状态下 2 分钟零事件（正常使用不可能）判定为事件流被掐，通知用户重启服务 */
    private fun scheduleWatchdog() {
        if (watchdogScheduled) return
        watchdogScheduled = true
        val h = android.os.Handler(mainLooper)
        val tick = object : Runnable {
            override fun run() {
                runCatching {
                    val screenOn = getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true
                    if (screenOn && lastEventAt > 0 &&
                        SystemClock.elapsedRealtime() - lastEventAt > 120_000) {
                        dlog("watchdog: no events for 2min while interactive")
                        notifyFakeAlive()
                    }
                }
                h.postDelayed(this, 60_000)
            }
        }
        h.postDelayed(tick, 60_000)
    }

    private fun notifyFakeAlive() {
        runCatching {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(
                android.app.NotificationChannel("status", "服务状态", android.app.NotificationManager.IMPORTANCE_DEFAULT),
            )
            val n = androidx.core.app.NotificationCompat.Builder(this, "status")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("网格服务受限")
                .setContentText("服务未收到系统事件（常见于刚重装后）。请到 设置 → 辅助功能 → 网格，先关闭再重新开启。")
                .setAutoCancel(true)
                .build()
            nm.notify(2, n)
        }
    }

    override fun onDestroy() {
        instance = null
        ServiceState.serviceConnected.value = false
        super.onDestroy()
    }

    override fun onInterrupt() {}

    private var appEnterAt = 0L
    private var snapshotKey: String? = null
    private var snapshotCount = 0
    private var lastSnapshotAt = 0L
    private var lastClickAt = 0L
    private var lastClickX = 0
    private var lastClickY = 0
    private var skipLoopRunning = false

    private fun clickAt(x: Int, y: Int): Boolean {
        lastClickAt = SystemClock.elapsedRealtime(); lastClickX = x; lastClickY = y
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(p, 0, 50L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    // ---- 录制模式：捕获目标 App 开屏上的"跳过/关闭"候选按钮，自动生成规则 ----

    /** 循环扫描：800ms 首扫（3 秒短广告等不起长延迟），未命中每 800ms 补扫，
     *  最多 8 秒——慢渲染/快闪过都有机会抓到；扫到候选即结束 */
    private fun scheduleRecordScan(pkg: String) {
        dlog("recording: scan scheduled for $pkg")
        val h = android.os.Handler(mainLooper)
        val startedAt = SystemClock.elapsedRealtime()
        val tick = object : Runnable {
            override fun run() {
                if (!ServiceState.recording.value || ServiceState.recordingTargetPkg.value != pkg) return
                val found = runCatching { scanRecordCandidates(pkg) }
                    .onFailure { dlog("recording scan error: ${it.message}") }
                    .getOrDefault(false)
                if (found) return
                if (SystemClock.elapsedRealtime() - startedAt < 8000) {
                    h.postDelayed(this, 800)
                } else {
                    ServiceState.recording.value = false
                    dlog("recording: no candidates after 8s")
                }
            }
        }
        h.postDelayed(tick, 800)
    }

    /** @return 是否扫到候选（true 时录制状态已结束） */
    private fun scanRecordCandidates(pkg: String): Boolean {
        val root = rootInActiveWindow ?: run {
            dlog("recording: root null"); return false
        }
        val out = mutableListOf<ServiceState.RecordCandidate>()
        var nodeCount = 0
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
            if (out.size >= 10) return
            nodeCount++
            val text = n.text?.toString()
            val desc = n.contentDescription?.toString()
            val vid = n.viewIdResourceName?.substringAfterLast("id/")
            val hit = listOfNotNull(text, desc, vid).any {
                it.contains("跳过") || it.contains("关闭") ||
                    it.contains("skip", true) || it.contains("close", true)
            }
            // 只收可点击的节点（自身或祖先可点）：真按钮必然可点；
            // 宽匹配下"关闭/跳过"字样的文案、链接等杂音节点点不动，全部排除
            val clickable = n.isClickable || run {
                var p = n.parent
                while (p != null && !p.isClickable) p = p.parent
                p != null
            }
            if (hit && clickable) {
                fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
                // 选择器生成优先级：文本 > 有特异性的 vid > 描述。
                // 混淆 id（obfuscated/过短）无 App 内特异性且可能动态变化，仅作最后手段
                val vidUsable = vid != null && vid.length >= 4 && !vid.contains("obfusc", true)
                val sel = when {
                    text != null && text.isNotBlank() -> {
                        // 剥离前导倒计时数字（"5s 跳过"/"5 跳过"→"跳过"）：扫描时机在倒计时
                        // 中途，数字是快照噪声（下次变成 4/3/2 就匹配不上）；改包含匹配
                        val stripped = text.replace(Regex("^\\d+\\s*[sS秒]?\\s*"), "")
                        if (stripped != text && stripped.isNotBlank()) """[text*="${esc(stripped)}"]"""
                        else """[text="${esc(text)}"]"""
                    }
                    vidUsable -> """[vid="$vid"]"""
                    desc != null && desc.isNotBlank() -> """[desc="${esc(desc)}"]"""
                    vid != null -> """[vid="$vid"]"""   // 混淆 id 也比没有强（用户可编辑改）
                    else -> null
                }
                // 显示名同样剥掉倒计时数字：扫描在倒计时中途（2.5s 后），快照是
                // "跳过 3"这类瞬时文字，直接展示会让人误以为规则与数字相关
                val name = (text?.replace(Regex("^\\d+\\s*[sS秒]?\\s*"), "") ?: text)
                    ?: desc ?: vid ?: ""
                if (sel != null && name.length <= 16 &&
                    out.none { it.selector == sel }
                ) {
                    out += ServiceState.RecordCandidate(pkg, name, sel)
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        // 自绘开屏（如大麦）：广告页不暴露节点（树近空），无按钮可抓。
        // 须等启动窗口过（>3s）再判：首轮 800ms 时 App 可能还没渲染完，树暂时为空
        if (out.isEmpty() && nodeCount <= 2 && SystemClock.elapsedRealtime() - appEnterAt > 3000) {
            val act = lastActivity
            if (act != null && pkg.isNotEmpty() && !act.contains("launcher", true)) {
                out += ServiceState.RecordCandidate(
                    pkg, "自绘开屏页（无按钮可抓）· 返回键关闭", "[visibleToUser=true]",
                    action = "back", activityId = act,
                )
                dlog("recording: blank tree, activity-rule for $act")
            }
        }
        if (out.isEmpty()) {
            dlog("recording: 0 candidates this round")
            return false   // 不覆盖已有候选、不结束录制，等下一轮
        }
        // 排序：「跳过/skip」字样的排前（开屏正主），再按文本短优先（按钮文案通常极短）
        out.sortWith(
            compareByDescending<ServiceState.RecordCandidate> {
                it.displayName.contains("跳过") || it.displayName.contains("skip", true)
            }.thenBy { it.displayName.length },
        )
        ServiceState.recordCandidates.value = out
        ServiceState.recording.value = false   // 一次录制一个 App，抓完自动结束
        dlog("recording: ${out.size} candidates for $pkg")
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                userTouching = true; touchStartAt = SystemClock.elapsedRealtime()
            }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> userTouching = false
        }
        eventsReceived = true
        lastEventAt = SystemClock.elapsedRealtime()
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return            // 忽略自身
        if (!Settings.enabled.value) return       // 总开关（Task 13 接 UI 开关）
        val now = SystemClock.elapsedRealtime()

        val pkgChanged = pkg != lastPackageName
        if (pkgChanged) {                         // App 切换：重置运行时（resetMatch=app 的语义）
            lastPackageName = pkg
            appEnterAt = now
            dlog("enter pkg=$pkg")
            engineState.onAppEnter(currentRulesFor(pkg), now)
            if (ServiceState.recording.value && pkg == ServiceState.recordingTargetPkg.value) {
                scheduleRecordScan(pkg)
            }
        }
        var activityChanged = false
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val act = event.className?.toString()
            if (act != null && act != lastActivity) {
                lastActivity = act
                activityChanged = true
                engineState.onActivityReset(currentRulesFor(pkg), now)
            }
        }

        // ---- 事件风暴静默（发热治理）：冷启动窗口过后 CONTENT_CHANGED 全跳；
        //      STATE_CHANGED 仅在包名/Activity 变化时进入匹配（桌面内抖动全跳） ----
        val inLaunchWindow = now - appEnterAt <= 20_000
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (!inLaunchWindow) return
            if (now - lastMatchAt < 400) return
        } else if (!pkgChanged && !activityChanged) {
            return
        }

        val rules = currentRulesFor(pkg)
        if (rules.isEmpty()) {
            // 无规则包（systemui/launcher 等高频事件源）：日志限频，避免主线程高频写 logcat
            if (pkg != lastNoRulesPkg || now - lastNoRulesAt > 2000) {
                lastNoRulesPkg = pkg; lastNoRulesAt = now
                dlog("no rules pkg=$pkg")
            }
            return
        }
        val root = rootInActiveWindow ?: run {
            dlog("skip: rootInActiveWindow=null pkg=$pkg rules=${rules.size}")
            return
        }
        lastMatchAt = now
        maybeSnapshot(pkg, root, now)
        val ctx = ScreenContext(
            root = A11yNode.wrap(root),
            eventNode = event.source?.let { A11yNode(it, 0) },
            packageName = pkg, activityId = lastActivity, now = now,
            deadline = now + 200,   // 单轮 200ms 硬预算（NodeMatcher 逐规则截断）
        )
        val hit = try {
            matcher.match(ctx, rules)
        } catch (e: Exception) {
            dlog("match error pkg=$pkg: ${e.javaClass.simpleName}: ${e.message}")
            null
        } ?: return
        dlog("HIT pkg=$pkg rule=${hit.ruleName ?: hit.ruleId.groupKey}/${hit.ruleId.ruleKey} action=${hit.action} target=${(hit.target as? A11yNode)?.vid ?: hit.target?.text}")
        // 跳过类按钮：立即进入循环点击（每 200ms 重查节点树并点击），直到按钮消失/切App/8s超时。
        // 倒计时期间点了无效但无害，高频点到生效为止——比解析倒计时更简单也更稳。
        // CLICK_CENTER 规则（如美图）同样纳入：坐标手势在部分 ROM 被丢弃，循环里节点/祖先点击优先
        val targetText = hit.target.text
        // 连点判定：文字带"跳过"的按钮，或自定义规则（groupKey=-2，用户录制即明确意图，
        // 自绘按钮无文字无法靠关键词识别，如大麦 vid=homepage_advert_pb）
        val looksLikeSkip = targetText?.contains("跳过") == true ||
            hit.target.desc?.contains("跳过") == true ||
            hit.ruleId.groupKey == -2
        if (looksLikeSkip &&
            (hit.action == ActionType.CLICK || hit.action == ActionType.CLICK_CENTER)
        ) {
            val node = hit.target as? A11yNode
            if (node != null) {
                dlog("skip loop start: vid=${node.vid} action=${hit.action}")
                scheduleSkipLoop(pkg, node.vid)
            }
            return
        }
        if (hit.action == ActionType.BACK) {
            // 返回动作。Activity 规则带守卫+补按：开屏已自己过去（无广告快闪）则不按防误退主页；
            // 200ms 首按（早按被吞也能靠补按救回），按完仍在开屏页说明被吞，400ms 后补（最多 3 次）。
            // 普通 back 规则无守卫不补按：800ms 单次，避免过早被启动过程吞掉
            val targetAct = rules.firstOrNull { it.id == hit.ruleId }?.activityIds?.firstOrNull()
            val h = android.os.Handler(mainLooper)
            var attempts = 0
            val runnable = object : Runnable {
                override fun run() {
                    if (lastPackageName != pkg) return
                    if (targetAct != null && lastActivity != targetAct) {
                        dlog("back skipped: left splash (act=$lastActivity)")
                        return
                    }
                    val ok = performGlobalAction(GLOBAL_ACTION_BACK)
                    attempts++
                    dlog("back action#$attempts ok=$ok pkg=$pkg act=$lastActivity")
                    recordIntercept(hit.action, hit.target as? A11yNode, pkg, clickOk = ok)
                    if (targetAct != null && attempts < 3 && lastActivity == targetAct) {
                        h.postDelayed(this, 400)
                    }
                }
            }
            h.postDelayed(runnable, if (targetAct != null) 200L else 800L)
            return
        }
        performAction(hit.action, hit.target as? A11yNode, pkg)   // 日志记真实前台包名（全局规则 appId 为 "*"")
    }

    /** 跳过按钮循环点击：每 200ms 在当前节点树中重查（text 含"跳过"或同 vid）并点击，
     *  直到按钮消失（广告关闭/进入主页）/切换 App/8 秒超时。直接树查找不走规则管线，
     *  规避 actionCd/actionMaximum 冷却导致的中途失配 */
    private fun scheduleSkipLoop(pkg: String, vid: String?) {
        if (skipLoopRunning) return
        skipLoopRunning = true
        val startAct = lastActivity   // 广告页 Activity：变化=广告已被关闭/跳过，立即收手
        val h = android.os.Handler(mainLooper)
        val startedAt = SystemClock.elapsedRealtime()
        val tick = object : Runnable {
            override fun run() {
                var again = false
                runCatching {
                    val now = SystemClock.elapsedRealtime()
                    if (lastPackageName != pkg) {
                        // 转场瞬间可能闪 launcher（点击已成功、广告页已关）：也算跳过成功，记录
                        dlog("skip loop end: pkg-left after ${now - startedAt}ms")
                        recordIntercept(ActionType.CLICK, null, pkg, descOverride = "跳过成功（离开广告页）")
                        return@runCatching
                    }
                    if (lastActivity != startAct) {   // 已进主页：主页文字（如"已点跳过"）会被误匹配连点
                        dlog("skip loop done: activity changed after ${now - startedAt}ms")
                        recordIntercept(ActionType.CLICK, null, pkg, descOverride = "跳过成功（页面切换）")
                        return@runCatching
                    }
                    if (now - startedAt > 8000) {
                        dlog("skip loop end: timeout after ${now - startedAt}ms")
                        recordIntercept(ActionType.CLICK, null, pkg, descOverride = "循环点击超时")
                        return@runCatching
                    }
                    if (userTouchActive()) {   // 用户手指在屏上：让路，松手后继续点
                        again = true; h.postDelayed(this, 200); return@runCatching
                    }
                    val root = rootInActiveWindow
                    if (root == null) {   // 窗口切换瞬间 root 可为空：稍后重试而非误判结束
                        again = true; h.postDelayed(this, 200); return@runCatching
                    }
                    val node = findSkipNode(root, vid)
                    // 纯文字节点（不可点且无可点小祖先）= 广告已不在，只是文字残留提及"跳过"：视为完成
                    val clickableAncestor = node?.let {
                        if (it.isClickable) it else nearestClickableAncestor(A11yNode(it, 0))?.raw()
                    }
                    if (node != null && clickableAncestor == null) {
                        dlog("skip loop done: text-only target after ${now - startedAt}ms")
                        recordIntercept(ActionType.CLICK, null, pkg, descOverride = "循环点击至跳过完成")
                        return@runCatching
                    }
                    if (node != null) {
                        var ok = runCatching {
                            node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                        }.getOrDefault(false)
                        if (!ok && clickableAncestor != null && clickableAncestor !== node) {
                            ok = runCatching {
                                clickableAncestor.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                            }.getOrDefault(false)
                        }
                        if (!ok) ok = clickCenter(A11yNode(node, 0))
                        dlog("skip loop click ok=$ok text=${node.text} vid=${node.viewIdResourceName}")
                        again = true
                        h.postDelayed(this, 200)
                    } else {
                        dlog("skip loop done: button gone after ${now - startedAt}ms")
                        recordIntercept(ActionType.CLICK, null, pkg, descOverride = "循环点击至跳过完成")
                    }
                }
                if (!again) skipLoopRunning = false
            }
        }
        h.post(tick)
    }

    private fun findSkipNode(
        n: android.view.accessibility.AccessibilityNodeInfo,
        vid: String?,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        val t = n.text?.toString()
        val d = n.contentDescription?.toString()
        val v = n.viewIdResourceName?.substringAfterLast("id/")
        if ((t != null && t.contains("跳过")) || (d != null && d.contains("跳过")) ||
            (vid != null && v == vid)
        ) return n
        for (i in 0 until n.childCount) n.getChild(i)?.let { c -> findSkipNode(c, vid)?.let { return it } }
        return null
    }

    private fun performAction(action: ActionType, node: A11yNode?, pkg: String) {        val now = SystemClock.elapsedRealtime()
        val b = node?.bounds
        // 全局点击去抖：同一位置 800ms 内只点一次。多条规则/多轮事件命中同一按钮时，
        // 快速连续坐标点击会被系统合并为双击而全部失效
        if (b != null && action != ActionType.BACK && action != ActionType.NONE) {
            if (now - lastClickAt < 800 &&
                kotlin.math.abs(b.centerX - lastClickX) < 60 && kotlin.math.abs(b.centerY - lastClickY) < 60
            ) {
                dlog("click debounce skipped")
                return
            }
            lastClickAt = now; lastClickX = b.centerX; lastClickY = b.centerY
        }
        val ok = when (action) {
            ActionType.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            ActionType.NONE -> true
            else -> when (action) {
                // 节点点击优先（ACTION_CLICK 不经屏幕坐标，不受顶部系统手势热区拦截——
                // 通知栏下方按钮的坐标触点会被 SystemUI 当下拉手势吃掉）；
                // 坐标手势在本华为机型还会被静默丢弃，仅作无节点可点时的降级
                ActionType.CLICK -> {
                    val target = when {
                        node?.raw()?.isClickable == true -> node
                        node != null -> nearestClickableAncestor(node)
                        else -> null
                    }
                    if (target != null) clickNode(target) else clickCenter(node)
                }
                ActionType.CLICK_NODE -> node?.let { clickNode(it) } ?: false
                ActionType.LONG_CLICK, ActionType.LONG_CLICK_NODE, ActionType.LONG_CLICK_CENTER ->
                    gesture(node, longPress = true)
                else -> clickCenter(node)   // CLICK_CENTER 及兜底
            }
        }
        // 点击失败的也记录（日志可区分"没点"与"点了派发失败"）
        recordIntercept(action, node, pkg, clickOk = ok)
    }

    // 按包名缓存（ruleSet 引用为失效键）：事件回调高频调用，避免每条事件重复做
    // 全局组过滤+列表拼接——无障碍回调与 UI 同住主线程，这是 App 内卡顿的大头
    private var rcSetRef: cn.hys159x.grid.engine.subscription.CompiledRuleSet? = null
    private var rcMap = HashMap<String, List<CompiledRule>>()

    private fun currentRulesFor(pkg: String): List<CompiledRule> {
        val set = ServiceState._ruleSet.value ?: return emptyList()
        if (rcSetRef !== set) {
            rcSetRef = set
            rcMap = HashMap()
        }
        return rcMap.getOrPut(pkg) {
            // 契约：应用 "*" 全局规则时，必须排除 CompiledRuleSet.globalGroupExcludedApps 中
            // 该规则 groupKey 排除名单内的当前 App（Task 8 实测：globalGroups[].apps 是排除名单）
            val global = set.rules["*"].orEmpty().filter { rule ->
                set.globalGroupExcludedApps[rule.id.groupKey]?.contains(pkg) != true
            }
            set.rules[pkg].orEmpty() + global
        }
    }

    /** 调试快照（spec §5 快照审查雏形）：规则开发期使用，默认关闭（整树遍历有 CPU 开销）。
     *  开启方式：filesDir 下创建 snap_on 标记文件后重启服务 */
    private fun maybeSnapshot(pkg: String, root: android.view.accessibility.AccessibilityNodeInfo, now: Long) {
        if (!java.io.File(filesDir, "snap_on").exists()) return
        if (now - appEnterAt > 6000) return
        val key = "$pkg@$appEnterAt"
        if (snapshotKey != key) { snapshotKey = key; snapshotCount = 0 }
        if (snapshotCount >= 5 || now - lastSnapshotAt < 1200) return
        snapshotCount++
        lastSnapshotAt = now
        val seq = snapshotCount
        kotlin.concurrent.thread {
            runCatching {
                val sb = StringBuilder()
                fun walk(n: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
                    val r = android.graphics.Rect(); n.getBoundsInScreen(r)
                    sb.append("  ".repeat(depth))
                        .append(n.className?.toString()?.substringAfterLast('.') ?: "?")
                        .append(" id=").append(n.viewIdResourceName ?: "")
                        .append(" text=").append(n.text ?: "")
                        .append(" desc=").append(n.contentDescription ?: "")
                        .append(" clk=").append(n.isClickable)
                        .append(" bounds=[").append(r.left).append(",").append(r.top).append("][").append(r.right).append(",").append(r.bottom).append("]")
                        .append('\n')
                    for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
                }
                walk(root, 0)
                val dir = java.io.File(filesDir, "snapshots").apply { mkdirs() }
                java.io.File(dir, "${pkg.replace('.', '_')}_s${seq}_${System.currentTimeMillis()}.txt").writeText(sb.toString())
                val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
                files.dropLast(30).forEach { it.delete() }
                dlog("snapshot#$seq saved pkg=$pkg chars=${sb.length}")
            }
        }
    }

    /** 记录拦截到内存流与 Room（保留最近 200 条） */
    private fun recordIntercept(
        action: ActionType, node: A11yNode?, pkg: String,
        descOverride: String? = null, clickOk: Boolean = true,
    ) {
        run {
            val event = InterceptEvent(
                time = System.currentTimeMillis(),
                packageName = pkg, appName = null,
                ruleName = null, action = action.name + if (clickOk) " ✓" else " ✗",
                nodeDesc = descOverride ?: node?.text ?: node?.desc ?: node?.vid,
            )
            ServiceState.record(event)
            kotlin.concurrent.thread {
                runCatching {
                    GridDatabase.get(applicationContext).interceptLogDao().apply {
                        insert(
                            cn.hys159x.grid.app.data.InterceptLog(
                                time = event.time, packageName = event.packageName,
                                action = event.action, nodeDesc = event.nodeDesc, clickOk = clickOk,
                            ),
                        )
                        trim()   // 保留最近 200 条（隐私声明承诺）
                    }
                }.onFailure { dlog("log insert failed: ${it.message}") }
            }
        }
    }

    private fun clickNode(node: A11yNode): Boolean =
        node.raw().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)

    /** 最近的可点击祖先。面积限制 ≤ 目标 4 倍：全屏广告容器也可点，但点它=进广告详情页 */
    private fun nearestClickableAncestor(node: A11yNode): A11yNode? {
        val nb = node.bounds
        val nArea = ((nb.right - nb.left) * (nb.bottom - nb.top)).coerceAtLeast(1)
        var p = node.parent as? A11yNode
        while (p != null) {
            if (p.clickable) {
                val pb = p.bounds
                val pArea = (pb.right - pb.left) * (pb.bottom - pb.top)
                return if (pArea <= nArea * 4) p else null
            }
            p = p.parent as? A11yNode
        }
        return null
    }

    private fun clickCenter(node: A11yNode?): Boolean = gesture(node, longPress = false)

    private fun gesture(node: A11yNode?, longPress: Boolean): Boolean {
        if (userTouchActive()) {   // 合成手势会与用户真实触摸冲突（打断滑动），让路
            dlog("gesture skipped: user touching")
            return false
        }
        val b = node?.bounds ?: return false
        val m = resources.displayMetrics
        if (b.offScreen(m.widthPixels, m.heightPixels)) return false
        val p = Path().apply { moveTo(b.centerX.toFloat(), b.centerY.toFloat()) }
        // 点击 stroke 150ms：真机验证 50ms 短手势在部分 ROM 被静默过滤（点击无效果）
        val stroke = GestureDescription.StrokeDescription(
            p, 0, if (longPress) 400L else 150L,
        )
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
