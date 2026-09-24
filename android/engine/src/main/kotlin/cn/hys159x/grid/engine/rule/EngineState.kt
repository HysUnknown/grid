package cn.hys159x.grid.engine.rule

class RuleRuntime {
    var lastActionAt: Long = 0
    var actionCount: Int = 0
    var activatedAt: Long = 0      // 规则唤醒时刻（matchTime/actionMaximum 从此计）
    var pendingSince: Long = 0     // actionDelay 首次查到时刻
    var lastExecutedAt: Long = 0   // preKeys 判定用
}

/**
 * 规则运行时状态。
 *
 * 并发契约：非线程安全。约定仅由无障碍服务主线程回调访问（Task 10 的 onAccessibilityEvent）；
 * 若未来跨线程访问需整体替换 ConcurrentHashMap 并同步 RuleRuntime 字段读写。
 */
class EngineState {
    val runtimes = HashMap<RuleId, RuleRuntime>()
    val clock: () -> Long                                          // 可注入测试时间

    constructor(clock: () -> Long = { System.currentTimeMillis() }) { this.clock = clock }

    fun runtime(id: RuleId) = runtimes.getOrPut(id) { RuleRuntime() }

    /** resetMatch=activity/match：Activity（界面）刷新时重置计数/计时；无 runtime 的规则预创建并落定唤醒时刻 */
    fun onActivityReset(rules: List<CompiledRule>, now: Long) {
        for (r in rules) if (r.resetMatch == "activity" || r.resetMatch == "match") resetRuntime(r.id, now)
    }

    /** resetMatch=app：重新进入 App 时重置（同时覆盖 activity/match 级） */
    fun onAppEnter(rules: List<CompiledRule>, now: Long) {
        for (r in rules) resetRuntime(r.id, now)
    }

    private fun resetRuntime(id: RuleId, now: Long) {
        val rt = runtime(id)   // getOrPut：从未评估过的规则也在此刻唤醒
        rt.actionCount = 0; rt.activatedAt = now; rt.pendingSince = 0
    }
}

/** preKeys 判定：前置 key 的规则在 PREKEY_WINDOW 内执行过 */
const val PREKEY_WINDOW_MS = 10_000L
