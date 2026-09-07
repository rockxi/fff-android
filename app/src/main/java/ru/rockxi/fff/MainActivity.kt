package ru.rockxi.fff

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ru.rockxi.fff.ui.FffApp
import ru.rockxi.fff.ui.theme.FffTheme
import ru.rockxi.fff.update.UpdatePrompt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FffTheme {
                FffApp()
                UpdatePrompt()
            }
        }
    }
}
