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

    @Test
    fun `root invariants and empty block`() {
        val root = fakeRoot(vid = "root") { }
        assertNull(root.parent)
        assertEquals(0, root.depth)
        assertEquals(0, root.index)
        assertEquals(0, root.childCount)
        assertNull(root.childAt(0))
    }

    @Test
    fun `sibling indices via interface`() {
        val root = fakeRoot(vid = "root") {
            child(vid = "a")
            child(vid = "b")
            child(vid = "c")
        }
        assertEquals(3, root.childCount)
        assertEquals("a", root.childAt(0)?.vid)
        assertEquals("b", root.childAt(1)?.vid)
        assertEquals("c", root.childAt(2)?.vid)
        assertEquals(0, root.childAt(0)?.index)
        assertEquals(1, root.childAt(1)?.index)
        assertEquals(2, root.childAt(2)?.index)
        assertNull(root.childAt(3)) // 越界
        assertEquals(1, root.childAt(2)?.depth)
    }

    @Test
    fun `bounds derived values and off screen branches`() {
        val b = Bounds(10, 20, 110, 220)
        assertEquals(100, b.width)
        assertEquals(200, b.height)
        assertEquals(60, b.centerX)
        assertEquals(120, b.centerY)
        // 完全在左/上/右/下屏幕外 + 零尺寸
        assertTrue(Bounds(-50, 0, -10, 100).offScreen(screenW = 1080, screenH = 2400))
        assertTrue(Bounds(0, -50, 100, -10).offScreen(screenW = 1080, screenH = 2400))
        assertTrue(Bounds(1090, 0, 1150, 100).offScreen(screenW = 1080, screenH = 2400))
        assertTrue(Bounds(0, 2450, 100, 2500).offScreen(screenW = 1080, screenH = 2400))
        assertTrue(Bounds(50, 50, 50, 100).offScreen(screenW = 1080, screenH = 2400)) // width=0
        // 在屏内（贴边可见）
        assertFalse(Bounds(0, 0, 1080, 2400).offScreen(screenW = 1080, screenH = 2400))
        assertFalse(Bounds(0, 2399, 100, 2400).offScreen(screenW = 1080, screenH = 2400))
    }
}
