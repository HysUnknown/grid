# 分册 03：规则执行器与订阅解析（Task 7-8）

前置：分册 02 完成（SelectorParser / PropEvaluator / ChainMatcher 可用）。

---

### Task 7: 规则执行器（含运行时状态与 Matcher 接口）

**Files:**
- Create: `android/engine/src/main/kotlin/cn/hys159x/grid/engine/rule/RuleTypes.kt`
- Create: `android/engine/src/main/kotlin/cn/hys159x/grid/engine/rule/EngineState.kt`
- Create: `android/engine/src/main/kotlin/cn/hys159x/grid/engine/rule/NodeMatcher.kt`
- Test: `android/engine/src/test/kotlin/cn/hys159x/grid/engine/rule/NodeMatcherTest.kt`

- [ ] **Step 1: 定义规则与结果类型**

`android/engine/src/main/kotlin/cn/hys159x/grid/engine/rule/RuleTypes.kt`：

```kotlin
package cn.hys159x.grid.engine.rule

import cn.hys159x.grid.engine.node.TreeNode
import cn.hys159x.grid.engine.selector.ParsedSelector

enum class ActionType { CLICK, CLICK_NODE, CLICK_CENTER, LONG_CLICK, LONG_CLICK_NODE, LONG_CLICK_CENTER, BACK, NONE }

/** GKD action 字符串 → ActionType；null=默认 CLICK；"swipe" 返回 null 表示不支持（上层跳过该规则） */
fun parseAction(s: String?): ActionType? = when (s) {
    null, "click" -> ActionType.CLICK
    "clickNode" -> ActionType.CLICK_NODE
    "clickCenter" -> ActionType.CLICK_CENTER
    "back" -> ActionType.BACK
    "longClick" -> ActionType.LONG_CLICK
    "longClickNode" -> ActionType.LONG_CLICK_NODE
    "longClickCenter" -> ActionType.LONG_CLICK_CENTER
    "none" -> ActionType.NONE
    else -> null
}

data class RuleId(val appId: String, val groupKey: Int, val ruleKey: Int?)

data class CompiledRule(
    val id: RuleId,
    val name: String?,
    val preKeys: List<Int>,
    val action: ActionType,
    val matches: List<ParsedSelector>,          // AND；目标 = 最后一项的目标节点
    val anyMatches: List<ParsedSelector>,       // OR 前置条件；空=无该条件
    val excludeMatches: List<ParsedSelector>,   // 任一命中即排除
    val excludeAllMatches: List<ParsedSelector>,// 全部命中才排除
    val actionCd: Long,                         // 默认 1000ms
    val actionDelay: Long,                      // 默认 0
    val actionMaximum: Int,                     // 默认无限
    val resetMatch: String,                     // activity | match | app
    val matchTime: Long,                        // 默认 0=不限
    val matchRoot: Boolean,                     // 默认 false
    val order: Int,
    val activityIds: List<String>,
    val excludeActivityIds: List<String>,
)

data class MatchResult(
    val ruleId: RuleId,
    val ruleName: String?,
    val target: TreeNode,
    val action: ActionType,
)

/** 匹配器接口：NodeMatcher 为主实现；VisionMatcher(YOLO) 为下期扩展点（spec §6） */
interface ScreenMatcher {
    fun match(ctx: ScreenContext, rule: CompiledRule): TreeNode?
}

data class ScreenContext(
    val root: TreeNode,
    val eventNode: TreeNode?,   // 事件源节点；null 时视为 root
    val packageName: String,
    val activityId: String?,
    val now: Long,              // SystemClock 或测试注入
)
```

- [ ] **Step 2: 定义运行时状态**

`android/engine/src/main/kotlin/cn/hys159x/grid/engine/rule/EngineState.kt`：

```kotlin
package cn.hys159x.grid.engine.rule

class RuleRuntime {
    var lastActionAt: Long = 0
    var actionCount: Int = 0
    var activatedAt: Long = 0      // 规则唤醒时刻（matchTime/actionMaximum 从此计）
    var pendingSince: Long = 0     // actionDelay 首次查到时刻
    var lastExecutedAt: Long = 0   // preKeys 判定用
}

class EngineState {
    val runtimes = HashMap<RuleId, RuleRuntime>()
    val clock: () -> Long                                          // 可注入测试时间

    constructor(clock: () -> Long = { System.currentTimeMillis() }) { this.clock = clock }

    fun runtime(id: RuleId) = runtimes.getOrPut(id) { RuleRuntime() }

    /** resetMatch=activity/match：Activity（界面）刷新时重置计数/计时；无 runtime 的规则预创建并落定唤醒时刻 */
    fun onActivityReset(rules: List<CompiledRule>, now: Long) {
        for (r in rules) if (r.resetMatch == "activity" || r.resetMatch == "match") resetRuntime(r.id, now)
    }

    /** resetMatch=app：重新进入 App 时重置（同时覆盖 activity/match 级） */
    fun onAppEnter(rules: List<CompiledRule>, now: Long) {
        for (r in rules) resetRuntime(r.id, now)
    }

    private fun resetRuntime(id: RuleId, now: Long) {
        val rt = runtime(id)   // getOrPut：从未评估过的规则也在此刻唤醒
        rt.actionCount = 0; rt.activatedAt = now; rt.pendingSince = 0
    }
}

/** preKeys 判定：前置 key 的规则在 PREKEY_WINDOW 内执行过 */
const val PREKEY_WINDOW_MS = 10_000L
```

- [ ] **Step 3: 写失败的测试**

`android/engine/src/test/kotlin/cn/hys159x/grid/engine/rule/NodeMatcherTest.kt`：

```kotlin
package cn.hys159x.grid.engine.rule

import cn.hys159x.grid.engine.node.Bounds
import cn.hys159x.grid.engine.node.child
import cn.hys159x.grid.engine.node.fakeRoot
import cn.hys159x.grid.engine.selector.SelectorParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NodeMatcherTest {
    private var now = 100_000L
    private val state = EngineState { now }
    private val matcher = NodeMatcher(state)

    private fun rule(
        matches: List<String>,
        action: String? = null,
        excludeMatches: List<String> = emptyList(),
        excludeAllMatches: List<String> = emptyList(),
        anyMatches: List<String> = emptyList(),
        actionCd: Long = 1000, actionMaximum: Int = Int.MAX_VALUE,
        actionDelay: Long = 0, matchTime: Long = 0,
        activityIds: List<String> = emptyList(),
        preKeys: List<Int> = emptyList(),
        order: Int = 0,
    ) = CompiledRule(
        id = RuleId("com.test.app", 0, null), name = "r",
        preKeys = preKeys, action = parseAction(action)!!,
        matches = matches.map { SelectorParser.parse(it) },
        anyMatches = anyMatches.map { SelectorParser.parse(it) },
        excludeMatches = excludeMatches.map { SelectorParser.parse(it) },
        excludeAllMatches = excludeAllMatches.map { SelectorParser.parse(it) },
        actionCd = actionCd, actionDelay = actionDelay, actionMaximum = actionMaximum,
        resetMatch = "activity", matchTime = matchTime, matchRoot = false, order = order,
        activityIds = activityIds, excludeActivityIds = emptyList(),
    )

    private fun screen(skipText: String = "跳过") = ScreenContext(
        root = fakeRoot(vid = "root", bounds = Bounds(0, 0, 1080, 2400)) {
            child(vid = "ad", bounds = Bounds(0, 0, 1080, 800)) {
                child(vid = "skip", text = skipText, clickable = true)
            }
        },
        eventNode = null, packageName = "com.test.app", activityId = "Main", now = now,
    )

    @Test
    fun `hit returns click action`() {
        val r = matcher.match(screen(), listOf(rule(listOf("""[vid="skip"][clickable=true]"""))))
        assertNotNull(r)
        assertEquals(ActionType.CLICK, r!!.action)
        assertEquals("skip", r.target.vid)
        assertEquals(now, state.runtime(r.ruleId).lastActionAt)
    }

    @Test
    fun `no rule no hit`() {
        assertNull(matcher.match(screen(), listOf(rule(listOf("""[vid="close"]""")))))
    }

    @Test
    fun `action cd blocks second hit`() {
        val rules = listOf(rule(listOf("""[vid="skip"]"""), actionCd = 1000))
        assertNotNull(matcher.match(screen(), rules))
        now += 500
        assertNull(matcher.match(screen(), rules))
        now += 600
        assertNotNull(matcher.match(screen(), rules))
    }

    @Test
    fun `action maximum sleeps rule`() {
        val rules = listOf(rule(listOf("""[vid="skip"]"""), actionMaximum = 1))
        assertNotNull(matcher.match(screen(), rules))
        now += 5000
        assertNull(matcher.match(screen(), rules))
    }

    @Test
    fun `activity reset restores action maximum`() {
        val rules = listOf(rule(listOf("""[vid="skip"]"""), actionMaximum = 1))
        assertNotNull(matcher.match(screen(), rules))
        state.onActivityReset(rules, now)
        now += 5000
        assertNotNull(matcher.match(screen(), rules))
    }

    @Test
    fun `exclude matches blocks`() {
        val rules = listOf(rule(
            listOf("""[vid="skip"]"""),
            excludeMatches = listOf("""[text="开会员免广告"]"""),
        ))
        val screen2 = ScreenContext(
            root = fakeRoot(vid = "root") {
                child(vid = "ad") {
                    child(vid = "skip", text = "跳过", clickable = true)
                    child(text = "开会员免广告")
                }
            },
            eventNode = null, packageName = "com.test.app", activityId = "Main", now = now,
        )
        assertNull(matcher.match(screen2, rules))
    }

    @Test
    fun `any matches requires at least one`() {
        val rules = listOf(rule(listOf("""[vid="skip"]"""), anyMatches = listOf("""[vid="vip_banner"]""")))
        assertNull(matcher.match(screen(), rules))
        val rules2 = listOf(rule(listOf("""[vid="skip"]"""), anyMatches = listOf("""[vid="ad"]""")))
        assertNotNull(matcher.match(screen(), rules2))
    }

    @Test
    fun `multi matches are and with last target`() {
        val rules = listOf(rule(listOf("""[vid="ad"] > [vid="skip"]""", """[vid="skip"]""")))
        val r = matcher.match(screen(), rules)
        assertNotNull(r)
        assertEquals("skip", r!!.target.vid)
    }

    @Test
    fun `activity id mismatch skips`() {
        val rules = listOf(rule(listOf("""[vid="skip"]"""), activityIds = listOf("Other")))
        assertNull(matcher.match(screen(), rules))
    }

    @Test
    fun `pre keys must be executed first`() {
        val rules = listOf(
            rule(listOf("""[vid="ad"]"""), action = "none").let { it.copy(id = RuleId("com.test.app", 0, 1)) },
            rule(listOf("""[vid="skip"]"""), preKeys = listOf(1)),
        )
        assertNull(matcher.match(screen(), rules.drop(1))) // 只执行第 2 条：前置未满足
        assertNotNull(matcher.match(screen(), rules))      // 第 1 条先执行
        assertNotNull(matcher.match(screen(), rules.drop(1))) // 现在前置满足
    }

    @Test
    fun `action delay requires second sighting`() {
        val rules = listOf(rule(listOf("""[vid="skip"]"""), actionDelay = 500))
        state.runtime(rules[0].id).activatedAt = now
        assertNull(matcher.match(screen(), rules))   // 首见，记录 pending，不执行
        now += 200
        assertNull(matcher.match(screen(), rules))   // 未到 delay
        now += 400
        assertNotNull(matcher.match(screen(), rules))// 二见且超时，执行
    }

    @Test
    fun `match time window expires`() {
        // actionCd=0 排除冷却干扰，专测 matchTime 窗口从 onAppEnter 唤醒时刻起算
        val rules = listOf(rule(listOf("""[vid="skip"]"""), matchTime = 1000, actionCd = 0))
        state.onAppEnter(rules, now)                 // t0 唤醒（预创建 runtime 并落定 activatedAt）
        now += 500
        assertNotNull(matcher.match(screen(), rules))  // 窗口内
        now += 501
        assertNull(matcher.match(screen(), rules))     // 1001 > 1000：窗口关闭
    }

    @Test
    fun `exclude all matches requires every selector`() {
        // AND 排除：两条 exclude 都命中才排除；只命中一条不排除
        val rules = listOf(rule(
            listOf("""[vid="skip"]"""),
            excludeAllMatches = listOf("""[vid="vip"]""", """[text="会员"]"""),
        ))
        // 树里只有 vip 命中第一条 exclude：不构成 AND 排除 → 规则执行
        val screenVipOnly = ScreenContext(
            root = fakeRoot(vid = "root") {
                child(vid = "vip")
                child(vid = "ad") { child(vid = "skip", clickable = true) }
            },
            eventNode = null, packageName = "com.test.app", activityId = "Main", now = now,
        )
        assertNotNull(matcher.match(screenVipOnly, rules))
        // 两条 exclude 都命中 → 排除
        val screenBoth = ScreenContext(
            root = fakeRoot(vid = "root") {
                child(vid = "vip")
                child(text = "会员")
                child(vid = "ad") { child(vid = "skip", clickable = true) }
            },
            eventNode = null, packageName = "com.test.app", activityId = "Main", now = now,
        )
        assertNull(matcher.match(screenBoth, rules))
    }
}
```

- [ ] **Step 4: 运行确认失败**

```bash
cd D:/dev/app/android && ./gradlew.bat :engine:test
```

Expected: `Unresolved reference: NodeMatcher`。

- [ ] **Step 5: 实现 NodeMatcher**

`android/engine/src/main/kotlin/cn/hys159x/grid/engine/rule/NodeMatcher.kt`：

```kotlin
package cn.hys159x.grid.engine.rule

import cn.hys159x.grid.engine.match.ChainMatcher
import cn.hys159x.grid.engine.node.TreeNode

/** 主匹配器：按 order 逐规则评估，命中即返回（并更新运行时状态） */
class NodeMatcher(private val state: EngineState) : ScreenMatcher {

    fun match(ctx: ScreenContext, rules: List<CompiledRule>): MatchResult? {
        val ordered = rules.sortedBy { it.order }
        for (rule in ordered) {
            if (!activityAllows(rule, ctx.activityId)) continue
            val target = evaluate(ctx, rule) ?: continue
            return recordHit(rule, target, ctx.now)
        }
        return null
    }

    override fun match(ctx: ScreenContext, rule: CompiledRule): TreeNode? = evaluate(ctx, rule)

    private fun activityAllows(rule: CompiledRule, activityId: String?): Boolean {
        if (rule.excludeActivityIds.contains(activityId)) return false
        if (rule.activityIds.isNotEmpty() && !rule.activityIds.contains(activityId)) return false
        return true
    }

    private fun evaluate(ctx: ScreenContext, rule: CompiledRule): TreeNode? {
        val rt = state.runtime(rule.id)
        val now = ctx.now

        // 唤醒时刻：首次见到该规则
        if (rt.activatedAt == 0L) rt.activatedAt = now
        // matchTime 窗口
        if (rule.matchTime > 0 && now - rt.activatedAt > rule.matchTime) return null
        // actionMaximum 休眠
        if (rt.actionCount >= rule.actionMaximum) return null
        // actionCd 冷却
        if (rt.lastActionAt > 0 && now - rt.lastActionAt < rule.actionCd) return null
        // preKeys：前置 key 在窗口内执行过（多个同 key 运行时取最近执行的）
        for (k in rule.preKeys) {
            val prev = state.runtimes.entries
                .filter { it.key.ruleKey == k && it.key.appId == rule.id.appId }
                .maxByOrNull { it.value.lastExecutedAt }
                ?: return null
            if (now - prev.value.lastExecutedAt > PREKEY_WINDOW_MS) return null
        }

        // 匹配范围：网格实现总是从树根全树 DFS（是"事件节点回溯至根+全部子树"的超集；
        // matchRoot/eventNode 仅影响候选首序，MVP 不做事件优先排序，行为差异见 selector-spec §6.1）
        val searchRoot = ctx.root

        // 排除：任一命中即放弃
        for (sel in rule.excludeMatches) if (ChainMatcher.match(searchRoot, sel) != null) return null
        // AND 排除
        if (rule.excludeAllMatches.isNotEmpty() &&
            rule.excludeAllMatches.all { ChainMatcher.match(searchRoot, it) != null }) return null
        // anyMatches：至少一项命中
        if (rule.anyMatches.isNotEmpty() && rule.anyMatches.none { ChainMatcher.match(searchRoot, it) != null }) return null
        // matches：全部命中；目标 = 最后一项
        var target: TreeNode? = null
        for (sel in rule.matches) {
            target = ChainMatcher.match(searchRoot, sel) ?: return null
        }
        // actionDelay：首见记录，二见且超时才放行
        if (rule.actionDelay > 0) {
            if (rt.pendingSince == 0L) { rt.pendingSince = now; return null }
            if (now - rt.pendingSince < rule.actionDelay) return null
        }
        rt.pendingSince = 0
        return target
    }

    private fun recordHit(rule: CompiledRule, target: TreeNode, now: Long): MatchResult {
        val rt = state.runtime(rule.id)
        rt.lastActionAt = now
        rt.actionCount += 1
        rt.lastExecutedAt = now
        return MatchResult(rule.id, rule.name, target, rule.action)
    }
}
```

- [ ] **Step 6: 运行测试通过**

```bash
cd D:/dev/app/android && ./gradlew.bat :engine:test
```

Expected: NodeMatcherTest 全部通过（13 个用例）。

- [ ] **Step 7: Commit**

```bash
cd D:/dev/app
git add android/engine
git commit -m "feat(engine): 规则执行器（冷却/上限/窗口/preKeys/actionDelay）"
```

---

### Task 8: GKD 订阅模型、编译器与官方回归

**Files:**
- Create: `android/engine/src/main/kotlin/cn/hys159x/grid/engine/subscription/RawSubscription.kt`
- Create: `android/engine/src/main/kotlin/cn/hys159x/grid/engine/subscription/SubscriptionCompiler.kt`
- Test: `android/engine/src/test/kotlin/cn/hys159x/grid/engine/subscription/SubscriptionCompilerTest.kt`
- Test: `android/engine/src/test/kotlin/cn/hys159x/grid/engine/subscription/OfficialSubscriptionRegressTest.kt`
- Fixture: `android/engine/src/test/resources/official_subscription.json`

- [ ] **Step 1: 写 Raw JSON 模型**

`android/engine/src/main/kotlin/cn/hys159x/grid/engine/subscription/RawSubscription.kt`：

```kotlin
package cn.hys159x.grid.engine.subscription

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** GKD 订阅编译产物；未知字段忽略；忽略字段清单见 selector-spec.md §4 */
@Serializable
data class RawSubscription(
    val id: Int = 0,
    val name: String? = null,
    val version: Int = 0,
    val author: String? = null,
    val apps: List<RawApp> = emptyList(),
    @SerialName("globalGroups") val globalGroups: List<RawGlobalGroup> = emptyList(),
)

@Serializable
data class RawApp(val id: String, val name: String? = null, val groups: List<RawGroup> = emptyList())

/** GKD 组级 RawCommonProps：组内规则的字段默认值（规则级覆盖组级） */
@Serializable
data class RawCommonProps(
    val actionCd: Int? = null,
    val actionDelay: Int? = null,
    val actionMaximum: Int? = null,
    val resetMatch: String? = null,
    val matchTime: Int? = null,
    val matchRoot: Boolean? = null,
    val order: Int? = null,
)

/** 全局组 app 引用：enable=false 表示该 App 被组排除（gkd 源码 getGlobalGroupInnerDisabled）；
 *  其余字段（name/deprecatedKeys 等）信息性，忽略 */
@Serializable
data class RawGlobalAppRef(
    val id: String,
    val name: String? = null,
    val enable: Boolean = true,
)

/** GKD 字段允许 string | string[]（官方 dist 的 matches/preKeys 大量使用单值形态），统一读为列表 */
object StringOrStringListSerializer : JsonTransformingSerializer<List<String>>(
    ListSerializer(String.serializer()),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive) JsonArray(listOf(element)) else element
}

/** 同上，int | int[]（preKeys 单值形态，如 preKeys:0） */
object IntOrIntListSerializer : JsonTransformingSerializer<List<Int>>(
    ListSerializer(Int.serializer()),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive) JsonArray(listOf(element)) else element
}

/** 全局组 apps 项允许 string | {id,...}（字符串视为 enable=true 引用）；混合形态在数组元素层，需逐元素转换 */
object StringOrGlobalAppRefListSerializer : JsonTransformingSerializer<List<RawGlobalAppRef>>(
    ListSerializer(RawGlobalAppRef.serializer()),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) JsonArray(element.map { el ->
            if (el is JsonPrimitive) buildJsonObject { put("id", el) } else el
        }) else element
}

/** 组的 rules 允许四种形态（GKD RawGroup.rules）：RawRule[] | RawRule | string | string[]
 *  （官方 dist 中单串简写 rules:'[...]'、单对象 rules:{...}、字符串数组 rules:['...'] 均有使用），统一转 RawRule[] */
object RulesListSerializer : JsonTransformingSerializer<List<RawRule>>(
    ListSerializer(RawRule.serializer()),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when (element) {
        is JsonPrimitive -> buildJsonArray { add(selectorToRule(element)) }
        is JsonObject -> buildJsonArray { add(element) }
        is JsonArray -> JsonArray(element.map { if (it is JsonPrimitive) selectorToRule(it) else it })
        else -> element
    }

    private fun selectorToRule(p: JsonPrimitive): JsonObject =
        buildJsonObject { put("matches", buildJsonArray { add(p) }) }
}

@Serializable
data class RawGlobalGroup(
    val key: Int = 0,
    val name: String? = null,
    val enable: Boolean = true,
    /** per-App 排除/覆盖名单：enable=false 的 App 不享受本组规则；组本身对所有 App 生效 */
    @Serializable(with = StringOrGlobalAppRefListSerializer::class)
    val apps: List<RawGlobalAppRef> = emptyList(),
    @Serializable(with = RulesListSerializer::class)
    val rules: List<RawRule> = emptyList(),
    val actionCd: Int? = null,
    val actionDelay: Int? = null,
    val actionMaximum: Int? = null,
    val resetMatch: String? = null,
    val matchTime: Int? = null,
    val matchRoot: Boolean? = null,
    val order: Int? = null,
)

@Serializable
data class RawGroup(
    val key: Int = 0,
    val name: String? = null,
    val enable: Boolean = true,
    @Serializable(with = RulesListSerializer::class)
    val rules: List<RawRule> = emptyList(),
    val actionCd: Int? = null,
    val actionDelay: Int? = null,
    val actionMaximum: Int? = null,
    val resetMatch: String? = null,
    val matchTime: Int? = null,
    val matchRoot: Boolean? = null,
    val order: Int? = null,
)

@Serializable
data class RawRule(
    val key: Int? = null,
    val name: String? = null,
    val action: String? = null,
    @Serializable(with = StringOrStringListSerializer::class)
    val matches: List<String> = emptyList(),
    @Serializable(with = StringOrStringListSerializer::class)
    val anyMatches: List<String>? = null,
    @Serializable(with = StringOrStringListSerializer::class)
    val excludeMatches: List<String> = emptyList(),
    @Serializable(with = StringOrStringListSerializer::class)
    val excludeAllMatches: List<String>? = null,
    @Serializable(with = IntOrIntListSerializer::class)
    val preKeys: List<Int> = emptyList(),
    val actionCd: Int? = null,
    val actionDelay: Int? = null,
    val actionMaximum: Int? = null,
    val resetMatch: String? = null,
    val matchTime: Int? = null,
    @SerialName("matchRoot") val matchRoot: Boolean? = null,
    val order: Int? = null,
    @Serializable(with = StringOrStringListSerializer::class)
    val activityIds: List<String> = emptyList(),
    @Serializable(with = StringOrStringListSerializer::class)
    val excludeActivityIds: List<String> = emptyList(),
)

object SubscriptionJson {
    /** 官方经 npm 分发的 dist/gkd.json5 是 JSON5：裸键 + 单引号字符串。
     *  kotlinx lenient 支持裸键/裸值但**不支持单引号字符串**，故先经 normalize 重写引号。
     *  已验证官方 fixture 无注释/无尾逗号/无 hex 等 JSON5 其它特征，最小处理即可。 */
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true   // null→默认值，容错官方数据
    }

    fun <T> decode(deserializer: DeserializationStrategy<T>, text: String): T =
        json.decodeFromString(deserializer, normalize(text))

    /** 单引号字符串 → 双引号字符串；双引号字符串与裸键/数值/布尔原样透传（交给 isLenient）。
     *  转义处理：\' → '（JSON 无 \' 转义）；" → \"；合法 JSON 转义对保留；\ + 其它字符按字面反斜杠转义 */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> {                          // 双引号字符串：原样透传
                    val start = i
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\') i++
                        i++
                    }
                    require(i < text.length) { "JSON 字符串未闭合" }
                    i++
                    sb.append(text, start, i)
                }
                c == '\'' -> {                         // 单引号字符串：重写为双引号
                    sb.append('"')
                    i++
                    while (i < text.length && text[i] != '\'') {
                        if (text[i] == '\\' && i + 1 < text.length) {
                            when (val e = text[i + 1]) {
                                '\'' -> sb.append('\'')
                                '"', '\\', '/', 'b', 'f', 'n', 'r', 't', 'u' -> sb.append(text[i]).append(e)
                                else -> sb.append('\\').append('\\').append(e)  // 字面反斜杠（正则 \d 等）
                            }
                            i += 2
                        } else if (text[i] == '"') {
                            sb.append('\\').append('"'); i++
                        } else {
                            sb.append(text[i]); i++
                        }
                    }
                    require(i < text.length) { "JSON5 单引号字符串未闭合" }
                    sb.append('"'); i++
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }
}
```

- [ ] **Step 2: 写失败的编译器测试**

`android/engine/src/test/kotlin/cn/hys159x/grid/engine/subscription/SubscriptionCompilerTest.kt`：

```kotlin
package cn.hys159x.grid.engine.subscription

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SubscriptionCompilerTest {

    private val json = """
        {
          "id": 10000, "name": "官方订阅", "version": 42,
          "apps": [
            { "id": "com.zhihu.android", "name": "知乎", "groups": [
              { "key": 0, "name": "开屏广告", "enable": true, "rules": [
                { "key": 1, "matches": ["[text*=\"跳过\"][text.length<=6]"], "actionMaximum": 1 },
                { "key": 2, "matches": ["[vid=\"x\"] -> [vid=\"y\"]"] }
              ]},
              { "key": 9, "name": "关闭通知提示", "enable": false, "rules": [
                { "matches": ["[text=\"稍后\"]"] }
              ]}
            ]}
          ],
          "globalGroups": [
            { "key": 10, "name": "全局开屏", "apps": [], "rules": [
              { "matches": ["[vid=\"global_skip\"]"], "actionMaximum": 1, "resetMatch": "app", "matchTime": 10000 }
            ]}
          ],
          "unknownField": "被忽略"
        }
    """.trimIndent()

    @Test
    fun `compile skips unsupported and disabled`() {
        val set = SubscriptionCompiler.compile(SubscriptionJson.decode(RawSubscription.serializer(), json))
        assertEquals(42, set.version)
        val zhihu = set.rules["com.zhihu.android"]!!
        assertEquals(1, zhihu.size)                    // key=1 有效；key=2 语法不支持被跳过；key=9 组禁用
        assertEquals(1, set.skipped)
        assertEquals(1, set.disabledGroups)
        // 计划修正：totalRules 统计整个订阅的规则条目（app 组 3 条 + 全局组 1 条 = 4；
        // 原文 3 漏计 globalGroups——禁用全局组也计 total，启用的不计则不自洽）
        assertEquals(4, set.totalRules)
        val global = set.rules["*"]!!
        assertEquals(1, global.size)
        assertEquals(1, global[0].actionMaximum)
        assertEquals(10000L, global[0].matchTime)
    }

    @Test
    fun `defaults applied`() {
        val set = SubscriptionCompiler.compile(SubscriptionJson.decode(RawSubscription.serializer(), json))
        val r = set.rules["com.zhihu.android"]!![0]
        assertEquals(1000L, r.actionCd)          // GKD 默认
        assertEquals(0, r.order)
        assertEquals("activity", r.resetMatch)
        assertEquals(cn.hys159x.grid.engine.rule.ActionType.CLICK, r.action)
    }

    // 校准修正①：GKD globalGroups[].apps 是 per-App 开关（enable=false = 排除该 App），
    // 组本就对所有 App 生效——非空 apps ≠ "仅这些包名生效"（见 gkd 源码 getGlobalGroupInnerDisabled）
    @Test
    fun `global group apps are exclusion overrides not inclusion`() {
        val json2 = """
            {
              "id": 1, "name": "n", "version": 1,
              "apps": [
                { "id": "com.a", "groups": [ { "key": 0, "rules": [ { "matches": ["[vid=\"x\"]"] } ] } ] }
              ],
              "globalGroups": [
                { "key": 5, "apps": [ "com.a", { "id": "com.b", "enable": false }, { "id": "com.c", "enable": true } ],
                  "matchTime": 8000, "actionMaximum": 2,
                  "rules": [ { "matches": ["[text=\"跳过\"]"] } ] }
              ]
            }
        """.trimIndent()
        val set = SubscriptionCompiler.compile(SubscriptionJson.decode(RawSubscription.serializer(), json2))
        val global = set.rules["*"]!!
        assertEquals(1, global.size)                      // 规则归 "*"，不按 apps 拆分
        assertEquals(setOf("com.b"), set.globalGroupExcludedApps[5])  // 仅 enable=false 排除
    }

    // 校准修正②：组级 RawCommonProps 是组内规则的字段默认值（规则级覆盖组级），
    // 官方开屏组在组级设 matchTime/actionMaximum 防护，丢弃会导致规则无限触发
    @Test
    fun `group common props inherited as rule defaults`() {
        val json3 = """
            {
              "id": 1, "name": "n", "version": 1,
              "apps": [
                { "id": "com.a", "groups": [
                  { "key": 3, "actionCd": 5000, "resetMatch": "app", "order": -2,
                    "rules": [
                      { "matches": ["[vid=\"y\"]"] },
                      { "matches": ["[vid=\"z\"]"], "actionCd": 100, "order": 7 }
                    ] }
                ]}
              ]
            }
        """.trimIndent()
        val set = SubscriptionCompiler.compile(SubscriptionJson.decode(RawSubscription.serializer(), json3))
        val rules = set.rules["com.a"]!!
        assertEquals(2, rules.size)
        assertEquals(5000L, rules[0].actionCd)            // 继承组级
        assertEquals("app", rules[0].resetMatch)
        assertEquals(-2, rules[0].order)
        assertEquals(100L, rules[1].actionCd)             // 规则级覆盖组级
        assertEquals(7, rules[1].order)
        // globalGroups 组级字段同样作为默认值（用例见上一测试的 matchTime=8000）
    }
}
```

- [ ] **Step 3: 运行确认失败**

```bash
cd D:/dev/app/android && ./gradlew.bat :engine:test
```

Expected: `Unresolved reference: SubscriptionCompiler`。

- [ ] **Step 4: 实现编译器**

`android/engine/src/main/kotlin/cn/hys159x/grid/engine/subscription/SubscriptionCompiler.kt`：

```kotlin
package cn.hys159x.grid.engine.subscription

import cn.hys159x.grid.engine.rule.*
import cn.hys159x.grid.engine.selector.ParsedSelector
import cn.hys159x.grid.engine.selector.SelectorParser
import cn.hys159x.grid.engine.selector.SelectorSyntaxException

data class CompiledRuleSet(
    val subscriptionName: String?,
    val version: Int,
    /** appId → 规则列表；"*" 为全局（来自 globalGroups，对所有 App 生效） */
    val rules: Map<String, List<CompiledRule>>,
    val totalRules: Int,
    val skipped: Int,
    val disabledGroups: Int,
    /** 全局组排除名单：groupKey → enable=false 的 appId 集合（这些 App 不享受该全局组规则） */
    val globalGroupExcludedApps: Map<Int, Set<String>> = emptyMap(),
    /** 被跳过规则的采样（官方回归打印用：定位高频失败语法） */
    val skippedSamples: List<SkippedRule> = emptyList(),
)

data class SkippedRule(
    val appId: String,
    val groupKey: Int,
    val ruleKey: Int?,
    val firstMatch: String?,
    val reason: String,
)

object SubscriptionCompiler {

    fun compile(raw: RawSubscription): CompiledRuleSet {
        val rules = LinkedHashMap<String, MutableList<CompiledRule>>()
        val samples = mutableListOf<SkippedRule>()
        val globalExcluded = HashMap<Int, Set<String>>()
        var total = 0; var skipped = 0; var disabled = 0

        fun compileRules(appId: String, groupKey: Int, defaults: RawCommonProps, rulesIn: List<RawRule>) {
            for (r in rulesIn) {
                total++
                val skip = compileOne(appId, groupKey, r, defaults, rules) ?: continue
                skipped++
                samples += SkippedRule(appId, groupKey, r.key, skip.selector ?: r.matches.firstOrNull(), skip.reason)
            }
        }

        for (app in raw.apps) {
            for (g in app.groups) {
                val defaults = RawCommonProps(g.actionCd, g.actionDelay, g.actionMaximum, g.resetMatch, g.matchTime, g.matchRoot, g.order)
                if (!g.enable) { disabled++; total += g.rules.size; continue }
                compileRules(app.id, g.key, defaults, g.rules)
            }
        }
        for (g in raw.globalGroups) {
            val defaults = RawCommonProps(g.actionCd, g.actionDelay, g.actionMaximum, g.resetMatch, g.matchTime, g.matchRoot, g.order)
            if (!g.enable) { disabled++; total += g.rules.size; continue }
            val excluded = g.apps.filter { !it.enable }.map { it.id }.toSet()
            if (excluded.isNotEmpty()) globalExcluded[g.key] = excluded
            compileRules("*", g.key, defaults, g.rules)
        }
        return CompiledRuleSet(raw.name, raw.version, rules, total, skipped, disabled, globalExcluded, samples)
    }

    /** 跳过原因 + 解析失败的那条选择器（null = 无对应选择器，如"无 matches"） */
    private data class Skip(val reason: String, val selector: String?)

    /** 返回 null = 编译成功；返回 Skip = 该规则被跳过（无 matches/动作不支持/语法不支持） */
    private fun compileOne(
        appId: String, groupKey: Int, r: RawRule, defaults: RawCommonProps,
        out: MutableMap<String, MutableList<CompiledRule>>,
    ): Skip? {
        if (r.matches.isEmpty()) return Skip("无 matches", null)
        val action = parseAction(r.action) ?: return Skip("动作不支持: ${r.action}", null)
        val matches: List<ParsedSelector>
        val anyM: List<ParsedSelector>
        val excl: List<ParsedSelector>
        val exclAll: List<ParsedSelector>
        var failed: String? = null   // 采样明细用：matches 首项未必是失败项
        fun parseAll(list: List<String>): List<ParsedSelector> = list.map {
            try {
                SelectorParser.parse(it)
            } catch (e: SelectorSyntaxException) {
                failed = it
                throw e
            }
        }
        try {
            matches = parseAll(r.matches)
            anyM = r.anyMatches?.let { parseAll(it) } ?: emptyList()
            excl = parseAll(r.excludeMatches)
            exclAll = r.excludeAllMatches?.let { parseAll(it) } ?: emptyList()
        } catch (e: SelectorSyntaxException) { return Skip("语法不支持: ${e.message}", failed) }
        val rule = CompiledRule(
            id = RuleId(appId, groupKey, r.key),
            name = r.name,
            preKeys = r.preKeys,
            action = action,
            matches = matches, anyMatches = anyM,
            excludeMatches = excl, excludeAllMatches = exclAll,
            // 字段默认值链：规则级 > 组级 > GKD 默认
            actionCd = (r.actionCd ?: defaults.actionCd ?: 1000).toLong(),
            actionDelay = (r.actionDelay ?: defaults.actionDelay ?: 0).toLong(),
            actionMaximum = r.actionMaximum ?: defaults.actionMaximum ?: Int.MAX_VALUE,
            resetMatch = r.resetMatch ?: defaults.resetMatch ?: "activity",
            matchTime = (r.matchTime ?: defaults.matchTime ?: 0).toLong(),
            matchRoot = r.matchRoot ?: defaults.matchRoot ?: false,
            order = r.order ?: defaults.order ?: 0,
            activityIds = r.activityIds,
            excludeActivityIds = r.excludeActivityIds,
        )
        out.getOrPut(appId) { mutableListOf() }.add(rule)
        return null
    }
}
```

- [ ] **Step 5: 运行测试通过**

```bash
cd D:/dev/app/android && ./gradlew.bat :engine:test
```

Expected: SubscriptionCompilerTest 通过（4 个用例）。

- [ ] **Step 6: 下载官方订阅 fixture 并写回归测试**

```bash
cd D:/dev/app/android
mkdir -p engine/src/test/resources
curl -L -o engine/src/test/resources/official_subscription.json "https://fastly.jsdelivr.net/npm/@gkd-kit/subscription"
ls -la engine/src/test/resources/official_subscription.json
```

Expected: 文件内容为 JSON5（裸键 + 单引号），大小 > 100KB（官方订阅含上千条规则）。若 URL 失效：改用备选 `https://registry.npmmirror.com/@gkd-kit/subscription/latest/files/dist/gkd.json5`，仍失败则以 github.com/gkd-kit/subscription 仓库 README 公布的最新分发地址替换后重试。

`android/engine/src/test/kotlin/cn/hys159x/grid/engine/subscription/OfficialSubscriptionRegressTest.kt`：

```kotlin
package cn.hys159x.grid.engine.subscription

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class OfficialSubscriptionRegressTest {
    @Test
    fun `official subscription compiles with high coverage`() {
        val stream = javaClass.getResourceAsStream("/official_subscription.json")
        assumeTrue(stream != null, "缺少 fixture：先执行 curl 下载官方订阅")
        val raw = SubscriptionJson.decode(
            RawSubscription.serializer(), stream!!.bufferedReader().readText(),
        )
        val set = SubscriptionCompiler.compile(raw)
        println("官方订阅回归：total=${set.totalRules} ok=${set.totalRules - set.skipped} " +
                "skipped=${set.skipped} disabledGroups=${set.disabledGroups} apps=${set.rules.size - 1}")
        // v1.3 扩展后（||/&&/()/字面n/通配*/null/反引号）剩余 skip 应 < 0.2%（真机验证发现
        // 全局开屏兜底规则属扩展语法，跳过会导致开屏广告不拦截）；逐条打印剩余 skip 定位
        set.skippedSamples.forEach {
            println("SKIP ${it.appId}/${it.groupKey}/${it.ruleKey} [${it.reason}] ${it.firstMatch}")
        }
        assertTrue(set.totalRules > 1000, "官方订阅应包含上千条规则")
        assertTrue(set.skipped.toFloat() / set.totalRules < 0.002f, "不支持语法占比应 < 0.2%")
        assertTrue(set.rules.containsKey("com.zhihu.android") || set.rules.size > 100, "应包含常见 App 规则")
    }
}
```

- [ ] **Step 7: 运行回归并检查覆盖率**

```bash
cd D:/dev/app/android && ./gradlew.bat :engine:test --tests "*OfficialSubscriptionRegress*" -i
```

Expected: 输出 `total=xxxx ok=xxxx skipped=xx ...`，skipped 占比 < 5%，测试通过。若 skipped ≥ 5%：把高频失败语法样例加入 Task 4 解析器（按 selector-spec 逐个补齐），重新本步验证——**不得放宽断言**。

- [ ] **Step 8: Commit**

```bash
cd D:/dev/app
git add android/engine
git commit -m "feat(engine): GKD 订阅解析编译器与官方订阅回归（覆盖率门槛95%）"
```

**实测数据（官方订阅回归，2026-09-20，官方订阅 v186）：**

- `total=1973 ok=1947 skipped=26`，skip 率 **1.32%**（覆盖率门槛 < 5% 通过）。
- 26 条 skip 的语法构成：属性括号内 `||` / `&&` 逻辑表达式 ×8、`*` 通配单元 ×6、字面 `n` 步数关系符（`<n` / `>n` / `<<n` / `+n`，字面 n 表示任意层）×6、`null` 字面量 ×4、反引号字符串 ×4（与 selector-spec §4.2 不支持清单对应，`skippedSamples` 可打印逐条定位）。
- **v1.3 实用扩展后（同日，真机验证驱动）**：上述 5 类语法 + 属性块内空格容忍 + 裸 name 单元作空格关系右侧全部纳入子集（spec v1.3），回归 **`total=1973 ok=1973 skipped=0`**（skip 率 0%，门槛收紧为 < 0.2%）；`compileOne` 同步改为返回 `Skip(reason, selector)` 以在采样明细中记录**实际解析失败的那条选择器**（matches 首项未必是失败项）。
- 官方订阅已停止维护：订阅名“默认订阅-已停止维护”，末版 v186，规则库不再更新。
