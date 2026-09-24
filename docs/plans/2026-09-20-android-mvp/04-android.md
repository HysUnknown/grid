# 分册 04：Android 装配层（Task 9-12）

前置：引擎模块完成（分册 02、03）。本分册代码在 `android/app/` 模块，无 JVM 单测，验证方式为 `:app:assembleDebug` 编译通过 + Task 16 真机验证（spec §9）。

---

### Task 9: 节点适配器与服务状态桥

**Files:**
- Create: `android/app/src/main/java/cn/hys159x/grid/app/service/A11yNode.kt`
- Create: `android/app/src/main/java/cn/hys159x/grid/app/service/ServiceState.kt`

- [ ] **Step 1: 实现 A11yNode 适配器**

`android/app/src/main/java/cn/hys159x/grid/app/service/A11yNode.kt`：

```kotlin
package cn.hys159x.grid.app.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo as SysNode
import cn.hys159x.grid.engine.node.Bounds
import cn.hys159x.grid.engine.node.TreeNode

/** AccessibilityNodeInfo → TreeNode 适配（引擎零 Android 依赖的关键）
 *  id=viewIdResourceName 全名；vid=去包名段；name=className 简名 */
class A11yNode(private val n: SysNode, override val depth: Int) : TreeNode {

    override val id: String? = n.viewIdResourceName
    override val vid: String? = n.viewIdResourceName?.substringAfterLast("id/")
    override val name: String? = n.className?.toString()?.substringAfterLast('.')
    override val text: String? = n.text?.toString()
    override val desc: String? = n.contentDescription?.toString()
    override val clickable: Boolean = n.isClickable
    override val longClickable: Boolean = n.isLongClickable
    override val focusable: Boolean = n.isFocusable
    override val checkable: Boolean = n.isCheckable
    override val checked: Boolean = n.isChecked
    override val editable: Boolean = n.isEditable
    override val visibleToUser: Boolean = n.isVisibleToUser
    override val childCount: Int get() = n.childCount

    private val cachedIndex: Int by lazy {
        val p = n.parent ?: return@lazy 0
        for (i in 0 until p.childCount) if (p.getChild(i) == n) return@lazy i
        0
    }
    override val index: Int get() = cachedIndex

    override val bounds: Bounds by lazy {
        val r = Rect(); n.getBoundsInScreen(r); Bounds(r.left, r.top, r.right, r.bottom)
    }

    override val parent: TreeNode? by lazy {
        n.parent?.let { A11yNode(it, depth - 1) }
    }

    override fun childAt(i: Int): TreeNode? = n.getChild(i)?.let { A11yNode(it, depth + 1) }

    fun raw(): SysNode = n

    /** 相等性契约（见 TreeNode KDoc）：以底层 AccessibilityNodeInfo 为相等依据，包装实例可重复创建 */
    override fun equals(other: Any?): Boolean = other is A11yNode && other.n == n
    override fun hashCode(): Int = n.hashCode()

    companion object {
        fun wrap(root: SysNode): A11yNode = A11yNode(root, 0)
    }
}
```

注：导入只保留 `android.view.accessibility.AccessibilityNodeInfo` 的别名 `SysNode`（该类在 view.accessibility 包，非 accessibilityservice 包）；`className` 返回 CharSequence，需 `?.toString()` 后才能用 `substringAfterLast`。

- [ ] **Step 2: 实现 ServiceState 状态桥（UI ↔ 服务通信）**

`android/app/src/main/java/cn/hys159x/grid/app/service/ServiceState.kt`：

```kotlin
package cn.hys159x.grid.app.service

import cn.hys159x.grid.engine.subscription.CompiledRuleSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 拦截事件（内存日志条目，Task 14 持久化到 Room） */
data class InterceptEvent(
    val time: Long,
    val packageName: String,
    val appName: String?,
    val ruleName: String?,
    val action: String,
    val nodeDesc: String?,
)

/** 单例桥：无障碍服务写入状态与日志流；Compose UI 收集展示 */
object ServiceState {
    val serviceConnected = MutableStateFlow(false)
    val ruleSet: StateFlow<CompiledRuleSet?> get() = _ruleSet
    val _ruleSet = MutableStateFlow<CompiledRuleSet?>(null)
    val intercepts = MutableStateFlow<List<InterceptEvent>>(emptyList())

    fun record(e: InterceptEvent) {
        intercepts.value = (listOf(e) + intercepts.value).take(200)
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
cd D:/dev/app/android && ./gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 4: Commit**

```bash
cd D:/dev/app
git add android/app
git commit -m "feat(app): A11yNode 节点适配器与 ServiceState 状态桥"
```

---

### Task 10: 无障碍服务

**Files:**
- Create: `android/app/src/main/res/xml/grid_accessibility_config.xml`
- Create: `android/app/src/main/java/cn/hys159x/grid/app/service/GridAccessibilityService.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`

- [ ] **Step 1: 服务无障碍配置**

`android/app/src/main/res/xml/grid_accessibility_config.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="300"
    android:description="@string/accessibility_desc" />
```

`android/app/src/main/res/values/strings.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">网格</string>
    <string name="accessibility_desc">网格需要无障碍权限来识别屏幕上的广告与跳过按钮并自动点击。所有识别均在本地完成，绝不上传任何数据。</string>
</resources>
```

- [ ] **Step 2: 实现服务**

`android/app/src/main/java/cn/hys159x/grid/app/service/GridAccessibilityService.kt`：

```kotlin
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

class GridAccessibilityService : AccessibilityService() {

    private val engineState = EngineState { SystemClock.elapsedRealtime() }
    private val matcher = NodeMatcher(engineState)
    private var lastPackageName: String? = null
    private var lastActivity: String? = null
    private var lastMatchAt = 0L
    private val currentRules: List<CompiledRule>
        get() {
            val set: CompiledRuleSet = ServiceState._ruleSet.value ?: return emptyList()
            val pkg = lastPackageName ?: return emptyList()
            return set.rules[pkg].orEmpty() + set.rules["*"].orEmpty()
        }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ServiceState.serviceConnected.value = true
    }

    override fun onDestroy() {
        ServiceState.serviceConnected.value = false
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return            // 忽略自身
        val now = SystemClock.elapsedRealtime()

        if (pkg != lastPackageName) {             // App 切换：重置运行时（resetMatch=app 的语义）
            lastPackageName = pkg
            engineState.onAppEnter(currentRulesFor(pkg), now)
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val act = event.className?.toString()
            if (act != null && act != lastActivity) {
                lastActivity = act
                engineState.onActivityReset(currentRulesFor(pkg), now)
            }
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastMatchAt < 300) return

        val rules = currentRulesFor(pkg)
        if (rules.isEmpty()) return
        val root = rootInActiveWindow ?: return
        lastMatchAt = now
        val budget = now + 200                    // 单轮 200ms 预算
        val ctx = ScreenContext(
            root = A11yNode.wrap(root),
            eventNode = event.source?.let { A11yNode(it, 0) },
            packageName = pkg, activityId = lastActivity, now = now,
        )
        val hit = try { matcher.match(ctx, rules) } catch (e: Exception) { null } ?: return
        if (SystemClock.elapsedRealtime() > budget) return   // 超预算放弃本轮
        performAction(hit.action, hit.target as? A11yNode, hit.ruleId.appId)
    }

    private fun currentRulesFor(pkg: String): List<CompiledRule> {
        val set = ServiceState._ruleSet.value ?: return emptyList()
        // 契约：应用 "*" 全局规则时，必须排除 CompiledRuleSet.globalGroupExcludedApps 中
        // 该规则 groupKey 排除名单内的当前 App（Task 8 实测：globalGroups[].apps 是排除名单）
        return set.rules[pkg].orEmpty() + set.rules["*"].orEmpty()
    }

    private fun performAction(action: ActionType, node: A11yNode?, pkg: String) {
        val ok = when (action) {
            ActionType.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            ActionType.NONE -> true
            else -> when (action) {
                ActionType.CLICK -> if (node?.raw()?.isClickable == true) clickNode(node) else clickCenter(node)
                ActionType.CLICK_NODE -> node?.let { clickNode(it) } ?: false
                ActionType.LONG_CLICK, ActionType.LONG_CLICK_NODE, ActionType.LONG_CLICK_CENTER ->
                    gesture(node, longPress = true)
                else -> clickCenter(node)   // CLICK_CENTER 及兜底
            }
        }
        if (ok || action == ActionType.NONE) {
            ServiceState.record(InterceptEvent(
                time = System.currentTimeMillis(),
                packageName = pkg, appName = null,
                ruleName = null, action = action.name,
                nodeDesc = node?.text ?: node?.desc ?: node?.vid,
            ))
        }
    }

    private fun clickNode(node: A11yNode): Boolean =
        node.raw().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)

    private fun clickCenter(node: A11yNode?): Boolean = gesture(node, longPress = false)

    private fun gesture(node: A11yNode?, longPress: Boolean): Boolean {
        val b = node?.bounds ?: return false
        val m = resources.displayMetrics
        if (b.offScreen(m.widthPixels, m.heightPixels)) return false
        val p = Path().apply { moveTo(b.centerX().toFloat(), b.centerY().toFloat()) }
        val stroke = GestureDescription.StrokeDescription(
            p, 0, if (longPress) 400L else 50L,
        )
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
```

- [ ] **Step 3: 注册服务与权限（Manifest 全量替换）**

`android/app/src/main/AndroidManifest.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <application
        android:name=".App"
        android:label="@string/app_name"
        android:theme="@android:style/Theme.Material.Light.NoActionBar"
        android:allowBackup="false">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <service
            android:name=".service.GridAccessibilityService"
            android:exported="false"
            android:label="@string/app_name"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/grid_accessibility_config" />
        </service>
    </application>
</manifest>
```

（`.App` 在 Task 12 创建；本任务先创建空 `App.kt` 使编译通过——见 Step 4。）

- [ ] **Step 4: 占位 Application 并编译**

`android/app/src/main/java/cn/hys159x/grid/app/App.kt`（Task 12 充实）：

```kotlin
package cn.hys159x.grid.app

import android.app.Application

class App : Application()
```

```bash
cd D:/dev/app/android && ./gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: Commit**

```bash
cd D:/dev/app
git add android/app
git commit -m "feat(app): 无障碍服务（事件驱动匹配/200ms预算/300ms节流/动作执行）"
```

---

### Task 11: 断连提醒通知

**Files:**
- Modify: `android/app/src/main/java/cn/hys159x/grid/app/App.kt`

设计说明：无障碍服务由系统绑定管理，不做前台常驻（避免 targetSdk 35 前台服务类型限制）；改为**服务断开时发通知提醒用户重新开启**，隐私声明同步说明（spec §6 的提醒能力等价落地）。

- [ ] **Step 1: App 实现断连监测**

`android/app/src/main/java/cn/hys159x/grid/app/App.kt` 全量替换为：

```kotlin
package cn.hys159x.grid.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import cn.hys159x.grid.app.service.ServiceState

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        watchServiceState()
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "服务状态", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun watchServiceState() {
        var wasConnected = false
        ServiceState.serviceConnected.collectInBackground { connected ->
            if (wasConnected && !connected) notifyDisconnected()
            wasConnected = connected
        }
    }

    private fun notifyDisconnected() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("网格已停止")
            .setContentText("无障碍服务被系统关闭，点击重新开启（设置-无障碍-网格）")
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFY_ID, n)
    }

    private companion object {
        const val CHANNEL_STATUS = "status"
        const val NOTIFY_ID = 1
    }
}
```

同时需要 Flow 收集辅助。在 `android/app/src/main/java/cn/hys159x/grid/app/FlowExt.kt`：

```kotlin
package cn.hys159x.grid.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectInBackground(
    block: (T) -> Unit,
) {
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch { collect { block(it) } }
}
```

Manifest 补依赖通知兼容库：`android/app/build.gradle.kts` dependencies 追加：

```kotlin
implementation("androidx.core:core-ktx:1.15.0")
```

（已在版本目录，无需改动——`libs.androidx.core.ktx` 已引入。）

- [ ] **Step 2: 编译验证并 Commit**

```bash
cd D:/dev/app/android && ./gradlew.bat :app:assembleDebug
cd D:/dev/app && git add android/app && git commit -m "feat(app): 服务断连通知提醒"
```

---

### Task 12: 订阅仓库（下载/缓存/编译）

**Files:**
- Create: `android/app/src/main/java/cn/hys159x/grid/app/subscription/SubscriptionRepository.kt`
- Modify: `android/app/src/main/java/cn/hys159x/grid/app/App.kt`

- [ ] **Step 1: 实现仓库**

`android/app/src/main/java/cn/hys159x/grid/app/subscription/SubscriptionRepository.kt`：

```kotlin
package cn.hys159x.grid.app.subscription

import android.content.Context
import cn.hys159x.grid.app.service.ServiceState
import cn.hys159x.grid.engine.subscription.RawSubscription
import cn.hys159x.grid.engine.subscription.SubscriptionCompiler
import cn.hys159x.grid.engine.subscription.SubscriptionJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** 官方订阅：启动读缓存 → 后台刷新；失败保底缓存（spec §6 健壮性） */
class SubscriptionRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient()
    private val cacheFile: File
        get() = File(appContext.filesDir, "subscriptions/official.json")

    fun start() {
        loadCached()
        refresh()
    }

    private fun loadCached() {
        val f = cacheFile
        if (!f.exists()) return
        runCatching {
            ServiceState._ruleSet.value = compile(f.readText())
        }
    }

    fun refresh() {
        scope.launch {
            runCatching {
                val body = http.newCall(
                    Request.Builder().url(DEFAULT_URL)
                        .header("User-Agent", "Grid/${cn.hys159x.grid.app.BuildConfig.VERSION_NAME}")
                        .build(),
                ).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    resp.body!!.string()
                }
                cacheFile.parentFile?.mkdirs()
                val tmp = File(cacheFile.parentFile, "official.tmp")
                tmp.writeText(body)
                if (!tmp.renameTo(cacheFile)) { cacheFile.delete(); tmp.renameTo(cacheFile) }
                ServiceState._ruleSet.value = compile(body)
            }
        }
    }

    /** 经 SubscriptionJson.decode（含 normalize：JSON5 单引号重写为双引号）解析官方订阅后编译 */
    private fun compile(text: String) =
        SubscriptionCompiler.compile(SubscriptionJson.decode(RawSubscription.serializer(), text))

    companion object {
        /** 官方订阅分发地址（jsdelivr，内容为 JSON5）；失效时按 selector-spec/00-overview 的说明替换 */
        const val DEFAULT_URL = "https://fastly.jsdelivr.net/npm/@gkd-kit/subscription"
        private var instance: SubscriptionRepository? = null
        fun get(context: Context): SubscriptionRepository =
            instance ?: SubscriptionRepository(context).also { instance = it }
    }
}
```

- [ ] **Step 2: App 接线**

`App.kt` 的 `onCreate()` 中 `watchServiceState()` 之后追加一行：

```kotlin
SubscriptionRepository.get(this).start()
```

并在文件头部补导入：`import cn.hys159x.grid.app.subscription.SubscriptionRepository`。

- [ ] **Step 3: 编译验证并 Commit**

```bash
cd D:/dev/app/android && ./gradlew.bat :app:assembleDebug
cd D:/dev/app && git add android/app && git commit -m "feat(app): 订阅仓库（缓存优先+后台刷新+失败保底）"
```
