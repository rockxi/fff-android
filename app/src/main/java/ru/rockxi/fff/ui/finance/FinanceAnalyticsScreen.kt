package ru.rockxi.fff.ui.finance

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import ru.rockxi.fff.data.finance.*
import ru.rockxi.fff.ui.components.FffInputKind
import ru.rockxi.fff.ui.components.FffModal
import ru.rockxi.fff.ui.components.FffTextInput
import ru.rockxi.fff.ui.theme.*
import java.io.File
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Currency
import java.util.Locale
import kotlin.math.roundToInt

internal data class AnalyticsChartSegment(
    val categoryId: Long,
    val label: String,
    val emoji: String,
    val amountMinor: Long,
    val fraction: Float,
    val colorIndex: Int,
)

internal fun analyticsChartSegments(categories: List<FinanceCategoryExpense>): List<AnalyticsChartSegment> {
    val positive = categories.filter { it.amountMinor > 0 }
    val total = positive.fold(0.0) { sum, category -> sum + category.amountMinor.toDouble() }
    if (total <= 0.0) return emptyList()
    return positive.map { category ->
        AnalyticsChartSegment(
            category.categoryId,
            category.categoryName,
            category.emoji,
            category.amountMinor,
            (category.amountMinor / total).toFloat(),
            Math.floorMod(category.categoryId.hashCode(), ANALYTICS_COLORS.size),
        )
    }
}

private val strictAnalyticsDateFormatter = DateTimeFormatterBuilder()
    .appendPattern("dd.MM.uuuu")
    .toFormatter(Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)

internal fun parseAnalyticsDate(value: String): LocalDate? = try {
    LocalDate.parse(value.trim(), strictAnalyticsDateFormatter)
} catch (_: DateTimeParseException) {
    null
}

@Composable
internal fun FinanceAnalyticsDashboard(
    state: FinanceUiState,
    onPreset: (FinanceAnalyticsPreset) -> Unit,
    onCustom: (LocalDate, LocalDate) -> Boolean,
    onExport: (FinanceReportExporter.Format) -> Unit,
) {
    var showCustomRange by remember { mutableStateOf(false) }
    val report = state.analyticsReport
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 92.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "analytics:range") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Период", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    presetButtons.forEach { (preset, title) ->
                        FilterChip(
                            selected = state.analyticsRange.preset == preset,
                            onClick = { onPreset(preset) },
                            label = { Text(title, maxLines = 1) },
                        )
                    }
                    FilterChip(
                        selected = state.analyticsRange.preset == FinanceAnalyticsPreset.CUSTOM,
                        onClick = { showCustomRange = true },
                        leadingIcon = { Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(18.dp)) },
                        label = { Text("Даты") },
                    )
                }
                Text(rangeLabel(report ?: FinanceAnalyticsReport(state.analyticsRange, state.analyticsRange.startInclusive, state.analyticsRange.endInclusive, 0, emptyList())), color = FffMuted, fontSize = 12.sp)
            }
        }
        if (state.analyticsLoading) item(key = "analytics:loading") {
            Row(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = FffMint)
            }
        } else if (report == null || report.currencies.isEmpty()) item(key = "analytics:empty") {
            AnalyticsEmptyState()
        } else {
            if (report.currencies.size > 1) item(key = "analytics:multi-currency") {
                Text(
                    "Валюты показаны отдельно — суммы не складываются по разным валютам.",
                    color = FffMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().background(FffSurface, RoundedCornerShape(14.dp)).padding(14.dp),
                )
            }
            report.currencies.forEach { currency ->
                item(key = "analytics:title:${currency.currency}") {
                    Text(currency.currency, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                item(key = "analytics:metrics:${currency.currency}") { AnalyticsMetrics(currency) }
                item(key = "analytics:chart:${currency.currency}") { ExpenseDonut(currency) }
            }
            item(key = "analytics:note") {
                Text("Переводы между счетами не считаются доходами или расходами.", color = FffMuted, fontSize = 12.sp)
            }
        }
        item(key = "analytics:exports") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Выгрузить отчёт", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onExport(FinanceReportExporter.Format.CSV) },
                        enabled = !state.analyticsLoading && !state.analyticsExportBusy && report != null,
                        modifier = Modifier.weight(1f),
                    ) { Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(5.dp)); Text("CSV") }
                    Button(
                        onClick = { onExport(FinanceReportExporter.Format.JSON) },
                        enabled = !state.analyticsLoading && !state.analyticsExportBusy && report != null,
                        modifier = Modifier.weight(1f),
                    ) { Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(5.dp)); Text("JSON") }
                }
            }
        }
    }
    if (showCustomRange) CustomAnalyticsRangeDialog(
        initialStart = state.analyticsRange.startInclusive ?: LocalDate.now().withDayOfMonth(1),
        initialEnd = state.analyticsRange.endInclusive ?: LocalDate.now(),
        onDismiss = { showCustomRange = false },
        onApply = { start, end -> if (onCustom(start, end)) showCustomRange = false },
    )
}

@Composable
private fun AnalyticsMetrics(value: FinanceCurrencyAnalytics) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnalyticsMetric("Расходы", analyticsMoney(value.expenseMinor, value.currency), Color(0xFFFF7C9B), Modifier.weight(1f))
            AnalyticsMetric("Доходы", analyticsMoney(value.incomeMinor, value.currency), FffMint, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnalyticsMetric("Поток", analyticsMoney(value.netFlowMinor, value.currency), FffViolet, Modifier.weight(1f))
            AnalyticsMetric("В среднем / день", analyticsMoney(value.dailyAverageExpenseMinor, value.currency), Color(0xFFFFC76B), Modifier.weight(1f))
        }
        val top = value.topExpenseCategory
        Text(
            "${value.transactionCount} операций" + if (top == null) "" else " · Лидер: ${top.emoji} ${top.categoryName}",
            color = FffMuted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun AnalyticsMetric(label: String, value: String, accent: Color, modifier: Modifier) = Column(
    modifier.background(accent.copy(alpha = .08f), RoundedCornerShape(15.dp)).border(1.dp, accent.copy(alpha = .2f), RoundedCornerShape(15.dp)).padding(13.dp),
) {
    Text(label.uppercase(), color = accent, fontFamily = FontFamily.Monospace, fontSize = 8.sp, maxLines = 1)
    Text(value, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ExpenseDonut(value: FinanceCurrencyAnalytics) {
    val segments = remember(value.expensesByCategory) { analyticsChartSegments(value.expensesByCategory) }
    Column(
        Modifier.fillMaxWidth().background(FffSurface, RoundedCornerShape(18.dp)).border(1.dp, FffLine, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Text("Расходы по категориям", fontWeight = FontWeight.Bold)
        if (segments.isEmpty()) Text("За этот период расходов нет", color = FffMuted, fontSize = 13.sp)
        else {
            Box(Modifier.fillMaxWidth().height(210.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(184.dp)) {
                    var start = -90f
                    segments.forEach { segment ->
                        val sweep = segment.fraction * 360f
                        drawArc(ANALYTICS_COLORS[segment.colorIndex], start + 1.2f, (sweep - 2.4f).coerceAtLeast(.6f), false, style = Stroke(width = 34.dp.toPx(), cap = StrokeCap.Butt))
                        start += sweep
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("РАСХОДЫ", color = FffMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Text(analyticsMoney(value.expenseMinor, value.currency), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            }
            Column(
                Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                segments.forEach { segment ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(ANALYTICS_COLORS[segment.colorIndex], CircleShape))
                        Text("${segment.emoji} ${segment.label}", Modifier.padding(start = 9.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                        Text("${(segment.fraction * 100).roundToInt()}%", color = FffMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
                        Text(analyticsMoney(segment.amountMinor, value.currency), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsEmptyState() = Column(
    Modifier.fillMaxWidth().background(FffSurface, RoundedCornerShape(18.dp)).border(1.dp, FffLine, RoundedCornerShape(18.dp)).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
) {
    Text("Пока нечего анализировать", fontWeight = FontWeight.Bold, fontSize = 18.sp)
    Text("Добавьте доход или расход в выбранном периоде. Переводы здесь не учитываются.", color = FffMuted, fontSize = 13.sp, lineHeight = 19.sp)
}

@Composable
private fun CustomAnalyticsRangeDialog(initialStart: LocalDate, initialEnd: LocalDate, onDismiss: () -> Unit, onApply: (LocalDate, LocalDate) -> Unit) {
    val formatter = remember { DateTimeFormatter.ofPattern("dd.MM.uuuu") }
    var startText by remember(initialStart) { mutableStateOf(initialStart.format(formatter)) }
    var endText by remember(initialEnd) { mutableStateOf(initialEnd.format(formatter)) }
    var submitted by remember { mutableStateOf(false) }
    val start = parseAnalyticsDate(startText)
    val end = parseAnalyticsDate(endText)
    val rangeError = if (submitted && start != null && end != null && start.isAfter(end)) "Начальная дата позже конечной" else null
    FffModal(
        title = "Произвольный период",
        onDismiss = onDismiss,
        confirmText = "Показать",
        onConfirm = { submitted = true; if (start != null && end != null && !start.isAfter(end)) onApply(start, end) },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FffTextInput("Начало", startText, { startText = it }, kind = FffInputKind.TEXT, supportingText = "ДД.ММ.ГГГГ", error = if (submitted && start == null) "Введите дату в формате ДД.ММ.ГГГГ" else null)
            FffTextInput("Конец", endText, { endText = it }, kind = FffInputKind.TEXT, supportingText = "ДД.ММ.ГГГГ", error = if (submitted && end == null) "Введите дату в формате ДД.ММ.ГГГГ" else rangeError)
        }
    }
}

internal fun shareFinanceReport(context: Context, format: FinanceReportExporter.Format, document: String) {
    val extension = format.name.lowercase(Locale.ROOT)
    val mime = if (format == FinanceReportExporter.Format.CSV) "text/csv" else "application/json"
    val directory = File(context.cacheDir, "finance-reports").apply { mkdirs() }
    directory.listFiles()?.forEach { if (it.isFile) it.delete() }
    val file = File(directory, "fff-finance-report.$extension")
    file.writeText(document, Charsets.UTF_8)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Поделиться отчётом"))
}

private fun rangeLabel(report: FinanceAnalyticsReport): String {
    val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    val start = report.effectiveStartInclusive?.format(formatter)
    val end = report.effectiveEndInclusive?.format(formatter)
    return if (start == null || end == null) "Всё время · операций пока нет" else "$start — $end"
}

private fun analyticsMoney(minor: Long, currencyCode: String): String = runCatching {
    NumberFormat.getCurrencyInstance(Locale("ru", "RU")).apply { currency = Currency.getInstance(currencyCode) }.format(BigDecimal.valueOf(minor, 2))
}.getOrElse { "%.2f %s".format(minor / 100.0, currencyCode) }

private val presetButtons = listOf(
    FinanceAnalyticsPreset.MONTH to "Месяц",
    FinanceAnalyticsPreset.QUARTER to "Квартал",
    FinanceAnalyticsPreset.HALF_YEAR to "Полгода",
    FinanceAnalyticsPreset.YEAR to "Год",
    FinanceAnalyticsPreset.ALL_TIME to "Всё время",
)

private val ANALYTICS_COLORS = listOf(
    FffMint, Color(0xFFFF7C9B), FffViolet, Color(0xFFFFC76B), Color(0xFF59C7FF),
    Color(0xFF7FE38E), Color(0xFFFF936B), Color(0xFFC69CFF), Color(0xFF5EEAD4), Color(0xFFFFD166),
)
