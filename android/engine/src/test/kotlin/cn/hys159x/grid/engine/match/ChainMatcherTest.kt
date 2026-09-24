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
