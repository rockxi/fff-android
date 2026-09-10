package ru.rockxi.fff.data.finance

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class FinanceAnalyticsRepositoryTest {
    @Test fun omittedRangeUsesSuppliedClockInSuppliedZoneAtMonthBoundary() = runBlocking {
        val database = FinanceDatabase.inMemory(ApplicationProvider.getApplicationContext())
        try {
            val repository = FinanceRepository(database)
            val zone = ZoneId.of("America/Los_Angeles")
            val clock = Clock.fixed(Instant.parse("2026-03-01T00:30:00Z"), ZoneId.of("UTC"))
            val account = repository.createAccount("Cash", "RUB")
            val category = repository.createCategory("Food", CategoryKind.EXPENSE)
            repository.addExpense(account, category, 100, occurredAt = Instant.parse("2026-02-28T20:00:00Z").toEpochMilli())
            repository.addExpense(account, category, 200, occurredAt = Instant.parse("2026-03-01T20:00:00Z").toEpochMilli())

            val report = repository.analytics(zone = zone, clock = clock)

            assertEquals(LocalDate.of(2026, 2, 1), report.range.startInclusive)
            assertEquals(LocalDate.of(2026, 2, 28), report.range.endInclusive)
            assertEquals(100, report.currencies.single().expenseMinor)
        } finally {
            database.close()
        }
    }
}
