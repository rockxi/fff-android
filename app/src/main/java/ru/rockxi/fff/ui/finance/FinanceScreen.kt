package ru.rockxi.fff.ui.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import ru.rockxi.fff.data.finance.*
import ru.rockxi.fff.ui.theme.*
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

private enum class FinanceTab(val title: String) { OVERVIEW("Обзор"), OPERATIONS("Операции"), ANALYTICS("Аналитика"), MANAGE("Управление") }
private enum class DialogKind { ENTRY, ACCOUNT, CATEGORY }

@Composable
fun FinanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val model: FinanceViewModel = viewModel(factory = FinanceViewModel.factory(RepositoryFinanceStore(FinanceRepository(FinanceDatabase.get(context)))))
    val state by model.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(FinanceTab.OVERVIEW) }
    var dialog by remember { mutableStateOf<DialogKind?>(null) }
    fun openDialog(kind: DialogKind) { model.clearError(); dialog = kind }
    fun closeDialog() { model.clearError(); dialog = null }

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
                    val icon = when(item) { FinanceTab.OVERVIEW -> Icons.Rounded.AccountBalanceWallet; FinanceTab.OPERATIONS -> Icons.AutoMirrored.Rounded.ReceiptLong; FinanceTab.ANALYTICS -> Icons.Rounded.PieChart; FinanceTab.MANAGE -> Icons.Rounded.Tune }
                    NavigationBarItem(selected = tab == item, onClick = { tab = item }, icon = { Icon(icon, item.title) }, label = { Text(item.title, maxLines = 1, fontSize = 10.sp) })
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = FffMint)
                else -> when(tab) {
                    FinanceTab.OVERVIEW -> Overview(state, onAdd = ::openDialog)
                    FinanceTab.OPERATIONS -> Operations(state)
                    FinanceTab.ANALYTICS -> Analytics(state)
                    FinanceTab.MANAGE -> Management(state, ::openDialog, model::archiveAccount, model::archiveCategory)
                }
            }
            if (dialog == null) state.error?.let { ErrorBanner(it, Modifier.align(Alignment.TopCenter)) }
        }
    }
    when(dialog) {
        DialogKind.ACCOUNT -> AccountDialog(state.error, ::closeDialog) { name, currency, balance -> model.createAccount(name, currency, balance) { if (it) closeDialog() } }
        DialogKind.CATEGORY -> CategoryDialog(state.error, ::closeDialog) { name, kind -> model.createCategory(name, kind) { if (it) closeDialog() } }
        DialogKind.ENTRY -> EntryDialog(state, ::closeDialog) { kind, amount, account, category, target, note -> model.addEntry(kind, amount, account, category, target, note) { if (it) closeDialog() } }
        null -> Unit
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

@Composable private fun Operations(state: FinanceUiState) = LazyColumn(
    Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 92.dp), verticalArrangement = Arrangement.spacedBy(9.dp),
) {
    item { SectionTitle("История") }
    if (state.entries.isEmpty()) item { EmptyText("Добавьте первую операцию кнопкой ниже") }
    items(state.entries, key = { it.id }) { EntryRow(it, state) }
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

@Composable private fun Management(state: FinanceUiState, onAdd: (DialogKind) -> Unit, archiveAccount: (Long) -> Unit, archiveCategory: (Long) -> Unit) = LazyColumn(
    Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 92.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
) {
    item { ManagementHeading("Счета", "Новый счёт") { onAdd(DialogKind.ACCOUNT) } }
    if (state.allAccounts.isEmpty()) item { EmptyText("Счетов нет") }
    items(state.allAccounts, key = { "a${it.id}" }) { entity -> ManageRow(entity.name, "${entity.currency} · ${formatMoney(entity.balanceMinor, entity.currency)}", entity.archived) { archiveAccount(entity.id) } }
    item { Spacer(Modifier.height(8.dp)); ManagementHeading("Категории", "Новая категория") { onAdd(DialogKind.CATEGORY) } }
    if (state.allCategories.isEmpty()) item { EmptyText("Категорий нет") }
    items(state.allCategories, key = { "c${it.id}" }) { entity -> ManageRow(entity.name, if(entity.kind == CategoryKind.INCOME) "Доход" else "Расход", entity.archived) { archiveCategory(entity.id) } }
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
@Composable private fun EntryRow(e: LedgerEntryEntity, state: FinanceUiState) {
    val color = when(e.kind) { EntryKind.INCOME -> FffMint; EntryKind.EXPENSE -> Color(0xFFFF7C9B); EntryKind.TRANSFER -> FffViolet }
    val title = when(e.kind) { EntryKind.TRANSFER -> "Перевод"; else -> state.allCategories.firstOrNull { it.id == e.categoryId }?.name ?: if(e.kind == EntryKind.INCOME) "Доход" else "Расход" }
    val currency = state.allAccounts.firstOrNull { it.id == e.accountId }?.currency ?: "RUB"
    Row(Modifier.fillMaxWidth().background(FffSurface, RoundedCornerShape(14.dp)).padding(14.dp), verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, maxLines=1, overflow=TextOverflow.Ellipsis); Text(SimpleDateFormat("dd MMM, HH:mm", Locale("ru")).format(Date(e.occurredAt)) + if(e.note.isBlank()) "" else " · ${e.note}", color=FffMuted, fontSize=11.sp, maxLines=1, overflow=TextOverflow.Ellipsis) }
        Text((if(e.kind==EntryKind.INCOME) "+" else if(e.kind==EntryKind.EXPENSE) "−" else "") + formatMoney(e.amountMinor, currency), color=color, fontWeight=FontWeight.Bold)
    }
}
@Composable private fun ManageRow(name: String, meta: String, archived: Boolean, archive: () -> Unit) = Row(Modifier.fillMaxWidth().background(FffSurface, RoundedCornerShape(14.dp)).padding(14.dp), verticalAlignment=Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) { Text(name, color=if(archived) FffMuted else FffText, maxLines=1, overflow=TextOverflow.Ellipsis); Text(if(archived) "$meta · В архиве" else meta, color=FffMuted, fontSize=11.sp) }
    if(!archived) TextButton(onClick=archive) { Text("В архив") }
}

@Composable private fun EmptyCard(title: String, text: String, action: String, onClick: () -> Unit) = Column(Modifier.fillMaxWidth().background(FffSurface, RoundedCornerShape(18.dp)).border(1.dp,FffLine,RoundedCornerShape(18.dp)).padding(20.dp)) { Text(title,fontSize=20.sp,fontWeight=FontWeight.Bold); Text(text,color=FffMuted,fontSize=13.sp,lineHeight=19.sp,modifier=Modifier.padding(vertical=9.dp)); Button(onClick=onClick){Text(action)} }
@Composable private fun EmptyText(text: String) = Text(text,color=FffMuted,modifier=Modifier.fillMaxWidth().background(FffSurface,RoundedCornerShape(14.dp)).padding(18.dp))
@Composable private fun SectionTitle(text: String, modifier: Modifier = Modifier) = Text(text, modifier=modifier, fontWeight=FontWeight.Bold,fontSize=18.sp)
@Composable private fun ErrorBanner(text: String, modifier: Modifier = Modifier) = Text(text,color=Color.White,fontSize=12.sp,modifier=modifier.fillMaxWidth().background(Color(0xFF8F2743)).padding(12.dp))

@Composable private fun AccountDialog(error: String?, dismiss: () -> Unit, save: (String,String,String)->Unit) {
    var name by remember { mutableStateOf("") }; var currency by remember { mutableStateOf("RUB") }; var balance by remember { mutableStateOf("") }
    FormDialog("Новый счёт", error, dismiss, { save(name,currency,balance) }) { Field("Название",name){name=it}; Field("Валюта (RUB, USD…)",currency){currency=it}; Field("Начальный баланс",balance){balance=it} }
}
@Composable private fun CategoryDialog(error: String?, dismiss: () -> Unit, save: (String,CategoryKind)->Unit) {
    var name by remember { mutableStateOf("") }; var kind by remember { mutableStateOf(CategoryKind.EXPENSE) }
    FormDialog("Новая категория", error, dismiss, { save(name,kind) }) { Field("Название",name){name=it}; ChoiceRow(listOf("Расход" to CategoryKind.EXPENSE,"Доход" to CategoryKind.INCOME),kind){kind=it} }
}
@Composable private fun EntryDialog(state: FinanceUiState, dismiss: () -> Unit, save:(EntryKind,String,Long?,Long?,Long?,String)->Unit) {
    val allowed = availableEntryKinds(state)
    var form by remember { mutableStateOf(OperationFormState(kind = allowed.firstOrNull() ?: EntryKind.EXPENSE, accountId = state.accounts.firstOrNull()?.id)) }; var amount by remember { mutableStateOf("") }; var note by remember { mutableStateOf("") }
    val cats = if(form.kind==EntryKind.INCOME) state.incomeCategories else state.expenseCategories
    FormDialog("Новая операция",state.error,dismiss,{save(form.kind,amount,form.accountId,form.categoryId,form.targetAccountId,note)}) {
        ChoiceRow(listOf("Расход" to EntryKind.EXPENSE,"Доход" to EntryKind.INCOME,"Перевод" to EntryKind.TRANSFER).filter { it.second in allowed },form.kind){form=form.selectKind(it)}
        Field("Сумма",amount){amount=it}; SelectList("Счёт",state.accounts,form.accountId,{it.id},{it.name}){form=form.selectSource(it)}
        if(form.kind==EntryKind.TRANSFER) SelectList("Счёт назначения",form.transferTargets(state.accounts),form.targetAccountId,{it.id},{it.name}){form=form.copy(targetAccountId=it)}
        else SelectList("Категория",cats,form.categoryId,{it.id},{it.name}){form=form.copy(categoryId=it)}
        Field("Комментарий (необязательно)",note){note=it}
    }
}

@Composable private fun FormDialog(title:String,error:String?,dismiss:()->Unit,save:()->Unit,content:@Composable ColumnScope.()->Unit)=AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={Column(Modifier.fillMaxWidth().heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){content(); if(error!=null) Text(error,color=Color(0xFFFF8DA8),fontSize=12.sp,lineHeight=17.sp)}},confirmButton={Button(onClick=save){Text("Сохранить")}},dismissButton={TextButton(onClick=dismiss){Text("Отмена")}})
@Composable private fun Field(label:String,value:String,onChange:(String)->Unit)=OutlinedTextField(value,onChange,Modifier.fillMaxWidth(),label={Text(label)},singleLine=true)
@Composable private fun <T> ChoiceRow(values:List<Pair<String,T>>,selected:T,onSelect:(T)->Unit)=Column(verticalArrangement=Arrangement.spacedBy(6.dp)){values.forEach{(label,value)->Row(Modifier.fillMaxWidth().clickable{onSelect(value)}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(selected==value,{onSelect(value)});Text(label)}}}
@Composable private fun <T> SelectList(label:String, values:List<T>, selected:Long?, id:(T)->Long, name:(T)->String, select:(Long)->Unit)=Column{Text(label,color=FffMuted,fontSize=12.sp); if(values.isEmpty()) Text("Нет доступных вариантов",color=Color(0xFFFF7C9B),fontSize=12.sp) else values.forEach{v->Row(Modifier.fillMaxWidth().clickable{select(id(v))}.padding(vertical=2.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(selected==id(v),{select(id(v))});Text(name(v),maxLines=1,overflow=TextOverflow.Ellipsis)}}}

private fun formatMoney(minor:Long,currencyCode:String="RUB"):String = runCatching { NumberFormat.getCurrencyInstance(Locale("ru","RU")).apply { currency=Currency.getInstance(currencyCode) }.format(BigDecimal.valueOf(minor,2)) }.getOrElse { "%.2f %s".format(minor/100.0,currencyCode) }
