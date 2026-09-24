package cn.hys159x.grid.ademo

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 主页：显示进入方式（点跳过 / 倒计时结束），用于验证广告是否被自动跳过 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val skipped = intent.getBooleanExtra("skipped", false)
        val remain = intent.getIntExtra("remain", 0)
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())

        val tv = TextView(this).apply {
            text = if (skipped) {
                "主页\n\n已点「跳过」进入\n剩余倒计时 ${remain}s\n$time"
            } else {
                "主页\n\n倒计时自然结束进入\n$time"
            }
            setTextColor(Color.parseColor("#222222"))
            textSize = 18f
            gravity = Gravity.CENTER
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(tv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            ))
        })
    }
}
