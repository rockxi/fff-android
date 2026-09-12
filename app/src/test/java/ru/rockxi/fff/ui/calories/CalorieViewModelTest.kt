package ru.rockxi.fff.ui.calories

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.rockxi.fff.data.calories.*

@OptIn(ExperimentalCoroutinesApi::class)
class CalorieViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun setup()=Dispatchers.setMain(dispatcher)
    @After fun cleanup()=Dispatchers.resetMain()

    @Test fun `opens injected today and keeps dates independent`()=runTest(dispatcher){
        val store=FakeCalorieStore();val vm=CalorieViewModel(store,Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"),ZoneOffset.UTC),dispatcher)
        advanceUntilIdle();assertEquals(LocalDate.of(2026,9,12),vm.state.value.date);assertEquals(216,vm.state.value.totals.caloriesKcal)
        vm.previousDay();advanceUntilIdle();assertEquals(LocalDate.of(2026,9,11),vm.state.value.date);assertEquals(0,vm.state.value.entries.size)
        vm.nextDay();advanceUntilIdle();assertEquals(216,vm.state.value.totals.caloriesKcal)
    }

    @Test fun `nutrition totals and meal totals aggregate snapshots`() {
        val entries=listOf(entry(1,216,32400,9000,5400,MealType.BREAKFAST),entry(2,300,1000,2000,3000,MealType.LUNCH))
        val projection=checkedCalorieProjection(entries)
        val state=CalorieState(LocalDate.parse("2026-09-12"),entries=entries,totals=projection.totals,mealTotals=projection.mealTotals)
        assertEquals(NutritionTotals(516,33400,11000,8400),state.totals)
        assertEquals(216,state.mealTotal(MealType.BREAKFAST));assertEquals(300,state.mealTotal(MealType.LUNCH))
    }

    @Test fun `grams and forms accept comma precision and reject invalid values`() {
        assertEquals(180_125L,parseGrams("180,125").mg)
        assertFalse(parseGrams("0").valid);assertFalse(parseGrams("1.0001").valid)
        val food=parseFoodForm(" Творог ","120","18","5","3").getOrThrow()
        assertEquals("Творог",food.name);assertEquals(18_000L,food.proteinMg)
        assertTrue(parseQuickForm("Обед","250","","","10,5").isSuccess)
        assertTrue(parseQuickForm("Обед","0","1","0","0").isFailure)
    }

    @Test fun `preview and recent default produce exact tvorog oracle`()=runTest(dispatcher){
        val store=FakeCalorieStore();val vm=CalorieViewModel(store,Clock.systemUTC(),dispatcher);advanceUntilIdle()
        val result=vm.preview(store.food,"180").getOrThrow()
        assertEquals(NutritionTotals(216,32400,9000,5400),result)
        assertEquals(180_000,vm.state.value.recents.single().defaultAmountGramsMg)
    }

    @Test fun `rapid duplicate saves are ignored while busy`()=runTest(dispatcher){
        val store=FakeCalorieStore();val vm=CalorieViewModel(store,Clock.systemUTC(),dispatcher);advanceUntilIdle()
        vm.addFood(store.food,MealType.LUNCH,"100",{},{});vm.addFood(store.food,MealType.LUNCH,"100",{},{});advanceUntilIdle()
        assertEquals(1,store.addCalls)
    }

    @Test fun `date label uses only actual today`() { val today=LocalDate.of(2026,9,12);assertEquals("Сегодня",formatCalorieDate(today,today));assertEquals("11.09.2026",formatCalorieDate(today.minusDays(1),today)) }

    @Test fun `checked projection rejects overflow instead of wrapping`() {
        val huge=listOf(entry(1,1,Long.MAX_VALUE,0,0,MealType.BREAKFAST),entry(2,1,1,0,0,MealType.BREAKFAST))
        val error=assertThrows(IllegalStateException::class.java){checkedCalorieProjection(huge)}
        assertEquals("Сумма дневника слишком велика",error.message)
    }

    @Test fun `overflow becomes recoverable view model error and keeps prior projection`()=runTest(dispatcher){
        val store=FakeCalorieStore();val vm=CalorieViewModel(store,Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"),ZoneOffset.UTC),dispatcher);advanceUntilIdle()
        val before=vm.state.value.totals;store.overflow=true;vm.reload();advanceUntilIdle()
        assertEquals(before,vm.state.value.totals);assertEquals("Сумма дневника слишком велика",vm.state.value.error);assertFalse(vm.state.value.busy)
        store.overflow=false;vm.reload();advanceUntilIdle();assertNull(vm.state.value.error)
    }

    @Test fun `reset search restores unfiltered catalog for next add`()=runTest(dispatcher){
        val store=FakeCalorieStore();val vm=CalorieViewModel(store,Clock.systemUTC(),dispatcher);advanceUntilIdle()
        vm.search("нет");advanceUntilIdle();assertEquals("нет",vm.state.value.search);assertTrue(vm.state.value.foods.isEmpty())
        vm.resetSearch();advanceUntilIdle();assertEquals("",vm.state.value.search);assertEquals(listOf("Творог"),vm.state.value.foods.map{it.name})
        assertEquals(listOf("Творог"),vm.state.value.allFoods.map{it.name})
    }

    @Test fun `filtered personal foods cannot hide catalog food needed by entry editor`()=runTest(dispatcher){
        val store=FakeCalorieStore();val vm=CalorieViewModel(store,Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"),ZoneOffset.UTC),dispatcher);advanceUntilIdle()
        val entry=vm.state.value.entries.single()
        vm.search("нет");advanceUntilIdle()
        assertTrue(vm.state.value.foods.isEmpty());assertEquals("Творог",resolveFoodForEntry(vm.state.value,entry)?.name)
        vm.resetSearch();advanceUntilIdle()
        assertEquals("",vm.state.value.search);assertEquals("Творог",resolveFoodForEntry(vm.state.value,entry)?.name)
    }

    @Test fun `numeric focus helper selects the complete useful default`() { assertEquals(androidx.compose.ui.text.TextRange(0,3),selectAllRange("100"));assertEquals(androidx.compose.ui.text.TextRange.Zero,selectAllRange("")) }
    private fun entry(id:Long,kcal:Int,p:Long,f:Long,c:Long,meal:MealType)=DiaryEntryEntity(id,"2026-09-12",meal,null,"Запись",null,kcal,p,f,c,id,id)
}

private class FakeCalorieStore:CalorieStore {
    val food=FoodEntity(1,"Творог",120,18_000,5_000,3_000,1,1)
    private val all=mutableListOf(DiaryEntryEntity(1,"2026-09-12",MealType.BREAKFAST,1,"Творог",180_000,216,32_400,9_000,5_400,1,1))
    var addCalls=0;var overflow=false;private var next=2L
    private var profile=CalorieProfileEntity(1,2000,120_000,70_000,230_000,1)
    override suspend fun profile()=profile
    override suspend fun updateProfile(calories:Int,proteinMg:Long,fatMg:Long,carbMg:Long){profile=profile.copy(dailyCaloriesKcal=calories,proteinTargetMg=proteinMg,fatTargetMg=fatMg,carbTargetMg=carbMg)}
    override suspend fun foods(query:String)=listOf(food).filter{query.isBlank()||it.name.contains(query,true)}
    override suspend fun createFood(name:String,calories:Int,proteinMg:Long,fatMg:Long,carbMg:Long)=2L
    override suspend fun updateFood(id:Long,name:String,calories:Int,proteinMg:Long,fatMg:Long,carbMg:Long)=Unit
    override suspend fun deleteFood(id:Long)=Unit
    override suspend fun entries(date:LocalDate)=if(overflow)listOf(DiaryEntryEntity(90,date.toString(),MealType.SNACK,null,"A",null,1,Long.MAX_VALUE,0,0,1,1),DiaryEntryEntity(91,date.toString(),MealType.SNACK,null,"B",null,1,1,0,0,2,2))else all.filter{it.localDate==date.toString()}
    override suspend fun recentFoods()=listOf(RecentFood(food,180_000,1))
    override fun calculate(food:FoodEntity,amountMg:Long)=NutritionTotals((food.caloriesPer100gKcal*amountMg+50_000)/100_000,(food.proteinPer100gMg*amountMg+50_000)/100_000,(food.fatPer100gMg*amountMg+50_000)/100_000,(food.carbPer100gMg*amountMg+50_000)/100_000)
    override suspend fun addFoodEntry(foodId:Long,date:LocalDate,meal:MealType,amountMg:Long):Long{addCalls++;val n=calculate(food,amountMg);val id=next++;all+=DiaryEntryEntity(id,date.toString(),meal,foodId,food.name,amountMg,n.caloriesKcal.toInt(),n.proteinMg,n.fatMg,n.carbMg,id,id);return id}
    override suspend fun addQuickEntry(name:String,date:LocalDate,meal:MealType,calories:Int,proteinMg:Long,fatMg:Long,carbMg:Long)=next++
    override suspend fun updateFoodEntry(id:Long,foodId:Long,date:LocalDate,meal:MealType,amountMg:Long)=Unit
    override suspend fun updateQuickEntry(id:Long,name:String,date:LocalDate,meal:MealType,calories:Int,proteinMg:Long,fatMg:Long,carbMg:Long)=Unit
    override suspend fun deleteEntry(id:Long){all.removeAll{it.id==id}}
}
