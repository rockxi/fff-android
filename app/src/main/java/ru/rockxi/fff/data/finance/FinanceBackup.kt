package ru.rockxi.fff.data.finance

import kotlinx.serialization.json.*

data class FinanceBackup(val formatVersion: Int = 1, val exportedAt: Long, val accounts: List<BackupAccount>, val budgets: List<BackupBudget>, val allocations: List<BackupAllocation>, val categories: List<BackupCategory>, val entries: List<BackupEntry>)
data class BackupAccount(val id: Long, val name: String, val currency: String, val balanceMinor: Long, val archived: Boolean, val createdAt: Long)
data class BackupBudget(val id: Long, val name: String, val currency: String, val archived: Boolean)
data class BackupAllocation(val budgetId: Long, val month: String, val amountMinor: Long)
data class BackupCategory(val id: Long, val name: String, val kind: String, val archived: Boolean, val createdAt: Long, val budgetId: Long, val emoji: String)
data class BackupEntry(val id: Long, val kind: String, val amountMinor: Long, val accountId: Long, val transferAccountId: Long?, val categoryId: Long?, val note: String, val occurredAt: Long)

/** Explicit codec is the versioned, stable repository boundary for user-selected backup files. */
internal object FinanceBackupCodec {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = false }

    fun encode(value: FinanceBackup): String = json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("formatVersion", value.formatVersion); put("exportedAt", value.exportedAt)
        put("accounts", buildJsonArray { value.accounts.forEach { v -> add(obj("id" to v.id, "name" to v.name, "currency" to v.currency, "balanceMinor" to v.balanceMinor, "archived" to v.archived, "createdAt" to v.createdAt)) } })
        put("budgets", buildJsonArray { value.budgets.forEach { v -> add(obj("id" to v.id, "name" to v.name, "currency" to v.currency, "archived" to v.archived)) } })
        put("allocations", buildJsonArray { value.allocations.forEach { v -> add(obj("budgetId" to v.budgetId, "month" to v.month, "amountMinor" to v.amountMinor)) } })
        put("categories", buildJsonArray { value.categories.forEach { v -> add(obj("id" to v.id, "name" to v.name, "kind" to v.kind, "archived" to v.archived, "createdAt" to v.createdAt, "budgetId" to v.budgetId, "emoji" to v.emoji)) } })
        put("entries", buildJsonArray { value.entries.forEach { v -> add(obj("id" to v.id, "kind" to v.kind, "amountMinor" to v.amountMinor, "accountId" to v.accountId, "transferAccountId" to v.transferAccountId, "categoryId" to v.categoryId, "note" to v.note, "occurredAt" to v.occurredAt)) } })
    })

    fun decode(document: String): FinanceBackup {
        val root = json.parseToJsonElement(document).jsonObject.strict("formatVersion", "exportedAt", "accounts", "budgets", "allocations", "categories", "entries")
        return FinanceBackup(
            root.int("formatVersion"), root.long("exportedAt"),
            root.array("accounts").map { it.jsonObject.strict("id", "name", "currency", "balanceMinor", "archived", "createdAt").let { o -> BackupAccount(o.long("id"), o.text("name"), o.text("currency"), o.long("balanceMinor"), o.bool("archived"), o.long("createdAt")) } },
            root.array("budgets").map { it.jsonObject.strict("id", "name", "currency", "archived").let { o -> BackupBudget(o.long("id"), o.text("name"), o.text("currency"), o.bool("archived")) } },
            root.array("allocations").map { it.jsonObject.strict("budgetId", "month", "amountMinor").let { o -> BackupAllocation(o.long("budgetId"), o.text("month"), o.long("amountMinor")) } },
            root.array("categories").map { it.jsonObject.strict("id", "name", "kind", "archived", "createdAt", "budgetId", "emoji").let { o -> BackupCategory(o.long("id"), o.text("name"), o.text("kind"), o.bool("archived"), o.long("createdAt"), o.long("budgetId"), o.text("emoji")) } },
            root.array("entries").map { it.jsonObject.strict("id", "kind", "amountMinor", "accountId", "transferAccountId", "categoryId", "note", "occurredAt").let { o -> BackupEntry(o.long("id"), o.text("kind"), o.long("amountMinor"), o.long("accountId"), o.nullableLong("transferAccountId"), o.nullableLong("categoryId"), o.text("note"), o.long("occurredAt")) } },
        )
    }

    private fun obj(vararg fields: Pair<String, Any?>) = buildJsonObject { fields.forEach { (key, value) -> put(key, when (value) { null -> JsonNull; is String -> JsonPrimitive(value); is Boolean -> JsonPrimitive(value); is Number -> JsonPrimitive(value); else -> error("Unsupported backup value") }) } }
    private fun JsonObject.strict(vararg keys: String) = apply { require(this.keys == keys.toSet()) { "Некорректные поля резервной копии" } }
    private fun JsonObject.required(key: String) = requireNotNull(this[key]) { "Отсутствует поле $key" }
    private fun JsonObject.text(key: String) = required(key).jsonPrimitive.also {
        require(it.isString) { "Поле $key должно быть строкой" }
    }.content
    private fun JsonObject.long(key: String) = required(key).jsonPrimitive.also {
        require(!it.isString) { "Поле $key должно быть числом" }
    }.long
    private fun JsonObject.int(key: String) = long(key).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    private fun JsonObject.bool(key: String) = required(key).jsonPrimitive.also {
        require(!it.isString && (it.content == "true" || it.content == "false")) { "Поле $key должно быть boolean" }
    }.boolean
    private fun JsonObject.array(key: String) = required(key).jsonArray
    private fun JsonObject.nullableLong(key: String) = required(key).let {
        if (it is JsonNull) null else it.jsonPrimitive.also { primitive ->
            require(!primitive.isString) { "Поле $key должно быть числом или null" }
        }.long
    }
}
