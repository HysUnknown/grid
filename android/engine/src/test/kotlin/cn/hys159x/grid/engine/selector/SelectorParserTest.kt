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
