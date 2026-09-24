package cn.hys159x.grid.app.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo as SysNode
import cn.hys159x.grid.engine.node.Bounds
import cn.hys159x.grid.engine.node.TreeNode

/** AccessibilityNodeInfo → TreeNode 适配（引擎零 Android 依赖的关键）
 *  id=viewIdResourceName 全名；vid=去包名段；name=className 简名 */
class A11yNode(private val n: SysNode, override val depth: Int) : TreeNode {

    override val id: String? = n.viewIdResourceName
    override val vid: String? = n.viewIdResourceName?.substringAfterLast("id/")
    override val name: String? = n.className?.toString()?.substringAfterLast('.')
    override val text: String? = n.text?.toString()
    override val desc: String? = n.contentDescription?.toString()
    override val clickable: Boolean = n.isClickable
    override val longClickable: Boolean = n.isLongClickable
    override val focusable: Boolean = n.isFocusable
    override val checkable: Boolean = n.isCheckable
    override val checked: Boolean = n.isChecked
    override val editable: Boolean = n.isEditable
    override val visibleToUser: Boolean = n.isVisibleToUser
    override val childCount: Int get() = n.childCount

    private val cachedIndex: Int by lazy {
        val p = n.parent ?: return@lazy 0
        for (i in 0 until p.childCount) if (p.getChild(i) == n) return@lazy i
        0
    }
    override val index: Int get() = cachedIndex

    override val bounds: Bounds by lazy {
        val r = Rect(); n.getBoundsInScreen(r); Bounds(r.left, r.top, r.right, r.bottom)
    }

    override val parent: TreeNode? by lazy {
        n.parent?.let { A11yNode(it, depth - 1) }
    }

    override fun childAt(i: Int): TreeNode? = n.getChild(i)?.let { A11yNode(it, depth + 1) }

    fun raw(): SysNode = n

    /** 相等性契约（见 TreeNode KDoc）：以底层 AccessibilityNodeInfo 为相等依据，包装实例可重复创建 */
    override fun equals(other: Any?): Boolean = other is A11yNode && other.n == n
    override fun hashCode(): Int = n.hashCode()

    companion object {
        fun wrap(root: SysNode): A11yNode = A11yNode(root, 0)
    }
}
