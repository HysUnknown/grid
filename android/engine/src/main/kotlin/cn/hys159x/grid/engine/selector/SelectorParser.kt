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
