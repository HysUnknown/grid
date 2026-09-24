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
