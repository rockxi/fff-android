package ru.rockxi.fff.data.finance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Read-only, explicitly enabled bridge from the device ledger to Harness. */
internal interface HarnessFinanceSource {
    suspend fun context(range: FinanceAnalyticsRange): FinanceHarnessContext
    suspend fun export(range: FinanceAnalyticsRange, format: FinanceReportExporter.Format): String
}

internal class RepositoryHarnessFinanceSource(
    private val repository: FinanceRepository,
) : HarnessFinanceSource {
    override suspend fun context(range: FinanceAnalyticsRange) =
        FinanceHarnessContextCodec.fromReport(repository.analytics(range))

    override suspend fun export(range: FinanceAnalyticsRange, format: FinanceReportExporter.Format) =
        repository.exportAnalytics(range, format)
}

internal data class FinanceHarnessContext(
    val json: String,
    val rangeLabel: String,
)

/**
 * Serializes aggregates only. Account/category identifiers, notes, operations,
 * backups and credentials never cross this boundary.
 */
internal object FinanceHarnessContextCodec {
    fun fromReport(report: FinanceAnalyticsReport): FinanceHarnessContext {
        validateRange(report)
        val categories = report.currencies.sumOf { it.expensesByCategory.size }
        require(report.currencies.size <= MAX_CURRENCIES && categories <= MAX_CATEGORIES) {
            "Слишком много данных для контекста ассистента"
        }
        val value = buildJsonObject {
            put("version", JsonPrimitive(1))
            put("preset", JsonPrimitive(report.range.preset.name.lowercase()))
            report.effectiveStartInclusive?.let { put("startDate", JsonPrimitive(it.toString())) }
            report.effectiveEndInclusive?.let { put("endDate", JsonPrimitive(it.toString())) }
            put("generatedAtEpochMillis", JsonPrimitive(report.generatedAtEpochMillis))
            put("currencies", buildJsonArray {
                report.currencies.forEach { currency ->
                    add(buildJsonObject {
                        put("currency", JsonPrimitive(currency.currency))
                        put("incomeMinor", JsonPrimitive(currency.incomeMinor))
                        put("expenseMinor", JsonPrimitive(currency.expenseMinor))
                        put("netFlowMinor", JsonPrimitive(currency.netFlowMinor))
                        put("transactionCount", JsonPrimitive(currency.transactionCount))
                        put("dailyAverageExpenseMinor", JsonPrimitive(currency.dailyAverageExpenseMinor))
                        put("expensesByCategory", buildJsonArray {
                            currency.expensesByCategory.forEach { category ->
                                add(buildJsonObject {
                                    put("name", JsonPrimitive(category.categoryName.take(MAX_FIELD_CHARS)))
                                    put("emoji", JsonPrimitive(category.emoji.take(MAX_FIELD_CHARS)))
                                    put("amountMinor", JsonPrimitive(category.amountMinor))
                                    put("transactionCount", JsonPrimitive(category.transactionCount))
                                })
                            }
                        })
                    })
                }
            })
        }.toString()
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_CONTEXT_BYTES) {
            "Контекст Finance слишком большой"
        }
        return FinanceHarnessContext(value, rangeLabel(report))
    }

    /** Restores one inseparable pending-request binding from private prefs. */
    fun restore(json: String, range: FinanceAnalyticsRange): FinanceHarnessContext? = runCatching {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_CONTEXT_BYTES)
        validateRequestedRange(range)
        val root = Json.parseToJsonElement(json).jsonObject
        require(root.keys.all {
            it in setOf("version", "preset", "startDate", "endDate", "generatedAtEpochMillis", "currencies")
        })
        val version = root["version"]?.jsonPrimitive ?: error("Missing version")
        require(!version.isString && version.content == "1")
        val preset = root["preset"]?.jsonPrimitive ?: error("Missing preset")
        require(preset.isString && preset.content == range.preset.name.lowercase())
        val generatedAt = root["generatedAtEpochMillis"]?.jsonPrimitive ?: error("Missing generatedAtEpochMillis")
        require(!generatedAt.isString && generatedAt.longOrNull != null)
        val contextStart = root["startDate"]?.jsonPrimitive?.contentOrNull?.let(LocalDate::parse)
        val contextEnd = root["endDate"]?.jsonPrimitive?.contentOrNull?.let(LocalDate::parse)
        require((contextStart == null) == (contextEnd == null))
        if (contextStart != null && contextEnd != null) {
            require(!contextStart.isAfter(contextEnd))
            require(ChronoUnit.DAYS.between(contextStart, contextEnd) <= MAX_RANGE_DAYS)
        }
        if (range.preset != FinanceAnalyticsPreset.ALL_TIME) {
            require(contextStart == range.startInclusive && contextEnd == range.endInclusive)
        }
        if (range.preset != FinanceAnalyticsPreset.ALL_TIME && range.preset != FinanceAnalyticsPreset.CUSTOM) {
            require(range == FinanceAnalyticsRange.preset(range.preset, requireNotNull(range.startInclusive)))
        }
        validateAggregateShape(root)
        FinanceHarnessContext(json, contextRangeLabel(contextStart, contextEnd))
    }.getOrNull()

    private fun validateAggregateShape(root: kotlinx.serialization.json.JsonObject) {
        val currencies = root["currencies"]?.jsonArray ?: error("Missing currencies")
        require(currencies.size <= MAX_CURRENCIES)
        var categoryCount = 0
        currencies.forEach { element ->
            val currency = element.jsonObject
            require(currency.keys == setOf(
                "currency", "incomeMinor", "expenseMinor", "netFlowMinor", "transactionCount",
                "dailyAverageExpenseMinor", "expensesByCategory",
            ))
            val currencyCode = currency["currency"]?.jsonPrimitive ?: error("Missing currency")
            require(currencyCode.isString && currencyCode.content.length in 1..16)
            listOf("incomeMinor", "expenseMinor", "netFlowMinor", "transactionCount", "dailyAverageExpenseMinor").forEach {
                val number = currency[it]?.jsonPrimitive ?: error("Missing numeric field")
                require(!number.isString && number.longOrNull != null)
            }
            val categories = currency["expensesByCategory"]?.jsonArray ?: error("Missing categories")
            categoryCount += categories.size
            require(categoryCount <= MAX_CATEGORIES)
            categories.forEach { categoryElement ->
                val category = categoryElement.jsonObject
                require(category.keys == setOf("name", "emoji", "amountMinor", "transactionCount"))
                val name = category["name"]?.jsonPrimitive ?: error("Missing category name")
                val emoji = category["emoji"]?.jsonPrimitive ?: error("Missing category emoji")
                require(name.isString && name.content.length in 1..MAX_FIELD_CHARS)
                require(emoji.isString && emoji.content.length in 1..MAX_FIELD_CHARS)
                listOf("amountMinor", "transactionCount").forEach {
                    val number = category[it]?.jsonPrimitive ?: error("Missing category numeric field")
                    require(!number.isString && number.longOrNull != null)
                }
            }
        }
    }

    private fun validateRequestedRange(range: FinanceAnalyticsRange) {
        val start = range.startInclusive
        val end = range.endInclusive
        require((start == null) == (end == null))
        require(range.preset == FinanceAnalyticsPreset.ALL_TIME || start != null)
        require(range.preset != FinanceAnalyticsPreset.ALL_TIME || start == null)
        if (start != null && end != null) {
            require(!start.isAfter(end) && ChronoUnit.DAYS.between(start, end) <= MAX_RANGE_DAYS)
        }
    }

    private fun contextRangeLabel(start: LocalDate?, end: LocalDate?) =
        if (start == null || end == null) "Нет операций" else "${format(start)} — ${format(end)}"

    private fun validateRange(report: FinanceAnalyticsReport) {
        val start = report.effectiveStartInclusive
        val end = report.effectiveEndInclusive
        require((start == null) == (end == null)) { "Некорректный диапазон отчёта" }
        if (start != null && end != null) {
            require(!start.isAfter(end) && ChronoUnit.DAYS.between(start, end) <= MAX_RANGE_DAYS) {
                "Период для ассистента слишком большой"
            }
        }
    }

    private fun rangeLabel(report: FinanceAnalyticsReport): String {
        val start = report.effectiveStartInclusive
        val end = report.effectiveEndInclusive
        return contextRangeLabel(start, end)
    }

    private fun format(value: LocalDate) = "%02d.%02d.%04d".format(value.dayOfMonth, value.monthValue, value.year)

    // Leaves room for the maximum 20 KiB message and JSON envelope under the
    // existing 32 KiB Harness/proxy request limit.
    const val MAX_CONTEXT_BYTES = 8 * 1024
    const val MAX_RANGE_DAYS = 36_525L
    private const val MAX_CURRENCIES = 16
    private const val MAX_CATEGORIES = 48
    private const val MAX_FIELD_CHARS = 128
}
