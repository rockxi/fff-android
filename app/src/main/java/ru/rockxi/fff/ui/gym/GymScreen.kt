package ru.rockxi.fff.ui.gym

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.format.DateTimeFormatter
import java.time.LocalDate
import java.util.Locale
import ru.rockxi.fff.data.gym.*
import ru.rockxi.fff.ui.components.*

private val GymBg = Color(0xFF06110C); private val GymSurface = Color(0xFF0C2117); private val GymGreen = Color(0xFF65F2AB)
private val GymGold = Color(0xFFFFD76A); private val GymText = Color(0xFFF0FFF7); private val GymMuted = Color(0xFF8CAD9C)

@Composable internal fun GymScreen(viewModel: GymViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState(); var calendar by remember { mutableStateOf(false) }; var chooser by remember { mutableStateOf(false) }; var creatingExercise by remember { mutableStateOf(false) }; var exerciseEditor by remember { mutableStateOf<GymExerciseEntity?>(null) }; var addingSet by remember { mutableStateOf(false) }; var setEditor by remember { mutableStateOf<GymSetEntity?>(null) }; var deletingSetId by remember { mutableStateOf<Long?>(null) }; var deletingExercise by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(GymBg)) {
        if (state.selectedExercise == null) DayScreen(state, onBack, { calendar = true }, { chooser = true }, viewModel::startWorkoutDay, viewModel::openExercise)
        else ExerciseScreen(state, viewModel::closeExercise, { addingSet = true }, { setEditor = it }, { exerciseEditor = state.selectedExercise }, { deletingExercise = true }, { deletingSetId = it })
        state.error?.let { Text(it, color = Color(0xFFFF8DA7), modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) }
    }
    if (calendar) GymCalendar(state, { calendar = false }, viewModel::previousMonth, viewModel::nextMonth, {
        viewModel.selectToday(); calendar = false
    }) { date -> viewModel.selectDate(date); calendar = false }
    if (chooser) ExerciseChooser(state, { chooser = false }, viewModel::openExercise, { chooser = false; creatingExercise = true })
    if (creatingExercise || exerciseEditor != null) ExerciseModal(state, exerciseEditor, { creatingExercise = false; exerciseEditor = null }) { id, category, name ->
        if (id == null) viewModel.createExercise(category, name) { createdId -> creatingExercise = false; exerciseEditor = null; viewModel.openExercise(createdId) }
        else viewModel.updateExercise(id, category, name) { creatingExercise = false; exerciseEditor = null }
    }
    if (addingSet || setEditor != null) SetModal(setEditor, state.busy, { addingSet = false; setEditor = null }) { id, mode, weight, reps, invalid, done -> viewModel.saveSet(id, mode, weight, reps, invalid) { done(); addingSet = false; setEditor = null } }
    deletingSetId?.let { id -> FffModal("Удалить подход?", { deletingSetId = null }, if(state.busy) "Удаление…" else "Удалить", { viewModel.deleteSet(id) { deletingSetId = null } }, confirmEnabled=!state.busy, dismissEnabled=!state.busy, destructive = true) { Text("Подход будет удалён безвозвратно.") } }
    if (deletingExercise) state.selectedExercise?.let { exercise -> FffModal("Удалить упражнение?", { deletingExercise=false }, if(state.busy) "Удаление…" else "Удалить", { viewModel.deleteExercise(exercise.id) { deletingExercise=false; viewModel.closeExercise() } }, confirmEnabled=!state.busy, dismissEnabled=!state.busy, destructive=true) { Text("Упражнение и все его подходы будут удалены безвозвратно.") } }
}

@Composable private fun DayScreen(s: GymState, back: () -> Unit, calendar: () -> Unit, choose: () -> Unit, start: () -> Unit, open: (Long) -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { IconButton(back, enabled = !s.busy) { Icon(Icons.Rounded.ArrowBack, "Назад", tint = GymText) }; Column(Modifier.weight(1f)) { Text("GYM TRACKER", color = GymGreen, letterSpacing = 2.sp); Text(s.date.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale("ru"))), color = GymText, fontSize = 23.sp, fontWeight = FontWeight.Bold) }; IconButton(calendar, enabled = !s.busy) { Icon(Icons.Rounded.CalendarMonth, "Календарь тренировок", tint = GymGreen) } }
        Text(formatGymDay(s.date, s.today), color = GymMuted)
        if (s.summary.isEmpty()) EmptyDay(!s.busy, s.monthActivity.any { it.localDate == s.date.toString() }, start, choose) else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(s.summary, key = { it.exerciseId }) { item -> Surface(Modifier.fillMaxWidth().clickable(enabled=!s.busy) { open(item.exerciseId) }, shape = RoundedCornerShape(20.dp), color = GymSurface, border = BorderStroke(1.dp, Color(0xFF1E4934))) { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(item.exerciseName, color = GymText, fontWeight = FontWeight.Bold, fontSize = 18.sp); Text(item.categoryName, color = GymMuted) }; Text("${item.setCount} подх.\n${item.totalRepetitions} повт.", color = GymGreen) } } }
            item { Button(choose, Modifier.fillMaxWidth(), enabled=!s.busy, colors = ButtonDefaults.buttonColors(containerColor = GymGreen, contentColor = GymBg)) { Icon(Icons.Rounded.Add, null); Text("Добавить упражнение") } }
        }
    }
}
@Composable private fun EmptyDay(enabled:Boolean, started:Boolean, start: () -> Unit, choose: () -> Unit) { Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = GymSurface) { Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Rounded.FitnessCenter, null, tint = GymGreen, modifier = Modifier.size(40.dp)); Text(if(started) "День тренировки начат" else "Тренировка ещё не началась", color = GymText, fontSize = 19.sp); Text(if(started) "Добавьте первое упражнение" else "Можно сохранить пустой день или сразу записать подход", color = GymMuted); if(!started) OutlinedButton(start, enabled=enabled, border=BorderStroke(1.dp,GymGreen)) { Text("Начать тренировку", color=GymGreen) }; Button(choose, enabled=enabled, colors = ButtonDefaults.buttonColors(containerColor = GymGreen, contentColor = GymBg)) { Text("Выбрать упражнение") } } } }

@Composable private fun GymCalendar(s: GymState, dismiss: () -> Unit, previous: () -> Unit, next: () -> Unit, today: () -> Unit, select: (LocalDate) -> Unit) {
    val activity = s.monthActivity.associateBy { it.localDate }
    FffModal("Календарь тренировок", dismiss, dismissEnabled = !s.busy) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(previous, enabled=!s.busy) { Icon(Icons.Rounded.ChevronLeft, "Предыдущий месяц", tint=GymGreen) }
            Text(formatGymMonth(s.month), Modifier.weight(1f), color=GymText, fontWeight=FontWeight.Bold, textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(next, enabled=!s.busy) { Icon(Icons.Rounded.ChevronRight, "Следующий месяц", tint=GymGreen) }
        }
        Row(Modifier.fillMaxWidth()) { listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс").forEach { Text(it, Modifier.weight(1f), color=GymMuted, fontSize=12.sp, textAlign=androidx.compose.ui.text.style.TextAlign.Center) } }
        gymCalendarGrid(s.month).chunked(7).forEach { week -> Row(Modifier.fillMaxWidth()) { week.forEach { cell ->
            val date=cell.date; val stats=date?.let { activity[it.toString()] }; val selected=date==s.date
            Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp).background(if(selected) Color(0xFF174D35) else Color.Transparent, RoundedCornerShape(10.dp)).clickable(enabled=date!=null && !s.busy) { date?.let(select) }, contentAlignment=Alignment.Center) {
                if(date!=null) Column(horizontalAlignment=Alignment.CenterHorizontally) { Text(date.dayOfMonth.toString(), color=if(date==s.today) GymGreen else GymText, fontWeight=if(selected) FontWeight.Bold else FontWeight.Normal); if(stats!=null) { Box(Modifier.size(5.dp).background(GymGreen, RoundedCornerShape(50))); Text("${stats.setCount}п", color=GymMuted, fontSize=8.sp) } }
            }
        } } }
        val trained=s.monthActivity.count { it.setCount>0 }; val sets=s.monthActivity.sumOf { it.setCount }; val reps=s.monthActivity.sumOf { it.totalRepetitions }
        Text("${s.monthActivity.size} дней · $trained с подходами · $sets подходов · $reps повторов", color=GymMuted, fontSize=12.sp)
        OutlinedButton(today, Modifier.fillMaxWidth(), enabled=!s.busy, border=BorderStroke(1.dp,GymGreen)) { Icon(Icons.Rounded.Today,null,tint=GymGreen); Spacer(Modifier.width(6.dp)); Text("Сегодня",color=GymGreen) }
    }
}

@Composable private fun ExerciseScreen(s: GymState, back: () -> Unit, addSet: () -> Unit, editSet: (GymSetEntity?) -> Unit, editExercise: () -> Unit, deleteExercise: () -> Unit, delete: (Long) -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { IconButton(back, enabled=!s.busy) { Icon(Icons.Rounded.ArrowBack, "К списку", tint = GymText) }; Column(Modifier.weight(1f)) { Text(s.selectedExercise!!.name, color = GymText, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text("${s.categories.firstOrNull { it.id == s.selectedExercise.categoryId }?.name.orEmpty()} · ${formatGymDay(s.date, s.today)}", color = GymMuted) }; IconButton(editExercise, enabled=!s.busy) { Icon(Icons.Rounded.Edit, "Редактировать упражнение", tint = GymGreen) }; IconButton(deleteExercise, enabled=!s.busy) { Icon(Icons.Rounded.DeleteForever, "Удалить упражнение", tint=Color(0xFFFF8DA7)) } }
        Button(addSet, Modifier.fillMaxWidth(), enabled=!s.busy, colors = ButtonDefaults.buttonColors(containerColor = GymGreen, contentColor = GymBg)) { Icon(Icons.Rounded.Add, null); Text("Добавить подход") }
        if (s.sets.isEmpty()) Text("Подходов пока нет", color = GymMuted) else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(s.sets, key = { it.set.id }) { item -> SetCard(item, !s.busy, { editSet(item.set) }, { delete(item.set.id) }) } }
    }
}
@Composable private fun SetCard(item: GymSetWithRecord, enabled:Boolean, edit: () -> Unit, delete: () -> Unit) { val gold = item.isAllTimeRecord; Surface(Modifier.fillMaxWidth().semantics { contentDescription = if (gold) "Личный рекорд" else "Подход" }, shape = RoundedCornerShape(18.dp), color = if (gold) Color(0xFF24301B) else GymSurface, border = BorderStroke(if (gold) 2.dp else 1.dp, if (gold) GymGold else Color(0xFF1E4934)), shadowElevation = if (gold) 12.dp else 0.dp) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { if (gold) Icon(Icons.Rounded.EmojiEvents, "Личный рекорд", tint = GymGold); Column(Modifier.weight(1f).padding(start = if (gold) 10.dp else 0.dp)) { Text(setWeightLabel(item.set), color = if (gold) GymGold else GymText, fontWeight = FontWeight.Bold, style = LocalTextStyle.current.copy(shadow = if (gold) Shadow(GymGold, blurRadius = 12f) else Shadow.None)); Text("${item.set.repetitions} повторений${if (gold) " · ЛИЧНЫЙ РЕКОРД" else ""}", color = if (gold) GymGold else GymMuted) }; IconButton(edit, enabled=enabled) { Icon(Icons.Rounded.Edit, "Изменить подход", tint = GymMuted) }; IconButton(delete, enabled=enabled) { Icon(Icons.Rounded.Delete, "Удалить подход", tint = Color(0xFFFF8DA7)) } } } }

@Composable private fun ExerciseChooser(s: GymState, dismiss: () -> Unit, open: (Long) -> Unit, create: () -> Unit) { FffModal("Выберите упражнение", dismiss, dismissEnabled=!s.busy) { s.categories.forEach { category -> Text(category.name, color = GymGreen, modifier = Modifier.padding(top = 10.dp)); s.exercises.filter { it.categoryId == category.id }.forEach { ex -> FffChoiceRow(false, { if(!s.busy) { dismiss(); open(ex.id) } }, ex.name) } }; Spacer(Modifier.height(12.dp)); Button(create, Modifier.fillMaxWidth(), enabled=!s.busy) { Text("Создать упражнение") } } }
@Composable private fun ExerciseModal(s: GymState, current: GymExerciseEntity?, dismiss: () -> Unit, save: (Long?, Long, String) -> Unit) { var name by remember(current) { mutableStateOf(current?.name.orEmpty()) }; var category by remember(current, s.categories) { mutableStateOf(current?.categoryId ?: s.categories.firstOrNull()?.id ?: 0) }; var error by remember { mutableStateOf<String?>(null) }; FffModal(if(current==null) "Новое упражнение" else "Упражнение", dismiss, if(s.busy) "Сохранение…" else "Сохранить", { if(name.isBlank()) error="Введите название" else save(current?.id, category, name.trim()) }, confirmEnabled = category != 0L && !s.busy, dismissEnabled=!s.busy) { FffTextInput("Название", name, { name=it; error=null }, error=error); Spacer(Modifier.height(10.dp)); Text("Категория"); s.categories.forEach { FffChoiceRow(category==it.id, { if(!s.busy) category=it.id }, it.name) } } }
@Composable private fun SetModal(current: GymSetEntity?, busy:Boolean, dismiss: () -> Unit, save: (Long?, GymSetMode, String, String, (SetValidation)->Unit, ()->Unit)->Unit) { var mode by remember(current) { mutableStateOf(current?.mode ?: GymSetMode.EXTERNAL_WEIGHT) }; var weight by remember(current) { mutableStateOf(current?.let { formatWeight(it.weightGrams ?: it.bodyWeightGrams!!).removeSuffix(" кг") }.orEmpty()) }; var reps by remember(current) { mutableStateOf(current?.repetitions?.toString().orEmpty()) }; var validation by remember { mutableStateOf(SetValidation()) }; FffModal(if(current==null) "Новый подход" else "Изменить подход", dismiss, if(busy) "Сохранение…" else "Сохранить", { save(current?.id, mode, weight, reps, { validation=it }, {}) }, confirmEnabled=!busy, dismissEnabled=!busy) { FffChoiceRow(mode==GymSetMode.EXTERNAL_WEIGHT, { if(!busy) mode=GymSetMode.EXTERNAL_WEIGHT }, "Вес снаряда"); FffChoiceRow(mode==GymSetMode.BODY_WEIGHT, { if(!busy) mode=GymSetMode.BODY_WEIGHT }, "Собственный вес", supportingText="Укажите фактический вес тела"); Spacer(Modifier.height(8.dp)); FffTextInput(if(mode==GymSetMode.BODY_WEIGHT) "Собственный вес, кг" else "Вес, кг", weight, { if(!busy) { weight=it; validation=validation.copy(weightError=null) } }, kind=FffInputKind.MONEY, error=validation.weightError); FffTextInput("Повторения", reps, { if(!busy) { reps=it; validation=validation.copy(repetitionsError=null) } }, kind=FffInputKind.MONEY, error=validation.repetitionsError) } }
