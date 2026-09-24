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
    fun `action cd boundary allows exactly at cd`() {
        val rules = listOf(rule(listOf("""[vid="skip"]"""), actionCd = 1000))
        assertNotNull(matcher.match(screen(), rules))
        now += 1000   // 恰好到达 cd：`< cd` 才拦截，故放行
        assertNotNull(matcher.match(screen(), rules))
    }

    @Test
    fun `pre keys expires after window`() {
        val rules = listOf(
            rule(listOf("""[vid="ad"]"""), action = "none").let { it.copy(id = RuleId("com.test.app", 0, 1)) },
            rule(listOf("""[vid="skip"]"""), preKeys = listOf(1)),
        )
        assertNotNull(matcher.match(screen(), rules.drop(0))) // 前置执行
        now += PREKEY_WINDOW_MS + 1                            // 超出 10s 窗口
        assertNull(matcher.match(screen(), rules.drop(1)))     // 前置过期：不触发
    }

    @Test
    fun `reset match value resets on activity too`() {
        val rules = listOf(rule(listOf("""[vid="skip"]"""), actionMaximum = 1)
            .let { it.copy(resetMatch = "match") })
        assertNotNull(matcher.match(screen(), rules))
        assertNull(matcher.match(screen(), rules))             // actionMaximum 休眠
        state.onActivityReset(rules, now)                      // resetMatch="match" 也在 Activity 重置范围
        now += 2000
        assertNotNull(matcher.match(screen(), rules))
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

    @Test
    fun `deadline truncates rule loop`() {
        val rules = listOf(
            rule(listOf("""[vid="none_a"]"""), order = 0),
            rule(listOf("""[vid="skip"]"""), order = 1),
        )
        // 截止时刻已过：直接放弃本轮（即使后面的规则能命中）
        assertNull(matcher.match(screen().copy(deadline = now - 1), rules))
        // 无 deadline（默认）：正常命中
        assertNotNull(matcher.match(screen(), rules))
    }
}
