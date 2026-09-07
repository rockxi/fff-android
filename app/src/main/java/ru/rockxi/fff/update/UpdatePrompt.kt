package ru.rockxi.fff.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.rockxi.fff.BuildConfig

@Composable
fun UpdatePrompt(
    currentVersion: String = BuildConfig.VERSION_NAME,
    client: GitHubReleaseClient = remember { GitHubReleaseClient() },
) {
    val context = LocalContext.current
    val preferences = remember(context.applicationContext) {
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    var automaticChecksEnabled by remember {
        mutableStateOf(preferences.getBoolean(AUTOMATIC_CHECKS_KEY, false))
    }
    var consentPromptDismissed by remember { mutableStateOf(false) }
    var checkRequested by remember { mutableStateOf(automaticChecksEnabled) }
    var availableRelease by remember { mutableStateOf<GitHubRelease?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    val gate = remember(client) { UpdateCheckGate(client) }

    LaunchedEffect(checkRequested, currentVersion, gate) {
        if (!checkRequested) return@LaunchedEffect
        availableRelease = withContext(Dispatchers.IO) {
            gate.findUpdate(currentVersion, userOptedIn = automaticChecksEnabled)
        }
        checkRequested = false
    }

    val release = availableRelease
    if (!automaticChecksEnabled && !consentPromptDismissed) {
        AlertDialog(
            onDismissRequest = { consentPromptDismissed = true },
            title = { Text("Проверять обновления?") },
            text = {
                Text(
                    "После вашего согласия FFF будет обращаться к GitHub при запуске, " +
                        "чтобы сообщать о новых версиях. Без согласия сетевых запросов не будет.",
                )
            },
            dismissButton = {
                TextButton(onClick = { consentPromptDismissed = true }) { Text("Не сейчас") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        preferences.edit().putBoolean(AUTOMATIC_CHECKS_KEY, true).apply()
                        automaticChecksEnabled = true
                        checkRequested = true
                    },
                ) {
                    Text("Включить и проверить")
                }
            },
        )
    } else if (release != null && !dismissed) {
        AlertDialog(
            onDismissRequest = { dismissed = true },
            title = { Text("Доступно обновление") },
            text = {
                Text("Версия ${release.tagName} уже опубликована. Открыть страницу загрузки?")
            },
            dismissButton = {
                TextButton(onClick = { dismissed = true }) { Text("Позже") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val url = release.apkUrl ?: release.releaseUrl
                        if (safelyLaunchExternalBrowser {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }) {
                            dismissed = true
                        }
                    },
                ) {
                    Text("Скачать")
                }
            },
        )
    }
}

private const val PREFERENCES_NAME = "update_preferences"
private const val AUTOMATIC_CHECKS_KEY = "automatic_checks_enabled"

internal fun safelyLaunchExternalBrowser(launch: () -> Unit): Boolean = try {
    launch()
    true
} catch (_: RuntimeException) {
    // Includes ActivityNotFoundException, SecurityException and malformed-intent failures.
    false
}
