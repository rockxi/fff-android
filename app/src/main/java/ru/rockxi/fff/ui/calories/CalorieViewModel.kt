package ru.rockxi.fff.ui.calories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.math.BigDecimal
import java.math.RoundingMode
import java.lang.Math.addExact
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.rockxi.fff.data.calories.*

internal interface CalorieStore {
    suspend fun profile(): CalorieProfileEntity
    suspend fun updateProfile(calories: Int, proteinMg: Long, fatMg: Long, carbMg: Long)
    suspend fun foods(query: String = ""): List<FoodEntity>
    suspend fun createFood(name: String, calories: Int, proteinMg: Long, fatMg: Long, carbMg: Long): Long
    suspend fun updateFood(id: Long, name: String, calories: Int, proteinMg: Long, fatMg: Long, carbMg: Long)
    suspend fun deleteFood(id: Long)
    suspend fun entries(date: LocalDate): List<DiaryEntryEntity>
    suspend fun recentFoods(): List<RecentFood>
    fun calculate(food: FoodEntity, amountMg: Long): NutritionTotals
    suspend fun addFoodEntry(foodId: Long, date: LocalDate, meal: MealType, amountMg: Long): Long
    suspend fun addQuickEntry(name: String, date: LocalDate, meal: MealType, calories: Int, proteinMg: Long, fatMg: Long, carbMg: Long): Long
    suspend fun updateFoodEntry(id: Long, foodId: Long, date: LocalDate, meal: MealType, amountMg: Long)
    suspend fun updateQuickEntry(id: Long, name: String, date: LocalDate, meal: MealType, calories: Int, proteinMg: Long, fatMg: Long, carbMg: Long)
    suspend fun deleteEntry(id: Long)
    suspend fun externalEntries(date: LocalDate): List<ExternalDiaryEntryEntity> = emptyList()
    suspend fun externalDayTotal(date: LocalDate): NutritionTotals = NutritionTotals()
    suspend fun addExternalEntry(foodId: String, servingId: String, date: LocalDate, meal: MealType, amountMg: Long, nutrition: NutritionTotals, amountUnit: String = "g"): Long = error("Внешний каталог недоступен")
    suspend fun correctExternalDay(date: LocalDate, entryId: Long?, nutrition: NutritionTotals): Unit = error("Исправление дневника недоступно")
}

internal class RepositoryCalorieStore(private val repository: CalorieRepository) : CalorieStore {
    override suspend fun profile() = repository.profile()
    override suspend fun updateProfile(calories: Int, proteinMg: Long, fatMg: Long, carbMg: Long) = repository.updateProfile(calories, proteinMg, fatMg, carbMg)
    override suspend fun foods(query: String) = repository.foods(query)
    override suspend fun createFood(name: String, calories: Int, proteinMg: Long, fatMg: Long, carbMg: Long) = repository.createFood(name, calories, proteinMg, fatMg, carbMg)
    override suspend fun updateFood(id: Long, name: String, calories: Int, proteinMg: Long, fatMg: Long, carbMg: Long) = repository.updateFood(id, name, calories, proteinMg, fatMg, carbMg)
    override suspend fun deleteFood(id: Long) = repository.deleteFood(id)
    override suspend fun entries(date: LocalDate) = repository.entries(date)
    override suspend fun recentFoods() = repository.recentFoods()
    override fun calculate(food: FoodEntity, amountMg: Long) = repository.calculate(food, amountMg)
    override suspend fun addFoodEntry(foodId: Long, date: LocalDate, meal: MealType, amountMg: Long) = repository.addFoodEntry(foodId, date, meal, amountMg)
    override suspend fun addQuickEntry(name: String, date: LocalDate, meal: MealType, calories: Int, proteinMg: Long, fatMg: Long, carbMg: Long) = repository.addQuickEntry(name, date, meal, calories, proteinMg, fatMg, carbMg)
    override suspend fun updateFoodEntry(id: Long, foodId: Long, date: LocalDate, meal: MealType, amountMg: Long) = repository.updateFoodEntry(id, foodId, date, meal, amountMg)
    override suspend fun updateQuickEntry(id: Long, name: String, date: LocalDate, meal: MealType, calories: Int, proteinMg: Long, fatMg: Long, carbMg: Long) = repository.updateQuickEntry(id, name, date, meal, calories, proteinMg, fatMg, carbMg)
    override suspend fun deleteEntry(id: Long) = repository.deleteEntry(id)
    override suspend fun externalEntries(date: LocalDate) = repository.externalEntries(date)
    override suspend fun externalDayTotal(date: LocalDate) = repository.externalDayTotal(date)
    override suspend fun addExternalEntry(foodId: String, servingId: String, date: LocalDate, meal: MealType, amountMg: Long, nutrition: NutritionTotals, amountUnit: String) = repository.addExternalEntry(foodId, servingId, date, meal, amountMg, nutrition, amountUnit)
    override suspend fun correctExternalDay(date: LocalDate, entryId: Long?, nutrition: NutritionTotals) = repository.correctExternalDay(date, entryId, nutrition)
}

internal data class CalorieState(
    val date: LocalDate,
    val today: LocalDate = date,
    val profile: CalorieProfileEntity? = null,
    val entries: List<DiaryEntryEntity> = emptyList(),
    val totals: NutritionTotals = NutritionTotals(),
    val mealTotals: Map<MealType, Long> = emptyMap(),
    val allFoods: List<FoodEntity> = emptyList(),
    val foods: List<FoodEntity> = emptyList(),
    val recents: List<RecentFood> = emptyList(),
    val externalEntries: List<ExternalDiaryEntryEntity> = emptyList(),
    val externalDayTotal: NutritionTotals = NutritionTotals(),
    val externalResults: List<ExternalFoodSummary> = emptyList(),
    val externalSelected: ExternalFood? = null,
    val externalSearchBusy: Boolean = false,
    val externalDetailBusy: Boolean = false,
    val externalError: String? = null,
    val externalDetails: Map<String, ExternalFood> = emptyMap(),
    val externalDetailFailedIds: Set<String> = emptySet(),
    val externalHistoryBusy: Boolean = false,
    val externalHistoryError: String? = null,
    val search: String = "",
    val busy: Boolean = true,
    val error: String? = null,
) {
    fun mealTotal(meal: MealType): Long = mealTotals[meal] ?: 0L
    fun overallTotals() = addNutrition(totals, externalDayTotal)
}

internal fun addNutrition(first: NutritionTotals, second: NutritionTotals) = try {
    NutritionTotals(addExact(first.caloriesKcal, second.caloriesKcal), addExact(first.proteinMg, second.proteinMg), addExact(first.fatMg, second.fatMg), addExact(first.carbMg, second.carbMg))
} catch (_: ArithmeticException) { throw IllegalStateException("Сумма дневника слишком велика") }

internal data class CalorieProjection(val totals: NutritionTotals, val mealTotals: Map<MealType, Long>)
internal fun checkedCalorieProjection(entries: List<DiaryEntryEntity>): CalorieProjection {
    var total = NutritionTotals()
    val byMeal = MealType.entries.associateWith { 0L }.toMutableMap()
    try {
        entries.forEach { entry ->
            total = NutritionTotals(
                addExact(total.caloriesKcal, entry.caloriesKcal.toLong()),
                addExact(total.proteinMg, entry.proteinMg), addExact(total.fatMg, entry.fatMg),
                addExact(total.carbMg, entry.carbMg),
            )
            byMeal[entry.mealType] = addExact(byMeal.getValue(entry.mealType), entry.caloriesKcal.toLong())
        }
    } catch (_: ArithmeticException) { throw IllegalStateException("Сумма дневника слишком велика") }
    return CalorieProjection(total, byMeal)
}
internal fun formatCalorieDate(date: LocalDate, today: LocalDate) = if (date == today) "Сегодня" else "%02d.%02d.%04d".format(date.dayOfMonth, date.monthValue, date.year)
internal fun gramsText(mg: Long): String = BigDecimal(mg).divide(BigDecimal(1000)).stripTrailingZeros().toPlainString()
internal fun resolveFoodForEntry(state: CalorieState, entry: DiaryEntryEntity): FoodEntity? =
    entry.foodId?.let { id -> state.allFoods.firstOrNull { it.id == id } }

internal data class ParsedAmount(val mg: Long? = null, val error: String? = null) { val valid get() = mg != null && error == null }
internal fun parseGrams(value: String, required: Boolean = true): ParsedAmount {
    if (value.isBlank()) return if (required) ParsedAmount(error = "Укажите количество") else ParsedAmount(0)
    val decimal = value.trim().replace(',', '.').toBigDecimalOrNull() ?: return ParsedAmount(error = "Введите число")
    val mg = runCatching { decimal.multiply(BigDecimal(1000)).setScale(0, RoundingMode.UNNECESSARY).longValueExact() }.getOrNull()
        ?: return ParsedAmount(error = "Не более 3 знаков после запятой")
    return if (mg <= 0 && required) ParsedAmount(error = "Значение должно быть больше нуля")
    else if (mg < 0) ParsedAmount(error = "Значение не может быть отрицательным")
    else if (mg > 100_000_000) ParsedAmount(error = "Слишком большое значение") else ParsedAmount(mg)
}
internal data class FoodFormValues(val name: String, val calories: Int, val proteinMg: Long, val fatMg: Long, val carbMg: Long)
internal fun parseFoodForm(name: String, calories: String, protein: String, fat: String, carbs: String): Result<FoodFormValues> = runCatching {
    val normalized = name.trim(); require(normalized.isNotEmpty() && normalized.length <= 120) { "Введите название до 120 символов" }
    val kcal = calories.trim().toIntOrNull() ?: error("Введите калории целым числом")
    require(kcal in 0..1_000_000) { "Некорректная калорийность" }
    val p = parseGrams(protein, false).let { require(it.valid) { it.error!! }; it.mg!! }
    val f = parseGrams(fat, false).let { require(it.valid) { it.error!! }; it.mg!! }
    val c = parseGrams(carbs, false).let { require(it.valid) { it.error!! }; it.mg!! }
    require(kcal > 0 || p > 0 || f > 0 || c > 0) { "Укажите пищевую ценность" }
    FoodFormValues(normalized, kcal, p, f, c)
}
internal fun parseQuickForm(name: String, calories: String, protein: String, fat: String, carbs: String): Result<FoodFormValues> =
    parseFoodForm(name, calories, protein, fat, carbs).mapCatching { require(it.calories > 0) { "Калории должны быть положительными" }; it }

internal fun parseExternalDayCorrection(calories: String, protein: String, fat: String, carbs: String): Result<NutritionTotals> = runCatching {
    val kcal = calories.trim().ifBlank { "0" }.toLongOrNull() ?: error("Калории должны быть целым числом")
    require(kcal in 0..1_000_000) { "Калории должны быть от 0 до 1 000 000" }
    fun macro(value: String): Long = parseGrams(value, required = false).let { require(it.valid) { it.error!! }; it.mg!! }
    NutritionTotals(kcal, macro(protein), macro(fat), macro(carbs))
}

internal class CalorieViewModel(
    private val store: CalorieStore,
    clock: Clock = Clock.systemDefaultZone(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val externalClient: FatSecretClient? = null,
    private val tokenProvider: () -> String? = { null },
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis()/1000 },
) : ViewModel() {
    private val today = LocalDate.now(clock)
    private val mutable = MutableStateFlow(CalorieState(today = today, date = today))
    val state = mutable.asStateFlow()
    private var externalSearchJob: Job? = null
    private var externalDetailsJob: Job? = null
    private var externalRequest = 0L
    private var externalDetailRequest = 0L
    init { reload() }

    fun reload() = launchLoad {
        val entries=store.entries(mutable.value.date); val projection=checkedCalorieProjection(entries)
        val allFoods=store.foods()
        mutable.value = mutable.value.copy(profile=store.profile(),entries=entries,totals=projection.totals,mealTotals=projection.mealTotals,allFoods=allFoods,foods=if(mutable.value.search.isBlank())allFoods else store.foods(mutable.value.search),recents=store.recentFoods(),externalEntries=store.externalEntries(mutable.value.date),externalDayTotal=store.externalDayTotal(mutable.value.date))
        launchDetailsRefresh()
    }
    fun selectDate(date: LocalDate) = launchLoad { val entries=store.entries(date);val projection=checkedCalorieProjection(entries);mutable.value=mutable.value.copy(date=date,entries=entries,totals=projection.totals,mealTotals=projection.mealTotals,externalEntries=store.externalEntries(date),externalDayTotal=store.externalDayTotal(date),externalDetails=emptyMap(),externalDetailFailedIds=emptySet());launchDetailsRefresh() }
    fun previousDay() = selectDate(mutable.value.date.minusDays(1))
    fun nextDay() = selectDate(mutable.value.date.plusDays(1))
    fun search(query: String) {
        externalSearchJob?.cancel()
        val request = ++externalRequest
        mutable.value = mutable.value.copy(search=query,foods=mutable.value.allFoods.filter { it.name.contains(query,true) },externalResults=emptyList(),externalError=null,externalSearchBusy=query.trim().length>=2)
        if (query.trim().length < 2) return
        externalSearchJob = viewModelScope.launch {
            delay(350)
            try {
                val result = withContext(io) { externalClient?.search(requireToken(), query) ?: error("Поиск FatSecret недоступен") }
                    .filter { fatSecretContentFresh(it.fetchedAtSeconds,nowEpochSeconds()) }
                if (request == externalRequest) mutable.value = mutable.value.copy(externalResults=result,externalSearchBusy=false)
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) { if (request == externalRequest) mutable.value=mutable.value.copy(externalSearchBusy=false,externalError=e.message?:"Ошибка поиска") }
        }
    }
    fun resetSearch() { externalSearchJob?.cancel();externalRequest++;mutable.value=mutable.value.copy(search="",foods=mutable.value.allFoods,externalResults=emptyList(),externalSearchBusy=false,externalError=null) }
    fun clearExternalSelection() { externalDetailRequest++;mutable.value = mutable.value.copy(externalSelected=null,externalDetailBusy=false,externalError=null) }
    fun expireExternal() {
        val now=nowEpochSeconds()
        mutable.value=mutable.value.copy(
            externalResults=mutable.value.externalResults.filter{fatSecretContentFresh(it.fetchedAtSeconds,now)},
            externalSelected=mutable.value.externalSelected?.takeIf{fatSecretContentFresh(it.fetchedAtSeconds,now)},
            externalDetails=mutable.value.externalDetails.filterValues{fatSecretContentFresh(it.fetchedAtSeconds,now)},
        )
    }
    fun openExternal(id: String) = loadExternal { client, token -> client.food(token,id) }
    fun lookupBarcode(code: String) = loadExternal { client, token -> client.barcode(token,code) }
    fun selectExternal(food: ExternalFood) {
        if (fatSecretContentFresh(food.fetchedAtSeconds,nowEpochSeconds()))
            mutable.value=mutable.value.copy(externalSelected=food,externalDetailBusy=false,externalError=null)
        else mutable.value=mutable.value.copy(externalSelected=null,externalError="Данные продукта устарели. Повторите запрос")
    }
    fun refreshExternalHistory(id: String) {
        mutable.value=mutable.value.copy(externalHistoryBusy=true,externalHistoryError=null)
        viewModelScope.launch {
            try {
                val food=withContext(io){requireNotNull(externalClient){"Поиск FatSecret недоступен"}.food(requireToken(),id)}
                require(fatSecretContentFresh(food.fetchedAtSeconds,nowEpochSeconds())){"Данные продукта устарели"}
                mutable.value=mutable.value.copy(externalDetails=mutable.value.externalDetails+(id to food),externalDetailFailedIds=mutable.value.externalDetailFailedIds-id,externalHistoryBusy=false)
            } catch(e:CancellationException){throw e}
            catch(e:Throwable){mutable.value=mutable.value.copy(externalHistoryBusy=false,externalHistoryError=e.message?:"Не удалось загрузить подробности")}
        }
    }
    private fun loadExternal(loader: suspend (FatSecretClient,String)->ExternalFood) {
        val request=++externalDetailRequest
        mutable.value=mutable.value.copy(externalSelected=null,externalDetailBusy=true,externalError=null)
        viewModelScope.launch {
            try { val food=withContext(io){loader(requireNotNull(externalClient){"Поиск FatSecret недоступен"},requireToken())}; require(fatSecretContentFresh(food.fetchedAtSeconds,nowEpochSeconds())){"Данные продукта устарели. Повторите запрос"};if(request==externalDetailRequest)mutable.value=mutable.value.copy(externalSelected=food,externalDetailBusy=false) }
            catch(e:CancellationException){throw e}
            catch(e:Throwable){if(request==externalDetailRequest)mutable.value=mutable.value.copy(externalDetailBusy=false,externalError=e.message?:"Не удалось загрузить продукт")}
        }
    }
    private fun requireToken(): String = tokenProvider()?.takeIf { it.isNotBlank() } ?: error("Подключите FFF в разделе Harness для поиска продуктов")
    private fun launchDetailsRefresh() {
        externalDetailsJob?.cancel()
        val client=externalClient?:return
        val entries=mutable.value.externalEntries
        val date=mutable.value.date
        if(entries.isEmpty())return
        externalDetailsJob=viewModelScope.launch {
            val token=withContext(io){tokenProvider()?.takeIf{it.isNotBlank()}}?:return@launch
            // One in-flight request at a time limits API load; cancellation on date changes
            // prevents an old day's results from leaking into the new screen.
            entries.map{it.foodId}.distinct().forEachIndexed { index,id ->
                if(index>0) delay(250)
                val food=try { withContext(io){client.food(token,id)}.takeIf{fatSecretContentFresh(it.fetchedAtSeconds,nowEpochSeconds())} } catch(e:CancellationException){throw e}catch (_: Exception) { null }
                if (food!=null && date==mutable.value.date) mutable.value=mutable.value.copy(externalDetails=mutable.value.externalDetails+(id to food))
                else if (date==mutable.value.date) mutable.value=mutable.value.copy(externalDetailFailedIds=mutable.value.externalDetailFailedIds+id)
            }
        }
    }
    fun addExternal(food: ExternalFood, serving: ExternalServing, meal: MealType, amount: String, done: ()->Unit, invalid: (String)->Unit) {
        if (!fatSecretContentFresh(food.fetchedAtSeconds,nowEpochSeconds())) { invalid("Данные FatSecret устарели. Загрузите продукт ещё раз"); return }
        val parsed=parseGrams(amount);if(!parsed.valid){invalid(parsed.error!!);return}
        val unit=serving.measureUnit?:run{invalid("Порция без массы или объёма не поддерживается");return}
        mutate({
            require(fatSecretContentFresh(food.fetchedAtSeconds,nowEpochSeconds())) { "Данные FatSecret устарели. Загрузите продукт ещё раз" }
            val nutrition=externalPortionNutrition(serving,parsed.mg!!)
            store.addExternalEntry(food.id,serving.id,mutable.value.date,meal,parsed.mg,nutrition,unit)
            reloadNow()
        },done)
    }
    fun correctExternalDay(date: LocalDate, entryId: Long?, nutrition: NutritionTotals, done: ()->Unit) = mutate({
        store.correctExternalDay(date,entryId,nutrition)
        reloadNow()
    },done)
    fun preview(food: FoodEntity, amount: String): Result<NutritionTotals> {
        val parsed = parseGrams(amount)
        return if (!parsed.valid) Result.failure(IllegalArgumentException(parsed.error)) else runCatching { store.calculate(food, parsed.mg!!) }
    }
    fun saveFood(id: Long?, values: FoodFormValues, done: () -> Unit) = mutate({
        if (id == null) store.createFood(values.name, values.calories, values.proteinMg, values.fatMg, values.carbMg)
        else store.updateFood(id, values.name, values.calories, values.proteinMg, values.fatMg, values.carbMg)
        reloadNow()
    }, done)
    fun deleteFood(id: Long, done: () -> Unit) = mutate({ store.deleteFood(id); reloadNow() }, done)
    fun addFood(food: FoodEntity, meal: MealType, amount: String, done: () -> Unit, invalid: (String) -> Unit) {
        val parsed = parseGrams(amount); if (!parsed.valid) { invalid(parsed.error!!); return }
        mutate({ store.addFoodEntry(food.id, mutable.value.date, meal, parsed.mg!!); reloadNow() }, done)
    }
    fun saveQuick(id: Long?, values: FoodFormValues, date: LocalDate, meal: MealType, done: () -> Unit) = mutate({
        if (id == null) store.addQuickEntry(values.name, date, meal, values.calories, values.proteinMg, values.fatMg, values.carbMg)
        else store.updateQuickEntry(id, values.name, date, meal, values.calories, values.proteinMg, values.fatMg, values.carbMg)
        reloadNow()
    }, done)
    fun updateFoodEntry(entry: DiaryEntryEntity, amount: String, date: LocalDate, meal: MealType, done: () -> Unit, invalid: (String) -> Unit) {
        val parsed = parseGrams(amount); if (!parsed.valid) { invalid(parsed.error!!); return }
        mutate({ store.updateFoodEntry(entry.id, requireNotNull(entry.foodId), date, meal, parsed.mg!!); reloadNow() }, done)
    }
    fun deleteEntry(id: Long, done: () -> Unit) = mutate({ store.deleteEntry(id); reloadNow() }, done)
    fun saveProfile(values: FoodFormValues, done: () -> Unit) = mutate({ store.updateProfile(values.calories, values.proteinMg, values.fatMg, values.carbMg); reloadNow() }, done)
    private suspend fun reloadNow() { val entries=store.entries(mutable.value.date);val projection=checkedCalorieProjection(entries);val allFoods=store.foods();mutable.value=mutable.value.copy(profile=store.profile(),entries=entries,totals=projection.totals,mealTotals=projection.mealTotals,allFoods=allFoods,foods=if(mutable.value.search.isBlank())allFoods else store.foods(mutable.value.search),recents=store.recentFoods(),externalEntries=store.externalEntries(mutable.value.date),externalDayTotal=store.externalDayTotal(mutable.value.date));launchDetailsRefresh() }
    private fun launchLoad(block: suspend () -> Unit) { mutable.value = mutable.value.copy(busy=true,error=null); viewModelScope.launch { try { withContext(io) { block() } } catch (e: CancellationException) { throw e } catch (e: Throwable) { mutable.value=mutable.value.copy(error=e.message?:"Ошибка") } finally { mutable.value=mutable.value.copy(busy=false) } } }
    private fun mutate(block: suspend () -> Unit, done: () -> Unit) { if(mutable.value.busy)return; mutable.value=mutable.value.copy(busy=true,error=null); viewModelScope.launch { var ok=false; try { withContext(io){block()};ok=true } catch(e:CancellationException){throw e}catch(e:Throwable){mutable.value=mutable.value.copy(error=e.message?:"Ошибка")}finally{mutable.value=mutable.value.copy(busy=false)};if(ok)done() } }
    companion object { fun factory(store: CalorieStore, clock: Clock = Clock.systemDefaultZone(), externalClient: FatSecretClient? = null, tokenProvider: ()->String? = {null}) = object:ViewModelProvider.Factory { @Suppress("UNCHECKED_CAST") override fun<T:ViewModel> create(modelClass:Class<T>):T=CalorieViewModel(store,clock,externalClient=externalClient,tokenProvider=tokenProvider) as T } }
}
