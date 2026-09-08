package ru.rockxi.fff.ui.gym

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.rockxi.fff.data.gym.*

internal interface GymStore {
    suspend fun categories(): List<GymCategoryEntity>
    suspend fun exercises(): List<GymExerciseEntity>
    suspend fun createExercise(categoryId: Long, name: String): Long
    suspend fun updateExercise(id: Long, categoryId: Long, name: String)
    suspend fun deleteExercise(id: Long)
    suspend fun daySummary(date: LocalDate): List<GymDayExerciseSummary>
    suspend fun monthActivity(month: YearMonth): List<GymMonthActivity>
    suspend fun ensureWorkoutDay(date: LocalDate)
    suspend fun sets(exerciseId: Long, date: LocalDate): List<GymSetWithRecord>
    suspend fun add(exerciseId: Long, date: LocalDate, reps: Int, mode: GymSetMode, grams: Long): Long
    suspend fun update(id: Long, exerciseId: Long, date: LocalDate, reps: Int, mode: GymSetMode, grams: Long)
    suspend fun deleteSet(id: Long)
}

internal class RepositoryGymStore(private val repository: GymRepository) : GymStore {
    override suspend fun categories() = repository.categories()
    override suspend fun exercises() = repository.exercises()
    override suspend fun createExercise(categoryId: Long, name: String) = repository.createExercise(categoryId, name)
    override suspend fun updateExercise(id: Long, categoryId: Long, name: String) = repository.updateExercise(id, categoryId, name)
    override suspend fun deleteExercise(id: Long) = repository.deleteExercise(id)
    override suspend fun daySummary(date: LocalDate) = repository.daySummary(date)
    override suspend fun monthActivity(month: YearMonth) = repository.monthActivity(month)
    override suspend fun ensureWorkoutDay(date: LocalDate) { repository.ensureWorkoutDay(date) }
    override suspend fun sets(exerciseId: Long, date: LocalDate) = repository.exerciseSets(exerciseId, date)
    override suspend fun add(exerciseId: Long, date: LocalDate, reps: Int, mode: GymSetMode, grams: Long) =
        if (mode == GymSetMode.BODY_WEIGHT) repository.addBodyWeightSet(exerciseId, date, reps, grams)
        else repository.addExternalWeightSet(exerciseId, date, reps, grams)
    override suspend fun update(id: Long, exerciseId: Long, date: LocalDate, reps: Int, mode: GymSetMode, grams: Long) =
        if (mode == GymSetMode.BODY_WEIGHT) repository.updateBodyWeightSet(id, exerciseId, date, reps, grams)
        else repository.updateExternalWeightSet(id, exerciseId, date, reps, grams)
    override suspend fun deleteSet(id: Long) = repository.deleteSet(id)
}

internal data class GymState(
    val date: LocalDate,
    val today: LocalDate = date,
    val month: YearMonth = YearMonth.from(date),
    val monthActivity: List<GymMonthActivity> = emptyList(),
    val categories: List<GymCategoryEntity> = emptyList(),
    val exercises: List<GymExerciseEntity> = emptyList(),
    val summary: List<GymDayExerciseSummary> = emptyList(),
    val selectedExercise: GymExerciseEntity? = null,
    val sets: List<GymSetWithRecord> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

internal data class GymCalendarCell(val date: LocalDate?)

/** Six complete Monday-first weeks keep the calendar stable on narrow screens. */
internal fun gymCalendarGrid(month: YearMonth): List<GymCalendarCell> {
    val leading = month.atDay(1).dayOfWeek.value - 1
    return List(42) { index ->
        val day = index - leading + 1
        GymCalendarCell(if (day in 1..month.lengthOfMonth()) month.atDay(day) else null)
    }
}

private val gymRussianLocale = Locale("ru")
internal fun formatGymMonth(month: YearMonth): String =
    month.month.getDisplayName(TextStyle.FULL_STANDALONE, gymRussianLocale)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(gymRussianLocale) else it.toString() } + " ${month.year}"
internal fun formatGymDay(date: LocalDate, today: LocalDate): String =
    if (date == today) "Сегодня" else date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))

internal data class SetValidation(val grams: Long? = null, val repetitions: Int? = null, val weightError: String? = null, val repetitionsError: String? = null) {
    val valid get() = weightError == null && repetitionsError == null && grams != null && repetitions != null
}

internal fun validateGymSet(weight: String, repetitions: String): SetValidation {
    val decimal = weight.trim().replace(',', '.').toBigDecimalOrNull()
    val grams = try { decimal?.multiply(BigDecimal(1000))?.setScale(0, RoundingMode.UNNECESSARY)?.longValueExact() } catch (_: ArithmeticException) { null }
    val reps = repetitions.trim().toIntOrNull()
    val weightError = when {
        weight.isBlank() -> "Укажите вес"
        decimal == null || grams == null -> "Введите вес в кг, не более 3 знаков после запятой"
        grams <= 0L -> "Вес должен быть больше нуля"
        grams > 2_000_000L -> "Максимальный вес — 2000 кг"
        else -> null
    }
    val repsError = when {
        repetitions.isBlank() -> "Укажите повторения"
        reps == null -> "Введите целое число"
        reps !in 1..10_000 -> "Допустимо от 1 до 10 000"
        else -> null
    }
    return SetValidation(grams, reps, weightError, repsError)
}

internal fun formatWeight(grams: Long): String = if (grams % 1000L == 0L) "${grams / 1000} кг" else "${BigDecimal(grams).divide(BigDecimal(1000)).stripTrailingZeros().toPlainString()} кг"
internal fun setWeightLabel(set: GymSetEntity): String = when (set.mode) {
    GymSetMode.EXTERNAL_WEIGHT -> formatWeight(requireNotNull(set.weightGrams))
    GymSetMode.BODY_WEIGHT -> "Свой вес · ${formatWeight(requireNotNull(set.bodyWeightGrams))}"
}

internal class GymViewModel(
    private val store: GymStore,
    clock: Clock = Clock.systemDefaultZone(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val initialToday = LocalDate.now(clock)
    private val mutable = MutableStateFlow(GymState(initialToday, today = initialToday))
    val state = mutable.asStateFlow()
    init { reload() }

    fun reload() = launch {
        val categories = store.categories(); val exercises = store.exercises(); val summary = store.daySummary(mutable.value.date)
        val activity = store.monthActivity(mutable.value.month)
        val selected = mutable.value.selectedExercise?.let { old -> exercises.firstOrNull { it.id == old.id } }
        mutable.value = mutable.value.copy(categories = categories, exercises = exercises, summary = summary,
            selectedExercise = selected, sets = selected?.let { store.sets(it.id, mutable.value.date) }.orEmpty(), monthActivity = activity, busy = false, error = null)
    }
    fun previousMonth() = changeMonth(mutable.value.month.minusMonths(1))
    fun nextMonth() = changeMonth(mutable.value.month.plusMonths(1))
    fun selectDate(date: LocalDate) = launch {
        val month = YearMonth.from(date)
        mutable.value = mutable.value.copy(date = date, month = month, selectedExercise = null, sets = emptyList(),
            summary = store.daySummary(date), monthActivity = store.monthActivity(month), busy = false, error = null)
    }
    fun selectToday() = selectDate(mutable.value.today)
    fun startWorkoutDay() = mutate(block = { store.ensureWorkoutDay(mutable.value.date); reloadNow() })
    fun openExercise(id: Long) = launch {
        val exercise = mutable.value.exercises.firstOrNull { it.id == id } ?: error("Упражнение не найдено")
        mutable.value = mutable.value.copy(selectedExercise = exercise, sets = store.sets(id, mutable.value.date), busy = false)
    }
    fun closeExercise() { mutable.value = mutable.value.copy(selectedExercise = null, sets = emptyList()) }
    fun createExercise(categoryId: Long, name: String, onDone: (Long) -> Unit = {}) = mutate(
        block = { store.createExercise(categoryId, name).also { reloadNow() } },
        onSuccess = onDone,
    )
    fun updateExercise(id: Long, categoryId: Long, name: String, onDone: () -> Unit = {}) = mutate(
        block = { store.updateExercise(id, categoryId, name); reloadNow() },
        onSuccess = { onDone() },
    )
    fun deleteExercise(id: Long, onDone: () -> Unit = {}) = mutate(
        block = { store.deleteExercise(id); reloadNow() },
        onSuccess = { onDone() },
    )
    fun saveSet(id: Long?, mode: GymSetMode, weight: String, repetitions: String, onInvalid: (SetValidation) -> Unit, onDone: () -> Unit) {
        val parsed = validateGymSet(weight, repetitions); if (!parsed.valid) { onInvalid(parsed); return }
        mutate(block = {
            val exercise = requireNotNull(mutable.value.selectedExercise)
            if (id == null) store.add(exercise.id, mutable.value.date, parsed.repetitions!!, mode, parsed.grams!!)
            else store.update(id, exercise.id, mutable.value.date, parsed.repetitions!!, mode, parsed.grams!!)
            reloadNow()
        }, onSuccess = { onDone() })
    }
    fun deleteSet(id: Long, onDone: () -> Unit = {}) = mutate(
        block = { store.deleteSet(id); reloadNow() },
        onSuccess = { onDone() },
    )
    private suspend fun reloadNow() {
        val exercises = store.exercises(); val selected = mutable.value.selectedExercise?.let { s -> exercises.firstOrNull { it.id == s.id } }
        mutable.value = mutable.value.copy(exercises = exercises, summary = store.daySummary(mutable.value.date), selectedExercise = selected,
            sets = selected?.let { store.sets(it.id, mutable.value.date) }.orEmpty(), monthActivity = store.monthActivity(mutable.value.month), busy = false, error = null)
    }
    private fun changeMonth(month: YearMonth) = launch {
        mutable.value = mutable.value.copy(month = month, monthActivity = store.monthActivity(month), busy = false, error = null)
    }
    private fun launch(block: suspend () -> Unit) { mutable.value = mutable.value.copy(busy = true, error = null); viewModelScope.launch { try { withContext(io) { block() } } catch (e: Throwable) { mutable.value = mutable.value.copy(busy = false, error = e.message ?: "Ошибка") } } }
    private fun <T> mutate(block: suspend () -> T, onSuccess: (T) -> Unit = {}) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        viewModelScope.launch {
            var result: T? = null
            var succeeded = false
            try { result = withContext(io) { block() }; succeeded = true }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { mutable.value = mutable.value.copy(error = e.message ?: "Ошибка") }
            finally { mutable.value = mutable.value.copy(busy = false) }
            if (succeeded) @Suppress("UNCHECKED_CAST") onSuccess(result as T)
        }
    }
    companion object { fun factory(store: GymStore, clock: Clock = Clock.systemDefaultZone()) = object : ViewModelProvider.Factory { @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = GymViewModel(store, clock) as T } }
}
