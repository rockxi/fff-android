package ru.rockxi.fff.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.rockxi.fff.ui.theme.FffBackground
import ru.rockxi.fff.ui.theme.FffLine
import ru.rockxi.fff.ui.theme.FffMint
import ru.rockxi.fff.ui.theme.FffText

@Composable
fun FffModal(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismissText: String = "Отмена",
    confirmEnabled: Boolean = true,
    dismissEnabled: Boolean = true,
    destructive: Boolean = false,
    dismissOnBackPress: Boolean = dismissEnabled,
    dismissOnClickOutside: Boolean = dismissEnabled,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (dismissEnabled) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = dismissOnClickOutside,
        ),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Surface(
                modifier = Modifier
                    .sizeIn(maxWidth = 560.dp, maxHeight = maxHeight)
                    .fillMaxWidth()
                    .align(Alignment.Center),
                shape = RoundedCornerShape(26.dp),
                color = FffBackground,
                contentColor = FffText,
                tonalElevation = 10.dp,
                shadowElevation = 18.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, FffLine),
            ) {
                Column(
                    Modifier.fillMaxWidth().background(FffBackground).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            Modifier.weight(1f).semantics { heading() },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        IconButton(onClick = onDismiss, enabled = dismissEnabled) {
                            Icon(Icons.Rounded.Close, "Закрыть")
                        }
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        content()
                    }
                    if (confirmText != null && onConfirm != null) {
                        FffModalActions(
                            confirmText = confirmText,
                            dismissText = dismissText,
                            onConfirm = onConfirm,
                            onDismiss = onDismiss,
                            confirmEnabled = confirmEnabled,
                            dismissEnabled = dismissEnabled,
                            destructive = destructive,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FffModalActions(
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean,
    dismissEnabled: Boolean,
    destructive: Boolean,
) {
    val confirm: @Composable (Modifier) -> Unit = { modifier ->
        Button(
            onClick = onConfirm,
            enabled = confirmEnabled,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (destructive) Color(0xFFB83255) else FffMint,
                contentColor = if (destructive) Color.White else Color(0xFF07100D),
            ),
        ) { Text(confirmText, fontSize = 14.sp) }
    }
    val dismiss: @Composable (Modifier) -> Unit = { modifier ->
        TextButton(onClick = onDismiss, enabled = dismissEnabled, modifier = modifier) {
            Text(dismissText)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 340.dp) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                confirm(Modifier.fillMaxWidth())
                dismiss(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                dismiss(Modifier)
                confirm(Modifier)
            }
        }
    }
}
