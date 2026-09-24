package cn.hys159x.grid.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("使用说明") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section("1. 开启服务")
            Body("设置 → 辅助功能 → 已下载的应用 → 网格 → 开启。开启后返回本应用，主页应显示\"服务运行中\"。")

            Section("2. 保活设置（重要，防服务被杀）")
            Body("华为/小米等机型：设置 → 应用 → 网格 → 启动管理（自启动管理）→ 关闭\"自动管理\"，三个手动开关全部允许；并在电池设置中将网格设为\"不受限制/关闭电池优化\"。")

            Section("3. 日常使用")
            Body("服务开启后自动工作，无需任何操作。打开其他 App 时，开屏广告与弹窗会被自动点击\"跳过 / 关闭\"。可在\"拦截日志\"查看记录。主页开关可临时停用全部拦截。")

            Section("4. 规则库")
            Body("规则库（数千条，覆盖常用 App）直接内置在应用中，完全离线运行、无需任何联网权限。规则更新随应用版本发布。")

            Section("5. 支持范围")
            Body("大多数 App 的开屏广告、弹窗、更新提醒可自动处理。少数 App 使用自绘广告（无障碍不可见）或带无障碍点击检测，暂时无法拦截，属系统限制。")

            Section("6. 注意事项")
            Body("更新或重装本应用后，系统会自动撤销无障碍权限，需要按第 1 步重新开启（华为部分机型经电脑安装后，建议在设置里先关再开一次）。")
            Body("华为机型：从最近任务划掉本应用 = 强制停止，会导致无障碍权限被移除！请在最近任务界面下拉本应用卡片进行\"锁定\"（出现挂锁图标），日常退出请按 Home 键。")
            Body("若发现拦截有误点，可在拦截日志核对对应 App，并向开发者反馈。长时间零拦截时，留意通知栏的\"服务受限\"提醒，按提示重启服务即可。")
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}
