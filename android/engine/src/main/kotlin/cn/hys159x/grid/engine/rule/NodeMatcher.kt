package cn.hys159x.grid.engine.rule

import cn.hys159x.grid.engine.match.ChainMatcher
import cn.hys159x.grid.engine.node.TreeNode

/**
 * 主匹配器：按 order 逐规则评估。
 *
 * 契约（宿主 Task 10 依赖）：
 * 1. 首个通过的门控+匹配规则即记录并返回（actionCd/actionCount/preKeys 窗口在**匹配时刻**消耗，
 *    即使宿主随后因超预算丢弃该 hit，配额也已扣减）；
 * 2. evaluate 除 actionDelay 的 pendingSince 外无副作用，可安全重复调用。
 */
class NodeMatcher(private val state: EngineState) : ScreenMatcher {

    fun match(ctx: ScreenContext, rules: List<CompiledRule>): MatchResult? {
        val ordered = rules.sortedBy { it.order }
        for (rule in ordered) {
            // 预算截断：超时立即放弃本轮（下一事件再试），防止长轮占满 CPU
            if (ctx.deadline != Long.MAX_VALUE && state.clock() > ctx.deadline) return null
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
