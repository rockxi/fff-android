package ru.rockxi.fff.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
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
    var promptState by remember { mutableStateOf(UpdatePromptState()) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloadAttempt by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    val downloadController = remember { UpdateDownloadController(SecureApkDownloader()) }
    val gate = remember(client) { UpdateCheckGate(client) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val ready = promptState.downloadState as? UpdateDownloadState.Ready
        promptState = promptState.onPermissionReturned(context.packageManager.canRequestPackageInstalls())
        if (ready != null && !promptState.awaitingPermission && promptState.message == null) {
            runCatching { launchApkInstaller(context, ready.apk) }
                .onSuccess { promptState = promptState.withMessage("Установщик Android открыт") }
                .onFailure { promptState = promptState.withMessage(it.message ?: "Не удалось открыть установщик") }
        }
    }

    fun install(apk: java.io.File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            promptState = promptState.onPermissionRequested()
            permissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
            )
        } else {
            runCatching { launchApkInstaller(context, apk) }
                .onSuccess { promptState = promptState.withMessage("Установщик Android открыт") }
                .onFailure { promptState = promptState.withMessage(it.message ?: "Не удалось открыть установщик") }
        }
    }

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
            onDismissRequest = { if (promptState.downloadState !is UpdateDownloadState.Downloading) dismissed = true },
            title = { Text("Доступно обновление") },
            text = {
                Column {
                    Text("Версия ${release.tagName} уже опубликована. Скачать и установить обновление?")
                    when (val state = promptState.downloadState) {
                        is UpdateDownloadState.Downloading -> {
                            val fraction = state.totalBytes?.takeIf { it > 0 }?.let { state.bytes.toFloat() / it }
                            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                            else LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                            Text("Загружено ${state.bytes / 1024} КиБ", modifier = Modifier.padding(top = 6.dp))
                        }
                        is UpdateDownloadState.Failed -> Text(state.message, modifier = Modifier.padding(top = 10.dp))
                        is UpdateDownloadState.Ready -> Text("APK загружен. Если Android запросит разрешение на установку, включите его и нажмите «Установить» ещё раз.", modifier = Modifier.padding(top = 10.dp))
                        UpdateDownloadState.Idle -> Unit
                    }
                    promptState.message?.let { Text(it, modifier = Modifier.padding(top = 10.dp)) }
                }
            },
            dismissButton = {
                if (promptState.downloadState is UpdateDownloadState.Downloading) {
                    TextButton(onClick = {
                        downloadAttempt += 1
                        promptState = promptState.cancelDownload(downloadAttempt)
                        downloadJob?.cancel()
                        downloadJob = null
                    }) { Text("Отменить") }
                } else {
                    TextButton(onClick = { dismissed = true }) { Text("Позже") }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ready = promptState.downloadState as? UpdateDownloadState.Ready
                        if (ready != null) {
                            install(ready.apk)
                        } else if (promptState.downloadState !is UpdateDownloadState.Downloading) {
                            val attempt = ++downloadAttempt
                            promptState = promptState.confirmDownload(attempt)
                            downloadJob = scope.launch {
                                val destination = updateDestination(context.cacheDir, release.tagName, attempt)
                                try {
                                    val apk = withContext(Dispatchers.IO) {
                                        downloadController.download(release, destination) { state ->
                                            scope.launch { promptState = promptState.onDownloadState(attempt, state) }
                                        }
                                    }
                                    if (apk != null && promptState.canComplete(attempt)) {
                                        promptState = promptState.completeDownload(attempt, apk)
                                        install(apk)
                                    }
                                } catch (_: CancellationException) {
                                    // The cancel action already publishes the user-visible state.
                                } finally {
                                    if (downloadAttempt == attempt) downloadJob = null
                                }
                            }
                        }
                    },
                    enabled = promptState.downloadState !is UpdateDownloadState.Downloading && !promptState.awaitingPermission,
                ) {
                    Text(if (promptState.downloadState is UpdateDownloadState.Ready) "Установить" else if (promptState.downloadState is UpdateDownloadState.Failed) "Повторить" else "Скачать")
                }
            },
        )
    }
}

private const val PREFERENCES_NAME = "update_preferences"
private const val AUTOMATIC_CHECKS_KEY = "automatic_checks_enabled"

internal fun updateDestination(
    cacheDir: java.io.File,
    tagName: String,
    attempt: Long,
    nonce: String = java.util.UUID.randomUUID().toString(),
): java.io.File {
    require(attempt > 0) { "Attempt must be positive" }
    require(Regex("[vV]?[0-9A-Za-z.+-]+").matches(tagName)) { "Invalid release tag" }
    require(Regex("[0-9A-Za-z-]+").matches(nonce)) { "Invalid attempt nonce" }
    return java.io.File(cacheDir, "updates/fff-$tagName-attempt-$attempt-$nonce.apk")
}

internal data class UpdatePromptState(
    val downloadState: UpdateDownloadState = UpdateDownloadState.Idle,
    val activeAttempt: Long = 0,
    val awaitingPermission: Boolean = false,
    val message: String? = null,
) {
    fun confirmDownload(attempt: Long = activeAttempt + 1) = copy(
        downloadState = UpdateDownloadState.Downloading(0, null),
        activeAttempt = attempt,
        message = null,
    )
    fun cancelDownload(invalidatedAttempt: Long = activeAttempt + 1) = copy(
        downloadState = UpdateDownloadState.Failed("Загрузка отменена"),
        activeAttempt = invalidatedAttempt,
        message = null,
    )
    fun canComplete(attempt: Long): Boolean =
        attempt == activeAttempt && (downloadState is UpdateDownloadState.Downloading || downloadState is UpdateDownloadState.Ready)
    fun onDownloadState(attempt: Long, state: UpdateDownloadState): UpdatePromptState =
        if (attempt == activeAttempt && downloadState is UpdateDownloadState.Downloading) {
            copy(downloadState = state, message = null)
        } else {
            this
        }
    fun completeDownload(attempt: Long, apk: java.io.File): UpdatePromptState =
        if (canComplete(attempt)) copy(downloadState = UpdateDownloadState.Ready(apk), message = null) else this
    fun onPermissionRequested() = copy(awaitingPermission = true, message = "Разрешите установку из этого источника")
    fun onPermissionReturned(granted: Boolean) = copy(
        awaitingPermission = false,
        message = if (granted) null else "Разрешение не выдано. Нажмите «Установить», чтобы повторить.",
    )
    fun withMessage(value: String) = copy(awaitingPermission = false, message = value)
}
