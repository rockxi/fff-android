package ru.rockxi.fff.ui.gym

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.rockxi.fff.data.gym.*

@OptIn(ExperimentalCoroutinesApi::class)
class GymViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()

    @Test fun `today comes from injected clock and selecting exercise loads its sets`() = runTest(dispatcher) {
        val store = FakeGymStore(); val vm = GymViewModel(store, Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC), dispatcher)
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 9, 8), vm.state.value.date)
        vm.openExercise(1); advanceUntilIdle()
        assertEquals("Жим лёжа", vm.state.value.selectedExercise?.name)
        assertEquals(1, vm.state.value.sets.size)
        assertTrue(vm.state.value.sets.single().isAllTimeRecord)
    }

    @Test fun `set create update delete reloads persisted projection`() = runTest(dispatcher) {
        val store = FakeGymStore(); val vm = GymViewModel(store, Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC), dispatcher)
        advanceUntilIdle(); vm.openExercise(1); advanceUntilIdle()
        vm.saveSet(null, GymSetMode.BODY_WEIGHT, "82,5", "12", failValidation(), {}); advanceUntilIdle()
        assertEquals(GymSetMode.BODY_WEIGHT, vm.state.value.sets.last().set.mode)
        assertEquals(82_500L, vm.state.value.sets.last().set.bodyWeightGrams)
        val id = vm.state.value.sets.last().set.id
        vm.saveSet(id, GymSetMode.EXTERNAL_WEIGHT, "90", "8", failValidation(), {}); advanceUntilIdle()
        assertEquals(90_000L, vm.state.value.sets.last().set.weightGrams)
        vm.deleteSet(id); advanceUntilIdle()
        assertFalse(vm.state.value.sets.any { it.set.id == id })
    }

    @Test fun `invalid fields are reported and input remains caller-owned`() {
        val zero = validateGymSet("0", "-1")
        assertFalse(zero.valid); assertNotNull(zero.weightError); assertNotNull(zero.repetitionsError)
        val precise = validateGymSet("82,125", "10")
        assertTrue(precise.valid); assertEquals(82_125L, precise.grams)
        assertFalse(validateGymSet("82.1234", "10").valid)
        assertFalse(validateGymSet("2001", "10").valid)
    }

    @Test fun `bodyweight and record presentation are explicit`() {
        val set = GymSetEntity(id=9, exerciseId=1, localDate="2026-09-08", repetitions=10, mode=GymSetMode.BODY_WEIGHT, bodyWeightGrams=82_500)
        assertEquals("Свой вес · 82.5 кг", setWeightLabel(set))
        assertTrue(GymSetWithRecord(set, true).isAllTimeRecord)
    }

    @Test fun `calendar grid is Monday first and handles leap and year boundaries`() {
        val june = gymCalendarGrid(YearMonth.of(2026, 6))
        assertEquals(LocalDate.of(2026, 6, 1), june.first().date)
        assertEquals(LocalDate.of(2026, 6, 30), june[29].date)
        assertNull(june[30].date)

        val leap = gymCalendarGrid(YearMonth.of(2028, 2))
        assertEquals(1, leap.takeWhile { it.date == null }.size) // 1 February 2028 is Tuesday.
        assertTrue(leap.any { it.date == LocalDate.of(2028, 2, 29) })
        val january = gymCalendarGrid(YearMonth.of(2027, 1))
        assertEquals(4, january.takeWhile { it.date == null }.size)
        assertEquals(LocalDate.of(2027, 1, 1), january[4].date)
        assertEquals(42, january.size)
    }

    @Test fun `date formatting calls only actual clock day today`() {
        val today = LocalDate.of(2026, 9, 8)
        assertEquals("Сегодня", formatGymDay(today, today))
        assertEquals("07.09.2026", formatGymDay(today.minusDays(1), today))
        assertEquals("Сентябрь 2026", formatGymMonth(YearMonth.of(2026, 9)))
    }

    @Test fun `date selection today reset and month navigation do not create workout days`() = runTest(dispatcher) {
        val store=FakeGymStore(); val vm=GymViewModel(store,Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"),ZoneOffset.UTC),dispatcher)
        advanceUntilIdle()
        vm.selectDate(LocalDate.of(2026, 8, 31)); advanceUntilIdle()
        assertEquals(LocalDate.of(2026,8,31),vm.state.value.date)
        assertEquals(YearMonth.of(2026,8),vm.state.value.month)
        assertTrue(vm.state.value.summary.isEmpty())
        assertEquals(0,store.ensureDayCalls)
        vm.nextMonth(); advanceUntilIdle(); assertEquals(YearMonth.of(2026,9),vm.state.value.month)
        vm.selectToday(); advanceUntilIdle(); assertEquals(LocalDate.of(2026,9,8),vm.state.value.date)
    }

    @Test fun `historical mutations stay on selected date and refresh activity marker`() = runTest(dispatcher) {
        val store=FakeGymStore(); val vm=GymViewModel(store,Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"),ZoneOffset.UTC),dispatcher)
        advanceUntilIdle(); val historical=LocalDate.of(2026,9,3)
        vm.selectDate(historical); advanceUntilIdle(); vm.startWorkoutDay(); advanceUntilIdle()
        assertTrue(vm.state.value.monthActivity.any { it.localDate == "2026-09-03" && it.setCount == 0L })
        vm.openExercise(1); advanceUntilIdle()
        vm.saveSet(null,GymSetMode.EXTERNAL_WEIGHT,"70","10",failValidation(),{}); advanceUntilIdle()
        assertEquals("2026-09-03",vm.state.value.sets.single().set.localDate)
        assertEquals(1L,vm.state.value.monthActivity.single { it.localDate=="2026-09-03" }.setCount)
        assertEquals(1,store.allSets.count { it.localDate=="2026-09-08" })
        val id=vm.state.value.sets.single().set.id
        vm.saveSet(id,GymSetMode.EXTERNAL_WEIGHT,"72.5","8",failValidation(),{}); advanceUntilIdle()
        assertEquals("2026-09-03",vm.state.value.sets.single().set.localDate)
        assertEquals(72_500L,vm.state.value.sets.single().set.weightGrams)
        assertEquals(1,store.allSets.count { it.localDate=="2026-09-08" })
        vm.deleteSet(id); advanceUntilIdle()
        assertEquals(0L,vm.state.value.monthActivity.single { it.localDate=="2026-09-03" }.setCount)
    }

    @Test fun `double set and exercise mutations execute once`() = runTest(dispatcher) {
        val store=FakeGymStore(); val vm=GymViewModel(store, Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"),ZoneOffset.UTC),dispatcher)
        advanceUntilIdle(); vm.openExercise(1); advanceUntilIdle()
        vm.saveSet(null,GymSetMode.EXTERNAL_WEIGHT,"100","5",failValidation(),{}); vm.saveSet(null,GymSetMode.EXTERNAL_WEIGHT,"100","5",failValidation(),{}); advanceUntilIdle()
        assertEquals(1,store.addCalls)
        vm.closeExercise(); vm.createExercise(1,"Разводка"); vm.createExercise(1,"Дубль"); advanceUntilIdle()
        assertEquals(1,store.createCalls)
        vm.deleteExercise(1); vm.deleteExercise(1); advanceUntilIdle()
        assertEquals(1,store.deleteExerciseCalls)
    }

    @Test fun `busy clears after failure and retry works`() = runTest(dispatcher) {
        val store=FakeGymStore(); val vm=GymViewModel(store,Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"),ZoneOffset.UTC),dispatcher)
        advanceUntilIdle(); vm.openExercise(1); advanceUntilIdle(); store.failNextAdd=true
        vm.saveSet(null,GymSetMode.EXTERNAL_WEIGHT,"100","5",failValidation(),{}); advanceUntilIdle()
        assertFalse(vm.state.value.busy); assertNotNull(vm.state.value.error)
        vm.saveSet(null,GymSetMode.EXTERNAL_WEIGHT,"100","5",failValidation(),{}); advanceUntilIdle()
        assertEquals(2,store.addCalls); assertFalse(vm.state.value.busy); assertNull(vm.state.value.error)
    }

    @Test fun `create then delayed open retains the new load busy latch`() = runTest(dispatcher) {
        val store=FakeGymStore(); val vm=GymViewModel(store,Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"),ZoneOffset.UTC),dispatcher)
        advanceUntilIdle(); store.delayExerciseTwoSets=true
        vm.createExercise(1,"Разводка") { vm.openExercise(it) }
        runCurrent()
        assertTrue("openExercise must own busy after create releases its latch",vm.state.value.busy)
        assertNull(vm.state.value.selectedExercise)
        store.exerciseTwoGate.complete(Unit); advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertEquals("Разводка",vm.state.value.selectedExercise?.name)
    }

    private fun failValidation(): (SetValidation) -> Unit = { fail("Unexpected validation: $it") }
}

private class FakeGymStore : GymStore {
    private val category = GymCategoryEntity(1, "Грудь")
    private val exercise = GymExerciseEntity(1, 1, "Жим лёжа")
    private val exerciseList=mutableListOf(exercise)
    val allSets = mutableListOf(GymSetEntity(1, 1, "2026-09-08", 8, GymSetMode.EXTERNAL_WEIGHT, 80_000))
    private val days = mutableSetOf("2026-09-08")
    private var next = 2L
    var addCalls=0; var createCalls=0; var deleteExerciseCalls=0; var ensureDayCalls=0; var failNextAdd=false; var delayExerciseTwoSets=false
    val exerciseTwoGate=CompletableDeferred<Unit>()
    override suspend fun categories() = listOf(category)
    override suspend fun exercises():List<GymExerciseEntity> = exerciseList.toList()
    override suspend fun createExercise(categoryId: Long, name: String):Long { createCalls++; exerciseList.removeAll { it.id==2L }; exerciseList += GymExerciseEntity(2,categoryId,name); return 2L }
    override suspend fun updateExercise(id: Long, categoryId: Long, name: String) = Unit
    override suspend fun deleteExercise(id:Long) { deleteExerciseCalls++; exerciseList.removeAll { it.id==id } }
    override suspend fun daySummary(date: LocalDate): List<GymDayExerciseSummary> { val sets=allSets.filter { it.localDate==date.toString() }; return if(sets.isNotEmpty()) listOf(GymDayExerciseSummary(1,"Жим лёжа",1,"Грудь",sets.size.toLong(),sets.sumOf { it.repetitions }.toLong())) else emptyList() }
    override suspend fun monthActivity(month: YearMonth): List<GymMonthActivity> = days.filter { YearMonth.from(LocalDate.parse(it))==month }.sorted().map { day -> val sets=allSets.filter { it.localDate==day }; GymMonthActivity(day,sets.map { it.exerciseId }.distinct().size.toLong(),sets.size.toLong(),sets.sumOf { it.repetitions }.toLong()) }
    override suspend fun ensureWorkoutDay(date: LocalDate) { ensureDayCalls++; days += date.toString() }
    override suspend fun sets(exerciseId: Long, date: LocalDate):List<GymSetWithRecord> { if(delayExerciseTwoSets && exerciseId==2L) exerciseTwoGate.await(); return allSets.filter { it.exerciseId==exerciseId && it.localDate==date.toString() }.map { GymSetWithRecord(it, it.id==1L) } }
    override suspend fun add(exerciseId: Long, date: LocalDate, reps: Int, mode: GymSetMode, grams: Long): Long { addCalls++; if(failNextAdd){failNextAdd=false; error("write failed")}; days += date.toString(); val id=next++; allSets += GymSetEntity(id,exerciseId,date.toString(),reps,mode,if(mode==GymSetMode.EXTERNAL_WEIGHT) grams else null,if(mode==GymSetMode.BODY_WEIGHT) grams else null); return id }
    override suspend fun update(id: Long, exerciseId: Long, date: LocalDate, reps: Int, mode: GymSetMode, grams: Long) { val i=allSets.indexOfFirst { it.id==id }; allSets[i]=GymSetEntity(id,exerciseId,date.toString(),reps,mode,if(mode==GymSetMode.EXTERNAL_WEIGHT) grams else null,if(mode==GymSetMode.BODY_WEIGHT) grams else null) }
    override suspend fun deleteSet(id: Long) { allSets.removeAll { it.id==id } }
}
