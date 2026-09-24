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
