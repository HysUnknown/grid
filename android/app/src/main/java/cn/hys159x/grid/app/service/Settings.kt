package cn.hys159x.grid.app.service

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

object Settings {
    private lateinit var prefs: SharedPreferences
    val enabled = MutableStateFlow(true)

    fun init(context: Context) {
        prefs = context.getSharedPreferences("grid", Context.MODE_PRIVATE)
        enabled.value = prefs.getBoolean("enabled", true)
    }

    fun setEnabled(v: Boolean) {
        enabled.value = v
        prefs.edit().putBoolean("enabled", v).apply()
    }
}
