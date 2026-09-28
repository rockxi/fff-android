package ru.rockxi.fff.data.calories

import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.rockxi.fff.data.harness.readBounded

/** Transient catalog values. Never store provider names or nutrition in Room. */
internal data class ExternalFoodSummary(val id: String, val name: String, val brand: String?, val fetchedAtSeconds: Long)
internal data class ExternalServing(
    val id: String,
    val description: String,
    val grams: Double?,
    val calories: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val amount: Double? = null,
    val unit: String? = null,
) {
    val measureUnit: String? get() = when {
        grams != null && grams > 0 -> "g"
        unit == "ml" && amount != null && amount > 0 -> "ml"
        else -> null
    }
    val measureAmount: Double? get() = when (measureUnit) {
        "g" -> grams
        "ml" -> amount
        else -> null
    }
}
internal data class ExternalFood(
    val id: String,
    val name: String,
    val brand: String?,
    val servings: List<ExternalServing>,
    val fetchedAtSeconds: Long,
    val source: String = "Perek",
    val sourceUrl: String? = null,
)

internal fun catalogContentFresh(fetchedAtSeconds: Long, nowSeconds: Long): Boolean =
    fetchedAtSeconds > 0 && nowSeconds >= fetchedAtSeconds && nowSeconds - fetchedAtSeconds < 86_400

/** Calculates a user's consumed totals; it must not be used to persist per-food API values. */
internal fun externalPortionNutrition(serving: ExternalServing, amountGramsMg: Long): NutritionTotals {
    require(amountGramsMg in 1..100_000_000) { "Некорректная масса порции" }
    val measure = serving.measureAmount
    require(measure != null && measure.isFinite() && measure > 0) { "Порция без г или мл" }
    val ratio = BigDecimal.valueOf(amountGramsMg)
        .divide(BigDecimal.valueOf(measure).multiply(BigDecimal(1000)), 12, RoundingMode.HALF_UP)
    fun rounded(value: Double, scale: Long): Long {
        require(value.isFinite() && value >= 0) { "Некорректная пищевая ценность" }
        return BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(scale)).multiply(ratio)
            .setScale(0, RoundingMode.HALF_UP).longValueExact()
    }
    return NutritionTotals(
        rounded(serving.calories, 1), rounded(serving.protein, 1000),
        rounded(serving.fat, 1000), rounded(serving.carbs, 1000),
    ).also {
        require(it.caloriesKcal <= 1_000_000 && it.proteinMg <= 100_000_000 &&
            it.fatMg <= 100_000_000 && it.carbMg <= 100_000_000) { "Порция слишком велика" }
    }
}

internal interface CalorieCatalogClient {
    suspend fun search(token: String, query: String): List<ExternalFoodSummary>
    suspend fun food(token: String, id: String): ExternalFood
    suspend fun barcode(token: String, gtin13: String): ExternalFood
}

internal object CalorieCatalogJson {
    private val json = Json { ignoreUnknownKeys = true }
    fun search(body: String): List<ExternalFoodSummary> {
        val root = json.parseToJsonElement(body).jsonObject
        require(root.str("source") == "Perek") { "Неизвестный источник продуктов" }
        val fetchedAt = root.long("fetchedAt")
        return (root["items"] as? JsonArray ?: error("Некорректный ответ поиска"))
            .take(50).map { item ->
                val obj = item.jsonObject
                ExternalFoodSummary(obj.str("id"), obj.str("name"), obj.optString("brand"), fetchedAt)
            }
    }

    fun food(body: String): ExternalFood {
        val obj = json.parseToJsonElement(body).jsonObject
        val source = obj.str("source")
        require(source == "Perek" || source == "FatSecret") { "Неизвестный источник продуктов" }
        val fetchedAt = obj.long("fetchedAt")
        val servings = (obj["servings"] as? JsonArray ?: error("Некорректные порции"))
            .take(100).mapNotNull { value ->
                val serving = value.jsonObject
                val grams = serving.num("grams")?.takeIf { it > 0.0 && it.isFinite() }
                val amount = serving.num("amount")?.takeIf { it > 0.0 && it.isFinite() }
                val unit = serving.optString("unit")
                if (grams == null && !(unit == "ml" && amount != null)) return@mapNotNull null
                ExternalServing(
                    serving.str("id"), serving.str("description"), grams,
                    serving.num("calories") ?: return@mapNotNull null,
                    serving.num("protein") ?: return@mapNotNull null,
                    serving.num("fat") ?: return@mapNotNull null,
                    serving.num("carbs") ?: return@mapNotNull null,
                    amount, unit,
                ).takeIf { listOf(it.calories, it.protein, it.fat, it.carbs).all { n -> n.isFinite() && n >= 0.0 } }
            }
        return ExternalFood(obj.str("id"), obj.str("name"), obj.optString("brand"), servings, fetchedAt, source, obj.optString("sourceUrl"))
    }

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: error("Некорректный ответ: $key")
    private fun JsonObject.optString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.takeIf { it > 0 }
        ?: error("Некорректное время ответа")
}

internal class HttpCalorieCatalogClient : CalorieCatalogClient {
    private val base = "https://fff.rockxi.ru/api/harness/calories"
    override suspend fun search(token: String, query: String): List<ExternalFoodSummary> {
        require(query.trim().length in 2..100) { "Введите от 2 до 100 символов" }
        return CalorieCatalogJson.search(get(token, "search?q=${encode(query.trim())}"))
    }
    override suspend fun food(token: String, id: String): ExternalFood {
        require(id.matches(Regex("(?:[1-9][0-9]{0,18}|p_[A-Za-z0-9_-]{2,220})"))) { "Некорректный ID продукта" }
        return CalorieCatalogJson.food(get(token, "foods/$id"))
    }
    override suspend fun barcode(token: String, gtin13: String): ExternalFood {
        require(gtin13.matches(Regex("[0-9]{13}"))) { "Некорректный штрихкод" }
        return CalorieCatalogJson.food(get(token, "barcodes/$gtin13"))
    }
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun get(token: String, path: String): String {
        require(token.isNotBlank()) { "Подключите FFF в разделе Harness" }
        val connection = (URL("$base/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 35_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-FFF-Catalog", "perek")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = input?.use { String(readBounded(it, connection.contentLengthLong, 256 * 1024), Charsets.UTF_8) }.orEmpty()
            if (status == 401) error("Подключите FFF в разделе Harness")
            if (status == 404) error("Продукт не найден")
            if (status == 429) error("Слишком много запросов. Попробуйте позже")
            if (status == 503) {
                val providerError = runCatching {
                    Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                if (providerError == "perek.us is not configured") error("Поиск продуктов пока не настроен на сервере")
                error("Каталог продуктов временно недоступен. Попробуйте позже")
            }
            if (status !in 200..299) error("Не удалось загрузить данные каталога ($status)")
            return body
        } finally {
            connection.disconnect()
        }
    }
}
