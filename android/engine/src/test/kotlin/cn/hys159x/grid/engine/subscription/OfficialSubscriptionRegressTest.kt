package cn.hys159x.grid.engine.subscription

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class OfficialSubscriptionRegressTest {
    @Test
    fun `official subscription compiles with high coverage`() {
        // fixture 不入库（避免随仓库分发大体积规则文件），按以下顺序查找：
        // 1. classpath（临时放回 test/resources）
        // 2. 环境变量 GRID_FIXTURE 指向的文件
        // 3. 仓库外 rules-fixture/（多级相对路径探测，兼容不同 workingDir）
        val text = runCatching {
            javaClass.getResourceAsStream("/official_subscription.json")?.bufferedReader()?.readText()
        }.getOrNull()
            ?: System.getenv("GRID_FIXTURE")?.let { f -> java.io.File(f).takeIf { it.exists() }?.readText() }
            ?: listOf("../../rules-fixture", "../../../rules-fixture")
                .map { java.io.File(it, "official_subscription.json") }
                .firstOrNull { it.exists() }?.readText()
        assumeTrue(text != null, "缺少 fixture：classpath / GRID_FIXTURE / ../rules-fixture/ 均未找到，跳过回归")
        val raw = SubscriptionJson.decode(
            RawSubscription.serializer(), text!!,
        )
        val set = SubscriptionCompiler.compile(raw)
        println("官方订阅回归：total=${set.totalRules} ok=${set.totalRules - set.skipped} " +
                "skipped=${set.skipped} disabledGroups=${set.disabledGroups} apps=${set.rules.size - 1}")
        // v1.3 扩展后（||/&&/()/字面n/通配*/null/反引号）剩余 skip 应 < 0.2%（真机验证发现
        // 全局开屏兜底规则属扩展语法，跳过会导致开屏广告不拦截）；逐条打印剩余 skip 定位
        set.skippedSamples.forEach {
            println("SKIP ${it.appId}/${it.groupKey}/${it.ruleKey} [${it.reason}] ${it.firstMatch}")
        }
        assertTrue(set.totalRules > 1000, "官方订阅应包含上千条规则")
        assertTrue(set.skipped.toFloat() / set.totalRules < 0.002f, "不支持语法占比应 < 0.2%")
        assertTrue(set.rules.containsKey("com.zhihu.android") || set.rules.size > 100, "应包含常见 App 规则")
    }
}
