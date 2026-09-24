package cn.hys159x.grid.engine.selector

/** 一条选择器 = 单元链；每个单元 = 可选关系符 + 节点选择器 */
data class ParsedSelector(val units: List<ChainUnit>) {
    /** @ 标记的单元下标；无标记取最后一个（GKD 语义） */
    val targetIndex: Int get() = units.indexOfFirst { it.atTarget }.takeIf { it >= 0 } ?: units.lastIndex
}

data class ChainUnit(
    val atTarget: Boolean,      // 是否带 @ 前缀
    val relation: Relation?,    // 与左侧单元的关系；仅 units[0] 为 null
    val node: NodeSelector,
)

/** 属性块（[...] 内）的布尔表达式；多个 [...] 并列与 name 前缀条件之间仍为 AND（见 NodeSelector.props） */
sealed interface PropExpr {
    data class Cond(val cond: PropCond) : PropExpr
    data class And(val parts: List<PropExpr>) : PropExpr
    data class Or(val parts: List<PropExpr>) : PropExpr
}

/** name 前缀（如 TextView[...]）转为 NAME= 等值条件（前置）；props 各项为 AND。
 *  `*` 通配单元 = props 为空列表（匹配任意节点）。 */
data class NodeSelector(val props: List<PropExpr>) {
    constructor(name: String?, props: List<PropExpr>) : this(
        (name?.let { listOf<PropExpr>(PropExpr.Cond(PropCond.str(PropName.NAME, CmpOp.EQ, it))) } ?: emptyList()) + props
    )
}

data class PropCond(
    val prop: PropName,
    val isLength: Boolean,     // text.length
    val op: CmpOp,
    val nullVal: Boolean? = null,   // [id=null] 的 null 字面量（解析器只产出 true；求值见 PropEvaluator）
    val strVal: String? = null,
    val boolVal: Boolean? = null,
    val intVal: Int? = null,
) {
    companion object {
        fun str(p: PropName, op: CmpOp, v: String) = PropCond(p, false, op, strVal = v)
        fun boolean(p: PropName, op: CmpOp, v: Boolean) = PropCond(p, false, op, boolVal = v)
        fun int(p: PropName, op: CmpOp, v: Int, isLength: Boolean = false) = PropCond(p, isLength, op, intVal = v)
    }
}

enum class PropName { ID, VID, NAME, TEXT, DESC, CLICKABLE, LONG_CLICKABLE, FOCUSABLE, CHECKABLE,
    CHECKED, EDITABLE, VISIBLE_TO_USER, LEFT, TOP, RIGHT, BOTTOM, WIDTH, HEIGHT, CHILD_COUNT, INDEX, DEPTH }

enum class CmpOp(val symbol: String) {
    EQ("="), NEQ("!="), GT(">"), GE(">="), LT("<"), LE("<="),
    STARTS_WITH("^="), NOT_STARTS_WITH("!^="), ENDS_WITH("$="), NOT_ENDS_WITH("!$="),
    CONTAINS("*="), NOT_CONTAINS("!*="), REGEX("~="), NOT_REGEX("!~=");
}

/** 关系符描述"左侧单元节点相对右侧单元节点"的位置。
 *  关系符数字三种形式：无数字 / 正整数 / (a,b) 多值；另支持字面字母 n = 任意（层/偏移不限定）。 */
sealed interface Relation {
    /** A >n B / 空格：A 是 B 的祖先；steps=null 表示任意层（含字面 n 写法 `>n`） */
    data class Ancestor(val steps: List<Int>?) : Relation
    /** A <n B：A 是 B 的第 n 层子节点；字面 n 写法 `<n` 等价任意层子孙（Descendant(null)） */
    data class Child(val steps: List<Int>) : Relation
    /** A <<n B：A 是 B 的子孙；steps=null 表示任意层（含字面 n 写法 `<<n`） */
    data class Descendant(val steps: List<Int>?) : Relation
    /** A +n B：A 是 B 的前置兄弟（A.index = B.index - n）；offsets=null（字面 n `+n`）= 任意偏移前置兄弟 */
    data class PrevSibling(val offsets: List<Int>?) : Relation
    /** A -n B：A 是 B 的后置兄弟；offsets=null（字面 n `-n`）= 任意偏移后置兄弟 */
    data class NextSibling(val offsets: List<Int>?) : Relation
}

class SelectorSyntaxException(msg: String) : Exception(msg)
