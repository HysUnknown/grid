# 分册 05：UI、日志持久化与发布（Task 13-15）

MVP 功能边界（相对 spec §5/§6 的裁剪，后续计划补齐）：订阅管理 UI、用户自加订阅 URL、本地自定义规则编辑、快照审查、防跳转白名单 UI——本期不做，仅内置官方订阅。总开关、无障碍引导、拦截日志为本期必做。

---

### Task 13: Compose UI（总开关/权限引导/日志页）

**Files:**
- Modify: `android/app/src/main/java/cn/hys159x/grid/app/MainActivity.kt`（全量重写）
- Create: `android/app/src/main/java/cn/hys159x/grid/app/ui/HomeScreen.kt`
- Create: `android/app/src/main/java/cn/hys159x/grid/app/ui/LogScreen.kt`
- Create: `android/app/src/main/java/cn/hys159x/grid/app/service/Settings.kt`

- [ ] **Step 1: 总开关存储**

`android/app/src/main/java/cn/hys159x/grid/app/service/Settings.kt`：

```kotlin
package cn.hys159x.grid.app.service

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

object Settings {
    private lateinit var prefs: SharedPreferences
    val enabled = MutableStateFlow(true)

    fun init(context: Context) {
        prefs = context.getSharedPreferences("grid", Context.MODE_PRIVATE)
        enabled.value = prefs.getBoolean("enabled", true)
    }

    fun setEnabled(v: Boolean) {
        enabled.value = v
        prefs.edit().putBoolean("enabled", v).apply()
    }
}
```

`App.onCreate()` 中 `watchServiceState()` 前追加：

```kotlin
Settings.init(this)
```

补导入 `import cn.hys159x.grid.app.service.Settings`。同时 `GridAccessibilityService.onAccessibilityEvent` 的事件入口（`if (pkg == packageName) return` 之后）追加：

```kotlin
if (!Settings.enabled.value) return
```

- [ ] **Step 2: HomeScreen（状态+开关+引导）**

`android/app/src/main/java/cn/hys159x/grid/app/ui/HomeScreen.kt`：

```kotlin
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
fun HomeScreen(onOpenLogs: () -> Unit) {
    val context = LocalContext.current
    val connected by ServiceState.serviceConnected.collectAsState()
    val enabled by Settings.enabled.collectAsState()
    val ruleSet by ServiceState.ruleSet.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("网格", style = MaterialTheme.typography.headlineLarge)
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
        Text(
            "隐私：无障碍数据仅在本地内存中匹配，不上传任何内容；仅在启动时拉取规则订阅。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
```

- [ ] **Step 3: LogScreen（内存日志）**

`android/app/src/main/java/cn/hys159x/grid/app/ui/LogScreen.kt`：

```kotlin
package cn.hys159x.grid.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.hys159x.grid.app.service.ServiceState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogScreen() {
    val events by ServiceState.intercepts.collectAsState()
    if (events.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp)) { Text("暂无拦截记录") }
        return
    }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        items(events) { e ->
            ListItem(
                headlineContent = { Text(e.action + "  " + (e.nodeDesc ?: "")) },
                supportingContent = { Text("${e.packageName} · ${e.ruleName ?: "-"}") },
                trailingContent = { Text(fmt.format(Date(e.time)), style = MaterialTheme.typography.bodySmall) },
            )
            HorizontalDivider()
        }
    }
}
```

- [ ] **Step 4: MainActivity 装配导航**

`android/app/src/main/java/cn/hys159x/grid/app/MainActivity.kt` 全量替换：

```kotlin
package cn.hys159x.grid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import cn.hys159x.grid.app.ui.HomeScreen
import cn.hys159x.grid.app.ui.LogScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2E7D32))) {
                Surface { var showLogs by remember { mutableStateOf(false) } }
                var showLogs by remember { mutableStateOf(false) }
                if (showLogs) LogScreen() else HomeScreen(onOpenLogs = { showLogs = true })
            }
        }
    }
}
```

注：`Surface { ... }` 行是干扰行，实现时写成 `Surface {` 后直接跟 `var showLogs ...`，删除重复声明行。

- [ ] **Step 5: 编译验证并 Commit**

```bash
cd D:/dev/app/android && ./gradlew.bat :app:assembleDebug
cd D:/dev/app && git add android/app && git commit -m "feat(app): 主界面（总开关/权限引导/拦截日志）"
```

---

### Task 14: Room 拦截日志持久化

**Files:**
- Create: `android/app/src/main/java/cn/hys159x/grid/app/data/InterceptLog.kt`
- Create: `android/app/src/main/java/cn/hys159x/grid/app/data/GridDatabase.kt`
- Modify: `android/app/src/main/java/cn/hys159x/grid/app/service/GridAccessibilityService.kt`
- Modify: `android/app/src/main/java/cn/hys159x/grid/app/ui/LogScreen.kt`

- [ ] **Step 1: Entity/Dao/Database**

`android/app/src/main/java/cn/hys159x/grid/app/data/InterceptLog.kt`：

```kotlin
package cn.hys159x.grid.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "intercept_log")
data class InterceptLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val packageName: String,
    val action: String,
    val nodeDesc: String?,
)

@Dao
interface InterceptLogDao {
    @Insert suspend fun insert(log: InterceptLog)

    @Query("SELECT * FROM intercept_log ORDER BY id DESC LIMIT 200")
    fun recent(): Flow<List<InterceptLog>>
}
```

`android/app/src/main/java/cn/hys159x/grid/app/data/GridDatabase.kt`：

```kotlin
package cn.hys159x.grid.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [InterceptLog::class], version = 1, exportSchema = false)
abstract class GridDatabase : RoomDatabase() {
    abstract fun interceptLogDao(): InterceptLogDao

    companion object {
        fun get(context: Context): GridDatabase =
            androidx.room.Room.databaseBuilder(
                context.applicationContext, GridDatabase::class.java, "grid.db",
            ).fallbackToDestructiveMigration().build()
    }
}
```

注：`Room.databaseBuilder` 用全限定调用即可，companion 顶部无需再 import `Room`。

- [ ] **Step 2: 服务写库（performAction 成功分支替换）**

`GridAccessibilityService.performAction` 末尾的 `ServiceState.record(...)` 块替换为：

```kotlin
if (ok || action == ActionType.NONE) {
    val event = InterceptEvent(
        time = System.currentTimeMillis(),
        packageName = pkg, appName = null,
        ruleName = null, action = action.name,
        nodeDesc = node?.text ?: node?.desc ?: node?.vid,
    )
    ServiceState.record(event)
    kotlin.concurrent.thread {
        runCatching {
            GridDatabase.get(applicationContext).interceptLogDao().insert(
                cn.hys159x.grid.app.data.InterceptLog(
                    time = event.time, packageName = event.packageName,
                    action = event.action, nodeDesc = event.nodeDesc,
                ),
            )
        }
    }
}
```

补导入：`import cn.hys159x.grid.app.data.GridDatabase`。

- [ ] **Step 3: LogScreen 切换为 Room 数据源**

`LogScreen.kt` 中 `val events by ServiceState.intercepts.collectAsState()` 替换为：

```kotlin
val context = androidx.compose.ui.platform.LocalContext.current
val events by remember { cn.hys159x.grid.app.data.GridDatabase.get(context).interceptLogDao().recent() }
    .collectAsState(initial = emptyList())
```

`items(events)` 内字段相应改为 `e.action + " " + (e.nodeDesc ?: "")`、`e.packageName`、`fmt.format(Date(e.time))`（ListItem 的 supportingContent 里 `${e.ruleName ?: "-"}` 改为 `e.packageName`）。

- [ ] **Step 4: 编译验证并 Commit**

```bash
cd D:/dev/app/android && ./gradlew.bat :app:assembleDebug
cd D:/dev/app && git add android/app && git commit -m "feat(app): Room 拦截日志持久化与 UI 接入"
```

---

### Task 15: README、隐私声明与 CI

**Files:**
- Create: `README.md`（仓库根）
- Create: `.github/workflows/android.yml`

- [ ] **Step 1: 写 README**（含隐私声明全文，分发合规用）

`README.md` 核心章节（逐节写实内容，不留空）：

1. **简介**：网格（Grid）——开源广告拦截工具；Android 版基于无障碍服务自动点击"跳过/关闭"；规则兼容 GKD 订阅格式；iOS（DNS 过滤）与 HarmonyOS NEXT 版规划见 `docs/specs/2026-09-20-ad-blocker-design.md`。
2. **下载安装**：GitHub Releases 下载 APK → 安装 → 开启无障碍（设置 → 无障碍 → 已下载的应用 → 网格）→ 华为/小米保活指引（允许自启动、关闭电池优化、锁后台）。
3. **工作原理**：拉取 GKD 官方订阅（jsdelivr 分发，JSON5）→ 无障碍事件驱动匹配选择器 → 自动执行点击/返回；本地引擎 4 模块结构图（engine/app）。
4. **隐私声明**：不采集、不上传任何用户数据；无障碍节点数据仅在内存中匹配后丢弃；唯一网络请求是拉取规则订阅（官方 npm 源），不含任何用户标识；日志仅存本地数据库，卸载即清除。
5. **构建**：`cd android && ./gradlew.bat :app:assembleDebug`；Release 需自备签名（`app/build.gradle.kts` 的 signingConfigs，占位说明留待发版时填写——发版前必须完成，见 Task 16 验收清单）。
6. **规则兼容**：支持的选择器子集见 `docs/specs/selector-spec.md`；不支持语法自动跳过并在引擎测试覆盖率报告中体现。
7. **免责**：仅供学习研究，规则数据版权归 GKD 社区订阅贡献者。

- [ ] **Step 2: CI**

`.github/workflows/android.yml`：

```yaml
name: android-build
on:
  push: { branches: [main] }
  pull_request:
jobs:
  build:
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: android } }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - name: Engine tests
        run: ./gradlew :engine:test
      - name: Build debug APK
        run: ./gradlew :app:assembleDebug
      - uses: actions/upload-artifact@v4
        with:
          name: grid-debug-apk
          path: android/app/build/outputs/apk/debug/app-debug.apk
```

注意：CI 里 engine 测试需要官方订阅 fixture，但 fixture 体积大不入库——`OfficialSubscriptionRegressTest` 已用 `assumeTrue` 在无 fixture 时跳过，CI 仍能跑其余单测。

- [ ] **Step 3: Commit**

```bash
cd D:/dev/app
git add README.md .github/workflows/android.yml
git commit -m "docs: README 隐私声明与 Android CI"
```
