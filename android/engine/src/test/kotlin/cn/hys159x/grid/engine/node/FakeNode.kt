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
}

fun fakeRoot(
    id: String? = null, vid: String? = null, name: String? = null,
    text: String? = null, desc: String? = null,
    bounds: Bounds = Bounds(0, 0, 1080, 2400),
    block: FakeNode.() -> Unit = {},
): FakeNode {
    val root = FakeNode(id = id, vid = vid, name = name, text = text, desc = desc, bounds = bounds)
    root.apply(block)
    root.wire()
    return root
}

/** DSL：为当前节点添加子节点；返回子节点以便链式嵌套。仅限 fakeRoot 的 block 内使用（之后 wire 重建链接） */
fun FakeNode.child(
    id: String? = null, vid: String? = null, name: String? = null,
    text: String? = null, desc: String? = null,
    clickable: Boolean = false, longClickable: Boolean = false,
    focusable: Boolean = false, checkable: Boolean = false,
    checked: Boolean = false, editable: Boolean = false,
    visibleToUser: Boolean = true,
    bounds: Bounds = Bounds(0, 0, 100, 100),
    block: FakeNode.() -> Unit = {},
): FakeNode {
    val node = FakeNode(
        id = id, vid = vid, name = name, text = text, desc = desc,
        clickable = clickable, longClickable = longClickable,
        focusable = focusable, checkable = checkable, checked = checked,
        editable = editable, visibleToUser = visibleToUser, bounds = bounds,
    )
    node.apply(block)
    children += node
    return node
}

/** 递归设置 parent/index/depth（index = 在父节点 children 中的位置） */
private fun FakeNode.wire() {
    children.forEachIndexed { i, c ->
        c.parent = this
        c.index = i
        c.depth = depth + 1
        c.wire()
    }
}
