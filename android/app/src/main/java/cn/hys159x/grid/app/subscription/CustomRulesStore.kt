package cn.hys159x.grid.app.subscription

import android.content.Context
import cn.hys159x.grid.engine.subscription.RawApp
import cn.hys159x.grid.engine.subscription.RawGroup
import cn.hys159x.grid.engine.subscription.RawRule
import cn.hys159x.grid.engine.subscription.RawSubscription
import cn.hys159x.grid.engine.subscription.SubscriptionJson
import java.io.File

/** 用户自定义规则：filesDir/custom_rules.json 持久化，GKD 订阅格式子集，优先级最高 */
object CustomRulesStore {

    data class Entry(
        val packageName: String, val selector: String, val name: String, val time: Long,
        /** 动作：click（默认）或 back（按返回键关闭开屏页，适配反无障碍点击的 App） */
        val action: String = "click",
        /** 非空=Activity 级规则：进入该 Activity 即触发（自绘开屏页无节点时的返回键退路） */
        val activityId: String? = null,
    )

    private fun file(context: Context) = File(context.filesDir, "custom_rules.json")

    fun list(context: Context): List<Entry> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching { readEntries(f.readText()) }.getOrDefault(emptyList())
    }

    fun add(
        context: Context, packageName: String, selector: String, name: String,
        action: String = "click", activityId: String? = null,
    ) {
        val all = list(context).filterNot { it.selector == selector && it.packageName == packageName }
        write(context, all + Entry(packageName, selector, name, System.currentTimeMillis(), action, activityId))
    }

    fun remove(context: Context, packageName: String, selector: String) {
        write(context, list(context).filterNot { it.selector == selector && it.packageName == packageName })
    }

    /** 编译为 RawSubscription（供合并管线；开屏模板组配置） */
    fun toSubscription(context: Context): RawSubscription {
        val byApp = list(context).groupBy { it.packageName }
        return RawSubscription(
            id = -2,
            name = "自定义规则",
            version = 1,
            apps = byApp.map { (pkg, entries) ->
                RawApp(
                    id = pkg,
                    groups = listOf(
                        RawGroup(
                            // groupKey=-2 与订阅 id 同值：服务端据此识别"自定义规则"——
                            // 用户录制的按钮一律循环点击（自绘按钮无文字，关键词判定不了）
                            key = -2,
                            name = "自定义",
                            enable = true,
                            matchTime = 10000,
                            actionMaximum = 2,
                            resetMatch = "app",
                            order = -2,   // 优先于官方全局兜底组（order=-1），否则自定义动作被全局无效点击抢占
                    rules = entries.map {
                        RawRule(
                            matches = listOf(it.selector), action = it.action,
                            activityIds = it.activityId?.let { a -> listOf(a) } ?: emptyList(),
                        )
                    },
                        ),
                    ),
                )
            },
        )
    }

    // ---- 分享导入/导出（JSON 文本，微信等直接发消息/复制粘贴） ----

    /** 导出为可分享 JSON 文本：{"grid":1,"rules":[{p,s,n,a,act}...]} */
    fun exportJson(context: Context): String {
        val rules = org.json.JSONArray()
        list(context).forEach {
            rules.put(
                org.json.JSONObject()
                    .put("p", it.packageName)
                    .put("s", it.selector)
                    .put("n", it.name)
                    .put("a", it.action)
                    .apply { it.activityId?.let { a -> put("act", a) } },
            )
        }
        return org.json.JSONObject().put("grid", 1).put("rules", rules).toString()
    }

    /** 导入分享文本：按 包名+选择器 去重合并。返回新增条数（格式非法返回 -1） */
    fun importJson(context: Context, text: String): Int {
        val arr = runCatching { org.json.JSONObject(text.trim()).optJSONArray("rules") }.getOrNull()
            ?: return -1
        val out = list(context).toMutableList()
        val seen = out.map { it.packageName to it.selector }.toHashSet()
        var added = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pkg = o.optString("p"); val sel = o.optString("s")
            if (pkg.isBlank() || sel.isBlank()) continue
            if (!seen.add(pkg to sel)) continue
            out += Entry(
                pkg, sel, o.optString("n").ifBlank { sel }, System.currentTimeMillis(),
                o.optString("a").ifBlank { "click" }, o.optString("act").ifBlank { null },
            )
            added++
        }
        if (added > 0) write(context, out)
        return added
    }

    private fun write(context: Context, entries: List<Entry>) {
        val f = file(context)
        f.parentFile?.mkdirs()
        f.writeText(entries.joinToString("\n") { encode(it) })
    }

    // 行格式：time<TAB>pkg<TAB>selector<TAB>name<TAB>action<TAB>activityId（后两列可缺省）
    private fun encode(e: Entry) =
        "${e.time}\t${e.packageName}\t${e.selector}\t${e.name.replace("\t", " ")}\t${e.action}\t${e.activityId ?: ""}"

    private fun readEntries(text: String): List<Entry> = text.lines()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val p = line.split("\t")
            if (p.size < 3) return@mapNotNull null
            Entry(
                p[1], p[2], p.getOrElse(3) { p[2] }, p[0].toLongOrNull() ?: 0L,
                p.getOrElse(4) { "click" }, p.getOrNull(5)?.ifBlank { null },
            )
        }
}
