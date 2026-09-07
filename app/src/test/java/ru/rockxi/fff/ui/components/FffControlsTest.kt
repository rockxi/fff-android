package ru.rockxi.fff.ui.components

import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Test

class FffControlsTest {
    @Test fun `input kinds request appropriate phone keyboards`() {
        assertEquals(KeyboardType.Text, fffInputSpec(FffInputKind.TEXT).keyboardType)
        assertEquals(KeyboardCapitalization.Sentences, fffInputSpec(FffInputKind.TEXT).capitalization)
        assertEquals(KeyboardType.Decimal, fffInputSpec(FffInputKind.MONEY).keyboardType)
        assertEquals(KeyboardCapitalization.None, fffInputSpec(FffInputKind.MONEY).capitalization)
        assertEquals(KeyboardType.Ascii, fffInputSpec(FffInputKind.CURRENCY).keyboardType)
        assertEquals(KeyboardCapitalization.Characters, fffInputSpec(FffInputKind.CURRENCY).capitalization)
    }
}
