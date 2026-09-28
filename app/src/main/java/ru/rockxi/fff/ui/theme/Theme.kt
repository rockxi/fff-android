package ru.rockxi.fff.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val FffBackground = Color(0xFF080A0E)
val FffSurface = Color(0xFF0E1218)
val FffLine = Color(0xFF202833)
val FffText = Color(0xFFEDF5F4)
val FffMuted = Color(0xFF77838F)
val FffMint = Color(0xFF66F6C9)
val FffViolet = Color(0xFFC7A7FF)

/** Shared spacing and type roles for the launcher and native modules. */
object FffMetrics {
    val pageGutter = 16.dp
    val sectionGap = 16.dp
    val itemGap = 10.dp
    val touchTarget = 48.dp
}

private val typography = Typography(
    headlineMedium = Typography().headlineMedium.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    titleLarge = Typography().titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = Typography().titleMedium.copy(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = Typography().bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = Typography().labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

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
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
