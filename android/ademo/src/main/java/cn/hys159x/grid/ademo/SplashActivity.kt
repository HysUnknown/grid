package cn.hys159x.grid.ademo

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView

/** 模拟开屏广告页：右上角"跳过 N"倒计时按钮，5 秒倒计时结束自动进主页，点跳过立即进 */
class SplashActivity : Activity() {

    private var remain = 5
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var skipBtn: Button
    private val entered = arrayOf(false)

    private val tick = object : Runnable {
        override fun run() {
            remain--
            if (remain <= 0) {
                goHome(skipped = false)
            } else {
                skipBtn.text = "跳过 $remain"
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#1B2A4A")) }

        val title = TextView(this).apply {
            text = "广 告"
            setTextColor(Color.WHITE)
            textSize = 44f
            gravity = Gravity.CENTER
        }
        root.addView(title, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ))

        val sub = TextView(this).apply {
            text = "模拟开屏广告"
            setTextColor(Color.parseColor("#8899BB"))
            textSize = 14f
            gravity = Gravity.CENTER
        }
        root.addView(sub, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM,
        ).apply { bottomMargin = 120 })

        skipBtn = Button(this).apply {
            id = R.id.btn_skip
            text = "跳过 5"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#66333333"))
            setPadding(28, 12, 28, 12)
            textSize = 14f
            isAllCaps = false
            setOnClickListener { goHome(skipped = true) }
        }
        root.addView(skipBtn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ).apply { topMargin = 110; marginEnd = 30 })

        setContentView(root)
        handler.postDelayed(tick, 1000)
    }

    private fun goHome(skipped: Boolean) {
        if (entered[0]) return
        entered[0] = true
        handler.removeCallbacks(tick)
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra("skipped", skipped)
                .putExtra("remain", remain),
        )
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }
}
