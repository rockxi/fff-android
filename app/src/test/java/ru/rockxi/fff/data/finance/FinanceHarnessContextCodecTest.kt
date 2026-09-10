package ru.rockxi.fff.data.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class FinanceHarnessContextCodecTest {
    @Test fun `restore accepts only context bound to the same range`() {
        val range = FinanceAnalyticsRange.custom(
            LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 10),
        )
        val report = FinanceAnalyticsReport(
            range = range,
            effectiveStartInclusive = range.startInclusive,
            effectiveEndInclusive = range.endInclusive,
            generatedAtEpochMillis = 1,
            currencies = emptyList(),
        )
        val encoded = FinanceHarnessContextCodec.fromReport(report)

        assertEquals(encoded.json, FinanceHarnessContextCodec.restore(encoded.json, range)?.json)
        assertNull(
            FinanceHarnessContextCodec.restore(
                encoded.json,
                FinanceAnalyticsRange.custom(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)),
            ),
        )
    }

    @Test fun `restore rejects oversized ranges and mismatched presets`() {
        val oversized = FinanceAnalyticsRange.custom(
            LocalDate.of(1900, 1, 1),
            LocalDate.of(2026, 9, 10),
        )
        val json = """{"version":1,"preset":"custom","startDate":"1900-01-01","endDate":"2026-09-10","generatedAtEpochMillis":1,"currencies":[]}"""
        assertNull(FinanceHarnessContextCodec.restore(json, oversized))

        val month = FinanceAnalyticsRange.preset(FinanceAnalyticsPreset.MONTH, LocalDate.of(2026, 9, 10))
        val wrongPreset = """{"version":1,"preset":"year","startDate":"2026-09-01","endDate":"2026-09-30","generatedAtEpochMillis":1,"currencies":[]}"""
        assertNull(FinanceHarnessContextCodec.restore(wrongPreset, month))
    }

    @Test fun `restore rejects unknown fields and malformed json`() {
        val range = FinanceAnalyticsRange.preset(FinanceAnalyticsPreset.MONTH, LocalDate.of(2026, 9, 10))
        assertNull(FinanceHarnessContextCodec.restore("not-json", range))
        assertNull(
            FinanceHarnessContextCodec.restore(
                """{"version":1,"preset":"month","startDate":"2026-09-01","endDate":"2026-09-30","generatedAtEpochMillis":1,"currencies":[],"path":"/tmp/report"}""",
                range,
            ),
        )
    }

    @Test fun `restore rejects noncanonical preset dates and unknown nested fields`() {
        val nonCanonicalMonth = FinanceAnalyticsRange(
            LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3), FinanceAnalyticsPreset.MONTH,
        )
        val shortMonth = """{"version":1,"preset":"month","startDate":"2026-09-02","endDate":"2026-09-03","generatedAtEpochMillis":1,"currencies":[]}"""
        assertNull(FinanceHarnessContextCodec.restore(shortMonth, nonCanonicalMonth))

        val month = FinanceAnalyticsRange.preset(FinanceAnalyticsPreset.MONTH, LocalDate.of(2026, 9, 10))
        val nestedExtra = """{"version":1,"preset":"month","startDate":"2026-09-01","endDate":"2026-09-30","generatedAtEpochMillis":1,"currencies":[{"currency":"RUB","incomeMinor":0,"expenseMinor":0,"netFlowMinor":0,"transactionCount":0,"dailyAverageExpenseMinor":0,"expensesByCategory":[],"note":"secret"}]}"""
        assertNull(FinanceHarnessContextCodec.restore(nestedExtra, month))
    }

    @Test fun `restore rejects quoted numerics wrong scalar types and noncanonical all time range`() {
        val month = FinanceAnalyticsRange.preset(FinanceAnalyticsPreset.MONTH, LocalDate.of(2026, 9, 10))
        val quotedVersion = """{"version":"1","preset":"month","startDate":"2026-09-01","endDate":"2026-09-30","generatedAtEpochMillis":1,"currencies":[]}"""
        assertNull(FinanceHarnessContextCodec.restore(quotedVersion, month))
        val wrongNestedTypes = """{"version":1,"preset":"month","startDate":"2026-09-01","endDate":"2026-09-30","generatedAtEpochMillis":1,"currencies":[{"currency":123,"incomeMinor":"0","expenseMinor":0,"netFlowMinor":0,"transactionCount":0,"dailyAverageExpenseMinor":0,"expensesByCategory":[{"name":true,"emoji":"🧾","amountMinor":0,"transactionCount":0}]}]}"""
        assertNull(FinanceHarnessContextCodec.restore(wrongNestedTypes, month))
        val datedAllTime = FinanceAnalyticsRange(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 10), FinanceAnalyticsPreset.ALL_TIME,
        )
        val allTimeJson = """{"version":1,"preset":"all_time","startDate":"2026-01-01","endDate":"2026-09-10","generatedAtEpochMillis":1,"currencies":[]}"""
        assertNull(FinanceHarnessContextCodec.restore(allTimeJson, datedAllTime))
    }
}
