package cn.hys159x.grid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import cn.hys159x.grid.app.ui.CustomRulesScreen
import cn.hys159x.grid.app.ui.GuideScreen
import cn.hys159x.grid.app.ui.HomeScreen
import cn.hys159x.grid.app.ui.LogScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2E7D32))) {
                Surface {
                    var screen by remember { mutableStateOf("home") }
                    when (screen) {
                        "logs" -> LogScreen(onBack = { screen = "home" })
                        "guide" -> GuideScreen(onBack = { screen = "home" })
                        "custom" -> CustomRulesScreen(onBack = { screen = "home" })
                        else -> HomeScreen(
                            onOpenLogs = { screen = "logs" },
                            onOpenGuide = { screen = "guide" },
                            onOpenCustomRules = { screen = "custom" },
                        )
                    }
                }
            }
        }
    }
}
