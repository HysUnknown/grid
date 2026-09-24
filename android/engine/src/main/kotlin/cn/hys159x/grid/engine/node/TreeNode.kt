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
