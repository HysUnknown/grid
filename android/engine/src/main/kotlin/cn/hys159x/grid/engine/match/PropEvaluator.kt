package cn.hys159x.grid.engine.match

import cn.hys159x.grid.engine.node.TreeNode
import cn.hys159x.grid.engine.selector.*

object PropEvaluator {

    private val regexCache = HashMap<String, Regex?>()   // 非线程安全：匹配当前单线程；引入并发时换 ConcurrentHashMap

    fun eval(node: TreeNode, c: PropCond): Boolean {
        val str: String? = when (c.prop) {
            PropName.ID -> node.id; PropName.VID -> node.vid; PropName.NAME -> node.name
            PropName.TEXT -> node.text; PropName.DESC -> node.desc
            else -> null
        }
        if (str != null || c.prop in STR_PROPS) {
            if (c.isLength) return intOps(c, str?.length)
            return strOps(str, c)
        }
        val bool: Boolean? = when (c.prop) {
            PropName.CLICKABLE -> node.clickable; PropName.LONG_CLICKABLE -> node.longClickable
            PropName.FOCUSABLE -> node.focusable; PropName.CHECKABLE -> node.checkable
            PropName.CHECKED -> node.checked; PropName.EDITABLE -> node.editable
            PropName.VISIBLE_TO_USER -> node.visibleToUser
            else -> null
        }
        if (bool != null || c.prop in BOOL_PROPS) {
            if (c.nullVal == true) return c.op == CmpOp.NEQ  // 布尔属性永不为 null：=null 恒假，!=null 恒真
            if (c.boolVal == null) return false
            return when (c.op) { CmpOp.EQ -> bool == c.boolVal; CmpOp.NEQ -> bool != c.boolVal; else -> false }
        }
        val int: Int? = when (c.prop) {
            PropName.LEFT -> node.bounds.left; PropName.TOP -> node.bounds.top
            PropName.RIGHT -> node.bounds.right; PropName.BOTTOM -> node.bounds.bottom
            PropName.WIDTH -> node.bounds.width; PropName.HEIGHT -> node.bounds.height
            PropName.CHILD_COUNT -> node.childCount; PropName.INDEX -> node.index; PropName.DEPTH -> node.depth
            else -> null
        }
        return intOps(c, int)
    }

    /** [...] 内布尔表达式递归求值（Cond 解包为单条件求值） */
    fun eval(node: TreeNode, expr: PropExpr): Boolean = when (expr) {
        is PropExpr.Cond -> eval(node, expr.cond)
        is PropExpr.And -> expr.parts.all { eval(node, it) }
        is PropExpr.Or -> expr.parts.any { eval(node, it) }
    }

    fun nodeMatches(node: TreeNode, sel: NodeSelector): Boolean = sel.props.all { eval(node, it) }

    private fun strOps(v: String?, c: PropCond): Boolean {
        // null 字面量：=null → 属性值为 null；!=null → 属性值非 null；其他操作符不可配 null（恒 false）
        if (c.nullVal == true) return when (c.op) {
            CmpOp.EQ -> v == null
            CmpOp.NEQ -> v != null
            else -> false
        }
        // EQ/NEQ 加 c.strVal != null 前缀：解析期校验后本不可达，防御跨类型 null 陷阱
        return when (c.op) {
            CmpOp.EQ -> c.strVal != null && v == c.strVal
            CmpOp.NEQ -> c.strVal != null && v != c.strVal
            CmpOp.STARTS_WITH -> v?.startsWith(c.strVal!!) == true
            CmpOp.NOT_STARTS_WITH -> v != null && !v.startsWith(c.strVal!!)
            CmpOp.ENDS_WITH -> v?.endsWith(c.strVal!!) == true
            CmpOp.NOT_ENDS_WITH -> v != null && !v.endsWith(c.strVal!!)
            CmpOp.CONTAINS -> v?.contains(c.strVal!!) == true
            CmpOp.NOT_CONTAINS -> v != null && !v.contains(c.strVal!!)
            CmpOp.REGEX -> v != null && regex(c.strVal!!)?.containsMatchIn(v) == true
            CmpOp.NOT_REGEX -> v != null && regex(c.strVal!!)?.containsMatchIn(v) != true
            else -> false
        }
    }

    private fun intOps(c: PropCond, v: Int?): Boolean {
        if (c.nullVal == true) return when (c.op) {  // 整型属性均非 null：=null 恒假，!=null 恒真
            CmpOp.EQ -> v == null
            CmpOp.NEQ -> v != null
            else -> false
        }
        // GKD: 属性为 null 时除 =/!= 外为 false
        return when (c.op) {
            CmpOp.EQ -> c.intVal != null && v == c.intVal
            CmpOp.NEQ -> c.intVal != null && v != c.intVal
            CmpOp.GT -> v != null && v > c.intVal!!
            CmpOp.GE -> v != null && v >= c.intVal!!
            CmpOp.LT -> v != null && v < c.intVal!!
            CmpOp.LE -> v != null && v <= c.intVal!!
            else -> false
        }
    }

    /** 非法正则返回 null（调用方按不匹配处理），不抛异常 */
    private fun regex(p: String): Regex? =
        regexCache.getOrPut(p) { runCatching { Regex(p) }.getOrNull() }

    private val STR_PROPS = setOf(PropName.ID, PropName.VID, PropName.NAME, PropName.TEXT, PropName.DESC)
    private val BOOL_PROPS = setOf(PropName.CLICKABLE, PropName.LONG_CLICKABLE, PropName.FOCUSABLE,
        PropName.CHECKABLE, PropName.CHECKED, PropName.EDITABLE, PropName.VISIBLE_TO_USER)
}
