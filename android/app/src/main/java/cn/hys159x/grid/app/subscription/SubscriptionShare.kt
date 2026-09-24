package cn.hys159x.grid.app.subscription

import android.content.Context
import cn.hys159x.grid.engine.subscription.RawSubscription
import cn.hys159x.grid.engine.subscription.SubscriptionCompiler
import cn.hys159x.grid.engine.subscription.SubscriptionJson
import java.io.File

/** 完整规则集（自定义+本地+官方）导出为单个 RawSubscription JSON 文件；
 *  导入的外部规则集落地 filesDir/imported_rules.json，加载时优先于内置参与匹配 */
object SubscriptionShare {

    /** 导出全文：当前全部生效规则（自定义 + 外部导入），GKD 订阅格式，导入零损耗还原 */
    fun fullRulesJson(context: Context): String {
        val importedApps = if (hasImported(context)) {
            runCatching {
                SubscriptionJson.decode(RawSubscription.serializer(), importedFile(context).readText()).apps
            }.getOrDefault(emptyList())
        } else emptyList()
        val merged = RawSubscription(
            id = -2, name = "网格规则集导出", version = 1,
            apps = CustomRulesStore.toSubscription(context).apps + importedApps,
        )
        return SubscriptionJson.json.encodeToString(RawSubscription.serializer(), merged)
    }

    fun importedFile(context: Context) = File(context.filesDir, "imported_rules.json")

    fun hasImported(context: Context) = importedFile(context).exists()

    /** 保存外部规则集（先校验能编译，坏文件拒绝写入）。返回是否成功 */
    fun saveImported(context: Context, text: String): Boolean = runCatching {
        val sub = SubscriptionJson.decode(RawSubscription.serializer(), text.trim())
        SubscriptionCompiler.compile(sub)   // 校验：可完整编译
        importedFile(context).writeText(text.trim())
    }.isSuccess

    fun removeImported(context: Context) {
        importedFile(context).delete()
    }
}
