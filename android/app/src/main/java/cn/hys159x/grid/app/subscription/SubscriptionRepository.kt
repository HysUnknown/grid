package cn.hys159x.grid.app.subscription

import android.content.Context
import cn.hys159x.grid.app.service.ServiceState
import cn.hys159x.grid.engine.rule.CompiledRule
import cn.hys159x.grid.engine.subscription.CompiledRuleSet
import cn.hys159x.grid.engine.subscription.RawSubscription
import cn.hys159x.grid.engine.subscription.SubscriptionCompiler
import cn.hys159x.grid.engine.subscription.SubscriptionJson

/**
 * 规则库完全离线：官方订阅（定版 v186，已停止维护故直接内置）与本地补充规则
 * 均随 APK assets 分发——零网络请求（Manifest 无 INTERNET 权限），启动后台线程加载一次。
 */
class SubscriptionRepository private constructor(context: Context) {

    private val appContext = context.applicationContext

    fun start() {
        Thread {
            runCatching {
                val custom = SubscriptionJson.json.encodeToString(
                    RawSubscription.serializer(), CustomRulesStore.toSubscription(appContext),
                )
                // 外部导入的完整规则集（若有）：优先级 custom > imported
                val imported = if (SubscriptionShare.hasImported(appContext)) {
                    SubscriptionShare.importedFile(appContext).readText()
                } else null
                ServiceState._ruleSet.value = merged(
                    listOfNotNull(custom, imported).map { text ->
                        SubscriptionCompiler.compile(SubscriptionJson.decode(RawSubscription.serializer(), text))
                    },
                )
                android.util.Log.i(
                    "GridRepo",
                    "rules loaded: total=${ServiceState._ruleSet.value?.totalRules} " +
                        "apps=${(ServiceState._ruleSet.value?.rules?.keys?.size ?: 1) - 1}" +
                        if (imported != null) " (+imported)" else "",
                )
            }.onFailure {
                android.util.Log.e("GridRepo", "rules load failed", it)
            }
        }.start()
    }

    /** 多订阅合并：列表顺序即优先级（前者的规则先评估）；版本取最后一个（官方） */
    private fun merged(sets: List<CompiledRuleSet>): CompiledRuleSet {
        val official = sets.last()
        val rules = LinkedHashMap<String, List<CompiledRule>>()
        for (set in sets) {
            // 追加到已有列表之后：sets 顺序 [custom, local, official] → custom 永远最前。
            // 曾反着拼（v + 已有），内置 local 的同名规则抢先命中，自定义动作永远不执行
            for ((k, v) in set.rules) rules[k] = (rules[k] ?: emptyList()) + v
        }
        val excluded = HashMap<Int, Set<String>>()
        for (set in sets) {
            for ((k, v) in set.globalGroupExcludedApps) excluded[k] = (excluded[k] ?: emptySet()) + v
        }
        return CompiledRuleSet(
            subscriptionName = official.subscriptionName,
            version = official.version,
            rules = rules,
            totalRules = sets.sumOf { it.totalRules },
            skipped = sets.sumOf { it.skipped },
            disabledGroups = sets.sumOf { it.disabledGroups },
            globalGroupExcludedApps = excluded,
        )
    }

    companion object {
        private var instance: SubscriptionRepository? = null
        fun get(context: Context): SubscriptionRepository =
            instance ?: SubscriptionRepository(context).also { instance = it }
    }
}
