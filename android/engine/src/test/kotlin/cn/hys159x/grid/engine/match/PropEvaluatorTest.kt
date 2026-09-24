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
