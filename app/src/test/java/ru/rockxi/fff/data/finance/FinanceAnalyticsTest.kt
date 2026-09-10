package ru.rockxi.fff.data.finance

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class FinanceAnalyticsTest {
    @Test fun presetsUseCalendarBoundariesAcrossYearTransitions() {
        val today = LocalDate.of(2026, 1, 12)
        assertRange(FinanceAnalyticsRange.preset(FinanceAnalyticsPreset.MONTH, today), "2026-01-01", "2026-01-31")
        assertRange(FinanceAnalyticsRange.preset(FinanceAnalyticsPreset.QUARTER, today), "2026-01-01", "2026-03-31")
        assertRange(FinanceAnalyticsRange.preset(FinanceAnalyticsPreset.HALF_YEAR, today), "2026-01-01", "2026-06-30")
        assertRange(FinanceAnalyticsRange.preset(FinanceAnalyticsPreset.YEAR, today), "2026-01-01", "2026-12-31")

        val december = LocalDate.of(2025, 12, 31)
        assertRange(FinanceAnalyticsRange.preset(FinanceAnalyticsPreset.QUARTER, december), "2025-10-01", "2025-12-31")
        assertRange(FinanceAnalyticsRange.preset(FinanceAnalyticsPreset.HALF_YEAR, december), "2025-07-01", "2025-12-31")
        val all = FinanceAnalyticsRange.preset(FinanceAnalyticsPreset.ALL_TIME, today)
        assertNull(all.startInclusive)
        assertNull(all.endInclusive)
    }

    @Test fun calculatorDefaultRangeUsesSuppliedClockAndZone() {
        val zone = java.time.ZoneId.of("America/Los_Angeles")
        val clock = Clock.fixed(Instant.parse("2026-03-01T00:30:00Z"), ZoneOffset.UTC)
        val account = AccountEntity(1, "Cash", "RUB")
        val category = CategoryEntity(2, "Food", CategoryKind.EXPENSE, emoji = "🍔")
        val report = FinanceAnalyticsCalculator.calculate(
            entries = listOf(
                LedgerEntryEntity(1, EntryKind.EXPENSE, 100, account.id, categoryId = category.id, occurredAt = Instant.parse("2026-02-28T20:00:00Z").toEpochMilli()),
                LedgerEntryEntity(2, EntryKind.EXPENSE, 200, account.id, categoryId = category.id, occurredAt = Instant.parse("2026-03-01T20:00:00Z").toEpochMilli()),
            ),
            accounts = listOf(account),
            categories = listOf(category),
            zone = zone,
            clock = clock,
        )

        assertEquals(LocalDate.of(2026, 2, 1), report.range.startInclusive)
        assertEquals(LocalDate.of(2026, 2, 28), report.range.endInclusive)
        assertEquals(100, report.currencies.single().expenseMinor)
    }

    @Test fun aggregationExcludesIncomeAndTransfersFromCategoriesAndSeparatesCurrencies() {
        val rub = AccountEntity(1, "RUB", "RUB")
        val usd = AccountEntity(2, "USD", "USD")
        val food = CategoryEntity(10, "Food", CategoryKind.EXPENSE, emoji = "🍔")
        val salary = CategoryEntity(11, "Salary", CategoryKind.INCOME, emoji = "💰")
        val date = at("2026-09-10")
        val report = FinanceAnalyticsCalculator.calculate(
            entries = listOf(
                LedgerEntryEntity(1, EntryKind.EXPENSE, 500, rub.id, categoryId = food.id, occurredAt = date),
                LedgerEntryEntity(2, EntryKind.EXPENSE, 700, usd.id, categoryId = food.id, occurredAt = date),
                LedgerEntryEntity(3, EntryKind.INCOME, 2_000, rub.id, categoryId = salary.id, occurredAt = date),
                LedgerEntryEntity(4, EntryKind.TRANSFER, 900, rub.id, usd.id, occurredAt = date),
            ),
            accounts = listOf(rub, usd),
            categories = listOf(food, salary),
            requestedRange = FinanceAnalyticsRange.custom(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)),
            zone = ZoneOffset.UTC,
            clock = FIXED_CLOCK,
        )

        assertEquals(listOf("RUB", "USD"), report.currencies.map { it.currency })
        val rubReport = report.currencies.first()
        assertEquals(2_000, rubReport.incomeMinor)
        assertEquals(500, rubReport.expenseMinor)
        assertEquals(1_500, rubReport.netFlowMinor)
        assertEquals(2, rubReport.transactionCount)
        assertEquals(listOf("Food"), rubReport.expensesByCategory.map { it.categoryName })
        assertFalse(rubReport.expensesByCategory.any { it.categoryName == "Salary" })
        assertEquals(700, report.currencies.last().expenseMinor)
    }

    @Test fun outOfBudgetExpenseGetsSyntheticCategoryWhileIncomeStaysInTotals() {
        val account = AccountEntity(1, "Cash", "RUB")
        val date = at("2026-09-10")
        val report = FinanceAnalyticsCalculator.calculate(
            entries = listOf(
                LedgerEntryEntity(1, EntryKind.EXPENSE, 500, account.id, categoryId = null, occurredAt = date),
                LedgerEntryEntity(2, EntryKind.INCOME, 900, account.id, categoryId = null, occurredAt = date),
            ),
            accounts = listOf(account),
            categories = emptyList(),
            requestedRange = FinanceAnalyticsRange.custom(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)),
            zone = ZoneOffset.UTC,
            clock = FIXED_CLOCK,
        ).currencies.single()

        assertEquals(900, report.incomeMinor)
        assertEquals(500, report.expenseMinor)
        assertEquals(2, report.transactionCount)
        assertEquals(OUT_OF_BUDGET_CATEGORY_ID, report.expensesByCategory.single().categoryId)
        assertEquals(OUT_OF_BUDGET_CATEGORY_NAME, report.expensesByCategory.single().categoryName)
    }

    @Test fun allTimeMaterializesActualDatesAndCategoryOrderIsDeterministic() {
        val account = AccountEntity(1, "Cash", "RUB")
        val alpha = CategoryEntity(2, "A", CategoryKind.EXPENSE, emoji = "🍎")
        val beta = CategoryEntity(3, "B", CategoryKind.EXPENSE, emoji = "🍌")
        val report = FinanceAnalyticsCalculator.calculate(
            listOf(
                LedgerEntryEntity(2, EntryKind.EXPENSE, 50, 1, categoryId = beta.id, occurredAt = at("2025-12-31")),
                LedgerEntryEntity(1, EntryKind.EXPENSE, 50, 1, categoryId = alpha.id, occurredAt = at("2026-01-02")),
            ),
            listOf(account), listOf(beta, alpha), FinanceAnalyticsRange.preset(FinanceAnalyticsPreset.ALL_TIME),
            ZoneOffset.UTC, FIXED_CLOCK,
        )
        assertEquals(LocalDate.of(2025, 12, 31), report.effectiveStartInclusive)
        assertEquals(LocalDate.of(2026, 1, 2), report.effectiveEndInclusive)
        assertEquals(listOf("A", "B"), report.currencies.single().expensesByCategory.map { it.categoryName })
        assertEquals("A", report.currencies.single().topExpenseCategory?.categoryName)
    }

    @Test fun csvEscapesFieldsAndJsonIsValidAndStable() {
        val category = FinanceCategoryExpense(7, "Food, \"home\"", "🍔", 125, 2)
        val report = FinanceAnalyticsReport(
            FinanceAnalyticsRange.currentMonth(LocalDate.of(2026, 9, 10)),
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 123,
            listOf(FinanceCurrencyAnalytics("RUB", 500, 125, 375, 3, 4, category, listOf(category))),
        )
        val csv = FinanceReportExporter.export(report, FinanceReportExporter.Format.CSV)
        assertTrue(csv.contains("\"Food, \"\"home\"\"\""))
        assertTrue(csv.endsWith("\r\n"))
        val json = FinanceReportExporter.export(report, FinanceReportExporter.Format.JSON)
        assertEquals(Json.parseToJsonElement(json), Json.parseToJsonElement(FinanceReportExporter.export(report, FinanceReportExporter.Format.JSON)))
        assertTrue(json.contains("\"categoryName\": \"Food, \\\"home\\\"\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun customRangeRejectsReversedDates() {
        FinanceAnalyticsRange.custom(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 31))
    }

    @Test(expected = IllegalArgumentException::class)
    fun exporterRejectsReportsOverBoundedOutputSize() {
        val huge = FinanceCategoryExpense(1, "x".repeat(1_100_000), "🍔", 1, 1)
        val report = FinanceAnalyticsReport(
            FinanceAnalyticsRange.currentMonth(LocalDate.of(2026, 9, 10)),
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 123,
            listOf(FinanceCurrencyAnalytics("RUB", 0, 1, -1, 1, 0, huge, listOf(huge))),
        )
        FinanceReportExporter.export(report, FinanceReportExporter.Format.JSON)
    }

    @Test(expected = IllegalArgumentException::class)
    fun exporterRejectsConservativelyOversizedReportBeforeRendering() {
        val categories = (1L..1_024L).map {
            FinanceCategoryExpense(it, "x".repeat(300), "🍔", 1, 1)
        }
        val report = FinanceAnalyticsReport(
            FinanceAnalyticsRange.currentMonth(LocalDate.of(2026, 9, 10)),
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 123,
            listOf(FinanceCurrencyAnalytics("RUB", 0, 1_024, -1_024, 1_024, 34, categories.first(), categories)),
        )
        FinanceReportExporter.export(report, FinanceReportExporter.Format.CSV)
    }

    @Test(expected = IllegalArgumentException::class)
    fun exporterPreflightAccountsForJsonControlCharacterExpansion() {
        val categories = (1L..1_024L).map {
            FinanceCategoryExpense(it, "\u0000".repeat(100), "🍔", 1, 1)
        }
        val report = FinanceAnalyticsReport(
            FinanceAnalyticsRange.currentMonth(LocalDate.of(2026, 9, 10)),
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 123,
            listOf(FinanceCurrencyAnalytics("RUB", 0, 1_024, -1_024, 1_024, 34, categories.first(), categories)),
        )
        FinanceReportExporter.export(report, FinanceReportExporter.Format.JSON)
    }

    @Test(expected = IllegalArgumentException::class)
    fun exporterPreflightAccountsForCurrencyRepeatedOnEveryCsvRow() {
        val categories = (1L..1_024L).map {
            FinanceCategoryExpense(it, "x", "🍔", 1, 1)
        }
        val report = FinanceAnalyticsReport(
            FinanceAnalyticsRange.currentMonth(LocalDate.of(2026, 9, 10)),
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 123,
            listOf(FinanceCurrencyAnalytics("R".repeat(4_096), 0, 1_024, -1_024, 1_024, 34, categories.first(), categories)),
        )
        FinanceReportExporter.export(report, FinanceReportExporter.Format.CSV)
    }

    private fun assertRange(range: FinanceAnalyticsRange, start: String, end: String) {
        assertEquals(LocalDate.parse(start), range.startInclusive)
        assertEquals(LocalDate.parse(end), range.endInclusive)
    }

    private fun at(date: String) = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC)
    }
}
