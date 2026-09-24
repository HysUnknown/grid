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
