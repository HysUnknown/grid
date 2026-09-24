# 分册 02：引擎核心（Task 3-6）

前置：Task 1 骨架已就绪；语义依据 [00-overview.md](00-overview.md) 校准结论。所有文件在 `android/engine/` 模块，包根 `cn.hys159x.grid.engine`。

> **v1.3 实用扩展同步（真机验证后回写）**：Task 4-6 的代码块已同步为扩展后的实现——属性块布尔表达式（`||`/`&&`/括号）、字面 `n` 关系符、`*` 通配单元、`null` 字面量、反引号字符串、属性块内空格容忍、裸 name 单元作空格关系右侧（selector-spec v1.3 §2.1/§2.2/§2.4/§2.5/§2.6）。AST 变化：`NodeSelector.props` 由 `List<PropCond>` 改为 `List<PropExpr>`（多 `[...]` 并列仍 AND，简单条件为 `PropExpr.Cond`）；`Relation.PrevSibling/NextSibling.offsets` 改为可空（null=字面 n 任意偏移）。官方订阅回归 skipped 26 → 0。

---

### Task 3: TreeNode 接口与测试树 DSL

**Files:**
- Create: `android/engine/src/main/kotlin/cn/hys159x/grid/engine/node/TreeNode.kt`
- Create: `android/engine/src/test/kotlin/cn/hys159x/grid/engine/node/FakeNode.kt`
- Test: `android/engine/src/test/kotlin/cn/hys159x/grid/engine/node/FakeNodeTest.kt`

- [ ] **Step 1: 写失败的测试**

`android/engine/src/test/kotlin/cn/hys159x/grid/engine/node/FakeNodeTest.kt`：

```kotlin
package cn.hys159x.grid.engine.node

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FakeNodeTest {
    @Test
    fun `tree links are set by dsl`() {
        val root = fakeRoot(vid = "root") {
            child(vid = "ad") {
                child(vid = "skip", text = "跳过", clickable = true)
            }
        }
        val ad = root.children.single()
        val skip = ad.children.single()
        assertEquals(1, ad.depth)
        assertEquals(2, skip.depth)
        assertEquals(0, skip.index)
        assertSame(root, ad.parent)
        assertSame(ad, skip.parent)
        assertEquals("跳过", skip.text)
        assertTrue(skip.clickable)
    }
}
```

`android/engine/src/test/kotlin/cn/hys159x/grid/engine/node/FakeNode.kt`：

```kotlin
package cn.hys159x.grid.engine.node

class FakeNode(
    override var id: String? = null,
    override var vid: String? = null,
    override var name: String? = null,
    override var text: String? = null,
    override var desc: String? = null,
    override var clickable: Boolean = false,
    override var longClickable: Boolean = false,
    override var focusable: Boolean = false,
    override var checkable: Boolean = false,
    override var checked: Boolean = false,
    override var editable: Boolean = false,
    override var visibleToUser: Boolean = true,
    override var bounds: Bounds = Bounds(0, 0, 100, 100),
) : TreeNode {
    val children = mutableListOf<FakeNode>()
    override var parent: TreeNode? = null
    override var depth: Int = 0
    override var index: Int = 0
    override val childCount: Int get() = children.size
    override fun childAt(i: Int): FakeNode? = children.getOrNull(i)
    private var indexSeed = 0
}

fun fakeRoot(
    id: String? = null, vid: String? = null, name: String? = null,
    text: String? = null, desc: String? = null,
    bounds: Bounds = Bounds(0, 0, 1080, 2400),
    block: FakeNode.() -> Unit = {},
): FakeNode {
    val root = FakeNode(id = id, vid = vid, name = name, text = text, desc = desc, bounds = bounds)
    root.apply(block)
    root.wire(root)
    return root
}

private fun FakeNode.wire(root: FakeNode) {
    children.forEachIndexed { i, c ->
        c.parent = this
        c.index = i
        c.depth = depth + 1
        c.wire(root)
    }
}
```

- [ ] **Step 2: 运行确认编译失败**

```bash
cd D:/dev/app/android && ./gradlew.bat :engine:test
```

Expected: 编译错误 `Unresolved reference: TreeNode`（接口未定义）。

- [ ] **Step 3: 实现 TreeNode 与 Bounds**

`android/engine/src/main/kotlin/cn/hys159x/grid/engine/node/TreeNode.kt`：

```kotlin
package cn.hys159x.grid.engine.node

/**
 * 平台无关的无障碍节点树接口；Android 侧由 AccessibilityNodeInfo 适配器实现。
 *
 * 相等性契约：匹配引擎（ChainMatcher）使用 == 比较节点。同一底层节点的不同包装实例
 * 必须 equals（适配器实现应以底层节点为相等依据，如 A11yNode 比较 AccessibilityNodeInfo）。
 *
 * id = 完整 viewIdResourceName（如 "com.x:id/skip"）；vid = 去包名短名（"skip"）。
 * childAt 越界（含负下标）必须返回 null（适配器不得抛异常）。
 */
interface TreeNode {
    val id: String?
    val vid: String?
    val name: String?
    val text: String?
    val desc: String?
    val clickable: Boolean
    val longClickable: Boolean
    val focusable: Boolean
    val checkable: Boolean
    val checked: Boolean
    val editable: Boolean
    val visibleToUser: Boolean
    val bounds: Bounds
    val childCount: Int
    val index: Int
    val depth: Int
    val parent: TreeNode?
    fun childAt(i: Int): TreeNode?
}

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    /** 是否在屏幕外（完全不可见）。screenW/screenH 为真实屏幕像素，宿主应传入实际值；默认值仅供测试兜底 */
    fun offScreen(screenW: Int = 4320, screenH: Int = 2400): Boolean =
        right <= 0 || bottom <= 0 || left >= screenW || top >= screenH || width <= 0 || height <= 0
}
```

注：`FakeNode` 中多余的 `indexSeed` 行是笔误干扰，创建文件时删除该行；`bounds` 在接口中是只读属性。

- [ ] **Step 4: 运行测试通过**

```bash
cd D:/dev/app/android && ./gradlew.bat :engine:test
```

Expected: `BUILD SUCCESSFUL`，FakeNodeTest 通过。

- [ ] **Step 5: Commit**

```bash
cd D:/dev/app
git add android/engine
git commit -m "feat(engine): TreeNode 平台无关节点接口与测试树 DSL"
```

---

### Task 4: 选择器 AST 与解析器

**Files:**
- Create: `android/engine/src/main/kotlin/cn/hys159x/grid/engine/selector/SelectorAst.kt`
- Create: `android/engine/src/main/kotlin/cn/hys159x/grid/engine/selector/SelectorParser.kt`
- Test: `android/engine/src/test/kotlin/cn/hys159x/grid/engine/selector/SelectorParserTest.kt`

- [ ] **Step 1: 写 AST 类型**

`android/engine/src/main/kotlin/cn/hys159x/grid/engine/selector/SelectorAst.kt`：

```kotlin
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
```

- [ ] **Step 2: 写失败的解析器测试**

`android/engine/src/test/kotlin/cn/hys159x/grid/engine/selector/SelectorParserTest.kt`：

```kotlin
package cn.hys159x.grid.engine.selector

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SelectorParserTest {

    private fun parse(s: String) = SelectorParser.parse(s)

    /** props[i] 解包为单条件（简单选择器均为 Cond） */
    private fun cond(sel: ParsedSelector, unit: Int = 0, idx: Int = 0): PropCond =
        (sel.units[unit].node.props[idx] as PropExpr.Cond).cond

    @Test
    fun `single prop`() {
        val r = parse("""[text="跳过"]""")
        assertEquals(1, r.units.size)
        val p = cond(r)
        assertEquals(PropName.TEXT, p.prop)
        assertEquals(CmpOp.EQ, p.op)
        assertEquals("跳过", p.strVal)
        assertNull(r.units[0].relation)
        assertEquals(0, r.targetIndex)
    }

    @Test
    fun `name prefix and chained props`() {
        val r = parse("""TextView[text*="跳过"][text.length<10][visibleToUser=true]""")
        val props = r.units[0].node.props
        assertEquals(4, props.size) // name + 3 条
        assertEquals(PropName.NAME, cond(r, idx = 0).prop)
        assertTrue(cond(r, idx = 2).isLength)
        assertEquals(CmpOp.LT, cond(r, idx = 2).op)
        assertEquals(10, cond(r, idx = 2).intVal)
        assertEquals(PropName.VISIBLE_TO_USER, cond(r, idx = 3).prop)
        assertEquals(true, cond(r, idx = 3).boolVal)
    }

    @Test
    fun `at marker and ancestor relation`() {
        val r = parse("""@[vid="skip"] >2 [vid="ad"]""")
        assertEquals(0, r.targetIndex)
        assertEquals(Relation.Ancestor(listOf(2)), r.units[1].relation)
    }

    @Test
    fun `bare relation defaults`() {
        assertEquals(Relation.Ancestor(null), parse("""[vid="a"] > [vid="b"]""").units[1].relation)
        assertEquals(Relation.Ancestor(null), parse("""[vid="a"] [vid="b"]""").units[1].relation)
        assertEquals(Relation.Child(listOf(1)), parse("""[vid="a"] < [vid="b"]""").units[1].relation)
        assertEquals(Relation.Descendant(listOf(2)), parse("""[vid="a"] <<2 [vid="b"]""").units[1].relation)
        assertEquals(Relation.Descendant(null), parse("""[vid="a"] << [vid="b"]""").units[1].relation)
        assertEquals(Relation.PrevSibling(listOf(1)), parse("""[vid="a"] + [vid="b"]""").units[1].relation)
        assertEquals(Relation.NextSibling(listOf(2)), parse("""[vid="a"] -2 [vid="b"]""").units[1].relation)
    }

    @Test
    fun `literal n relations parse to any semantics`() {
        assertEquals(Relation.Ancestor(null), parse("""[vid="a"] >n [vid="b"]""").units[1].relation)
        assertEquals(Relation.Descendant(null), parse("""[vid="a"] <n [vid="b"]""").units[1].relation)
        assertEquals(Relation.Descendant(null), parse("""[vid="a"] <<n [vid="b"]""").units[1].relation)
        assertEquals(Relation.PrevSibling(null), parse("""[vid="a"] +n [vid="b"]""").units[1].relation)
        assertEquals(Relation.NextSibling(null), parse("""[vid="a"] -n [vid="b"]""").units[1].relation)
        // n 后紧跟标识符字符 = 节点名前缀而非字面 n
        assertEquals(Relation.Ancestor(null), parse("""[vid="a"] >nameView""").units[1].relation)
        assertEquals("nameView", cond(parse("""[vid="a"] >nameView"""), unit = 1).strVal)
    }

    @Test
    fun `string operators`() {
        val r = parse("""[text^="打开"][desc$="按钮"][text~="\d+"]""")
        val ops = r.units[0].node.props.map { (it as PropExpr.Cond).cond.op }
        assertEquals(listOf(CmpOp.STARTS_WITH, CmpOp.ENDS_WITH, CmpOp.REGEX), ops)
    }

    @Test
    fun `int and bool props`() {
        val r = parse("""[width>=100][clickable=true][childCount>0]""")
        assertEquals(CmpOp.GE, cond(r, idx = 0).op)
        assertEquals(100, cond(r, idx = 0).intVal)
        assertEquals(true, cond(r, idx = 2).boolVal ?: cond(r, idx = 1).boolVal)
    }

    @Test
    fun `unsupported arrow relation throws`() {
        assertThrows<SelectorSyntaxException> { parse("""[vid="a"] -> [vid="b"]""") }
    }

    @Test
    fun `escaped quote in string value`() {
        val r = parse("""[text="a\"b"]""")
        assertEquals("a\"b", cond(r).strVal)
    }

    @Test
    fun `malformed inputs throw syntax exception`() {
        assertThrows<SelectorSyntaxException> { parse("""[text="abc""") }        // 未闭合字符串
        assertThrows<SelectorSyntaxException> { parse("""[text="a\""") }         // 尾反斜杠后未闭合
        assertThrows<SelectorSyntaxException> { parse("") }                      // 空串
        assertThrows<SelectorSyntaxException> { parse("@") }                     // 裸 @
        assertThrows<SelectorSyntaxException> { parse("""[vid="a"] >(,2) [vid="b"]""") } // 多值空项
        assertThrows<SelectorSyntaxException> { parse("""[vid="a"] >0 [vid="b"]""") }    // 0 层
        assertThrows<SelectorSyntaxException> { parse("""[width=99999999999]""") }      // 溢出
        assertThrows<SelectorSyntaxException> { parse("""[vid^=5]""") }       // 字符串属性配数字值
        assertThrows<SelectorSyntaxException> { parse("""[width>=true]""") }  // 整型属性配布尔值
        assertThrows<SelectorSyntaxException> { parse("""[vid=5]""") }        // 字符串属性配数字值
    }

    @Test
    fun `regex backslash preserved and multi-value parsed`() {
        val r = parse("""[text~="\d+"]""")
        assertEquals("""\d+""", cond(r).strVal)
        assertEquals(Relation.Ancestor(listOf(1, 2)), parse("""[vid="a"] >(1,2) [vid="b"]""").units[1].relation)
        assertEquals(Relation.Child(listOf(1, 2)), parse("""[vid="a"] <(1,2) [vid="b"]""").units[1].relation)
    }

    @Test
    fun `target index falls back to last unit and accepts later at`() {
        assertEquals(1, parse("""[vid="a"] > @[vid="b"]""").targetIndex)
        assertEquals(1, parse("""[vid="a"] > [vid="b"]""").targetIndex)
    }

    @Test
    fun `space relation accepts bare name and wildcard unit`() {
        // 空格=任意祖先，右侧可为裸 name 单元或 * 通配（官方 com.tencent.mtt 实例：`FrameLayout TextView[...]`）
        val r = parse("""LinearLayout TextView[text="x"]""")
        assertEquals(2, r.units.size)
        assertEquals(Relation.Ancestor(null), r.units[1].relation)
        assertEquals(PropName.TEXT, cond(r, unit = 1, idx = 1).prop)   // props[0] = name 前缀条件
        val r2 = parse("""[vid="a"] *""")
        assertEquals(2, r2.units.size)
        assertEquals(Relation.Ancestor(null), r2.units[1].relation)
        // 紧邻（无空格）仍非法
        assertThrows<SelectorSyntaxException> { parse("""[vid="a"]TextView""") }
    }

    // ---- v1.3 扩展：布尔表达式 / 通配单元 / null 字面量 / 反引号字符串 / 空格容忍 ----

    @Test
    fun `or expression in prop block`() {
        val e = parse("""[desc="跳过"||desc="GdtCountDownView"]""").units[0].node.props.single()
        val or = e as PropExpr.Or
        assertEquals(2, or.parts.size)
        val c0 = (or.parts[0] as PropExpr.Cond).cond
        val c1 = (or.parts[1] as PropExpr.Cond).cond
        assertEquals(PropName.DESC, c0.prop); assertEquals("跳过", c0.strVal)
        assertEquals(PropName.DESC, c1.prop); assertEquals("GdtCountDownView", c1.strVal)
    }

    @Test
    fun `and binds tighter than or`() {
        val e = parse("""[text^="还剩"&&text$="秒"||vid="x"]""").units[0].node.props.single()
        val or = e as PropExpr.Or   // (text^ && text$) || vid= ，而非 text^ && (text$ || vid=)
        assertEquals(2, or.parts.size)
        assertTrue(or.parts[0] is PropExpr.And)
        assertTrue(or.parts[1] is PropExpr.Cond)
    }

    @Test
    fun `parentheses group expression`() {
        val e = parse("""[(text.length<10&&text*="跳过") || id$="tt_splash_skip_btn"]""")
            .units[0].node.props.single()
        val or = e as PropExpr.Or
        assertEquals(2, or.parts.size)
        val and = or.parts[0] as PropExpr.And
        assertTrue((and.parts[0] as PropExpr.Cond).cond.isLength)
        assertEquals(10, (and.parts[0] as PropExpr.Cond).cond.intVal)
        assertEquals(CmpOp.ENDS_WITH, (or.parts[1] as PropExpr.Cond).cond.op)
    }

    @Test
    fun `nested parentheses and spaces around logical operators`() {
        // 官方全局开屏兜底规则形态：嵌套括号 + 逻辑符两侧空格
        val s = """[childCount=0][(text.length<10&&(text*="跳过"||text*="跳过^")) || vid*="skip" || (vid*="count" && vid*="down" && vid!*="download")]"""
        val props = parse(s).units[0].node.props
        assertEquals(2, props.size)                       // 两个 [...] 并列仍为 AND
        val or = props[1] as PropExpr.Or
        assertEquals(3, or.parts.size)
        val innerOr = ((or.parts[0] as PropExpr.And).parts[1] as PropExpr.Or)
        assertEquals(2, innerOr.parts.size)
        val tripleAnd = or.parts[2] as PropExpr.And
        assertEquals(3, tripleAnd.parts.size)
        assertEquals(CmpOp.NOT_CONTAINS, (tripleAnd.parts[2] as PropExpr.Cond).cond.op)
    }

    @Test
    fun `unbalanced expression throws`() {
        assertThrows<SelectorSyntaxException> { parse("""[(text="a"||vid="x"]""") }   // 括号未闭合
        assertThrows<SelectorSyntaxException> { parse("""[text="a" &&]""") }          // && 后无条件
        assertThrows<SelectorSyntaxException> { parse("""[|| text="a"]""") }          // 起始即逻辑符
    }

    @Test
    fun `wildcard unit parses as empty selector`() {
        val r = parse("""@View[clickable=true] <3 * <2 * < FrameLayout[id="com.x:id/ad"]""")
        assertEquals(4, r.units.size)
        assertEquals(0, r.targetIndex)                        // @View 仍是目标
        assertTrue(r.units[1].node.props.isEmpty())           // * = 任意节点（无条件）
        assertTrue(r.units[2].node.props.isEmpty())
        assertEquals(Relation.Child(listOf(3)), r.units[1].relation)
        assertEquals(Relation.Child(listOf(2)), r.units[2].relation)
        assertEquals(Relation.Child(listOf(1)), r.units[3].relation)
    }

    @Test
    fun `wildcard unit matches any node in chain`() {
        // * 与前后单元构成链；单独 * 也是合法选择器
        val r = parse("""*""")
        assertEquals(1, r.units.size)
        assertTrue(r.units[0].node.props.isEmpty())
        // 紧跟属性块的 * 不吞并属性（* 与 [..] 之间无关系符 → 语法错误）
        assertThrows<SelectorSyntaxException> { parse("""*[vid="a"]""") }
    }

    @Test
    fun `null literal value parses`() {
        val c = cond(parse("""[id=null]"""))
        assertEquals(true, c.nullVal)
        assertEquals(PropName.ID, c.prop)
        assertEquals(CmpOp.EQ, c.op)
        val c2 = cond(parse("""[text!=null]"""))
        assertEquals(true, c2.nullVal)
        assertEquals(CmpOp.NEQ, c2.op)
        // null 不受属性类型校验约束（整型属性也可写 null）
        assertEquals(true, cond(parse("""[width=null]""")).nullVal)
    }

    @Test
    fun `null literal with non-eq op parses but is inert`() {
        // *=null 等仍可解析（求值恒 false，见 PropEvaluatorTest）
        val c = cond(parse("""[text*=null]"""))
        assertEquals(true, c.nullVal)
        assertEquals(CmpOp.CONTAINS, c.op)
    }

    @Test
    fun `backtick string value parses without escape`() {
        val r = parse("""[id=`tv.danmaku.bili:id/ad_tint_frame`]""")
        assertEquals("tv.danmaku.bili:id/ad_tint_frame", cond(r).strVal)
        // 反引号内反斜杠不转义（保留字面）
        assertEquals("""\d+""", cond(parse("""[text~=`\d+`]""")).strVal)
        assertThrows<SelectorSyntaxException> { parse("""[id=`unclosed]""") }  // 未闭合反引号
    }

    @Test
    fun `backtick works in chained and expr contexts`() {
        val e = parse("""[id=`com.x:id/a`||text=`跳 过`]""").units[0].node.props.single()
        val or = e as PropExpr.Or
        assertEquals("com.x:id/a", (or.parts[0] as PropExpr.Cond).cond.strVal)
        assertEquals("跳 过", (or.parts[1] as PropExpr.Cond).cond.strVal)
    }

    @Test
    fun `spaces tolerated inside prop block`() {
        // 官方订阅实例写法（com.dianxinai.mobile）：操作符两侧空格
        val c = cond(parse("""[clickable = true]"""))
        assertEquals(true, c.boolVal)
        val e = parse("""TextView[text $="s" && text.length=2]""").units[0].node.props[1]  // props[0] = name 前缀条件
        val and = e as PropExpr.And
        assertEquals(CmpOp.ENDS_WITH, (and.parts[0] as PropExpr.Cond).cond.op)
        assertEquals(2, (and.parts[1] as PropExpr.Cond).cond.intVal)
    }
}
```

- [ ] **Step 3: 运行确认失败**

```bash
cd D:/dev/app/android && ./gradlew.bat :engine:test
```

Expected: `Unresolved reference: SelectorParser`。

- [ ] **Step 4: 实现解析器**

`android/engine/src/main/kotlin/cn/hys159x/grid/engine/selector/SelectorParser.kt`：

```kotlin
package cn.hys159x.grid.engine.selector

/** 手写递归下降解析器：选择器字符串 → ParsedSelector。
 *  文法：selector := unit (relation unit)* ；unit := '*' | name? propBlock*；
 *  propBlock := or（or := and ('||' and)*，and := primary ('&&' primary)*，primary := '(' or ')' | cond）。
 *  [...] 内允许空格（操作符/值/逻辑符两侧）；不支持 '->'（抛异常由上层标记 skip）。 */
object SelectorParser {

    private val STRING_PROPS = setOf(PropName.ID, PropName.VID, PropName.NAME, PropName.TEXT, PropName.DESC)
    private val BOOL_PROPS = setOf(PropName.CLICKABLE, PropName.LONG_CLICKABLE, PropName.FOCUSABLE,
        PropName.CHECKABLE, PropName.CHECKED, PropName.EDITABLE, PropName.VISIBLE_TO_USER)

    /** parseValue 的 null 字面量哨兵（区别于合法值类型 String/Boolean/Int） */
    private object NullLit

    fun parse(input: String): ParsedSelector {
        val p = Parser(input)
        val units = mutableListOf(p.parseUnit())
        while (true) {
            val rel = p.parseRelation() ?: break
            units += p.parseUnit(rel)
        }
        p.skipSpaces()
        if (!p.eof()) throw SelectorSyntaxException("未消费完全: '${input}' 剩余 '${p.rest()}'")
        return ParsedSelector(units)
    }

    private class Parser(val s: String) {
        var i = 0
        fun eof() = i >= s.length
        fun rest() = s.substring(i)
        fun peek(): Char? = s.getOrNull(i)
        fun skipSpaces() { while (peek() == ' ') i++ }

        fun parseUnit(rel: Relation? = null): ChainUnit {
            skipSpaces()
            val at = peek() == '@'
            if (at) i++
            if (peek() == '*') {   // `*` 通配单元：匹配任意节点（无 name/props）
                i++
                return ChainUnit(at, rel, NodeSelector(emptyList()))
            }
            var name: String? = null
            if (peek()?.isLetter() == true) {
                val start = i
                while (peek()?.let { it.isLetterOrDigit() || it == '_' } == true) i++
                name = s.substring(start, i)
            }
            val props = mutableListOf<PropExpr>()
            while (peek() == '[') {
                i++
                props += parsePropBlock()
                skipSpaces()
                expect(']')
            }
            if (name == null && props.isEmpty()) throw SelectorSyntaxException("空节点选择器 @ $i")
            return ChainUnit(at, rel, NodeSelector(name, props))
        }

        /** [...] 内布尔表达式：or 层 → and 层 → primary（括号分组 | 单条件）；&& 优先级高于 || */
        fun parsePropBlock(): PropExpr {
            val e = parseOr()
            skipSpaces()
            return e
        }

        fun parseOr(): PropExpr {
            val parts = mutableListOf(parseAnd())
            while (takeTok("||")) parts += parseAnd()
            return if (parts.size == 1) parts[0] else PropExpr.Or(parts)
        }

        fun parseAnd(): PropExpr {
            val parts = mutableListOf(parsePrimary())
            while (takeTok("&&")) parts += parsePrimary()
            return if (parts.size == 1) parts[0] else PropExpr.And(parts)
        }

        /** 括号只在 [...] 内作为分组符；多值 (1,2) 在 [] 外由 relAfter 处理，互不影响 */
        fun parsePrimary(): PropExpr {
            skipSpaces()
            if (peek() == '(') {
                i++
                val e = parseOr()
                skipSpaces()
                expect(')')
                return e
            }
            return PropExpr.Cond(parseProp())
        }

        fun parseProp(): PropCond {
            skipSpaces()
            val propStart = i
            while (peek()?.let { it.isLetterOrDigit() || it == '_' } == true) i++
            val propName = s.substring(propStart, i)
            val prop = when (propName) {
                "id" -> PropName.ID; "vid" -> PropName.VID; "name" -> PropName.NAME
                "text" -> PropName.TEXT; "desc" -> PropName.DESC
                "clickable" -> PropName.CLICKABLE; "longClickable" -> PropName.LONG_CLICKABLE
                "focusable" -> PropName.FOCUSABLE; "checkable" -> PropName.CHECKABLE
                "checked" -> PropName.CHECKED; "editable" -> PropName.EDITABLE
                "visibleToUser" -> PropName.VISIBLE_TO_USER
                "left" -> PropName.LEFT; "top" -> PropName.TOP
                "right" -> PropName.RIGHT; "bottom" -> PropName.BOTTOM
                "width" -> PropName.WIDTH; "height" -> PropName.HEIGHT
                "childCount" -> PropName.CHILD_COUNT; "index" -> PropName.INDEX; "depth" -> PropName.DEPTH
                else -> throw SelectorSyntaxException("不支持属性 '$propName'")
            }
            val isLength = if (peek() == '.' && s.startsWith(".length", i)) { i += 7; true } else false
            val op = parseOp()
            val v = parseValue()
            if (v === NullLit) return PropCond(prop, isLength, op, nullVal = true)
            validateValueType(prop, isLength, op, v)
            return when (v) {
                is String -> PropCond(prop, isLength, op, strVal = v)
                is Boolean -> PropCond(prop, isLength, op, boolVal = v)
                is Int -> PropCond(prop, isLength, op, intVal = v)
                else -> throw SelectorSyntaxException("值类型错误")
            }
        }

        /** 值类型必须匹配属性类别，与操作符无关（GKD 同样按属性类型约束值）：
         *  字符串属性非 length 要求 String；字符串属性 length 要求 Int（length 是 int）；
         *  布尔属性要求 Boolean；整型属性要求 Int。null 字面量不参与类型校验（任何属性均可写 =null/!=null）。 */
        private fun validateValueType(prop: PropName, isLength: Boolean, op: CmpOp, v: Any) {
            val ok = when {
                prop in STRING_PROPS -> if (isLength) v is Int else v is String
                prop in BOOL_PROPS -> v is Boolean
                else -> v is Int
            }
            if (!ok) throw SelectorSyntaxException("值类型与属性/操作符不符 @ $i")
        }

        fun parseOp(): CmpOp {
            skipSpaces() // 官方订阅存在 [text $="s"] 写法：操作符两侧允许空格
            // 先匹配三字符否定操作符
            for (op in listOf(CmpOp.NOT_STARTS_WITH, CmpOp.NOT_ENDS_WITH, CmpOp.NOT_CONTAINS, CmpOp.NOT_REGEX))
                if (s.startsWith(op.symbol, i)) { i += op.symbol.length; return op }
            for (op in listOf(CmpOp.EQ, CmpOp.NEQ, CmpOp.GE, CmpOp.LE, CmpOp.STARTS_WITH, CmpOp.ENDS_WITH, CmpOp.CONTAINS, CmpOp.REGEX, CmpOp.GT, CmpOp.LT))
                if (s.startsWith(op.symbol, i)) { i += op.symbol.length; return op }
            throw SelectorSyntaxException("未知操作符 @ $i")
        }

        fun parseValue(): Any {
            skipSpaces()
            return when {
                peek() == '"' -> parseString()
                peek() == '`' -> parseBacktick()
                s.startsWith("null", i) -> { i += 4; NullLit }
                s.startsWith("true", i) -> { i += 4; true }
                s.startsWith("false", i) -> { i += 5; false }
                peek()?.let { it.isDigit() || it == '-' } == true -> {
                    val start = i
                    if (peek() == '-') {
                        i++
                        if (peek()?.isDigit() != true) throw SelectorSyntaxException("数字格式错误 @ $i")
                    }
                    while (peek()?.isDigit() == true) i++
                    s.substring(start, i).toIntOrNull() ?: throw SelectorSyntaxException("数字格式错误 @ $i")
                }
                else -> throw SelectorSyntaxException("值解析失败 @ $i")
            }
        }

        fun parseString(): String {
            i++ // 开头引号
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '\\' -> {
                        i++
                        if (i >= s.length) throw SelectorSyntaxException("字符串未闭合（尾部孤立反斜杠）")
                        when (val e = s[i]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            '"', '\\' -> sb.append(e)
                            else -> { sb.append('\\'); sb.append(e) } // 未知转义原样保留（正则 \d 等）
                        }
                        i++
                    }
                    c == '"' -> { i++; return sb.toString() }
                    else -> { sb.append(c); i++ }
                }
            }
            throw SelectorSyntaxException("字符串未闭合 @ ${i - 1}")
        }

        /** 反引号字符串：内部无转义，读到下一个反引号为止（官方订阅用于含 : 的 id 值等） */
        fun parseBacktick(): String {
            i++ // 开头反引号
            val start = i
            while (i < s.length && s[i] != '`') i++
            if (i >= s.length) throw SelectorSyntaxException("反引号字符串未闭合 @ ${i - 1}")
            val v = s.substring(start, i)
            i++
            return v
        }

        fun expect(c: Char) {
            if (peek() != c) throw SelectorSyntaxException("期望 '$c' @ $i")
            i++
        }

        /** 跳过空格后若位于 token 则消费并返回 true（[...] 内空格不敏感） */
        private fun takeTok(tok: String): Boolean {
            skipSpaces()
            if (s.startsWith(tok, i)) { i += tok.length; return true }
            return false
        }

        /** 返回 null 表示选择器结束 */
        fun parseRelation(): Relation? {
            skipSpaces()
            val spaceSeen = i > 0 && s[i - 1] == ' '
            if (peek() == null && !spaceSeen || eof()) return null
            val c = peek() ?: return null
            when (c) {
                '>' -> { i++; return if (literalN()) Relation.Ancestor(null) else relAfter { Relation.Ancestor(it) } }
                '<' -> {
                    i++
                    if (peek() == '<') {
                        i++
                        return if (literalN()) Relation.Descendant(null) else relAfter { Relation.Descendant(it) }
                    }
                    return if (literalN()) Relation.Descendant(null) else relAfter { Relation.Child(it ?: listOf(1)) }
                }
                '+' -> { i++; return if (literalN()) Relation.PrevSibling(null) else relAfter { Relation.PrevSibling(it ?: listOf(1)) } }
                '-' -> {
                    i++
                    if (peek() == '>') throw SelectorSyntaxException("不支持 '->' 查询顺序符")
                    return if (literalN()) Relation.NextSibling(null) else relAfter { Relation.NextSibling(it ?: listOf(1)) }
                }
                else -> {
                    // 无显式关系符：空格=任意祖先（后随 [ / @ / name 前缀 / * 通配均可，如 `LinearLayout TextView`）；紧邻=非法
                    if (c == '[' || c == '@' || c == '*' || c.isLetter()) {
                        if (spaceSeen) return Relation.Ancestor(null)
                        throw SelectorSyntaxException("单元之间缺少关系符 @ $i")
                    }
                    throw SelectorSyntaxException("非法字符 '$c' @ $i")
                }
            }
        }

        /** 关系符后的字面字母 n（任意层/任意偏移，spec §2.2）：n 后紧跟标识符字符时视为节点名前缀（如 `>nameView`） */
        private fun literalN(): Boolean {
            skipSpaces()
            if (peek() != 'n') return false
            val next = s.getOrNull(i + 1)
            if (next != null && (next.isLetterOrDigit() || next == '_')) return false
            i++
            return true
        }

        /** 关系符后可选数字或 (a,b) 多值；无数字返回 null（Ancestor/Descendant=null 为任意层；Child/Prev/Next 由调用方默认 1） */
        private fun <T> relAfter(build: (List<Int>?) -> T): T {
            skipSpaces()
            if (peek() == '(') {
                i++
                val nums = mutableListOf<Int>()
                while (true) {
                    val start = i
                    while (peek()?.isDigit() == true) i++
                    nums += positiveRelNum(s.substring(start, i))
                    if (peek() == ',') { i++; continue }
                    expect(')'); break
                }
                return build(nums)
            }
            val start = i
            while (peek()?.isDigit() == true) i++
            val raw = s.substring(start, i)
            return build(if (raw.isEmpty()) null else listOf(positiveRelNum(raw)))
        }

        /** 关系层数：非空数字串 → Int；空串/非数字/溢出抛格式异常，且必须为正整数（spec §2.2） */
        private fun positiveRelNum(raw: String): Int {
            val n = raw.toIntOrNull() ?: throw SelectorSyntaxException("关系数字格式错误 @ $i")
            if (n < 1) throw SelectorSyntaxException("关系数字必须为正整数: $n")
            return n
        }
    }
}
```

注：裸 `<`/`+`/`-`（无数字）默认按 1 层/1 位处理（spec §2.2），`>`/`<<` 无数字为任意层。

- [ ] **Step 5: 运行测试通过**

```bash
cd D:/dev/app/android && ./gradlew.bat :engine:test
```

Expected: SelectorParserTest 全部通过。

- [ ] **Step 6: Commit**

```bash
cd D:/dev/app
git add android/engine
git commit -m "feat(engine): GKD 子集选择器解析器与 AST"
```

---

### Task 5: 属性求值器

**Files:**
- Create: `android/engine/src/main/kotlin/cn/hys159x/grid/engine/match/PropEvaluator.kt`
- Test: `android/engine/src/test/kotlin/cn/hys159x/grid/engine/match/PropEvaluatorTest.kt`

- [ ] **Step 1: 写失败的测试**

`android/engine/src/test/kotlin/cn/hys159x/grid/engine/match/PropEvaluatorTest.kt`：

```kotlin
package cn.hys159x.grid.engine.match

import cn.hys159x.grid.engine.node.fakeRoot
import cn.hys159x.grid.engine.selector.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PropEvaluatorTest {
    private val node = fakeRoot(vid = "ad", text = "跳过广告 3", bounds = cn.hys159x.grid.engine.node.Bounds(0, 0, 200, 100))

    /** 解析 prop 块并解包首个单条件（简单选择器均为 Cond） */
    private fun condOf(prop: String): PropCond =
        (SelectorParser.parse(prop).units[0].node.props[0] as PropExpr.Cond).cond

    private fun eval(prop: String): Boolean {
        val sel = SelectorParser.parse(prop)
        return PropEvaluator.eval(node, sel.units[0].node.props[0])
    }

    @Test fun eq() = assertTrue(eval("""[vid="ad"]"""))
    @Test fun eqMiss() = assertFalse(eval("""[vid="close"]"""))
    @Test fun contains() = assertTrue(eval("""[text*="广告"]"""))
    @Test fun starts() = assertTrue(eval("""[text^="跳过"]"""))
    @Test fun endsMiss() = assertFalse(eval("""[text$="广告"]"""))
    @Test fun regex() = assertTrue(eval("""[text~="跳过广告 \d"]"""))
    @Test fun notContains() = assertTrue(eval("""[text!*="关闭"]"""))
    @Test fun intCmp() { assertTrue(eval("""[width>=200]""")); assertFalse(eval("""[height>100]""")) }
    @Test fun lengthProp() = assertTrue(eval("""[text.length<10]"""))
    @Test fun boolProp() = assertTrue(eval("""[visibleToUser=true]"""))
    @Test fun nullTextWithNonEqIsFalse() {
        val n2 = fakeRoot(vid = "x") // text=null
        assertFalse(PropEvaluator.eval(n2, condOf("""[text*="任意"]""")))
    }
    @Test fun nullTextNeqIsTrue() {
        val n2 = fakeRoot(vid = "x")
        assertTrue(PropEvaluator.eval(n2, condOf("""[text!="关闭"]""")))
    }
    @Test fun negOpsOnNullTextAreFalse() {
        val n2 = fakeRoot(vid = "x")
        assertFalse(PropEvaluator.eval(n2, condOf("""[text!*="任意"]""")))
        assertFalse(PropEvaluator.eval(n2, condOf("""[text!~="\d"]""")))
    }
    @Test fun negStartsWithOnNonNull() {
        assertTrue(eval("""[text!^="关闭"]"""))
    }
    @Test fun descAndLengthOnNull() {
        val n3 = fakeRoot(desc = "ad")
        fun evalOn(prop: String) = PropEvaluator.eval(n3, condOf(prop))
        assertTrue(evalOn("""[desc="ad"]"""))
        assertTrue(evalOn("""[desc.length>0]"""))
        assertFalse(evalOn("""[desc.length<1]"""))
    }
    @Test fun lengthOnNullTextIsFalse() {
        val n2 = fakeRoot(vid = "x") // text=null
        assertFalse(PropEvaluator.eval(n2, condOf("""[text.length<10]""")))
    }
    @Test fun invalidRegexIsFalseNotCrash() {
        assertFalse(PropEvaluator.eval(node, condOf("""[text~="["]"""))) // 非法正则 "["
    }

    // ---- v1.3 扩展：null 字面量 / 布尔表达式求值 ----

    @Test fun nullLiteralEqMatchesNullId() {
        val nNull = fakeRoot(vid = "x")                       // id=null
        val nSet = fakeRoot(id = "com.x:id/ad")               // id 有值
        assertTrue(PropEvaluator.eval(nNull, condOf("""[id=null]""")))
        assertFalse(PropEvaluator.eval(nSet, condOf("""[id=null]""")))
    }

    @Test fun nullLiteralNeqMatchesNonNull() {
        val nNull = fakeRoot(vid = "x")
        val nSet = fakeRoot(text = "跳过")
        assertTrue(PropEvaluator.eval(nSet, condOf("""[text!=null]""")))
        assertFalse(PropEvaluator.eval(nNull, condOf("""[text!=null]""")))
    }

    @Test fun nullLiteralWithOtherOpsIsFalse() {
        val nNull = fakeRoot(vid = "x") // text=null
        assertFalse(PropEvaluator.eval(nNull, condOf("""[text*=null]""")))
        assertFalse(PropEvaluator.eval(node, condOf("""[text*=null]""")))  // 非 null 属性同样恒 false
    }

    @Test fun nullLiteralOnBoolAndIntProps() {
        // 布尔/整型属性永不为 null：=null 恒假，!=null 恒真
        assertFalse(PropEvaluator.eval(node, condOf("""[clickable=null]""")))
        assertTrue(PropEvaluator.eval(node, condOf("""[width!=null]""")))
    }

    @Test fun orExprEval() {
        val n = fakeRoot(vid = "skip", text = "跳过 3")
        fun ev(s: String) = PropEvaluator.eval(n, SelectorParser.parse(s).units[0].node.props[0])
        assertTrue(ev("""[vid="close"||vid="skip"]"""))
        assertFalse(ev("""[vid="close"||vid="other"]"""))
    }

    @Test fun andExprEval() {
        val n = fakeRoot(vid = "skip", text = "跳过 3")
        fun ev(s: String) = PropEvaluator.eval(n, SelectorParser.parse(s).units[0].node.props[0])
        assertTrue(ev("""[vid="skip"&&text*="跳过"]"""))
        assertFalse(ev("""[vid="skip"&&text*="关闭"]"""))
    }

    @Test fun nestedExprEvalWithPrecedence() {
        // 官方开屏兜底形态：(text.length<10&&text*="跳过") || id$="btn"
        val short = fakeRoot(text = "跳过 3")
        val long = fakeRoot(text = "跳过倒计时还有 888888 秒")   // length>=10
        val btn = fakeRoot(id = "com.x:id/tt_splash_skip_btn")
        fun ev(n: cn.hys159x.grid.engine.node.TreeNode, s: String) =
            PropEvaluator.eval(n, SelectorParser.parse(s).units[0].node.props[0])
        val s = """[(text.length<10&&text*="跳过") || id$="tt_splash_skip_btn"]"""
        assertTrue(ev(short, s))
        assertFalse(ev(long, s))
        assertTrue(ev(btn, s))
    }

    @Test fun nodeMatchesAndsMultipleBlocks() {
        // 多个 [...] 并列仍为 AND：一块为表达式、一块为单条件
        val n = fakeRoot(vid = "skip")   // FakeNode.visibleToUser 默认 true
        val sel = SelectorParser.parse("""[vid*="ski"||vid="x"][visibleToUser=true]""")
        assertTrue(PropEvaluator.nodeMatches(n, sel.units[0].node))
        val sel2 = SelectorParser.parse("""[vid*="nope"||vid="x"][visibleToUser=true]""")
        assertFalse(PropEvaluator.nodeMatches(n, sel2.units[0].node))
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
cd D:/dev/app/android && ./gradlew.bat :engine:test
```

Expected: `Unresolved reference: PropEvaluator`。

- [ ] **Step 3: 实现 PropEvaluator**

`android/engine/src/main/kotlin/cn/hys159x/grid/engine/match/PropEvaluator.kt`：

```kotlin
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
```

- [ ] **Step 4: 运行测试通过**

```bash
cd D:/dev/app/android && ./gradlew.bat :engine:test
```

Expected: PropEvaluatorTest 全部通过。

- [ ] **Step 5: Commit**

```bash
cd D:/dev/app
git add android/engine
git commit -m "feat(engine): 属性求值器（null 语义与 9 类操作符）"
```

---

### Task 6: 链匹配器

**Files:**
- Create: `android/engine/src/main/kotlin/cn/hys159x/grid/engine/match/ChainMatcher.kt`
- Test: `android/engine/src/test/kotlin/cn/hys159x/grid/engine/match/ChainMatcherTest.kt`

- [ ] **Step 1: 写失败的测试**

`android/engine/src/test/kotlin/cn/hys159x/grid/engine/match/ChainMatcherTest.kt`：

```kotlin
package cn.hys159x.grid.engine.match

import cn.hys159x.grid.engine.node.Bounds
import cn.hys159x.grid.engine.node.FakeNode
import cn.hys159x.grid.engine.node.TreeNode
import cn.hys159x.grid.engine.node.child
import cn.hys159x.grid.engine.node.fakeRoot
import cn.hys159x.grid.engine.selector.SelectorParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ChainMatcherTest {

    @Test
    fun `single selector returns matching node`() {
        val root = fakeRoot(vid = "root") {
            child(vid = "content") { child(vid = "skip", text = "跳过") }
        }
        val hit = ChainMatcher.match(root, SelectorParser.parse("""[vid="skip"]"""))
        assertEquals("skip", hit?.vid)
    }

    @Test
    fun `bare descendant excludes self`() {
        val root = fakeRoot(vid = "root") { child(vid = "x") }
        // x 不是自身的子孙：镜像两种写法都必须不命中
        assertNull(ChainMatcher.match(root, SelectorParser.parse("""[vid="x"] << @[vid="x"]""")))
        assertNull(ChainMatcher.match(root, SelectorParser.parse("""@[vid="x"] << [vid="x"]""")))
    }

    @Test
    fun `descendant with exact steps`() {
        val root = fakeRoot(vid = "root") {
            child(vid = "ad") {
                child(vid = "layer1") { child(vid = "skip") }
            }
        }
        // skip 是 ad 的 2 层子孙；@ 指向目标 skip
        assertEquals("skip", ChainMatcher.match(root, SelectorParser.parse("""@[vid="skip"] <<2 [vid="ad"]"""))?.vid)
        assertNull(ChainMatcher.match(root, SelectorParser.parse("""@[vid="skip"] <<1 [vid="ad"]""")))
    }

    @Test
    fun `ancestor and child relations`() {
        val root = fakeRoot(vid = "root") {
            child(vid = "ad") {
                child(vid = "btn", text = "×", clickable = true)
            }
        }
        // btn 是 ad 的 1 层子；@ 指向目标 btn
        assertEquals("btn", ChainMatcher.match(root, SelectorParser.parse("""@[vid="btn"] <1 [vid="ad"]"""))?.vid)
        // ad 是 btn 的 1 层祖先，@ 指向目标 ad
        assertEquals("ad", ChainMatcher.match(root, SelectorParser.parse("""@[vid="ad"] >1 [vid="btn"]"""))?.vid)
    }

    @Test
    fun `sibling relations`() {
        val root = fakeRoot(vid = "root") {
            child(vid = "left")
            child(vid = "right")
            child(vid = "far")
        }
        // left 是 right 的前置兄弟 1 位，@ 指向 left
        assertEquals("left", ChainMatcher.match(root, SelectorParser.parse("""@[vid="left"] + [vid="right"]"""))?.vid)
        // far 是 right 的后置兄弟 1 位
        assertEquals("far", ChainMatcher.match(root, SelectorParser.parse("""@[vid="far"] - [vid="right"]"""))?.vid)
        // left 与 far 差 2 位：+2 可命中，+3 不命中
        assertEquals("left", ChainMatcher.match(root, SelectorParser.parse("""@[vid="left"] +2 [vid="far"]"""))?.vid)
        assertNull(ChainMatcher.match(root, SelectorParser.parse("""@[vid="left"] +3 [vid="far"]""")))
    }

    @Test
    fun `three unit chain`() {
        val root = fakeRoot(vid = "root") {
            child(vid = "dialog") {
                child(vid = "body") {
                    child(vid = "cancel", text = "取消")
                }
            }
        }
        // cancel 是 body 的直接子，body 是 dialog 的直接子；目标 cancel
        assertEquals("cancel", ChainMatcher.match(root,
            SelectorParser.parse("""@[vid="cancel"] <1 [vid="body"] <1 [vid="dialog"]"""))?.vid)
    }

    @Test
    fun `target in middle with at marker`() {
        val root = fakeRoot(vid = "root") {
            child(vid = "a") { child(vid = "mid", clickable = true) }
        }
        // a 是 mid 的祖先；mid 是 root 的 2 层子；无 @ 目标=最后=root，@ 在 mid 则目标=mid
        assertEquals("root", ChainMatcher.match(root, SelectorParser.parse("""[vid="a"] > [vid="mid"] <2 [vid="root"]"""))?.vid)
        assertEquals("mid", ChainMatcher.match(root, SelectorParser.parse("""[vid="a"] > @[vid="mid"] <2 [vid="root"]"""))?.vid)
    }

    /** 包装式节点：同一底层节点的不同包装实例 equals（模拟 A11yNode），锁定相等契约 */
    class Wrap(private val inner: FakeNode) : TreeNode {
        override val id: String? get() = inner.id
        override val vid: String? get() = inner.vid
        override val name: String? get() = inner.name
        override val text: String? get() = inner.text
        override val desc: String? get() = inner.desc
        override val clickable: Boolean get() = inner.clickable
        override val longClickable: Boolean get() = inner.longClickable
        override val focusable: Boolean get() = inner.focusable
        override val checkable: Boolean get() = inner.checkable
        override val checked: Boolean get() = inner.checked
        override val editable: Boolean get() = inner.editable
        override val visibleToUser: Boolean get() = inner.visibleToUser
        override val bounds: Bounds get() = inner.bounds
        override val childCount: Int get() = inner.childCount
        override val index: Int get() = inner.index
        override val depth: Int get() = inner.depth
        override val parent: TreeNode? get() = (inner.parent as? FakeNode)?.let { Wrap(it) }
        override fun childAt(i: Int): TreeNode? = (inner.childAt(i) as? FakeNode)?.let { Wrap(it) }
        override fun equals(other: Any?) = other is Wrap && other.inner === inner
        override fun hashCode() = inner.hashCode()
    }

    @Test
    fun `wrapped nodes exclude self on bare descendant`() {
        val tree = fakeRoot(vid = "root") { child(vid = "x") }
        val wrappedRoot = Wrap(tree)
        val wrappedX = wrappedRoot.childAt(0)!!   // 新包装实例
        // x 不是自身子孙：镜像两种写法都必须不命中（验证 != 而非 !== 生效）
        val s1 = SelectorParser.parse("""[vid="x"] << @[vid="x"]""")
        assertNull(ChainMatcher.match(wrappedRoot, s1))
        val s2 = SelectorParser.parse("""@[vid="x"] << [vid="x"]""")
        assertNull(ChainMatcher.match(wrappedRoot, s2))
    }

    @Test
    fun `backtracking picks node satisfying relation`() {
        val root = fakeRoot(vid = "root") {
            child(vid = "list") { child(vid = "item") }      // 干扰项：不在 target 容器内
            child(vid = "target") { child(vid = "item") }    // 正确项
        }
        // 两个 item 都匹配目标单元，只有 target 内的那个满足 <1 关系
        assertEquals("item", ChainMatcher.match(root,
            SelectorParser.parse("""@[vid="item"] <1 [vid="target"]"""))?.vid)
        // 干扰项不满足：加 vid 区分验证选中的确实是 target 的子
        val r = ChainMatcher.match(root, SelectorParser.parse("""@[vid="item"] <1 [vid="target"]"""))
        assertEquals("target", (r?.parent as? FakeNode)?.vid)
    }

    @Test
    fun `multi value steps match either depth`() {
        val root = fakeRoot(vid = "root") {
            child(vid = "a") { child(vid = "b") { child(vid = "c") { child(vid = "d") } } }
        }
        // a 是 d 的 2 或 3 层祖先（实际 3）：任一命中即可
        assertEquals("a", ChainMatcher.match(root,
            SelectorParser.parse("""@[vid="a"] >(2,3) [vid="d"]"""))?.vid)
        // 只有 1、2 层（不匹配 3）：命中 b
        assertEquals("b", ChainMatcher.match(root,
            SelectorParser.parse("""@[vid="b"] >(1,2) [vid="d"]"""))?.vid)
    }

    @Test
    fun `prev sibling underflow returns null`() {
        val root = fakeRoot(vid = "root") {
            child(vid = "first")
            child(vid = "second")
        }
        // first 已是最左兄弟：不存在它的前置兄弟
        assertNull(ChainMatcher.match(root,
            SelectorParser.parse("""@[vid="ghost"] + [vid="first"]""")))
    }

    // ---- v1.3 扩展：字面 n 任意层/任意偏移 / * 通配单元 ----

    @Test
    fun `literal n child matches any depth`() {
        val root = fakeRoot(vid = "root") {
            child(vid = "dialog") {
                child(vid = "layer") { child(vid = "know", text = "知道了") }
            }
        }
        // know 是 dialog 的 2 层子孙：普通 <1 不命中，<n（任意层）命中
        assertEquals("know", ChainMatcher.match(root,
            SelectorParser.parse("""@[vid="know"] <n [vid="dialog"]"""))?.vid)
        assertNull(ChainMatcher.match(root,
            SelectorParser.parse("""@[vid="know"] <1 [vid="dialog"]""")))
        // <<n 与 <n 同为任意层子孙
        assertEquals("know", ChainMatcher.match(root,
            SelectorParser.parse("""@[vid="know"] <<n [vid="dialog"]"""))?.vid)
    }

    @Test
    fun `literal n ancestor matches any depth`() {
        val root = fakeRoot(vid = "root") {
            child(vid = "mid") { child(vid = "leaf") }
        }
        // leaf 的任意层祖先中名为 root 的节点（隔 2 层）
        assertEquals("root", ChainMatcher.match(root,
            SelectorParser.parse("""@[vid="root"] >n [vid="leaf"]"""))?.vid)
    }

    @Test
    fun `literal n sibling matches any offset`() {
        val root = fakeRoot(vid = "root") {
            child(vid = "a")
            child(vid = "b")
            child(vid = "c")
            child(vid = "know", text = "知道了")
        }
        // a 与 know 差 3 位：+1/+2 不命中，+n（任意偏移）命中
        assertEquals("a", ChainMatcher.match(root,
            SelectorParser.parse("""@[vid="a"] +n [vid="know"]"""))?.vid)
        assertNull(ChainMatcher.match(root,
            SelectorParser.parse("""@[vid="a"] +2 [vid="know"]""")))
        // 反向：-n 任意偏移后置兄弟
        assertEquals("know", ChainMatcher.match(root,
            SelectorParser.parse("""@[vid="know"] -n [vid="a"]"""))?.vid)
    }

    @Test
    fun `literal n sibling requires same parent`() {
        val root = fakeRoot(vid = "root") {
            child(vid = "outer") { child(vid = "deep") }
            child(vid = "sibling")
        }
        // deep 与 sibling 不同父：即使顺序上"在前"也不构成兄弟
        assertNull(ChainMatcher.match(root,
            SelectorParser.parse("""@[vid="deep"] +n [vid="sibling"]""")))
    }

    @Test
    fun `wildcard units bridge chain`() {
        // 官方形态：@View[clickable=true] <3 * <2 * < FrameLayout[...]
        // 链自右向左：FrameLayout(1) → *其子(2) → *再下 2 层(4) → @View 再下 3 层(7)
        val root = fakeRoot(vid = "root") {
            child(name = "FrameLayout", id = "com.x:id/ad") {   // depth 1
                child(vid = "l2") {                              // depth 2（第二个 *）
                    child(vid = "l3") {
                        child(vid = "l4") {                      // depth 4（第一个 *）
                            child(vid = "l5") {
                                child(vid = "l6") { child(name = "View", vid = "btn", clickable = true) }  // depth 7
                            }
                        }
                    }
                }
            }
        }
        assertEquals("btn", ChainMatcher.match(root, SelectorParser.parse(
            """@View[clickable=true] <3 * <2 * < FrameLayout[id="com.x:id/ad"]"""))?.vid)
        // 累计深度固定 2+2+1=5 ≠ 实际 6：不命中（* 只占一层单元，不放大层数）
        assertNull(ChainMatcher.match(root, SelectorParser.parse(
            """@View[clickable=true] <2 * <2 * < FrameLayout[id="com.x:id/ad"]""")))
    }

    @Test
    fun `wildcard with literal n matches flexible depth`() {
        val root = fakeRoot(vid = "root") {
            child(vid = "ad") { child(vid = "x") { child(name = "View", vid = "btn", clickable = true) } }
        }
        // <n * ：任意层 + 通配单元，btn 与 ad 间隔任意层均可命中（@View 要求 name="View"）
        assertEquals("btn", ChainMatcher.match(root, SelectorParser.parse(
            """@View[clickable=true] <n * < [vid="ad"]"""))?.vid)
    }

    @Test
    fun `space relation with bare name matches nested node`() {
        // 官方实例形态：`... FrameLayout TextView[text=...]`（空格=任意祖先，右侧裸 name 单元）
        val root = fakeRoot(vid = "root") {
            child(name = "FrameLayout") {
                child(name = "TextView", vid = "tip", text = "向上滑动或点击查看")
            }
        }
        assertEquals("tip", ChainMatcher.match(root,
            SelectorParser.parse("""FrameLayout TextView[text="向上滑动或点击查看"]"""))?.vid)
        assertNull(ChainMatcher.match(root,
            SelectorParser.parse("""FrameLayout TextView[text="其他"]""")))
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
cd D:/dev/app/android && ./gradlew.bat :engine:test
```

Expected: `Unresolved reference: ChainMatcher`。

- [ ] **Step 3: 实现 ChainMatcher**

算法（与 selector-spec.md §6 一致）：枚举目标单元匹配的候选节点（深度优先序）→ 以候选为起点回溯为所有单元分配节点，逐对验证相邻关系，冲突回溯；首个全链通过者即命中。

`android/engine/src/main/kotlin/cn/hys159x/grid/engine/match/ChainMatcher.kt`：

```kotlin
package cn.hys159x.grid.engine.match

import cn.hys159x.grid.engine.node.TreeNode
import cn.hys159x.grid.engine.selector.*

object ChainMatcher {

    fun match(root: TreeNode, sel: ParsedSelector): TreeNode? {
        val t = sel.targetIndex
        val target = sel.units[t].node
        // 非 inline 的 forEachNode 不允许非局部 return，用 hit 守卫保证返回 DFS 序第一个命中
        var hit: TreeNode? = null
        forEachNode(root) { candidate ->
            if (hit == null && PropEvaluator.nodeMatches(candidate, target) && solve(sel, t, candidate)) {
                hit = candidate
            }
        }
        return hit
    }

    /** 以 assigned[targetIdx]=seed 为起点，向两侧扩散回溯分配，全部单元满足相邻关系即成功 */
    private fun solve(sel: ParsedSelector, targetIdx: Int, seed: TreeNode): Boolean {
        val assigned = arrayOfNulls<TreeNode>(sel.units.size)
        assigned[targetIdx] = seed
        fun pairOk(i: Int, j: Int): Boolean { // i<j 相邻；关系挂在 units[j].relation
            val a = assigned[i] ?: return true
            val b = assigned[j] ?: return true
            return relationOk(a, b, sel.units[j].relation!!)
        }
        fun dfs(): Boolean {
            val idx = (0 until sel.units.size)
                .filter { assigned[it] == null && ((it > 0 && assigned[it - 1] != null) || (it < sel.units.size - 1 && assigned[it + 1] != null)) }
                .minOrNull() ?: return true
            val candidates = neighborsOf(sel, assigned, idx)
            for (c in candidates) {
                assigned[idx] = c
                val leftOk = idx == 0 || pairOk(idx - 1, idx)
                val rightOk = idx == sel.units.size - 1 || pairOk(idx, idx + 1)
                if (leftOk && rightOk && dfs()) return true
                assigned[idx] = null
            }
            return false
        }
        return dfs()
    }

    /** 为未赋值单元 idx 生成候选：取已赋值邻居，按其关系反推 */
    private fun neighborsOf(sel: ParsedSelector, assigned: Array<TreeNode?>, idx: Int): List<TreeNode> {
        val node = sel.units[idx].node
        val out = LinkedHashSet<TreeNode>()
        val left = if (idx > 0) assigned[idx - 1] else null
        val right = if (idx < sel.units.size - 1) assigned[idx + 1] else null
        // 已知左邻居 left 与关系 units[idx].relation（描述 left 相对 idx）：求 idx（关系右侧）→ RightKnown
        if (left != null) candidatesForRightKnown(left, sel.units[idx].relation!!, node, out)
        // 已知右邻居 right 与关系 units[idx+1].relation（描述 idx 相对 right）：求 idx（关系左侧）→ LeftKnown
        if (right != null) candidatesForLeftKnown(right, sel.units[idx + 1].relation!!, node, out)
        return out.filter { PropEvaluator.nodeMatches(it, node) }
    }

    /** 已知右侧节点 b 与关系 rel（描述"左侧节点相对 b"），枚举左侧候选 */
    private fun candidatesForLeftKnown(b: TreeNode, rel: Relation, node: NodeSelector, out: MutableSet<TreeNode>) {
        when (rel) {
            is Relation.Ancestor -> {
                if (rel.steps == null) ancestors(b) { out += it }
                else rel.steps.forEach { s -> ancestorAt(b, s)?.let { out += it } }
            }
            is Relation.Child -> rel.steps.forEach { s -> descendantsAt(b, s) { out += it } }
            is Relation.Descendant ->
                if (rel.steps == null) forEachNode(b) { out += it } else rel.steps.forEach { s -> descendantsAt(b, s) { out += it } }
            is Relation.PrevSibling ->
                if (rel.offsets == null) precedingSiblings(b) { out += it }   // 字面 n：任意偏移前置兄弟
                else rel.offsets.forEach { o -> sibling(b, -o)?.let { out += it } }
            is Relation.NextSibling ->
                if (rel.offsets == null) followingSiblings(b) { out += it }
                else rel.offsets.forEach { o -> sibling(b, o)?.let { out += it } }
        }
    }

    /** 已知左侧节点 a 与关系 rel（描述"a 相对右侧节点"），枚举右侧候选 */
    private fun candidatesForRightKnown(a: TreeNode, rel: Relation, node: NodeSelector, out: MutableSet<TreeNode>) {
        when (rel) {
            is Relation.Ancestor -> {
                // 右侧 b 满足：a 是 b 的（steps 层）祖先 → b 在 a 子树中，depth 差 ∈ steps
                if (rel.steps == null) forEachNode(a) { out += it }
                else rel.steps.forEach { s -> descendantsAt(a, s) { out += it } }
            }
            is Relation.Child ->
                // a 是 b 的 s 层子 → b 是 a 的 s 层祖先
                rel.steps.forEach { s -> ancestorAt(a, s)?.let { out += it } }
            is Relation.Descendant ->
                if (rel.steps == null) ancestors(a) { out += it }
                else rel.steps.forEach { s -> ancestorAt(a, s)?.let { out += it } }
            is Relation.PrevSibling ->
                if (rel.offsets == null) followingSiblings(a) { out += it }   // b 是 a 的任意后置兄弟
                else rel.offsets.forEach { o -> sibling(a, o)?.let { out += it } } // b = a.index + o
            is Relation.NextSibling ->
                if (rel.offsets == null) precedingSiblings(a) { out += it }   // b 是 a 的任意前置兄弟
                else rel.offsets.forEach { o -> sibling(a, -o)?.let { out += it } } // b = a.index - o
        }
    }

    /** 验证 a（左侧单元）相对 b（右侧单元）满足 rel */
    private fun relationOk(a: TreeNode, b: TreeNode, rel: Relation): Boolean = when (rel) {
        is Relation.Ancestor ->
            if (rel.steps == null) ancestors(b).any { it == a }
            else rel.steps.any { s -> ancestorAt(b, s) == a }
        // a 是 b 的 s 层子/子孙 ⟺ b 是 a 的 s 层祖先：O(depth) 上行等价替换
        is Relation.Child -> rel.steps.any { s -> ancestorAt(a, s) == b }
        is Relation.Descendant ->
            // 子孙不含自身（spec §2.2：`<<n` 强制 n>=1），a==b 不构成子孙关系
            if (rel.steps == null) a != b && ancestors(a).any { it == b }
            else rel.steps.any { s -> ancestorAt(a, s) == b }
        is Relation.PrevSibling ->
            if (rel.offsets == null) sameParent(a, b) && a.index < b.index
            else rel.offsets.any { o -> sibling(b, -o) == a }
        is Relation.NextSibling ->
            if (rel.offsets == null) sameParent(a, b) && a.index > b.index
            else rel.offsets.any { o -> sibling(b, o) == a }
    }

    // ---- 树遍历工具 ----
    fun forEachNode(root: TreeNode, f: (TreeNode) -> Unit) {
        f(root)
        for (i in 0 until root.childCount) root.childAt(i)?.let { forEachNode(it, f) }
    }

    fun ancestors(n: TreeNode): Sequence<TreeNode> = generateSequence(n.parent) { it.parent }

    inline fun ancestors(n: TreeNode, f: (TreeNode) -> Unit) { ancestors(n).forEach(f) }

    fun ancestorAt(n: TreeNode, steps: Int): TreeNode? {
        var c: TreeNode = n
        repeat(steps) { c = c.parent ?: return null }
        return c
    }

    fun descendantsAt(n: TreeNode, steps: Int, f: (TreeNode) -> Unit) {
        if (steps == 0) { f(n); return }
        if (steps < 0) return
        for (i in 0 until n.childCount) n.childAt(i)?.let { descendantsAt(it, steps - 1, f) }
    }

    /** n 的第 offset 位兄弟（offset 可负）；越界返回 null */
    fun sibling(n: TreeNode, offset: Int): TreeNode? {
        val p = n.parent ?: return null
        val t = n.index + offset
        if (t < 0 || t >= p.childCount) return null
        return p.childAt(t)
    }

    /** a 与 b 是否同父兄弟（包装节点按 equals 相等契约比较 parent） */
    private fun sameParent(a: TreeNode, b: TreeNode): Boolean {
        val p = b.parent ?: return false
        return a.parent == p
    }

    /** n 之前的全部兄弟（字面 n 任意偏移候选/验证用） */
    private fun precedingSiblings(n: TreeNode, f: (TreeNode) -> Unit) {
        val p = n.parent ?: return
        for (j in 0 until n.index) p.childAt(j)?.let(f)
    }

    /** n 之后的全部兄弟 */
    private fun followingSiblings(n: TreeNode, f: (TreeNode) -> Unit) {
        val p = n.parent ?: return
        for (j in n.index + 1 until p.childCount) p.childAt(j)?.let(f)
    }
}
```

- [ ] **Step 4: 运行测试通过**

```bash
cd D:/dev/app/android && ./gradlew.bat :engine:test
```

Expected: ChainMatcherTest 全部通过。

- [ ] **Step 5: Commit**

```bash
cd D:/dev/app
git add android/engine
git commit -m "feat(engine): 链匹配器（回溯式多单元关系验证）"
```
