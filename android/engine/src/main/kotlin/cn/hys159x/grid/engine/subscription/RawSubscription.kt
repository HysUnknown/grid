package cn.hys159x.grid.engine.subscription

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** GKD 订阅编译产物；未知字段忽略；忽略字段清单见 selector-spec.md §4 */
@Serializable
data class RawSubscription(
    val id: Int = 0,
    val name: String? = null,
    val version: Int = 0,
    val author: String? = null,
    val apps: List<RawApp> = emptyList(),
    @SerialName("globalGroups") val globalGroups: List<RawGlobalGroup> = emptyList(),
)

@Serializable
data class RawApp(val id: String, val name: String? = null, val groups: List<RawGroup> = emptyList())

/** GKD 组级 RawCommonProps：组内规则的字段默认值（规则级覆盖组级） */
@Serializable
data class RawCommonProps(
    val actionCd: Int? = null,
    val actionDelay: Int? = null,
    val actionMaximum: Int? = null,
    val resetMatch: String? = null,
    val matchTime: Int? = null,
    val matchRoot: Boolean? = null,
    val order: Int? = null,
)

/** 全局组 app 引用：enable=false 表示该 App 被组排除（gkd 源码 getGlobalGroupInnerDisabled）；
 *  其余字段（name/deprecatedKeys 等）信息性，忽略 */
@Serializable
data class RawGlobalAppRef(
    val id: String,
    val name: String? = null,
    val enable: Boolean = true,
)

/** GKD 字段允许 string | string[]（官方 dist 的 matches/preKeys 大量使用单值形态），统一读为列表 */
object StringOrStringListSerializer : JsonTransformingSerializer<List<String>>(
    ListSerializer(String.serializer()),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive) JsonArray(listOf(element)) else element
}

/** 同上，int | int[]（preKeys 单值形态，如 preKeys:0） */
object IntOrIntListSerializer : JsonTransformingSerializer<List<Int>>(
    ListSerializer(Int.serializer()),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive) JsonArray(listOf(element)) else element
}

/** 全局组 apps 项允许 string | {id,...}（字符串视为 enable=true 引用）；混合形态在数组元素层，需逐元素转换 */
object StringOrGlobalAppRefListSerializer : JsonTransformingSerializer<List<RawGlobalAppRef>>(
    ListSerializer(RawGlobalAppRef.serializer()),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) JsonArray(element.map { el ->
            if (el is JsonPrimitive) buildJsonObject { put("id", el) } else el
        }) else element
}

/** 组的 rules 允许四种形态（GKD RawGroup.rules）：RawRule[] | RawRule | string | string[]
 *  （官方 dist 中单串简写 rules:'[...]'、单对象 rules:{...}、字符串数组 rules:['...'] 均有使用），统一转 RawRule[] */
object RulesListSerializer : JsonTransformingSerializer<List<RawRule>>(
    ListSerializer(RawRule.serializer()),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when (element) {
        is JsonPrimitive -> buildJsonArray { add(selectorToRule(element)) }
        is JsonObject -> buildJsonArray { add(element) }
        is JsonArray -> JsonArray(element.map { if (it is JsonPrimitive) selectorToRule(it) else it })
        else -> element
    }

    private fun selectorToRule(p: JsonPrimitive): JsonObject =
        buildJsonObject { put("matches", buildJsonArray { add(p) }) }
}

@Serializable
data class RawGlobalGroup(
    val key: Int = 0,
    val name: String? = null,
    val enable: Boolean = true,
    /** per-App 排除/覆盖名单：enable=false 的 App 不享受本组规则；组本身对所有 App 生效 */
    @Serializable(with = StringOrGlobalAppRefListSerializer::class)
    val apps: List<RawGlobalAppRef> = emptyList(),
    @Serializable(with = RulesListSerializer::class)
    val rules: List<RawRule> = emptyList(),
    val actionCd: Int? = null,
    val actionDelay: Int? = null,
    val actionMaximum: Int? = null,
    val resetMatch: String? = null,
    val matchTime: Int? = null,
    val matchRoot: Boolean? = null,
    val order: Int? = null,
)

@Serializable
data class RawGroup(
    val key: Int = 0,
    val name: String? = null,
    val enable: Boolean = true,
    @Serializable(with = RulesListSerializer::class)
    val rules: List<RawRule> = emptyList(),
    val actionCd: Int? = null,
    val actionDelay: Int? = null,
    val actionMaximum: Int? = null,
    val resetMatch: String? = null,
    val matchTime: Int? = null,
    val matchRoot: Boolean? = null,
    val order: Int? = null,
)

@Serializable
data class RawRule(
    val key: Int? = null,
    val name: String? = null,
    val action: String? = null,
    @Serializable(with = StringOrStringListSerializer::class)
    val matches: List<String> = emptyList(),
    @Serializable(with = StringOrStringListSerializer::class)
    val anyMatches: List<String>? = null,
    @Serializable(with = StringOrStringListSerializer::class)
    val excludeMatches: List<String> = emptyList(),
    @Serializable(with = StringOrStringListSerializer::class)
    val excludeAllMatches: List<String>? = null,
    @Serializable(with = IntOrIntListSerializer::class)
    val preKeys: List<Int> = emptyList(),
    val actionCd: Int? = null,
    val actionDelay: Int? = null,
    val actionMaximum: Int? = null,
    val resetMatch: String? = null,
    val matchTime: Int? = null,
    @SerialName("matchRoot") val matchRoot: Boolean? = null,
    val order: Int? = null,
    @Serializable(with = StringOrStringListSerializer::class)
    val activityIds: List<String> = emptyList(),
    @Serializable(with = StringOrStringListSerializer::class)
    val excludeActivityIds: List<String> = emptyList(),
)

object SubscriptionJson {
    /** 官方经 npm 分发的 dist/gkd.json5 是 JSON5：裸键 + 单引号字符串。
     *  kotlinx lenient 支持裸键/裸值但**不支持单引号字符串**，故先经 normalize 重写引号。
     *  已验证官方 fixture 无注释/无尾逗号/无 hex 等 JSON5 其它特征，最小处理即可。 */
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true   // null→默认值，容错官方数据
    }

    fun <T> decode(deserializer: DeserializationStrategy<T>, text: String): T =
        json.decodeFromString(deserializer, normalize(text))

    /** 单引号字符串 → 双引号字符串；双引号字符串与裸键/数值/布尔原样透传（交给 isLenient）。
     *  转义处理：\' → '（JSON 无 \' 转义）；" → \"；合法 JSON 转义对保留；\ + 其它字符按字面反斜杠转义 */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> {                          // 双引号字符串：原样透传
                    val start = i
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\') i++
                        i++
                    }
                    require(i < text.length) { "JSON 字符串未闭合" }
                    i++
                    sb.append(text, start, i)
                }
                c == '\'' -> {                         // 单引号字符串：重写为双引号
                    sb.append('"')
                    i++
                    while (i < text.length && text[i] != '\'') {
                        if (text[i] == '\\' && i + 1 < text.length) {
                            when (val e = text[i + 1]) {
                                '\'' -> sb.append('\'')
                                '"', '\\', '/', 'b', 'f', 'n', 'r', 't', 'u' -> sb.append(text[i]).append(e)
                                else -> sb.append('\\').append('\\').append(e)  // 字面反斜杠（正则 \d 等）
                            }
                            i += 2
                        } else if (text[i] == '"') {
                            sb.append('\\').append('"'); i++
                        } else {
                            sb.append(text[i]); i++
                        }
                    }
                    require(i < text.length) { "JSON5 单引号字符串未闭合" }
                    sb.append('"'); i++
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }
}
