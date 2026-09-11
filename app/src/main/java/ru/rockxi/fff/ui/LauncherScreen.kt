package ru.rockxi.fff.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.rockxi.fff.model.AppAccent
import ru.rockxi.fff.model.AppCatalog
import ru.rockxi.fff.model.FffApplication
import ru.rockxi.fff.navigation.Destination
import ru.rockxi.fff.ui.theme.FffLine
import ru.rockxi.fff.ui.theme.FffMint
import ru.rockxi.fff.ui.theme.FffMuted
import ru.rockxi.fff.ui.theme.FffSurface
import ru.rockxi.fff.ui.theme.FffText
import ru.rockxi.fff.ui.theme.FffViolet
import ru.rockxi.fff.update.UpdateRequests

@Composable
fun LauncherScreen(onOpen: (Destination) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(52.dp))
        BrandBar()
        Spacer(Modifier.height(38.dp))
        AccentLabel("PERSONAL OPERATING SYSTEM", FffMint)
        Text("Приложения", color = FffText, fontSize = 38.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Твои инструменты, данные и агенты — в одном приватном пространстве.",
            color = FffMuted,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        AppCatalog.applications.forEachIndexed { index, app ->
            AppCard(app = app, number = index + 1, onClick = { onOpen(app.destination) })
            Spacer(Modifier.height(14.dp))
        }
        OutlinedButton(
            onClick = UpdateRequests::requestCheck,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Rounded.SystemUpdate, contentDescription = null)
            Text("Проверить обновления", modifier = Modifier.padding(start = 8.dp))
        }
        Text("●  LOCAL FIRST  ·  PRIVATE BY DESIGN", color = FffMint.copy(alpha = .7f), fontSize = 10.sp,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, modifier = Modifier.padding(vertical = 12.dp))
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BrandBar() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF141B22))
                .border(1.dp, Color(0xFF33414D), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("F", color = FffMint, fontWeight = FontWeight.Black) }
        Text("FFF", color = FffText, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            modifier = Modifier.padding(start = 10.dp))
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(7.dp).background(FffMint, CircleShape))
        Text("  SECURE LINK", color = FffMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp)
    }
}

@Composable
private fun AppCard(app: FffApplication, number: Int, onClick: () -> Unit) {
    val accent = if (app.accent == AppAccent.Mint) FffMint else FffViolet
    val darkAccent = if (app.accent == AppAccent.Mint) Color(0xFF13231F) else Color(0xFF1F192C)
    val icon = when (app.destination) {
        Destination.Launcher -> Icons.Rounded.Dashboard
        Destination.Finance -> Icons.Rounded.AccountBalanceWallet
        Destination.RemoteControl -> Icons.Rounded.Terminal
        Destination.Harness -> Icons.Rounded.AutoAwesome
        Destination.Gym -> Icons.Rounded.FitnessCenter
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(FffSurface)
            .border(1.dp, FffLine, RoundedCornerShape(18.dp)).clickable(onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().height(146.dp)
                .background(Brush.linearGradient(listOf(darkAccent, Color(0xFF090C11)))),
        ) {
            GridDecoration(accent)
            Text("%02d".format(number), color = accent, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(18.dp).border(1.dp, accent.copy(alpha=.6f), CircleShape).padding(horizontal=9.dp, vertical=4.dp))
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).size(40.dp))
        }
        Column(Modifier.padding(18.dp)) {
            AccentLabel(app.kicker, accent)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(app.name, color = FffText, fontWeight = FontWeight.Bold, fontSize = 23.sp)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.ArrowOutward, contentDescription = "Открыть ${app.name}", tint = FffMuted)
            }
            Text(app.description, color = Color(0xFF98A4AE), fontSize = 13.sp, lineHeight = 19.sp,
                modifier = Modifier.padding(top = 9.dp, bottom = 16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(FffLine))
            Text(app.meta, color = Color(0xFF687682), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp, modifier = Modifier.padding(top = 13.dp))
        }
    }
}

@Composable
private fun GridDecoration(accent: Color) {
    Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        repeat(6) { Box(Modifier.size(1.dp, 106.dp).background(accent.copy(alpha = .06f))) }
    }
}

@Composable
private fun AccentLabel(text: String, accent: Color) {
    Text(text, color = accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp, modifier = Modifier.padding(bottom = 7.dp))
}

@Composable
fun ModulePlaceholder(kicker: String, title: String, description: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(52.dp))
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack).padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад", tint = FffMuted)
            Text("Все приложения", color = FffMuted, modifier = Modifier.padding(start = 7.dp), fontSize = 13.sp)
        }
        Spacer(Modifier.height(36.dp))
        AccentLabel(kicker, FffMint)
        Text(title, color = FffText, fontWeight = FontWeight.SemiBold, fontSize = 34.sp)
        Text(description, color = FffMuted, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(30.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FffSurface)
                .border(1.dp, FffLine, RoundedCornerShape(16.dp)).padding(24.dp),
        ) { Text("Модуль готов к подключению данных", color = FffMint, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
}
