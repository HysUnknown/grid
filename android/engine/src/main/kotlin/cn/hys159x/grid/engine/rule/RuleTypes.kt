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
    /** 本轮匹配的硬截止时刻（与 now 同钟；默认无限制）。超时后逐规则截断，防止长轮烧 CPU */
    val deadline: Long = Long.MAX_VALUE,
)
