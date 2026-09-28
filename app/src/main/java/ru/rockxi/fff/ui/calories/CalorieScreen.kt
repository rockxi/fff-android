package ru.rockxi.fff.ui.calories

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.math.BigDecimal
import kotlinx.coroutines.delay
import ru.rockxi.fff.data.calories.*
import ru.rockxi.fff.ui.components.*
import ru.rockxi.fff.ui.theme.FffMetrics
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

private val CalBg=Color(0xFF07100B); private val CalSurface=Color(0xFF101B14); private val CalLime=Color(0xFFB7F35A)
private val CalText=Color(0xFFF1F7ED); private val CalMuted=Color(0xFF95A28F); private val Protein=Color(0xFFFF8D82); private val Fat=Color(0xFFFFC766); private val Carbs=Color(0xFF63DCEB)
private val meals = listOf(MealType.BREAKFAST to "Завтрак", MealType.LUNCH to "Обед", MealType.DINNER to "Ужин", MealType.SNACK to "Перекусы")

@Composable internal fun CalorieScreen(viewModel: CalorieViewModel, onBack:()->Unit) {
    val s by viewModel.state.collectAsState()
    LaunchedEffect(s.externalDetails, s.externalSelected, s.externalResults) {
        val oldest = (s.externalDetails.values.map { it.fetchedAtSeconds } + listOfNotNull(s.externalSelected?.fetchedAtSeconds) + s.externalResults.map { it.fetchedAtSeconds }).minOrNull()
        if (oldest != null) {
            val expiryMillis = (oldest + 86_400) * 1000
            delay((expiryMillis - System.currentTimeMillis()).coerceAtLeast(0))
            viewModel.expireExternal()
        }
    }
    var addMeal by remember { mutableStateOf<MealType?>(null) }; var amountFood by remember { mutableStateOf<FoodEntity?>(null) }; var amountMeal by remember { mutableStateOf(MealType.SNACK) }
    var quickMeal by remember { mutableStateOf<MealType?>(null) }; var editingEntry by remember { mutableStateOf<DiaryEntryEntity?>(null) }
    var deleteEntry by remember { mutableStateOf<DiaryEntryEntity?>(null) }; var datePicker by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }; var foods by remember { mutableStateOf(false) }; var editFood by remember { mutableStateOf<FoodEntity?>(null) }
    var createFood by remember { mutableStateOf(false) }; var deleteFood by remember { mutableStateOf<FoodEntity?>(null) }
    var scannedCode by remember { mutableStateOf<String?>(null) }
    var scannedMeal by remember { mutableStateOf<MealType?>(null) }
    var createFoodMeal by remember { mutableStateOf<MealType?>(null) }
    var createFoodBarcode by remember { mutableStateOf<String?>(null) }
    var foodsBeforeCreate by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var scanBusy by remember { mutableStateOf(false) }
    var externalMeal by remember { mutableStateOf(MealType.SNACK) }
    var externalHistory by remember { mutableStateOf<ExternalDiaryEntryEntity?>(null) }
    var correctingExternal by remember { mutableStateOf<ExternalDiaryEntryEntity?>(null) }
    val context = LocalContext.current
    val scanner = remember(context) {
        GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_EAN_13)
            .enableAutoZoom().build())
    }
    Box(Modifier.fillMaxSize().background(CalBg)) {
        Diary(s,onBack,viewModel::previousDay,viewModel::nextDay,{datePicker=true},{settings=true},{ meal -> viewModel.resetSearch();addMeal=meal },{editingEntry=it},{entry->externalHistory=entry;if(s.externalDetails[entry.foodId]==null)viewModel.refreshExternalHistory(entry.foodId)})
        s.error?.let { Text(it,color=Color(0xFFFF8DA7),modifier=Modifier.align(Alignment.BottomCenter).padding(16.dp).background(CalSurface,RoundedCornerShape(12.dp)).padding(12.dp)) }
    }
    if(datePicker) DateModal(s.date,{datePicker=false}){viewModel.selectDate(it);datePicker=false}
    addMeal?.let { meal -> FoodPicker(s, meal, viewModel::search, {viewModel.resetSearch();addMeal=null;scanError=null}, {food->amountMeal=meal;amountFood=food;viewModel.resetSearch();addMeal=null}, {quickMeal=meal;viewModel.resetSearch();addMeal=null}, {createFoodMeal=meal;createFoodBarcode=null;foodsBeforeCreate=s.allFoods.mapTo(mutableSetOf()){it.id};createFood=true;viewModel.resetSearch();addMeal=null}, {foods=true;viewModel.resetSearch();addMeal=null}, scanBusy, scanError, { item -> externalMeal=meal;addMeal=null;viewModel.resetSearch();viewModel.openExternal(item.id) }) {
        if (!scanBusy) {
            scanError = null
            scanBusy = true
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    scanBusy = false
                    normalizeFoodBarcode(barcode.rawValue)?.let { code ->
                        scannedCode = code
                        scannedMeal = meal
                        viewModel.lookupBarcode(code)
                        viewModel.resetSearch()
                        addMeal = null
                    } ?: run { scanError = "Не удалось прочитать EAN/UPC. Проверьте штрихкод и повторите попытку." }
                }
                .addOnCanceledListener { scanBusy = false }
                .addOnFailureListener {
                    scanBusy = false
                    scanError = "Сканер недоступен. Проверьте Google Play Services или создайте продукт вручную."
                }
        }
    } }
    scannedCode?.let { code -> BarcodeResultModal(code, s.externalSelected, s.externalDetailBusy, s.externalError, {scannedCode=null;scannedMeal=null;viewModel.clearExternalSelection()}, {
        viewModel.clearExternalSelection()
        createFoodMeal=scannedMeal
        createFoodBarcode=code
        foodsBeforeCreate=viewModel.state.value.allFoods.mapTo(mutableSetOf()){it.id}
        scannedCode=null
        scannedMeal=null
        createFood=true
    }, { food -> externalMeal=scannedMeal?:MealType.SNACK;scannedCode=null;scannedMeal=null;viewModel.selectExternal(food) }, { viewModel.lookupBarcode(code) }) }
    if(scannedCode==null && s.externalSelected!=null) {
        val food=s.externalSelected
        if(food!=null) ExternalAmountModal(food,externalMeal,s.busy,{viewModel.clearExternalSelection()}) { serving, amount, invalid ->
            viewModel.addExternal(food,serving,externalMeal,amount,{viewModel.clearExternalSelection()},invalid)
        }
    }
    if(scannedCode==null && s.externalDetailBusy) FffModal("Загружаем продукт",{viewModel.clearExternalSelection()},dismissEnabled=false){LinearProgressIndicator(Modifier.fillMaxWidth(),color=CalLime);FatSecretAttribution()}
    if(scannedCode==null && addMeal==null && s.externalError!=null && s.externalSelected==null && !s.externalDetailBusy) FffModal("Не удалось открыть продукт",{viewModel.clearExternalSelection()},"Закрыть",{viewModel.clearExternalSelection()}){Text(s.externalError.orEmpty(),color=Protein);FatSecretAttribution()}
    externalHistory?.let { entry -> ExternalHistoryModal(entry,s.externalDetails[entry.foodId],s.externalHistoryBusy,s.externalHistoryError,{viewModel.refreshExternalHistory(entry.foodId)},{externalHistory=null;correctingExternal=entry}){externalHistory=null} }
    correctingExternal?.let { entry -> ExternalDayCorrectionModal(entry,s.externalDayTotal,s.busy,{correctingExternal=null}) { totals,deleteEntry ->
        viewModel.correctExternalDay(LocalDate.parse(entry.localDate),if(deleteEntry)entry.id else null,totals){correctingExternal=null}
    } }
    amountFood?.let { food -> AmountModal(food, s.recents.firstOrNull{it.food.id==food.id}?.defaultAmountGramsMg, s.busy, {amountFood=null}, viewModel::preview) { amount,error -> viewModel.addFood(food, amountMeal, amount, {amountFood=null}, error) } }
    quickMeal?.let { meal -> QuickModal(null,s.date,meal,s.busy,{quickMeal=null},save={ values,date,m -> viewModel.saveQuick(null,values,date,m,{quickMeal=null}) }) }
    editingEntry?.let { entry -> if(entry.foodId==null) QuickModal(entry,LocalDate.parse(entry.localDate),entry.mealType,s.busy,{editingEntry=null},{values,date,m->viewModel.saveQuick(entry.id,values,date,m,{editingEntry=null})},{deleteEntry=entry;editingEntry=null}) else EntryAmountModal(entry,resolveFoodForEntry(s,entry),s.busy,{editingEntry=null},{amount,date,m,error->viewModel.updateFoodEntry(entry,amount,date,m,{editingEntry=null},error)},{deleteEntry=entry;editingEntry=null}) }
    deleteEntry?.let { entry -> FffModal("Удалить запись?",{deleteEntry=null},if(s.busy)"Удаление…" else "Удалить",{viewModel.deleteEntry(entry.id){deleteEntry=null}},confirmEnabled=!s.busy,dismissEnabled=!s.busy,destructive=true){Text("«${entry.displayNameSnapshot}» будет удалена безвозвратно.",color=CalMuted)} }
    if(settings) TargetsModal(s.profile,s.busy,{settings=false},{viewModel.resetSearch();foods=true;settings=false}){v->viewModel.saveProfile(v){settings=false}}
    if(foods) FoodsModal(s,viewModel::search,{viewModel.resetSearch();foods=false},{viewModel.resetSearch();foods=false;createFood=true},{viewModel.resetSearch();foods=false;editFood=it},{viewModel.resetSearch();foods=false;deleteFood=it})
    if(createFood || editFood!=null) FoodModal(editFood,s.busy,{createFood=false;editFood=null;createFoodMeal=null;createFoodBarcode=null},createFoodBarcode){values->
        val requestedMeal=createFoodMeal
        val priorIds=foodsBeforeCreate
        viewModel.saveFood(editFood?.id,values){
            val created=if(editFood==null&&requestedMeal!=null) findNewlyCreatedFood(priorIds,viewModel.state.value.allFoods) else null
            createFood=false;editFood=null;createFoodMeal=null;createFoodBarcode=null
            if(created!=null&&requestedMeal!=null){amountMeal=requestedMeal;amountFood=created}
        }
    }
    deleteFood?.let { food -> FffModal("Удалить продукт?",{deleteFood=null},if(s.busy)"Удаление…" else "Удалить",{viewModel.deleteFood(food.id){deleteFood=null}},confirmEnabled=!s.busy,dismissEnabled=!s.busy,destructive=true){Text("«${food.name}» удалится из каталога. Если продукт есть в дневнике, сначала удалите его записи.",color=CalMuted)} }
}

@Composable private fun Diary(s:CalorieState,back:()->Unit,prev:()->Unit,next:()->Unit,date:()->Unit,settings:()->Unit,add:(MealType)->Unit,edit:(DiaryEntryEntity)->Unit,openExternal:(ExternalDiaryEntryEntity)->Unit){
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),contentPadding=PaddingValues(FffMetrics.pageGutter),verticalArrangement=Arrangement.spacedBy(FffMetrics.itemGap)){
        item { Row(verticalAlignment=Alignment.CenterVertically){IconButton(back,Modifier.size(48.dp)){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад",tint=CalText)};Column(Modifier.weight(1f)){Text("NUTRITION LOG",color=CalLime,fontSize=10.sp,letterSpacing=2.sp);Text("Калории",color=CalText,fontSize=26.sp,fontWeight=FontWeight.Bold)};IconButton(settings,Modifier.size(48.dp),enabled=!s.busy){Icon(Icons.Rounded.Settings,"Настройки калорий",tint=CalLime)}} }
        item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){IconButton(prev,Modifier.size(48.dp),enabled=!s.busy){Icon(Icons.Rounded.ChevronLeft,"Предыдущий день",tint=CalLime)};Surface(Modifier.weight(1f).heightIn(min=48.dp).clickable(enabled=!s.busy,onClick=date),shape=RoundedCornerShape(15.dp),color=CalSurface,border=BorderStroke(1.dp,CalLime.copy(.35f))){Row(Modifier.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Rounded.CalendarMonth,null,tint=CalLime);Spacer(Modifier.width(8.dp));Text(formatCalorieDate(s.date,s.today),color=CalText,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)}};IconButton(next,Modifier.size(48.dp),enabled=!s.busy){Icon(Icons.Rounded.ChevronRight,"Следующий день",tint=CalLime)}} }
        item { SummaryCard(s) }
        meals.forEach { (meal,label) -> item(key="head-$meal") { MealHeader(label,s.mealTotal(meal),s.externalEntries.any{it.mealType==meal},!s.busy){add(meal)} }; val values=s.entries.filter{it.mealType==meal}; val external=s.externalEntries.filter{it.mealType==meal}; if(values.isEmpty()&&external.isEmpty()) item(key="empty-$meal"){Text("Пока пусто · нажмите +, чтобы добавить",color=CalMuted,fontSize=12.sp,modifier=Modifier.padding(start=8.dp,end=8.dp,bottom=4.dp))} else {items(values,key={"local-${it.id}"}){entry->EntryRow(entry,!s.busy){edit(entry)}};items(external,key={"external-${it.id}"}){entry->ExternalEntryRow(entry,s.externalDetails[entry.foodId],entry.foodId in s.externalDetailFailedIds){openExternal(entry)}}} }
        item { FatSecretAttribution() }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable private fun SummaryCard(s:CalorieState){val p=s.profile;val t=s.overallTotals();val target=p?.dailyCaloriesKcal?:0;val remaining=target-t.caloriesKcal;Surface(Modifier.fillMaxWidth().semantics{contentDescription=if(p==null)"Загрузка дневника" else "Съедено ${t.caloriesKcal} килокалорий из $target"},shape=RoundedCornerShape(24.dp),color=CalSurface,border=BorderStroke(1.dp,CalLime.copy(.3f))){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){if(p==null){Text("Загрузка дневника…",color=CalMuted);LinearProgressIndicator(Modifier.fillMaxWidth(),color=CalLime);return@Column};Row(verticalAlignment=Alignment.Bottom){Column(Modifier.weight(1f)){Text("СЪЕДЕНО",color=CalMuted,fontSize=10.sp,letterSpacing=1.5.sp);Text("${t.caloriesKcal} ккал",color=CalText,fontSize=30.sp,fontWeight=FontWeight.Bold)};Column(horizontalAlignment=Alignment.End){Text(if(remaining>=0)"Осталось" else "Сверх цели",color=CalMuted);Text("${kotlin.math.abs(remaining)} ккал",color=if(remaining>=0)CalLime else Protein,fontWeight=FontWeight.Bold)}};LinearProgressIndicator({if(target>0)(t.caloriesKcal.toFloat()/target).coerceIn(0f,1f) else 0f},Modifier.fillMaxWidth().height(7.dp),color=CalLime,trackColor=Color(0xFF263126));Macro("Белки",t.proteinMg,p.proteinTargetMg,Protein);Macro("Жиры",t.fatMg,p.fatTargetMg,Fat);Macro("Углеводы",t.carbMg,p.carbTargetMg,Carbs)}}}
@Composable private fun Macro(label:String,value:Long,target:Long,color:Color){val progress=if(target>0)(value.toFloat()/target).coerceIn(0f,1f) else 0f;Column(Modifier.semantics{contentDescription="$label ${gramsText(value)} из ${gramsText(target)} граммов";progressBarRangeInfo=ProgressBarRangeInfo(progress,0f..1f)}){Row{Text(label,color=CalMuted,fontSize=12.sp,modifier=Modifier.weight(1f));Text("${gramsText(value)} / ${gramsText(target)} г",color=CalText,fontSize=12.sp)};LinearProgressIndicator({progress},Modifier.fillMaxWidth().height(5.dp),color=color,trackColor=Color(0xFF263126))}}
@Composable private fun MealHeader(label:String,kcal:Long,hasExternal:Boolean,enabled:Boolean,add:()->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,color=CalText,fontSize=19.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));Text(if(hasExternal)"Локально $kcal ккал" else "$kcal ккал",color=CalMuted,fontSize=if(hasExternal)12.sp else 14.sp);IconButton(add,Modifier.size(48.dp),enabled=enabled){Icon(Icons.Rounded.Add,"Добавить в $label",tint=CalLime)}}}
@Composable private fun EntryRow(e:DiaryEntryEntity,enabled:Boolean,open:()->Unit){Surface(Modifier.fillMaxWidth().heightIn(min=58.dp).clickable(enabled=enabled,onClick=open),shape=RoundedCornerShape(16.dp),color=CalSurface){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(e.displayNameSnapshot,color=CalText,fontWeight=FontWeight.SemiBold);Text(e.amountGramsMg?.let{"${gramsText(it)} г"}?:"Быстрая запись",color=CalMuted,fontSize=12.sp)};Text("${e.caloriesKcal} ккал",color=CalLime,fontWeight=FontWeight.Bold)}}}
@Composable private fun ExternalEntryRow(e:ExternalDiaryEntryEntity,food:ExternalFood?,failed:Boolean,open:()->Unit){
    val fresh=food?.takeIf{fatSecretContentFresh(it.fetchedAtSeconds,System.currentTimeMillis()/1000)}
    Surface(Modifier.fillMaxWidth().heightIn(min=58.dp).clickable(onClick=open),shape=RoundedCornerShape(16.dp),color=CalSurface){
        Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Text(fresh?.name?:"Продукт FatSecret #${e.foodId}",color=CalText,fontWeight=FontWeight.SemiBold)
                Text("${gramsText(e.amountGramsMg)} ${e.amountUnit} · ${if(fresh!=null)"FatSecret" else if(failed)"не загружено, нажмите повторить" else "подробности доступны онлайн"}",color=CalMuted,fontSize=12.sp)
            }
            Icon(Icons.Rounded.CloudSync,"Подробности загружаются из FatSecret",tint=CalMuted)
        }
    }
}
@Composable private fun ExternalHistoryModal(entry:ExternalDiaryEntryEntity,food:ExternalFood?,loading:Boolean,error:String?,retry:()->Unit,correct:()->Unit,dismiss:()->Unit){
    val fresh=food?.takeIf{fatSecretContentFresh(it.fetchedAtSeconds,System.currentTimeMillis()/1000)}
    val serving=fresh?.servings?.firstOrNull{it.id==entry.servingId}
    val nutrients=serving?.takeIf{it.measureUnit==entry.amountUnit}?.let{runCatching{externalPortionNutrition(it,entry.amountGramsMg)}.getOrNull()}
    FffModal(fresh?.name?:"Продукт FatSecret",dismiss){
        Text("${gramsText(entry.amountGramsMg)} ${entry.amountUnit} · ${mealName(entry.mealType)} · ${entry.localDate}",color=CalMuted)
        if(fresh==null) {
            Text("История и итог дня доступны офлайн. Для названия и состава подключитесь к сети; они повторно загружаются из FatSecret.",color=CalMuted)
            if(loading) LinearProgressIndicator(Modifier.fillMaxWidth(),color=CalLime)
            error?.let{Text(it,color=Protein,fontSize=12.sp)}
            OutlinedButton(retry,Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=!loading){Text("Загрузить подробности")}
        }
        else {
            fresh.brand?.let{Text(it,color=CalMuted)}
            serving?.let{Text(it.description,color=CalMuted)}
            nutrients?.let{NutritionPreview(it)} ?: Text("Информация об этой порции больше не доступна в FatSecret.",color=CalMuted)
            Text("Состав показан по текущим данным FatSecret; сохранённый итог дня не меняется.",color=CalMuted,fontSize=12.sp)
        }
        OutlinedButton(correct,Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("Исправить итог дня") }
        FatSecretAttribution()
    }
}

@Composable private fun ExternalDayCorrectionModal(entry:ExternalDiaryEntryEntity,current:NutritionTotals,busy:Boolean,dismiss:()->Unit,save:(NutritionTotals,Boolean)->Unit){
    var calories by remember(entry.id){mutableStateOf(current.caloriesKcal.toString())}
    var protein by remember(entry.id){mutableStateOf(gramsText(current.proteinMg))}
    var fat by remember(entry.id){mutableStateOf(gramsText(current.fatMg))}
    var carbs by remember(entry.id){mutableStateOf(gramsText(current.carbMg))}
    var removeEntry by remember(entry.id){mutableStateOf(false)}
    val parsed=parseExternalDayCorrection(calories,protein,fat,carbs)
    FffModal("Исправить итог дня",dismiss,if(busy)"Сохранение…" else "Сохранить итог",{parsed.getOrNull()?.let{save(it,removeEntry)}},confirmEnabled=!busy&&parsed.isSuccess,dismissEnabled=!busy){
        Text("${entry.localDate} · только сумма записей FatSecret за день",color=CalMuted)
        Text("Это ручная корректировка итога. Исходная пищевая ценность отдельных продуктов FatSecret не сохраняется. При удалении выбранной записи укажите ниже уже исправленный итог дня.",color=CalMuted,fontSize=13.sp)
        CalorieNumericInput("Калории, ккал",calories,{calories=it})
        MacroFields(protein,{protein=it},fat,{fat=it},carbs,{carbs=it})
        parsed.exceptionOrNull()?.message?.let{Text(it,color=Protein,fontSize=12.sp)}
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable{removeEntry=!removeEntry},verticalAlignment=Alignment.CenterVertically){
            Checkbox(removeEntry,{removeEntry=it},colors=CheckboxDefaults.colors(checkedColor=CalLime,checkmarkColor=CalBg))
            Text("Удалить выбранную запись",color=CalText,modifier=Modifier.weight(1f))
        }
        if(removeEntry) Text("Запись #${entry.id} будет удалена только после нажатия «Сохранить итог».",color=Protein,fontSize=12.sp)
    }
}

@Composable private fun FoodPicker(s:CalorieState,meal:MealType,search:(String)->Unit,dismiss:()->Unit,select:(FoodEntity)->Unit,quick:()->Unit,create:()->Unit,all:()->Unit,scanBusy:Boolean,scanError:String?,selectExternal:(ExternalFoodSummary)->Unit,scan:()->Unit){
    val focus=remember{FocusRequester()}
    val keyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit){focus.requestFocus()}
    FffModal("Добавить · ${mealName(meal)}",dismiss,dismissEnabled=!s.busy&&!scanBusy){
        FffTextInput("Поиск продуктов",s.search,search,Modifier.focusRequester(focus))
        Button({keyboard?.hide();scan()},Modifier.fillMaxWidth().heightIn(min=52.dp),enabled=!s.busy&&!scanBusy,colors=ButtonDefaults.buttonColors(containerColor=CalLime,contentColor=CalBg)){
            Icon(Icons.Rounded.QrCodeScanner,null)
            Spacer(Modifier.width(10.dp))
            Text(if(scanBusy)"Открываем сканер…" else "Сканировать штрихкод",fontWeight=FontWeight.SemiBold)
        }
        scanError?.let{Text(it,color=Protein,fontSize=12.sp,modifier=Modifier.semantics{contentDescription=it})}
        Text("Ищите в своих продуктах и FatSecret. Для онлайн-поиска подключите FFF в разделе Harness.",color=CalMuted,fontSize=12.sp)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton(quick,Modifier.weight(1f).heightIn(min=48.dp)){Text("Быстрая запись",maxLines=2)}
            OutlinedButton(create,Modifier.weight(1f).heightIn(min=48.dp)){Text("Новый продукт",maxLines=2)}
        }
        Text(if(s.search.isBlank())"Недавние" else "Мои продукты · результаты",color=CalLime,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=12.dp))
        val values=if(s.search.isBlank())s.recents.map{it.food}else s.foods
        if(values.isEmpty())Text(if(s.search.isBlank())"Добавьте первый продукт или быструю запись" else "В локальном каталоге ничего не найдено",color=CalMuted)
        else values.forEach{food->FoodChoice(food){select(food)}}
        if(s.search.trim().length>=2){
            Text("FatSecret",color=CalLime,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=12.dp))
            when {
                s.externalSearchBusy -> LinearProgressIndicator(Modifier.fillMaxWidth(),color=CalLime)
                s.externalError!=null -> Text(s.externalError,color=Protein,fontSize=13.sp)
                s.externalResults.isEmpty() -> Text("В FatSecret ничего не найдено",color=CalMuted,fontSize=13.sp)
                else -> s.externalResults.forEach { item ->
                    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable { selectExternal(item) }.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f)){
                            Text(item.name,color=CalText,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                            item.brand?.let{Text(it,color=CalMuted,fontSize=12.sp)}
                        }
                        Icon(Icons.Rounded.ChevronRight,null,tint=CalLime)
                    }
                }
            }
        }
        FatSecretAttribution()
        TextButton(all,Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Управление продуктами")}
    }
}

@Composable private fun BarcodeResultModal(code:String,food:ExternalFood?,loading:Boolean,error:String?,dismiss:()->Unit,create:()->Unit,select:(ExternalFood)->Unit,retry:()->Unit){
    val clipboard=LocalClipboardManager.current
    FffModal("Штрихкод считан",dismiss,"Создать продукт",create){
        Surface(shape=RoundedCornerShape(16.dp),color=CalSurface,border=BorderStroke(1.dp,CalLime.copy(.3f))){
            Column(Modifier.fillMaxWidth().padding(16.dp)){
                Text("GTIN-13",color=CalMuted,fontSize=11.sp,letterSpacing=1.sp)
                Text(code,color=CalText,fontSize=22.sp,fontWeight=FontWeight.Bold,modifier=Modifier.semantics{contentDescription="Штрихкод $code"})
            }
        }
        when {
            loading -> { Text("Ищем продукт в FatSecret…",color=CalMuted);LinearProgressIndicator(Modifier.fillMaxWidth(),color=CalLime) }
            food!=null -> {
                Text(food.name,color=CalText,fontWeight=FontWeight.SemiBold)
                food.brand?.let { Text(it,color=CalMuted) }
                Button({select(food)},Modifier.fillMaxWidth().heightIn(min=52.dp),colors=ButtonDefaults.buttonColors(containerColor=CalLime,contentColor=CalBg)){Text("Добавить из FatSecret")}
            }
            else -> {Text(error?:"Продукт не найден. Можно создать свой по данным с упаковки.",color=CalMuted);OutlinedButton(retry,Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Повторить поиск")}}
        }
        FatSecretAttribution()
        OutlinedButton({clipboard.setText(AnnotatedString(code))},Modifier.fillMaxWidth().heightIn(min=48.dp)){
            Icon(Icons.Rounded.ContentCopy,null)
            Spacer(Modifier.width(8.dp))
            Text("Скопировать код")
        }
    }
}
@Composable private fun FatSecretAttribution(){
    val uri=LocalUriHandler.current
    Text("Powered by fatsecret Platform API",color=CalMuted,fontSize=12.sp,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).clickable{uri.openUri("https://platform.fatsecret.com")}.wrapContentHeight(Alignment.CenterVertically).semantics{contentDescription="Powered by fatsecret Platform API, открыть сайт FatSecret"},textAlign=TextAlign.Center)
}

@Composable private fun ExternalAmountModal(food:ExternalFood,meal:MealType,busy:Boolean,dismiss:()->Unit,save:(ExternalServing,String,(String)->Unit)->Unit){
    var serving by remember(food.id){mutableStateOf(food.servings.firstOrNull{it.measureUnit!=null})}
    var amount by remember(food.id){mutableStateOf(serving?.measureAmount?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }?:"100")}
    var error by remember(food.id){mutableStateOf<String?>(null)}
    val grams=parseGrams(amount)
    val totals=if(grams.valid&&serving!=null) runCatching{externalPortionNutrition(serving!!,grams.mg!!)}.getOrNull() else null
    FffModal(food.name,dismiss,if(busy)"Добавление…" else "Добавить",{serving?.let{save(it,amount){error=it}}},confirmEnabled=!busy&&totals!=null,dismissEnabled=!busy){
        food.brand?.let{Text(it,color=CalMuted)}
        Text("Порция",color=CalMuted)
        food.servings.filter{it.measureUnit!=null}.forEach { option ->
            FffChoiceRow(serving?.id==option.id,{serving=option;amount=BigDecimal.valueOf(option.measureAmount!!).stripTrailingZeros().toPlainString();error=null},"${option.description} · ${BigDecimal.valueOf(option.measureAmount!!).stripTrailingZeros().toPlainString()} ${option.measureUnit}")
        }
        if(serving==null) Text("FatSecret не указал массу или объём этой порции. Можно создать свой продукт вручную.",color=Protein)
        else CalorieNumericInput("Количество, ${serving?.measureUnit}",amount,{amount=it;error=null},error=error?:grams.error)
        totals?.let { NutritionPreview(it) }
        Text("Запись сохранит только ID продукта и порцию. Итоги дня останутся офлайн; подробности загрузятся при подключении к сети.",color=CalMuted,fontSize=12.sp)
        FatSecretAttribution()
    }
}
@Composable private fun FoodChoice(food:FoodEntity,click:()->Unit){Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable(onClick=click).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(food.name,color=CalText);Text("на 100 г · Б ${gramsText(food.proteinPer100gMg)} · Ж ${gramsText(food.fatPer100gMg)} · У ${gramsText(food.carbPer100gMg)}",color=CalMuted,fontSize=10.sp)};Text("${food.caloriesPer100gKcal} ккал",color=CalLime)}}

internal fun selectAllRange(value:String)=TextRange(0,value.length)
@Composable private fun CalorieNumericInput(label:String,value:String,onValueChange:(String)->Unit,modifier:Modifier=Modifier,error:String?=null){var field by remember{mutableStateOf(TextFieldValue(value))};LaunchedEffect(value){if(value!=field.text)field=TextFieldValue(value,selection=TextRange(value.length))};OutlinedTextField(field,{field=it;onValueChange(it.text)},modifier.fillMaxWidth().heightIn(min=56.dp).onFocusChanged{if(it.isFocused&&field.text.isNotEmpty())field=field.copy(selection=selectAllRange(field.text))},label={Text(label)},supportingText=error?.let{{Text(it)}},isError=error!=null,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),shape=RoundedCornerShape(15.dp))}
@Composable private fun AmountModal(food:FoodEntity,recent:Long?,busy:Boolean,dismiss:()->Unit,preview:(FoodEntity,String)->Result<NutritionTotals>,save:(String,(String)->Unit)->Unit){var amount by remember(food){mutableStateOf(recent?.let(::gramsText)?:"100")};var error by remember{mutableStateOf<String?>(null)};val focus=remember{FocusRequester()};LaunchedEffect(Unit){focus.requestFocus()};val totals=preview(food,amount).getOrNull();FffModal(food.name,dismiss,if(busy)"Добавление…" else "Добавить",{save(amount){error=it}},confirmEnabled=!busy&&totals!=null,dismissEnabled=!busy){CalorieNumericInput("Количество, г",amount,{amount=it;error=null},Modifier.focusRequester(focus),error?:preview(food,amount).exceptionOrNull()?.message);totals?.let{NutritionPreview(it)}}}
@Composable private fun EntryAmountModal(entry:DiaryEntryEntity,food:FoodEntity?,busy:Boolean,dismiss:()->Unit,save:(String,LocalDate,MealType,(String)->Unit)->Unit,delete:()->Unit){var amount by remember{mutableStateOf(entry.amountGramsMg?.let(::gramsText).orEmpty())};var date by remember{mutableStateOf(LocalDate.parse(entry.localDate).toString())};var meal by remember{mutableStateOf(entry.mealType)};var error by remember{mutableStateOf<String?>(null)};FffModal("Изменить запись",dismiss,if(busy)"Сохранение…" else "Сохранить",{val d=runCatching{LocalDate.parse(date)}.getOrNull();if(d==null)error="Дата: ГГГГ-ММ-ДД" else save(amount,d,meal){error=it}},confirmEnabled=!busy&&food!=null,dismissEnabled=!busy){CalorieNumericInput("Количество, г",amount,{amount=it;error=null},error=error);FffTextInput("Дата",date,{date=it.take(10)});MealChoices(meal){meal=it};TextButton(delete,Modifier.fillMaxWidth().heightIn(min=48.dp)){Icon(Icons.Rounded.Delete,null);Text("Удалить запись")}}}
@Composable private fun NutritionPreview(t:NutritionTotals){Surface(shape=RoundedCornerShape(14.dp),color=CalLime.copy(.08f)){Column(Modifier.fillMaxWidth().padding(12.dp)){Text("${t.caloriesKcal} ккал",color=CalLime,fontWeight=FontWeight.Bold);Text("Б ${gramsText(t.proteinMg)} · Ж ${gramsText(t.fatMg)} · У ${gramsText(t.carbMg)} г",color=CalMuted)}}}

@Composable private fun QuickModal(entry:DiaryEntryEntity?,initialDate:LocalDate,initialMeal:MealType,busy:Boolean,dismiss:()->Unit,save:(FoodFormValues,LocalDate,MealType)->Unit,delete:(()->Unit)?=null){var name by remember{mutableStateOf(entry?.displayNameSnapshot.orEmpty())};var calories by remember{mutableStateOf(entry?.caloriesKcal?.toString().orEmpty())};var protein by remember{mutableStateOf(entry?.let{gramsText(it.proteinMg)}.orEmpty())};var fat by remember{mutableStateOf(entry?.let{gramsText(it.fatMg)}.orEmpty())};var carbs by remember{mutableStateOf(entry?.let{gramsText(it.carbMg)}.orEmpty())};var date by remember{mutableStateOf(initialDate.toString())};var meal by remember{mutableStateOf(initialMeal)};var error by remember{mutableStateOf<String?>(null)};val parsed=parseQuickForm(name,calories,protein,fat,carbs);FffModal(if(entry==null)"Быстрая запись" else "Изменить запись",dismiss,if(busy)"Сохранение…" else "Сохранить",{val d=runCatching{LocalDate.parse(date)}.getOrNull();if(d==null)error="Введите дату ГГГГ-ММ-ДД" else parsed.onSuccess{save(it,d,meal)}.onFailure{error=it.message}},confirmEnabled=!busy&&parsed.isSuccess,dismissEnabled=!busy){FffTextInput("Название",name,{name=it.take(120);error=null});CalorieNumericInput("Калории",calories,{calories=it;error=null});MacroFields(protein,{protein=it},fat,{fat=it},carbs,{carbs=it});FffTextInput("Дата",date,{date=it.take(10)},error=error);MealChoices(meal){meal=it};delete?.let{TextButton(it,Modifier.fillMaxWidth().heightIn(min=48.dp)){Icon(Icons.Rounded.Delete,null);Text("Удалить запись")}}}}
@Composable private fun MacroFields(p:String,setP:(String)->Unit,f:String,setF:(String)->Unit,c:String,setC:(String)->Unit){CalorieNumericInput("Белки, г",p,setP);CalorieNumericInput("Жиры, г",f,setF);CalorieNumericInput("Углеводы, г",c,setC)}
@Composable private fun MealChoices(selected:MealType,onSelect:(MealType)->Unit){Text("Приём пищи",color=CalMuted);meals.forEach{(type,label)->FffChoiceRow(selected==type,{onSelect(type)},label)}}

@Composable private fun FoodModal(current:FoodEntity?,busy:Boolean,dismiss:()->Unit,barcode:String?=null,save:(FoodFormValues)->Unit){
    var name by remember(current){mutableStateOf(current?.name.orEmpty())}
    var calories by remember(current){mutableStateOf(current?.caloriesPer100gKcal?.toString().orEmpty())}
    var p by remember(current){mutableStateOf(current?.let{gramsText(it.proteinPer100gMg)}.orEmpty())}
    var f by remember(current){mutableStateOf(current?.let{gramsText(it.fatPer100gMg)}.orEmpty())}
    var c by remember(current){mutableStateOf(current?.let{gramsText(it.carbPer100gMg)}.orEmpty())}
    var error by remember{mutableStateOf<String?>(null)}
    val clipboard=LocalClipboardManager.current
    val parsed=parseFoodForm(name,calories,p,f,c)
    FffModal(if(current==null)"Новый продукт" else "Изменить продукт",dismiss,if(busy)"Сохранение…" else "Сохранить",{parsed.onSuccess(save).onFailure{error=it.message}},confirmEnabled=!busy&&parsed.isSuccess,dismissEnabled=!busy){
        barcode?.let{code->
            Surface(shape=RoundedCornerShape(14.dp),color=CalSurface,border=BorderStroke(1.dp,CalLime.copy(.3f))){
                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){Text("СЧИТАННЫЙ ШТРИХКОД",color=CalMuted,fontSize=10.sp);Text(code,color=CalText,fontWeight=FontWeight.Bold)}
                    IconButton({clipboard.setText(AnnotatedString(code))},Modifier.size(48.dp)){Icon(Icons.Rounded.ContentCopy,"Скопировать штрихкод $code",tint=CalLime)}
                }
            }
            Text("Пока штрихкод не привязывается к продукту. Заполните данные с упаковки; после сохранения откроется добавление порции.",color=CalMuted,fontSize=12.sp)
        }
        Text("Пищевая ценность на 100 г",color=CalMuted)
        FffTextInput("Название",name,{name=it.take(120);error=null})
        CalorieNumericInput("Калории",calories,{calories=it;error=null},error=error)
        MacroFields(p,{p=it},f,{f=it},c,{c=it})
    }
}
@Composable private fun FoodsModal(s:CalorieState,search:(String)->Unit,dismiss:()->Unit,create:()->Unit,edit:(FoodEntity)->Unit,delete:(FoodEntity)->Unit){FffModal("Мои продукты",dismiss,dismissEnabled=!s.busy){FffTextInput("Поиск",s.search,search);Button(create,Modifier.fillMaxWidth().heightIn(min=48.dp),colors=ButtonDefaults.buttonColors(containerColor=CalLime,contentColor=CalBg)){Icon(Icons.Rounded.Add,null);Text("Создать продукт")};if(s.foods.isEmpty())Text("Каталог пока пуст",color=CalMuted) else s.foods.forEach{food->Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(food.name,color=CalText);Text("${food.caloriesPer100gKcal} ккал / 100 г",color=CalMuted,fontSize=12.sp)};IconButton({edit(food)},enabled=!s.busy){Icon(Icons.Rounded.Edit,"Изменить ${food.name}")};IconButton({delete(food)},enabled=!s.busy){Icon(Icons.Rounded.Delete,"Удалить ${food.name}")}}}}}
@Composable private fun TargetsModal(profile:CalorieProfileEntity?,busy:Boolean,dismiss:()->Unit,foods:()->Unit,save:(FoodFormValues)->Unit){if(profile==null)return;var kcal by remember{mutableStateOf(profile.dailyCaloriesKcal.toString())};var p by remember{mutableStateOf(gramsText(profile.proteinTargetMg))};var f by remember{mutableStateOf(gramsText(profile.fatTargetMg))};var c by remember{mutableStateOf(gramsText(profile.carbTargetMg))};var error by remember{mutableStateOf<String?>(null)};val parsed=parseFoodForm("Цели",kcal,p,f,c).mapCatching{require(it.calories>0){"Цель калорий должна быть положительной"};it};FffModal("Цели и продукты",dismiss,if(busy)"Сохранение…" else "Сохранить цели",{parsed.onSuccess(save).onFailure{error=it.message}},confirmEnabled=!busy&&parsed.isSuccess,dismissEnabled=!busy){Text("Дневные цели",color=CalMuted);CalorieNumericInput("Калории",kcal,{kcal=it;error=null},error=error);MacroFields(p,{p=it},f,{f=it},c,{c=it});OutlinedButton(foods,Modifier.fillMaxWidth().heightIn(min=48.dp)){Icon(Icons.Rounded.RestaurantMenu,null);Text("Мои продукты")}}}
@Composable private fun DateModal(current:LocalDate,dismiss:()->Unit,select:(LocalDate)->Unit){var value by remember{mutableStateOf(current.toString())};val parsed=try{LocalDate.parse(value)}catch(_:DateTimeParseException){null};FffModal("Выбрать дату",dismiss,"Открыть",{parsed?.let(select)},confirmEnabled=parsed!=null){FffTextInput("Дата · ГГГГ-ММ-ДД",value,{value=it.take(10)},error=if(value.length==10&&parsed==null)"Некорректная дата" else null);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("Сегодня" to LocalDate.now(),"Вчера" to LocalDate.now().minusDays(1)).forEach{(label,date)->OutlinedButton({value=date.toString()},Modifier.weight(1f).heightIn(min=48.dp)){Text(label)}}}}}
private fun mealName(type:MealType)=meals.first{it.first==type}.second
