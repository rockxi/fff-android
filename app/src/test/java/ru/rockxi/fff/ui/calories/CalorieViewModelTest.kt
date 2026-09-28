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

    @Test fun `offline external day aggregate contributes to summary but not false meal subtotal`()=runTest(dispatcher){
        val store=FakeCalorieStore().apply { externalTotal=NutritionTotals(450,12_000,10_000,38_000) }
        val vm=CalorieViewModel(store,Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"),ZoneOffset.UTC),dispatcher)
        advanceUntilIdle()
        assertEquals(666,vm.state.value.overallTotals().caloriesKcal)
        assertEquals(216,vm.state.value.mealTotal(MealType.BREAKFAST))
        assertEquals(450,vm.state.value.externalDayTotal.caloriesKcal)
    }

    @Test fun `external search debounces and keeps latest query only`()=runTest(dispatcher){
        val client=FakeFatSecretClient()
        val vm=CalorieViewModel(FakeCalorieStore(),Clock.systemUTC(),dispatcher,client,{"owner-token"})
        advanceUntilIdle()
        vm.search("яб");vm.search("яблоко");advanceUntilIdle()
        assertEquals(listOf("яблоко"),client.queries)
        assertEquals("яблоко",vm.state.value.externalResults.single().name)
        vm.resetSearch();advanceUntilIdle()
        assertTrue(vm.state.value.externalResults.isEmpty())
    }

    @Test fun `external entry stores only IDs and user portion while totals stay local`()=runTest(dispatcher){
        val store=FakeCalorieStore()
        val food=ExternalFood("321","Яблоко",null,listOf(ExternalServing("s1","100 g",100.0,52.0,0.3,0.2,14.0)),System.currentTimeMillis()/1000)
        val vm=CalorieViewModel(store,Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"),ZoneOffset.UTC),dispatcher)
        advanceUntilIdle()
        var completed=false
        vm.addExternal(food,food.servings.single(),MealType.LUNCH,"150",{completed=true}){fail(it)}
        advanceUntilIdle()
        assertTrue(completed)
        assertEquals("321",store.external.single().foodId)
        assertEquals("s1",store.external.single().servingId)
        assertEquals(150_000,store.external.single().amountGramsMg)
        assertEquals(294,vm.state.value.overallTotals().caloriesKcal)
    }

    @Test fun `online search explains missing pairing without invoking provider`()=runTest(dispatcher){
        val client=FakeFatSecretClient()
        val vm=CalorieViewModel(FakeCalorieStore(),Clock.systemUTC(),dispatcher,client,{null})
        advanceUntilIdle();vm.search("молоко");advanceUntilIdle()
        assertTrue(vm.state.value.externalError.orEmpty().contains("Harness"))
        assertTrue(client.queries.isEmpty())
    }

    @Test fun `ml portion is recorded with volume unit without density guess`()=runTest(dispatcher){
        val store=FakeCalorieStore()
        val serving=ExternalServing("ml1","стакан",null,90.0,3.0,2.0,12.0,200.0,"ml")
        val food=ExternalFood("654","Напиток",null,listOf(serving),System.currentTimeMillis()/1000)
        val vm=CalorieViewModel(store,Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"),ZoneOffset.UTC),dispatcher)
        advanceUntilIdle()
        vm.addExternal(food,serving,MealType.LUNCH,"200",{},::fail)
        advanceUntilIdle()
        assertEquals("ml",store.external.single().amountUnit)
        assertEquals(306,vm.state.value.overallTotals().caloriesKcal)
    }

    @Test fun `external content expiring while save is queued cannot mutate durable totals`()=runTest(dispatcher){
        val store=FakeCalorieStore()
        var now=100_000L
        val serving=ExternalServing("s1","100 g",100.0,52.0,0.3,0.2,14.0)
        val food=ExternalFood("321","Яблоко",null,listOf(serving),13_601L)
        val vm=CalorieViewModel(store,Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"),ZoneOffset.UTC),dispatcher,nowEpochSeconds={now})
        advanceUntilIdle()
        vm.addExternal(food,serving,MealType.LUNCH,"100",{},::fail)
        now=100_001L
        advanceUntilIdle()
        assertTrue(store.external.isEmpty())
        assertEquals(0,store.externalTotal.caloriesKcal)
        assertTrue(vm.state.value.error.orEmpty().contains("устарели"))
    }

    @Test fun `history refresh does not silently stop after thirty distinct food IDs`()=runTest(dispatcher){
        val store=FakeCalorieStore()
        val client=FakeFatSecretClient().apply{canLoadDetails=true}
        repeat(32){index->store.external+=ExternalDiaryEntryEntity(index+1L,"2026-09-12",MealType.LUNCH,(index+1).toString(),"s1",100_000,"g",index.toLong(),index.toLong())}
        val vm=CalorieViewModel(store,Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"),ZoneOffset.UTC),dispatcher,client,{"owner-token"})
        advanceUntilIdle()
        assertEquals(32,client.requestedIds.size)
        assertEquals(32,vm.state.value.externalDetails.size)
        assertTrue(vm.state.value.externalDetailFailedIds.isEmpty())
    }

    @Test fun `manual external day correction permits all zeros and optional entry removal`()=runTest(dispatcher){
        val date=LocalDate.parse("2026-09-12")
        assertEquals(NutritionTotals(),parseExternalDayCorrection("0","0","0","0").getOrThrow())
        assertEquals(NutritionTotals(),parseExternalDayCorrection("","","","").getOrThrow())
        assertTrue(parseExternalDayCorrection("-1","0","0","0").isFailure)
        val store=FakeCalorieStore().apply{
            externalTotal=NutritionTotals(250,10_000,5_000,35_000)
            external+=ExternalDiaryEntryEntity(7,date.toString(),MealType.LUNCH,"321","s1",100_000,"g",7,7)
        }
        val vm=CalorieViewModel(store,Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"),ZoneOffset.UTC),dispatcher)
        advanceUntilIdle()
        var done=false
        vm.correctExternalDay(date,7,NutritionTotals()){done=true}
        advanceUntilIdle()
        assertTrue(done)
        assertTrue(vm.state.value.externalEntries.isEmpty())
        assertEquals(216,vm.state.value.overallTotals().caloriesKcal)
    }
    private fun entry(id:Long,kcal:Int,p:Long,f:Long,c:Long,meal:MealType)=DiaryEntryEntity(id,"2026-09-12",meal,null,"Запись",null,kcal,p,f,c,id,id)
}

private class FakeCalorieStore:CalorieStore {
    val food=FoodEntity(1,"Творог",120,18_000,5_000,3_000,1,1)
    private val all=mutableListOf(DiaryEntryEntity(1,"2026-09-12",MealType.BREAKFAST,1,"Творог",180_000,216,32_400,9_000,5_400,1,1))
    var addCalls=0;var overflow=false;private var next=2L
    var externalTotal=NutritionTotals()
    val external=mutableListOf<ExternalDiaryEntryEntity>()
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
    override suspend fun externalEntries(date:LocalDate)=external.filter{it.localDate==date.toString()}
    override suspend fun externalDayTotal(date:LocalDate)=externalTotal
    override suspend fun addExternalEntry(foodId:String,servingId:String,date:LocalDate,meal:MealType,amountMg:Long,nutrition:NutritionTotals,amountUnit:String):Long{
        val id=next++
        external+=ExternalDiaryEntryEntity(id,date.toString(),meal,foodId,servingId,amountMg,amountUnit,id,id)
        externalTotal=addNutrition(externalTotal,nutrition)
        return id
    }
    override suspend fun correctExternalDay(date:LocalDate,entryId:Long?,nutrition:NutritionTotals){
        if(entryId!=null) external.removeAll{it.id==entryId && it.localDate==date.toString()}
        externalTotal=nutrition
    }
}

private class FakeFatSecretClient:FatSecretClient {
    val queries=mutableListOf<String>()
    val requestedIds=mutableListOf<String>()
    var canLoadDetails=false
    override suspend fun search(token:String,query:String):List<ExternalFoodSummary>{
        queries+=query
        return listOf(ExternalFoodSummary("321",query,null,System.currentTimeMillis()/1000))
    }
    override suspend fun food(token:String,id:String):ExternalFood{
        requestedIds+=id
        if(!canLoadDetails)error("unused")
        return ExternalFood(id,"Продукт $id",null,listOf(ExternalServing("s1","100 g",100.0,10.0,1.0,1.0,1.0)),System.currentTimeMillis()/1000)
    }
    override suspend fun barcode(token:String,gtin13:String):ExternalFood=error("unused")
}
