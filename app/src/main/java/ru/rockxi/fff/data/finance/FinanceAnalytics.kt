package ru.rockxi.fff.data.finance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class FinanceAnalyticsPreset { MONTH, QUARTER, HALF_YEAR, YEAR, ALL_TIME, CUSTOM }

data class FinanceAnalyticsRange(
    val startInclusive: LocalDate?,
    val endInclusive: LocalDate?,
    val preset: FinanceAnalyticsPreset,
) {
    init {
        require((startInclusive == null) == (endInclusive == null)) { "Обе даты диапазона должны быть заданы" }
        if (startInclusive != null) require(!startInclusive.isAfter(endInclusive)) { "Начальная дата позже конечной" }
        require(preset == FinanceAnalyticsPreset.ALL_TIME || startInclusive != null) { "Диапазон без дат допустим только для всего времени" }
        require(preset != FinanceAnalyticsPreset.CUSTOM || startInclusive != null) { "Для произвольного периода нужны даты" }
    }

    companion object {
        fun currentMonth(today: LocalDate = LocalDate.now()) = preset(FinanceAnalyticsPreset.MONTH, today)

        fun custom(startInclusive: LocalDate, endInclusive: LocalDate) =
            FinanceAnalyticsRange(startInclusive, endInclusive, FinanceAnalyticsPreset.CUSTOM)

        fun preset(value: FinanceAnalyticsPreset, today: LocalDate = LocalDate.now()): FinanceAnalyticsRange {
            require(value != FinanceAnalyticsPreset.CUSTOM) { "Для произвольного периода укажите даты" }
            if (value == FinanceAnalyticsPreset.ALL_TIME) return FinanceAnalyticsRange(null, null, value)
            val start = when (value) {
                FinanceAnalyticsPreset.MONTH -> today.withDayOfMonth(1)
                FinanceAnalyticsPreset.QUARTER -> today.withMonth(((today.monthValue - 1) / 3) * 3 + 1).withDayOfMonth(1)
                FinanceAnalyticsPreset.HALF_YEAR -> today.withMonth(if (today.monthValue <= 6) 1 else 7).withDayOfMonth(1)
                FinanceAnalyticsPreset.YEAR -> today.withDayOfYear(1)
                else -> error("Unsupported preset")
            }
            val end = when (value) {
                FinanceAnalyticsPreset.MONTH -> start.plusMonths(1).minusDays(1)
                FinanceAnalyticsPreset.QUARTER -> start.plusMonths(3).minusDays(1)
                FinanceAnalyticsPreset.HALF_YEAR -> start.plusMonths(6).minusDays(1)
                FinanceAnalyticsPreset.YEAR -> start.plusYears(1).minusDays(1)
                else -> error("Unsupported preset")
            }
            return FinanceAnalyticsRange(start, end, value)
        }
    }
}

data class FinanceCategoryExpense(
    val categoryId: Long,
    val categoryName: String,
    val emoji: String,
    val amountMinor: Long,
    val transactionCount: Int,
)

internal const val OUT_OF_BUDGET_CATEGORY_ID = 0L
internal const val OUT_OF_BUDGET_CATEGORY_NAME = "Вне бюджета"
internal const val OUT_OF_BUDGET_CATEGORY_EMOJI = "🧾"

data class FinanceCurrencyAnalytics(
    val currency: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val netFlowMinor: Long,
    val transactionCount: Int,
    val dailyAverageExpenseMinor: Long,
    val topExpenseCategory: FinanceCategoryExpense?,
    val expensesByCategory: List<FinanceCategoryExpense>,
)

data class FinanceAnalyticsReport(
    val range: FinanceAnalyticsRange,
    val effectiveStartInclusive: LocalDate?,
    val effectiveEndInclusive: LocalDate?,
    val generatedAtEpochMillis: Long,
    val currencies: List<FinanceCurrencyAnalytics>,
)

internal object FinanceAnalyticsCalculator {
    fun calculate(
        entries: List<LedgerEntryEntity>,
        accounts: List<AccountEntity>,
        categories: List<CategoryEntity>,
        requestedRange: FinanceAnalyticsRange? = null,
        zone: ZoneId = ZoneId.systemDefault(),
        clock: Clock = Clock.system(zone),
    ): FinanceAnalyticsReport {
        val resolvedRange = requestedRange ?: FinanceAnalyticsRange.currentMonth(LocalDate.now(clock.withZone(zone)))
        val accountsById = accounts.associateBy { it.id }
        val categoriesById = categories.associateBy { it.id }
        val categorized = entries.asSequence()
            .filter { it.kind != EntryKind.TRANSFER }
            .mapNotNull { entry ->
                val account = accountsById[entry.accountId] ?: return@mapNotNull null
                val category = entry.categoryId?.let(categoriesById::get)
                if (entry.categoryId != null && category == null) return@mapNotNull null
                Triple(entry, account, category)
            }
            .toList()
        val dated = categorized.filter { (entry, _, _) ->
            val date = entry.occurredAt.toLocalDate(zone)
            (resolvedRange.startInclusive == null || !date.isBefore(resolvedRange.startInclusive)) &&
                (resolvedRange.endInclusive == null || !date.isAfter(resolvedRange.endInclusive))
        }
        val effectiveStart = resolvedRange.startInclusive ?: dated.minOfOrNull { it.first.occurredAt.toLocalDate(zone) }
        val effectiveEnd = resolvedRange.endInclusive ?: dated.maxOfOrNull { it.first.occurredAt.toLocalDate(zone) }
        val dayCount = if (effectiveStart == null || effectiveEnd == null) 0L else ChronoUnit.DAYS.between(effectiveStart, effectiveEnd) + 1

        val currencies = dated.groupBy { it.second.currency }.toSortedMap().map { (currency, values) ->
            val income = saturated(values.asSequence().filter { it.first.kind == EntryKind.INCOME }.map { it.first.amountMinor })
            val expenseValues = values.filter { it.first.kind == EntryKind.EXPENSE }
            val expense = saturated(expenseValues.asSequence().map { it.first.amountMinor })
            val categories = expenseValues.groupBy { it.third?.id }.map { (_, categoryValues) ->
                val category = categoryValues.first().third
                FinanceCategoryExpense(
                    categoryId = category?.id ?: OUT_OF_BUDGET_CATEGORY_ID,
                    categoryName = category?.name ?: OUT_OF_BUDGET_CATEGORY_NAME,
                    emoji = category?.emoji ?: OUT_OF_BUDGET_CATEGORY_EMOJI,
                    amountMinor = saturated(categoryValues.asSequence().map { it.first.amountMinor }),
                    transactionCount = categoryValues.size,
                )
            }.sortedWith(compareByDescending<FinanceCategoryExpense> { it.amountMinor }.thenBy { it.categoryName }.thenBy { it.categoryId })
            FinanceCurrencyAnalytics(
                currency = currency,
                incomeMinor = income,
                expenseMinor = expense,
                netFlowMinor = subtractSaturated(income, expense),
                transactionCount = values.size,
                dailyAverageExpenseMinor = if (dayCount == 0L) 0 else expense / dayCount,
                topExpenseCategory = categories.firstOrNull(),
                expensesByCategory = categories,
            )
        }
        return FinanceAnalyticsReport(resolvedRange, effectiveStart, effectiveEnd, clock.millis(), currencies)
    }

    private fun Long.toLocalDate(zone: ZoneId) = java.time.Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
    private fun saturated(values: Sequence<Long>) = values.fold(0L) { total, value ->
        if (value > 0 && total > Long.MAX_VALUE - value) Long.MAX_VALUE else total + value
    }
    private fun subtractSaturated(left: Long, right: Long): Long = try {
        Math.subtractExact(left, right)
    } catch (_: ArithmeticException) {
        if (left >= 0) Long.MAX_VALUE else Long.MIN_VALUE
    }
}

object FinanceReportExporter {
    enum class Format { CSV, JSON }

    fun export(report: FinanceAnalyticsReport, format: Format): String {
        validate(report)
        return when (format) {
            Format.CSV -> csv(report)
            Format.JSON -> json(report)
        }.also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_REPORT_BYTES) { "Отчёт слишком большой" } }
    }

    private fun csv(report: FinanceAnalyticsReport): String = buildString {
        append("start_date,end_date,currency,income_minor,expense_minor,net_flow_minor,transaction_count,daily_average_expense_minor,category_id,category_name,category_emoji,category_expense_minor,category_transaction_count\r\n")
        report.currencies.forEach { currency ->
            val categories = currency.expensesByCategory.ifEmpty { listOf(null) }
            categories.forEach { category ->
                append(csvCell(report.effectiveStartInclusive?.toString().orEmpty())).append(',')
                append(csvCell(report.effectiveEndInclusive?.toString().orEmpty())).append(',')
                append(csvCell(currency.currency)).append(',')
                append(currency.incomeMinor).append(',').append(currency.expenseMinor).append(',').append(currency.netFlowMinor).append(',')
                append(currency.transactionCount).append(',').append(currency.dailyAverageExpenseMinor).append(',')
                append(category?.categoryId ?: "").append(',').append(csvCell(category?.categoryName.orEmpty())).append(',')
                append(csvCell(category?.emoji.orEmpty())).append(',').append(category?.amountMinor ?: "").append(',')
                append(category?.transactionCount ?: "").append("\r\n")
            }
        }
    }

    private fun json(report: FinanceAnalyticsReport): String = Json { prettyPrint = true }.encodeToString(
        kotlinx.serialization.json.JsonElement.serializer(),
        buildJsonObject {
            put("preset", JsonPrimitive(report.range.preset.name))
            put("startDate", report.effectiveStartInclusive?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            put("endDate", report.effectiveEndInclusive?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            put("generatedAtEpochMillis", JsonPrimitive(report.generatedAtEpochMillis))
            put("currencies", buildJsonArray {
                report.currencies.forEach { currency -> add(buildJsonObject {
                    put("currency", JsonPrimitive(currency.currency))
                    put("incomeMinor", JsonPrimitive(currency.incomeMinor))
                    put("expenseMinor", JsonPrimitive(currency.expenseMinor))
                    put("netFlowMinor", JsonPrimitive(currency.netFlowMinor))
                    put("transactionCount", JsonPrimitive(currency.transactionCount))
                    put("dailyAverageExpenseMinor", JsonPrimitive(currency.dailyAverageExpenseMinor))
                    put("expensesByCategory", JsonArray(currency.expensesByCategory.map { category -> buildJsonObject {
                        put("categoryId", JsonPrimitive(category.categoryId))
                        put("categoryName", JsonPrimitive(category.categoryName))
                        put("emoji", JsonPrimitive(category.emoji))
                        put("amountMinor", JsonPrimitive(category.amountMinor))
                        put("transactionCount", JsonPrimitive(category.transactionCount))
                    } }))
                }) }
            })
        },
    )

    private fun csvCell(value: String): String = "\"${value.replace("\"", "\"\"")}\""
    private fun validate(report: FinanceAnalyticsReport) {
        require(report.currencies.size <= MAX_CURRENCIES) { "Слишком много валют в отчёте" }
        require(report.currencies.sumOf { it.expensesByCategory.size } <= MAX_CATEGORIES) { "Слишком много категорий в отчёте" }
        var estimatedBytes = BASE_EXPORT_BYTES + report.currencies.size * BYTES_PER_CURRENCY
        report.currencies.forEach { currency ->
            require(currency.currency.length <= MAX_FIELD_CHARS) { "Слишком длинный код валюты" }
            // CSV repeats the currency on every category row (and emits one
            // summary row when there are no categories), while JSON uses it
            // once. Budget for the larger representation before rendering.
            estimatedBytes += escapedBytesUpperBound(currency.currency) *
                maxOf(1, currency.expensesByCategory.size)
            currency.expensesByCategory.forEach { category ->
                require(category.categoryName.length <= MAX_FIELD_CHARS && category.emoji.length <= MAX_FIELD_CHARS) {
                    "Слишком длинное поле категории"
                }
                estimatedBytes += BYTES_PER_CATEGORY + escapedBytesUpperBound(category.categoryName) +
                    escapedBytesUpperBound(category.emoji)
                require(estimatedBytes <= MAX_REPORT_BYTES) { "Отчёт слишком большой" }
            }
        }
    }
    private fun escapedBytesUpperBound(value: String): Int {
        val bytes = value.toByteArray(Charsets.UTF_8).size
        // JSON may render one UTF-16 control character as six ASCII bytes
        // (for example NUL becomes "\\u0000"). Using six times the larger of
        // the UTF-8 and UTF-16 lengths also remains conservative for CSV quote
        // doubling and for supplementary Unicode code points.
        val units = maxOf(bytes, value.length)
        return if (units > Int.MAX_VALUE / 6) Int.MAX_VALUE else units * 6
    }
    private const val MAX_REPORT_BYTES = 1_048_576
    private const val MAX_CURRENCIES = 32
    private const val MAX_CATEGORIES = 1_024
    private const val MAX_FIELD_CHARS = 4_096
    private const val BASE_EXPORT_BYTES = 4_096
    private const val BYTES_PER_CURRENCY = 512
    private const val BYTES_PER_CATEGORY = 512
}
