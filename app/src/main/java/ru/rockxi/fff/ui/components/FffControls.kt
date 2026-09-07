package ru.rockxi.fff.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.rockxi.fff.ui.theme.FffBackground
import ru.rockxi.fff.ui.theme.FffLine
import ru.rockxi.fff.ui.theme.FffMint
import ru.rockxi.fff.ui.theme.FffMuted
import ru.rockxi.fff.ui.theme.FffSurface
import ru.rockxi.fff.ui.theme.FffText

enum class FffInputKind { TEXT, MONEY, CURRENCY }

data class FffInputSpec(
    val keyboardType: KeyboardType,
    val capitalization: KeyboardCapitalization,
)

fun fffInputSpec(kind: FffInputKind): FffInputSpec = when (kind) {
    FffInputKind.TEXT -> FffInputSpec(KeyboardType.Text, KeyboardCapitalization.Sentences)
    FffInputKind.MONEY -> FffInputSpec(KeyboardType.Decimal, KeyboardCapitalization.None)
    FffInputKind.CURRENCY -> FffInputSpec(KeyboardType.Ascii, KeyboardCapitalization.Characters)
}

@Composable
fun FffTextInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    kind: FffInputKind = FffInputKind.TEXT,
    supportingText: String? = null,
    error: String? = null,
    singleLine: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val glow by animateColorAsState(
        if (focused) FffMint.copy(alpha = .08f) else Color.Transparent,
        label = "fff-input-glow",
    )
    val spec = fffInputSpec(kind)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(glow, RoundedCornerShape(15.dp))
            .onFocusChanged { focused = it.isFocused },
        label = { Text(label) },
        supportingText = when {
            error != null -> ({ Text(error) })
            supportingText != null -> ({ Text(supportingText) })
            else -> null
        },
        trailingIcon = if (value.isNotEmpty()) ({
            IconButton(onClick = { onValueChange("") }) {
                Icon(Icons.Rounded.Close, "Очистить поле $label")
            }
        }) else null,
        isError = error != null,
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(
            capitalization = spec.capitalization,
            keyboardType = spec.keyboardType,
            imeAction = ImeAction.Next,
        ),
        shape = RoundedCornerShape(15.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = FffMint,
            unfocusedBorderColor = FffLine,
            errorBorderColor = Color(0xFFFF7C9B),
            focusedLabelColor = FffMint,
            unfocusedLabelColor = FffMuted,
            cursorColor = FffMint,
            focusedContainerColor = FffBackground.copy(alpha = .72f),
            unfocusedContainerColor = FffSurface.copy(alpha = .72f),
        ),
    )
}

@Composable
fun FffChoiceRow(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    val background by animateColorAsState(
        if (selected) FffMint.copy(alpha = .12f) else Color.Transparent,
        label = "fff-choice-background",
    )
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .background(background)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FffSelectionBadge(selected, if (selected) "$label выбрано" else "$label не выбрано")
        Spacer(Modifier.width(10.dp))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(label, color = FffText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            supportingText?.let { Text(it, color = FffMuted, fontSize = 11.sp, maxLines = 2) }
        }
    }
}

@Composable
fun FffSelectionBadge(selected: Boolean, description: String, modifier: Modifier = Modifier) {
    val color by animateColorAsState(if (selected) FffMint else FffLine, label = "fff-check-color")
    Surface(
        modifier = modifier.size(24.dp).semantics { contentDescription = description },
        shape = CircleShape,
        color = if (selected) color else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, color),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Rounded.Check, null, tint = Color(0xFF07100D), modifier = Modifier.size(16.dp))
        }
    }
}
