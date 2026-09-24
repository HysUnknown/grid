package cn.hys159x.grid.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectInBackground(
    block: (T) -> Unit,
) {
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch { collect { block(it) } }
}
