package ru.rockxi.fff.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val FffBackground = Color(0xFF080A0E)
val FffSurface = Color(0xFF0E1218)
val FffLine = Color(0xFF202833)
val FffText = Color(0xFFEDF5F4)
val FffMuted = Color(0xFF77838F)
val FffMint = Color(0xFF66F6C9)
val FffViolet = Color(0xFFC7A7FF)

private val colors = darkColorScheme(
    primary = FffMint,
    secondary = FffViolet,
    background = FffBackground,
    surface = FffSurface,
    onPrimary = Color(0xFF07100D),
    onBackground = FffText,
    onSurface = FffText,
)

@Composable
fun FffTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}

