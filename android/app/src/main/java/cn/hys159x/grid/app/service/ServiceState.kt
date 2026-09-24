package cn.hys159x.grid.app.service

import cn.hys159x.grid.engine.subscription.CompiledRuleSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 拦截事件（内存日志条目，Task 14 持久化到 Room） */
data class InterceptEvent(
    val time: Long,
    val packageName: String,
    val appName: String?,
    val ruleName: String?,
    val action: String,
    val nodeDesc: String?,
)

/** 单例桥：无障碍服务写入状态与日志流；Compose UI 收集展示 */
object ServiceState {
    val serviceConnected = MutableStateFlow(false)
    val ruleSet: StateFlow<CompiledRuleSet?> get() = _ruleSet
    val _ruleSet = MutableStateFlow<CompiledRuleSet?>(null)
    val intercepts = MutableStateFlow<List<InterceptEvent>>(emptyList())

    // ---- 录制模式（自定义规则捕获） ----
    /** 录制开关：开启后切换到目标 App，服务在冷启动后扫描候选按钮 */
    val recording = MutableStateFlow(false)
    /** 录制目标包名：仅该 App 被扫描（用户在列表中选定并启动） */
    val recordingTargetPkg = MutableStateFlow<String?>(null)

    data class RecordCandidate(
        val packageName: String,
        val displayName: String,   // 展示：文本/vid/desc
        val selector: String,      // 生成的选择器
        val action: String = "click",
        /** 非空=Activity 级规则（自绘开屏页节点树为空，按 Activity 触发返回键） */
        val activityId: String? = null,
    )

    val recordCandidates = MutableStateFlow<List<RecordCandidate>>(emptyList())

    fun record(e: InterceptEvent) {
        intercepts.value = (listOf(e) + intercepts.value).take(200)
    }
}
