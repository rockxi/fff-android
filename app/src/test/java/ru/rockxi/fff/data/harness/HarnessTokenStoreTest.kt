package ru.rockxi.fff.data.harness

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.rockxi.fff.data.finance.FinanceAnalyticsPreset
import ru.rockxi.fff.data.finance.FinanceAnalyticsRange
import ru.rockxi.fff.data.finance.FinanceHarnessContext
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class HarnessTokenStoreTest {
    private lateinit var context: Context

    @Before fun clearPrefs() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("harness_device", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `corrupt finance binding is discarded but ordinary pending request survives`() {
        val store = KeystoreHarnessTokenStore(context)
        val range = FinanceAnalyticsRange.preset(FinanceAnalyticsPreset.MONTH, LocalDate.of(2026, 9, 10))
        val json = """{"version":1,"preset":"month","startDate":"2026-09-01","endDate":"2026-09-30","generatedAtEpochMillis":1,"currencies":[]}"""
        store.savePendingMessage(
            "chat",
            PendingHarnessMessage("client", "question", HarnessProvider.CODEX, "gpt-a", FinanceHarnessContext(json, "ignored"), range),
        )
        context.getSharedPreferences("harness_device", Context.MODE_PRIVATE).edit()
            .putString("pending_message_finance_start_chat", "2026-09-02")
            .putString("pending_message_finance_end_chat", "2026-09-03")
            .commit()

        val restored = store.pendingMessage("chat")!!
        assertEquals("client", restored.clientId)
        assertEquals("question", restored.text)
        assertEquals(HarnessProvider.CODEX, restored.provider)
        assertEquals("gpt-a", restored.model)
        assertNull(restored.financeContext)
        assertNull(restored.financeRange)
    }

    @Test fun `wrong json scalar types discard restored finance binding`() {
        val store = KeystoreHarnessTokenStore(context)
        val range = FinanceAnalyticsRange.preset(FinanceAnalyticsPreset.MONTH, LocalDate.of(2026, 9, 10))
        val valid = """{"version":1,"preset":"month","startDate":"2026-09-01","endDate":"2026-09-30","generatedAtEpochMillis":1,"currencies":[]}"""
        store.savePendingMessage("chat", PendingHarnessMessage("id", "text", financeContext = FinanceHarnessContext(valid, "ignored"), financeRange = range))
        context.getSharedPreferences("harness_device", Context.MODE_PRIVATE).edit()
            .putString("pending_message_finance_context_chat", valid.replace("\"version\":1", "\"version\":\"1\""))
            .commit()

        val restored = store.pendingMessage("chat")!!
        assertNull(restored.financeContext)
        assertNull(restored.financeRange)
        assertEquals("id", restored.clientId)
        assertEquals("text", restored.text)
    }
}
