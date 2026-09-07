package ru.rockxi.fff.ui.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import ru.rockxi.fff.data.finance.*
import ru.rockxi.fff.ui.components.FffModal
import ru.rockxi.fff.ui.theme.*
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import java.time.format.DateTimeFormatter

private enum class FinanceTab(val title: String) { OVERVIEW("Обзор"), BUDGETS("Бюджеты"), OPERATIONS("Операции"), ANALYTICS("Аналитика"), MANAGE("Ещё") }
private enum class DialogKind { ENTRY, ACCOUNT, CATEGORY, BUDGET, ALLOCATION }
private data class DeleteRequest(val title: String, val detail: String, val action: () -> Unit)

internal fun shouldCloseBudgetDetailsOnBack(isBudgetsTab: Boolean, selectedBudgetId: Long?): Boolean =
    isBudgetsTab && selectedBudgetId != null

@Composable
fun FinanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val model: FinanceViewModel = viewModel(factory = FinanceViewModel.factory(RepositoryFinanceStore(FinanceRepository(FinanceDatabase.get(context)))))
    val state by model.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(FinanceTab.OVERVIEW) }
    var dialog by remember { mutableStateOf<DialogKind?>(null) }
    var selectedBudgetId by remember { mutableStateOf<Long?>(null) }
    var deleteRequest by remember { mutableStateOf<DeleteRequest?>(null) }
    // Do not save a transient document grant across Activity recreation. If the
    // screen is recreated, the user selects the backup again instead of leaving
    // an invalid URI in saved state.
    var pendingRestoreUri by remember { mutableStateOf<String?>(null) }
    var backupPickerInFlight by remember { mutableStateOf(false) }
    val resolver = context.applicationContext.contentResolver
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        backupPickerInFlight = false
        if (uri != null) model.createBackup(openOutput = { resolver.openOutputStream(uri) })
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        backupPickerInFlight = false
        pendingRestoreUri = uri?.toString()
    }
    fun openDialog(kind: DialogKind) { model.clearError(); dialog = kind }
    fun closeDialog() { model.clearError(); dialog = null }
    BackHandler(enabled = shouldCloseBudgetDetailsOnBack(tab == FinanceTab.BUDGETS, selectedBudgetId)) {
        selectedBudgetId = null
    }

    Scaffold(
        containerColor = FffBackground,
        topBar = { FinanceHeader(onBack) },
        floatingActionButton = {
            if (availableEntryKinds(state).isNotEmpty()) ExtendedFloatingActionButton(
                text = { Text("Операция") }, icon = { Icon(Icons.Rounded.Add, null) },
                onClick = { openDialog(DialogKind.ENTRY) }, containerColor = FffMint, contentColor = Color(0xFF07100D),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0B0F14)) {
                FinanceTab.entries.forEach { item ->
                    val icon = when(item) { FinanceTab.OVERVIEW -> Icons.Rounded.AccountBalanceWallet; FinanceTab.BUDGETS -> Icons.Rounded.Savings; FinanceTab.OPERATIONS -> Icons.AutoMirrored.Rounded.ReceiptLong; FinanceTab.ANALYTICS -> Icons.Rounded.PieChart; FinanceTab.MANAGE -> Icons.Rounded.Tune }
                    NavigationBarItem(selected = tab == item, onClick = {
                        if (tab != item) selectedBudgetId = null
                        tab = item
                    }, icon = { Icon(icon, item.title, Modifier.size(21.dp)) }, label = { Text(item.title, maxLines = 1, fontSize = 9.sp) })
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = FffMint)
                else -> AnimatedContent(tab, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) }, label = "finance-tab") { selected -> when(selected) {
                    FinanceTab.OVERVIEW -> Overview(state, onAdd = ::openDialog)
                    FinanceTab.BUDGETS -> if (selectedBudgetId == null) Budgets(state, model::changeMonth, ::openDialog) { selectedBudgetId = it } else BudgetDetails(state, selectedBudgetId!!, { selectedBudgetId = null }) { entry -> deleteRequest = DeleteRequest("Удалить операцию?", "Баланс счетов будет пересчитан. Отменить это действие нельзя.") { model.deleteEntry(entry.id) } }
                    FinanceTab.OPERATIONS -> Operations(state) { entry -> deleteRequest = DeleteRequest("Удалить операцию?", "Баланс счетов будет пересчитан. Отменить это действие нельзя.") { model.deleteEntry(entry.id) } }
                    FinanceTab.ANALYTICS -> Analytics(state)
                    FinanceTab.MANAGE -> Management(
                        state, ::openDialog,
                        archiveAccount = model::archiveAccount,
                        archiveCategory = model::archiveCategory,
                        archiveBudget = model::archiveBudget,
                        requestAccountDelete = { entity -> deleteRequest = DeleteRequest("Удалить счёт «${entity.name}»?", "Удалить можно только архивный счёт без операций.") { model.deleteAccount(entity.id) } },
                        requestCategoryDelete = { entity -> deleteRequest = DeleteRequest("Удалить категорию «${entity.name}»?", "Удалить можно только архивную категорию без операций.") { model.deleteCategory(entity.id) } },
                        requestBudgetDelete = { entity -> deleteRequest = DeleteRequest("Удалить бюджет «${entity.name}»?", "Удалить можно только архивный бюджет без категорий.") { model.deleteBudget(entity.id) } },
                        backupWorking = state.backupBusy || backupPickerInFlight,
                        exportBackup = {
                            if (!state.backupBusy && !backupPickerInFlight) {
                                backupPickerInFlight = true
                                exportLauncher.launch("fff-finance-${java.time.LocalDate.now()}.json")
                            }
                        },
                        importBackup = {
                            if (!state.backupBusy && !backupPickerInFlight) {
                                backupPickerInFlight = true
                                importLauncher.launch(arrayOf("application/json", "text/plain"))
                            }
                        },
                    )
                } }
            }
            if (dialog == null) {
                state.error?.let { ErrorBanner(it, Modifier.align(Alignment.TopCenter)) }
                if (state.error == null) state.notice?.let { NoticeBanner(it, Modifier.align(Alignment.TopCenter)) }
            }
        }
    }
    when(dialog) {
        DialogKind.ACCOUNT -> AccountDialog(state.error, ::closeDialog) { name, currency, balance -> model.createAccount(name, currency, balance) { if (it) closeDialog() } }
        DialogKind.CATEGORY -> CategoryDialog(state, ::closeDialog) { name, kind, budget, emoji -> model.createCategory(name, kind, budget, emoji) { if (it) closeDialog() } }
        DialogKind.BUDGET -> BudgetDialog(state.error, ::closeDialog) { name, currency -> model.createBudget(name, currency) { if (it) closeDialog() } }
        DialogKind.ALLOCATION -> AllocationDialog(state, ::closeDialog) { budget, amount -> model.setAllocation(budget, amount) { if (it) closeDialog() } }
        DialogKind.ENTRY -> EntryDialog(state, ::closeDialog) { kind, amount, account, category, target, note -> model.addEntry(kind, amount, account, category, target, note) { if (it) closeDialog() } }
        null -> Unit
    }
    deleteRequest?.let { request ->
        FffModal(request.title, { deleteRequest = null }, "Удалить", {
            deleteRequest = null
            request.action()
        }, destructive = true) { Text(request.detail, color = FffMuted, fontSize = 13.sp, lineHeight = 19.sp) }
    }
    pendingRestoreUri?.let { uriText ->
        FffModal(
            title = "Восстановить резервную копию?",
            onDismiss = { pendingRestoreUri = null },
            confirmText = "Заменить данные",
            onConfirm = {
                pendingRestoreUri = null
                model.restoreBackup(openInput = { resolver.openInputStream(android.net.Uri.parse(uriText)) })
            },
            confirmEnabled = !state.backupBusy,
            dismissEnabled = !state.backupBusy,
            destructive = true,
        ) {
            Text("Все текущие данные Finance будут заменены содержимым выбранной копии. Операцию нельзя отменить.", color = FffMuted, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

@Composable private fun Budgets(state: FinanceUiState, changeMonth: (Long) -> Unit, onAdd: (DialogKind) -> Unit, onBudget: (Long) -> Unit) = LazyColumn(
    Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp, 10.dp, 14.dp, 92.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
) {
    item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick={changeMonth(-1)}) { Icon(Icons.Rounded.ChevronLeft,"Предыдущий месяц") }
        Text(state.selectedMonth.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru"))).replaceFirstChar { it.uppercase() }, Modifier.weight(1f), textAlign=androidx.compose.ui.text.style.TextAlign.Center, fontWeight=FontWeight.Bold)
        IconButton(onClick={changeMonth(1)}) { Icon(Icons.Rounded.ChevronRight,"Следующий месяц") }
    } }
    item { Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton({onAdd(DialogKind.BUDGET)}, Modifier.weight(1f)) { Text("Новый", maxLines=1) }
        Button({onAdd(DialogKind.ALLOCATION)}, Modifier.weight(1f)) { Text("Распределить", maxLines=1) }
    } }
    items(state.budgetStatuses, key={it.budget.id}) { status -> BudgetCard(status) { onBudget(status.budget.id) } }
}

@Composable private fun BudgetDetails(state: FinanceUiState, budgetId: Long, onBack: () -> Unit, requestDelete: (LedgerEntryEntity) -> Unit) {
    val budget = state.allBudgets.firstOrNull { it.id == budgetId } ?: return
    val status = state.budgetStatuses.firstOrNull { it.budget.id == budgetId }
    val breakdown = remember(budgetId, state.selectedMonth, state.allCategories, state.entries) {
        budgetBreakdown(budgetId, state.selectedMonth, state.allCategories, state.entries)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp, 8.dp, 14.dp, 92.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "К бюджетам") }; Column { Text(budget.name, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text(state.selectedMonth.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru"))), color = FffMuted) } } }
        if (status != null) item { BudgetCard(status, {}) }
        item { SectionTitle("По категориям") }
        if (breakdown.categories.isEmpty()) item { EmptyText("В этом месяце расходов пока нет") }
        items(breakdown.categories, key = { it.category.id }) { item ->
            Row(Modifier.fillMaxWidth().background(FffSurface, RoundedCornerShape(14.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.category.emoji, fontSize = 28.sp)
                Text(item.category.name, Modifier.padding(start = 12.dp).weight(1f))
                Text(formatMoney(item.amountMinor, budget.currency), color = Color(0xFFFF7C9B), fontWeight = FontWeight.Bold)
            }
        }
        item { SectionTitle("Операции") }
        items(breakdown.entries, key = { it.id }) { entry -> EntryRow(entry, state, { requestDelete(entry) }) }
    }
}

@Composable private fun BudgetCard(status: BudgetStatus, onClick: () -> Unit) {
    val ratio = if(status.allocatedMinor <= 0) if(status.spentMinor > 0) 1f else 0f else (status.spentMinor.toDouble()/status.allocatedMinor).coerceIn(0.0,1.0).toFloat()
    val overspent = status.remainingMinor < 0
    Column(Modifier.fillMaxWidth().background(FffSurface,RoundedCornerShape(16.dp)).border(1.dp,if(overspent) Color(0xFFFF7C9B) else FffLine,RoundedCornerShape(16.dp)).clickable(onClick=onClick).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) { Text(status.budget.name,Modifier.weight(1f),fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(status.budget.currency,color=FffMuted,fontSize=11.sp) }
        LinearProgressIndicator(progress={ratio},Modifier.fillMaxWidth(),color=if(overspent) Color(0xFFFF7C9B) else FffMint,trackColor=FffLine)
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text("Выделено ${formatMoney(status.allocatedMinor,status.budget.currency)}",Modifier.fillMaxWidth(),fontSize=11.sp,color=FffMuted,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("Потрачено ${formatMoney(status.spentMinor,status.budget.currency)}",Modifier.fillMaxWidth(),fontSize=11.sp,color=FffMuted,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        Text((if(overspent) "Перерасход " else "Осталось ")+formatMoney(kotlin.math.abs(status.remainingMinor),status.budget.currency),color=if(overspent) Color(0xFFFF7C9B) else FffMint,fontWeight=FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun FinanceHeader(onBack: () -> Unit) = TopAppBar(
    title = { Column { Text("Finance", fontWeight = FontWeight.Bold); Text("LOCAL LEDGER", color = FffMint, fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp) } },
    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
    colors = TopAppBarDefaults.topAppBarColors(containerColor = FffBackground),
)

@Composable private fun Overview(state: FinanceUiState, onAdd: (DialogKind) -> Unit) = LazyColumn(
    Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 92.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
) {
    item {
        if (state.accounts.isEmpty()) EmptyCard("Пока нет счетов", "Создайте первый локальный счёт, чтобы добавлять операции.", "Создать счёт") { onAdd(DialogKind.ACCOUNT) }
        else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val activeCurrencies = state.accounts.map { it.currency }.toSet()
            state.currencySummaries.filter { it.currency in activeCurrencies }.forEach { MetricCard("БАЛАНС · ${it.currency}", formatMoney(it.balanceMinor, it.currency), FffViolet) }
        }
    }
    if (state.accounts.isNotEmpty()) {
        item { SectionTitle("Счета") }
        items(state.accounts, key = { it.id }) { AccountCard(it) }
        item { SectionTitle("Последние операции") }
        if (state.entries.isEmpty()) item { EmptyText("Операций пока нет") }
        items(state.entries.take(5), key = { it.id }) { EntryRow(it, state) }
    }
}

@Composable private fun Operations(state: FinanceUiState, onDelete: (LedgerEntryEntity) -> Unit) = LazyColumn(
    Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 92.dp), verticalArrangement = Arrangement.spacedBy(9.dp),
) {
    item { SectionTitle("История") }
    if (state.entries.isEmpty()) item { EmptyText("Добавьте первую операцию кнопкой ниже") }
    items(state.entries, key = { it.id }) { entry -> EntryRow(entry, state) { onDelete(entry) } }
}

@Composable private fun Analytics(state: FinanceUiState) = LazyColumn(
    Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 92.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
) {
    item { SectionTitle("За всё время") }
    if (state.currencySummaries.isEmpty()) item { EmptyText("Создайте счёт, чтобы увидеть аналитику") }
    state.currencySummaries.forEach { summary ->
        item(key = "income-${summary.currency}") { MetricCard("ДОХОДЫ · ${summary.currency}", formatMoney(summary.incomeMinor, summary.currency), FffMint) }
        item(key = "expense-${summary.currency}") { MetricCard("РАСХОДЫ · ${summary.currency}", formatMoney(summary.expenseMinor, summary.currency), Color(0xFFFF7C9B)) }
        item(key = "net-${summary.currency}") { MetricCard("ДЕНЕЖНЫЙ ПОТОК · ${summary.currency}", formatMoney(summary.netMinor, summary.currency), FffViolet) }
    }
    item { Text("Переводы между счетами не входят в доходы и расходы.", color = FffMuted, fontSize = 13.sp, lineHeight = 19.sp) }
}

@Composable private fun Management(
    state: FinanceUiState,
    onAdd: (DialogKind) -> Unit,
    archiveAccount: (Long, Boolean) -> Unit,
    archiveCategory: (Long, Boolean) -> Unit,
    archiveBudget: (Long, Boolean) -> Unit,
    requestAccountDelete: (AccountEntity) -> Unit,
    requestCategoryDelete: (CategoryEntity) -> Unit,
    requestBudgetDelete: (BudgetEntity) -> Unit,
    backupWorking: Boolean,
    exportBackup: () -> Unit,
    importBackup: () -> Unit,
) = LazyColumn(
    Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 92.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
) {
    item { ManagementHeading("Счета", "Новый счёт") { onAdd(DialogKind.ACCOUNT) } }
    if (state.allAccounts.isEmpty()) item { EmptyText("Счетов нет") }
    items(state.allAccounts, key = { "a${it.id}" }) { entity -> ManageRow(entity.name, "${entity.currency} · ${formatMoney(entity.balanceMinor, entity.currency)}", entity.archived, { archiveAccount(entity.id, !entity.archived) }, { requestAccountDelete(entity) }) }
    item { Spacer(Modifier.height(8.dp)); ManagementHeading("Категории", "Новая категория") { onAdd(DialogKind.CATEGORY) } }
    if (state.allCategories.isEmpty()) item { EmptyText("Категорий нет") }
    items(state.allCategories, key = { "c${it.id}" }) { entity -> val budget=state.allBudgets.firstOrNull{it.id==entity.budgetId}?.name ?: "—"; ManageRow("${entity.emoji}  ${entity.name}", (if(entity.kind == CategoryKind.INCOME) "Доход" else "Расход")+" · $budget", entity.archived, { archiveCategory(entity.id, !entity.archived) }, { requestCategoryDelete(entity) }) }
    item { Spacer(Modifier.height(8.dp)); ManagementHeading("Бюджеты", "Новый бюджет") { onAdd(DialogKind.BUDGET) } }
    items(state.allBudgets, key = { "b${it.id}" }) { entity -> ManageRow(entity.name, entity.currency, entity.archived, { archiveBudget(entity.id, !entity.archived) }, { requestBudgetDelete(entity) }) }
    item {
        Spacer(Modifier.height(8.dp)); SectionTitle("Резервная копия")
        Text("Экспорт включает счета, бюджеты, категории, операции и архив.", color = FffMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
        if (backupWorking) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = FffMint)
            Text("Обработка резервной копии…", Modifier.padding(start = 10.dp), color = FffMuted, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(exportBackup, Modifier.weight(1f), enabled = !backupWorking) { Icon(Icons.Rounded.UploadFile, null); Text("Сохранить") }
            Button(importBackup, Modifier.weight(1f), enabled = !backupWorking) { Icon(Icons.Rounded.Download, null); Text("Загрузить") }
        }
    }
}

@Composable private fun ManagementHeading(title: String, action: String, onClick: () -> Unit) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    SectionTitle(title, Modifier.weight(1f)); TextButton(onClick = onClick) { Icon(Icons.Rounded.Add, null); Text(action) }
}

@Composable private fun MetricCard(label: String, value: String, accent: Color) = Column(Modifier.fillMaxWidth().background(accent.copy(alpha=.09f), RoundedCornerShape(18.dp)).border(1.dp, accent.copy(alpha=.2f), RoundedCornerShape(18.dp)).padding(18.dp)) {
    Text(label, color = accent, fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp)
    Text(value, color = FffText, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
}
@Composable private fun AccountCard(a: AccountEntity) = Row(Modifier.fillMaxWidth().background(FffSurface, RoundedCornerShape(15.dp)).border(1.dp, FffLine, RoundedCornerShape(15.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(Icons.Rounded.CreditCard, null, tint = FffMint); Column(Modifier.padding(start=12.dp).weight(1f)) { Text(a.name, fontWeight=FontWeight.SemiBold, maxLines=1, overflow=TextOverflow.Ellipsis); Text(a.currency, color=FffMuted, fontSize=11.sp) }; Text(formatMoney(a.balanceMinor, a.currency), fontWeight=FontWeight.Bold)
}
@Composable private fun EntryRow(e: LedgerEntryEntity, state: FinanceUiState, onDelete: (() -> Unit)? = null) {
    val color = when(e.kind) { EntryKind.INCOME -> FffMint; EntryKind.EXPENSE -> Color(0xFFFF7C9B); EntryKind.TRANSFER -> FffViolet }
    val title = when(e.kind) { EntryKind.TRANSFER -> "Перевод"; else -> state.allCategories.firstOrNull { it.id == e.categoryId }?.name ?: if(e.kind == EntryKind.INCOME) "Доход" else "Расход" }
    val currency = state.allAccounts.firstOrNull { it.id == e.accountId }?.currency ?: "RUB"
    Row(Modifier.fillMaxWidth().background(FffSurface, RoundedCornerShape(14.dp)).padding(14.dp), verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, maxLines=1, overflow=TextOverflow.Ellipsis); Text(SimpleDateFormat("dd MMM, HH:mm", Locale("ru")).format(Date(e.occurredAt)) + if(e.note.isBlank()) "" else " · ${e.note}", color=FffMuted, fontSize=11.sp, maxLines=1, overflow=TextOverflow.Ellipsis) }
        Text((if(e.kind==EntryKind.INCOME) "+" else if(e.kind==EntryKind.EXPENSE) "−" else "") + formatMoney(e.amountMinor, currency), color=color, fontWeight=FontWeight.Bold)
        if(onDelete != null) IconButton(onClick=onDelete) { Icon(Icons.Rounded.DeleteOutline, "Удалить", tint=Color(0xFFFF7C9B)) }
    }
}
@Composable private fun ManageRow(name: String, meta: String, archived: Boolean, archive: () -> Unit, delete: () -> Unit) = Row(Modifier.fillMaxWidth().background(FffSurface, RoundedCornerShape(14.dp)).padding(10.dp), verticalAlignment=Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) { Text(name, color=if(archived) FffMuted else FffText, maxLines=1, overflow=TextOverflow.Ellipsis); Text(if(archived) "$meta · В архиве" else meta, color=FffMuted, fontSize=11.sp) }
    TextButton(onClick=archive) { Text(if(archived) "Вернуть" else "В архив") }
    if(archived) IconButton(onClick=delete) { Icon(Icons.Rounded.DeleteForever, "Удалить навсегда", tint=Color(0xFFFF7C9B)) }
}

@Composable private fun EmptyCard(title: String, text: String, action: String, onClick: () -> Unit) = Column(Modifier.fillMaxWidth().background(FffSurface, RoundedCornerShape(18.dp)).border(1.dp,FffLine,RoundedCornerShape(18.dp)).padding(20.dp)) { Text(title,fontSize=20.sp,fontWeight=FontWeight.Bold); Text(text,color=FffMuted,fontSize=13.sp,lineHeight=19.sp,modifier=Modifier.padding(vertical=9.dp)); Button(onClick=onClick){Text(action)} }
@Composable private fun EmptyText(text: String) = Text(text,color=FffMuted,modifier=Modifier.fillMaxWidth().background(FffSurface,RoundedCornerShape(14.dp)).padding(18.dp))
@Composable private fun SectionTitle(text: String, modifier: Modifier = Modifier) = Text(text, modifier=modifier, fontWeight=FontWeight.Bold,fontSize=18.sp)
@Composable private fun ErrorBanner(text: String, modifier: Modifier = Modifier) = Text(text,color=Color.White,fontSize=12.sp,modifier=modifier.fillMaxWidth().background(Color(0xFF8F2743)).padding(12.dp))
@Composable private fun NoticeBanner(text: String, modifier: Modifier = Modifier) = Text(text,color=Color(0xFF07100D),fontSize=12.sp,modifier=modifier.fillMaxWidth().background(FffMint).padding(12.dp))

@Composable private fun AccountDialog(error: String?, dismiss: () -> Unit, save: (String,String,String)->Unit) {
    var name by remember { mutableStateOf("") }; var currency by remember { mutableStateOf("RUB") }; var balance by remember { mutableStateOf("") }
    FormDialog("Новый счёт", error, dismiss, { save(name,currency,balance) }) { Field("Название",name){name=it}; Field("Валюта (RUB, USD…)",currency){currency=it}; Field("Начальный баланс",balance){balance=it} }
}
@Composable private fun CategoryDialog(state: FinanceUiState, dismiss: () -> Unit, save: (String,CategoryKind,Long?,String)->Unit) {
    var name by remember { mutableStateOf("") }; var kind by remember { mutableStateOf(CategoryKind.EXPENSE) }; var budget by remember { mutableStateOf<Long?>(null) }; var emoji by remember { mutableStateOf("🛒") }
    FormDialog("Новая категория", state.error, dismiss, { save(name,kind,budget,emoji) }) { Field("Название",name){name=it}; Text("Смайлик", color=FffMuted, fontSize=12.sp); EmojiPicker(emoji){emoji=it}; ChoiceRow(listOf("Расход" to CategoryKind.EXPENSE,"Доход" to CategoryKind.INCOME),kind){kind=it}; SelectList("Бюджет",state.budgets,budget,{it.id},{"${it.name} · ${it.currency}"}){budget=it} }
}
@Composable private fun BudgetDialog(error:String?,dismiss:()->Unit,save:(String,String)->Unit){var name by remember{mutableStateOf("")};var currency by remember{mutableStateOf("RUB")};FormDialog("Новый бюджет",error,dismiss,{save(name,currency)}){Field("Название",name){name=it};Field("Валюта",currency){currency=it}}}
@Composable private fun AllocationDialog(state:FinanceUiState,dismiss:()->Unit,save:(Long?,String)->Unit){var budget by remember{mutableStateOf<Long?>(state.budgets.firstOrNull()?.id)};var amount by remember{mutableStateOf("")};FormDialog("Бюджет на месяц",state.error,dismiss,{save(budget,amount)}){SelectList("Бюджет",state.budgets,budget,{it.id},{"${it.name} · ${it.currency}"}){budget=it};Field("Сумма",amount){amount=it}}}
@Composable private fun EntryDialog(state: FinanceUiState, dismiss: () -> Unit, save:(EntryKind,String,Long?,Long?,Long?,String)->Unit) {
    val allowed = availableEntryKinds(state)
    var form by remember { mutableStateOf(OperationFormState(kind = allowed.firstOrNull() ?: EntryKind.EXPENSE, accountId = state.accounts.firstOrNull()?.id)) }; var amount by remember { mutableStateOf("") }; var note by remember { mutableStateOf("") }
    val cats = form.categories(state)
    FormDialog("Новая операция",state.error,dismiss,{save(form.kind,amount,form.accountId,form.categoryId,form.targetAccountId,note)}) {
        ChoiceRow(listOf("Расход" to EntryKind.EXPENSE,"Доход" to EntryKind.INCOME,"Перевод" to EntryKind.TRANSFER).filter { it.second in allowed },form.kind){form=form.selectKind(it)}
        Field("Сумма",amount){amount=it}; SelectList("Счёт",state.accounts,form.accountId,{it.id},{it.name}){form=form.selectSource(it,state)}
        if(form.kind==EntryKind.TRANSFER) SelectList("Счёт назначения",form.transferTargets(state.accounts),form.targetAccountId,{it.id},{it.name}){form=form.copy(targetAccountId=it)}
        else CategoryTiles(cats, form.categoryId) { form=form.copy(categoryId=it) }
        Field("Комментарий (необязательно)",note){note=it}
    }
}

@Composable private fun FormDialog(title:String,error:String?,dismiss:()->Unit,save:()->Unit,content:@Composable ColumnScope.()->Unit)=FffModal(title,dismiss,"Сохранить",save) { Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)){content(); if(error!=null) Text(error,color=Color(0xFFFF8DA8),fontSize=12.sp,lineHeight=17.sp)} }
@Composable private fun Field(label:String,value:String,onChange:(String)->Unit)=OutlinedTextField(value,onChange,Modifier.fillMaxWidth(),label={Text(label)},singleLine=true)
@Composable private fun <T> ChoiceRow(values:List<Pair<String,T>>,selected:T,onSelect:(T)->Unit)=Column(verticalArrangement=Arrangement.spacedBy(6.dp)){values.forEach{(label,value)->Row(Modifier.fillMaxWidth().clickable{onSelect(value)}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(selected==value,{onSelect(value)});Text(label)}}}
@Composable private fun <T> SelectList(label:String, values:List<T>, selected:Long?, id:(T)->Long, name:(T)->String, select:(Long)->Unit)=Column{Text(label,color=FffMuted,fontSize=12.sp); if(values.isEmpty()) Text("Нет доступных вариантов",color=Color(0xFFFF7C9B),fontSize=12.sp) else values.forEach{v->Row(Modifier.fillMaxWidth().clickable{select(id(v))}.padding(vertical=2.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(selected==id(v),{select(id(v))});Text(name(v),maxLines=1,overflow=TextOverflow.Ellipsis)}}}

private val categoryEmoji = listOf("🛒","🍔","☕","🏠","🚕","🚗","✈️","🎁","❤️","💊","🎮","📱","👕","💡","🎓","🐾","💰","💼","📈","🏷️")

@Composable private fun EmojiPicker(selected: String, onSelect: (String) -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    categoryEmoji.chunked(5).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        row.forEach { emoji -> Surface(
            modifier = Modifier.weight(1f).clickable { onSelect(emoji) },
            shape = RoundedCornerShape(12.dp),
            color = if(selected == emoji) FffMint.copy(alpha=.22f) else FffSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, if(selected == emoji) FffMint else FffLine),
        ) { Text(emoji, fontSize=24.sp, modifier=Modifier.padding(vertical=8.dp), textAlign=androidx.compose.ui.text.style.TextAlign.Center) } }
        repeat(5-row.size) { Spacer(Modifier.weight(1f)) }
    } }
}

@Composable private fun CategoryTiles(categories: List<CategoryEntity>, selected: Long?, onSelect: (Long) -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Категория", color=FffMuted, fontSize=12.sp)
    if(categories.isEmpty()) Text("Нет доступных категорий", color=Color(0xFFFF7C9B), fontSize=12.sp)
    categoryGridRows(categories).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(7.dp)) {
        row.forEach { category -> Surface(
            modifier=Modifier.weight(1f).clickable { onSelect(category.id) },
            shape=RoundedCornerShape(14.dp),
            color=if(selected==category.id) FffMint.copy(alpha=.18f) else FffSurface,
            border=androidx.compose.foundation.BorderStroke(1.dp, if(selected==category.id) FffMint else FffLine),
        ) { Column(Modifier.padding(horizontal=4.dp, vertical=10.dp), horizontalAlignment=Alignment.CenterHorizontally) { Text(category.emoji,fontSize=30.sp);Text(category.name,fontSize=10.sp,maxLines=2,overflow=TextOverflow.Ellipsis,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.padding(top=4.dp)) } } }
        repeat(4-row.size) { Spacer(Modifier.weight(1f)) }
    } }
}

private fun formatMoney(minor:Long,currencyCode:String="RUB"):String = runCatching { NumberFormat.getCurrencyInstance(Locale("ru","RU")).apply { currency=Currency.getInstance(currencyCode) }.format(BigDecimal.valueOf(minor,2)) }.getOrElse { "%.2f %s".format(minor/100.0,currencyCode) }
private fun saturatedMoney(left: Long, right: Long): Long = if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
