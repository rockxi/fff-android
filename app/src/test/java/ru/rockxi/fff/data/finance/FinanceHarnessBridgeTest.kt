package ru.rockxi.fff.data.finance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FinanceHarnessBridgeTest {
    @Test fun `assistant context contains bounded aggregates without ledger details`() {
        val context = FinanceHarnessContextCodec.fromReport(report())
        val root = Json.parseToJsonElement(context.json).jsonObject
        assertEquals(setOf("version", "preset", "startDate", "endDate", "generatedAtEpochMillis", "currencies"), root.keys)
        assertFalse(context.json.contains("categoryId"))
        assertFalse(context.json.contains("account", ignoreCase = true))
        assertFalse(context.json.contains("note", ignoreCase = true))
        assertTrue(context.json.toByteArray().size <= FinanceHarnessContextCodec.MAX_CONTEXT_BYTES)
    }

    @Test fun `assistant context rejects a range beyond one hundred years`() {
        val huge = report().copy(
            range = FinanceAnalyticsRange.custom(LocalDate.of(1900, 1, 1), LocalDate.of(2026, 1, 2)),
            effectiveStartInclusive = LocalDate.of(1900, 1, 1),
            effectiveEndInclusive = LocalDate.of(2026, 1, 2),
        )
        assertThrows(IllegalArgumentException::class.java) { FinanceHarnessContextCodec.fromReport(huge) }
    }

    private fun report() = FinanceAnalyticsReport(
        range = FinanceAnalyticsRange.currentMonth(LocalDate.of(2026, 9, 10)),
        effectiveStartInclusive = LocalDate.of(2026, 9, 1),
        effectiveEndInclusive = LocalDate.of(2026, 9, 30),
        generatedAtEpochMillis = 1L,
        currencies = listOf(FinanceCurrencyAnalytics(
            currency = "RUB", incomeMinor = 10_000, expenseMinor = 2_500,
            netFlowMinor = 7_500, transactionCount = 2, dailyAverageExpenseMinor = 83,
            topExpenseCategory = null,
            expensesByCategory = listOf(FinanceCategoryExpense(99, "Еда", "🍲", 2_500, 1)),
        )),
    )
}
