package ru.rockxi.fff.ui.harness

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.net.URI
import java.io.File
import java.time.LocalDate
import ru.rockxi.fff.data.finance.*
import ru.rockxi.fff.data.harness.*
import ru.rockxi.fff.ui.components.*
import ru.rockxi.fff.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun HarnessScreen(onBack:()->Unit){
 val context=LocalContext.current
 val model:HarnessViewModel=viewModel(factory=HarnessViewModel.factory(HttpHarnessApi(),KeystoreHarnessTokenStore(context),RepositoryHarnessFinanceSource(FinanceRepository(FinanceDatabase.get(context)))))
 val state by model.state.collectAsStateWithLifecycle()
 LaunchedEffect(state.financeExport?.id){state.financeExport?.let{shareHarnessFinanceExport(context,it);model.consumeFinanceExport(it.id)}}
 val lifecycle=LocalLifecycleOwner.current.lifecycle
 DisposableEffect(lifecycle,model){val observer=LifecycleEventObserver{_,event->when(event){Lifecycle.Event.ON_START->model.setScreenActive(true);Lifecycle.Event.ON_STOP->model.setScreenActive(false);else->Unit}};lifecycle.addObserver(observer);model.setScreenActive(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED));onDispose{lifecycle.removeObserver(observer);model.setScreenActive(false)}}
 Scaffold(containerColor=FffBackground,contentWindowInsets=WindowInsets(0,0,0,0),topBar={TopAppBar(title={Text(state.selected?.title?:"AI Harness",fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)},navigationIcon={IconButton(if(state.selected!=null)model::close else onBack){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}},colors=TopAppBarDefaults.topAppBarColors(containerColor=FffBackground))}){padding->Box(Modifier.fillMaxSize().padding(padding)){when(state.phase){HarnessPhase.Unpaired->AccessIntro(state.error,state.loading,model::connect);HarnessPhase.Revoking->Revoking(state,model::retryPendingRevoke);HarnessPhase.Paired->if(state.selected==null)ConversationList(state,model) else ConversationChat(state,model)}}}
}

@Composable private fun ConversationList(state:HarnessState,model:HarnessViewModel){var modal by remember{mutableStateOf<String?>(null)};var title by remember{mutableStateOf("")};Column(Modifier.fillMaxSize()){Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Text(if(state.showArchived)"Архив" else "Диалоги",fontSize=22.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));TextButton(model::toggleArchived){Text(if(state.showArchived)"Активные" else "Архив")};IconButton({title="";modal="new"}){Icon(Icons.Rounded.Add,"Новый диалог",tint=FffMint)}};CodexAccountCard(state,model);if(state.loading&&state.conversations.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator(color=FffMint)}else if(state.conversations.isEmpty())Text("Здесь появятся ваши диалоги",color=FffMuted,modifier=Modifier.padding(24.dp))else LazyColumn(contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){items(state.conversations,key={it.id}){c->Surface(onClick={model.open(c)},shape=RoundedCornerShape(18.dp),color=FffSurface,border=BorderStroke(1.dp,FffLine)){Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(42.dp).background(FffMint.copy(alpha=.16f),RoundedCornerShape(13.dp)),contentAlignment=Alignment.Center){Icon(Icons.Rounded.Chat,null,tint=FffMint)};Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(c.title,fontWeight=FontWeight.Bold);Text(c.lastMessage?:"Пока нет сообщений",color=FffMuted,maxLines=2,overflow=TextOverflow.Ellipsis,fontSize=13.sp);Text(formatHarnessActivity(c.updatedAt),color=FffMuted,fontSize=10.sp,modifier=Modifier.padding(top=4.dp))}}}}};state.error?.let{Text(it,color=Color(0xFFFF7C9B),modifier=Modifier.padding(16.dp))}
 if(modal=="new")FffModal("Новый диалог",{modal=null},"Создать",{model.create(title);modal=null},confirmEnabled=title.trim().isNotEmpty()){FffTextInput("Название",title,{title=it.take(120)})}}
}

@Composable private fun ConversationChat(state:HarnessState,model:HarnessViewModel){val c=state.selected!!;var menu by remember{mutableStateOf(false)};var modal by remember{mutableStateOf<String?>(null)};var title by remember(c.id){mutableStateOf(c.title)};val list=rememberLazyListState();var followTail by remember(c.id){mutableStateOf(true)};var wasScrolling by remember(c.id){mutableStateOf(false)};LaunchedEffect(list,c.id){snapshotFlow{list.isScrollInProgress}.collect{scrolling->val info=list.layoutInfo;followTail=nextHarnessFollowTail(followTail,wasScrolling,scrolling,info.visibleItemsInfo.lastOrNull()?.index,info.totalItemsCount);wasScrolling=scrolling}};LaunchedEffect(state.sending){if(state.sending)followTail=true};LaunchedEffect(state.messages.lastOrNull()?.id){if(shouldAutoScrollHarnessTail(followTail,list.isScrollInProgress)&&state.messages.isNotEmpty())list.animateScrollToItem(state.messages.lastIndex)};/* Activity uses adjustNothing: Compose is the single owner of the keyboard inset. */Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()){Row(Modifier.fillMaxWidth().padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Отдельный контекст",fontSize=11.sp,color=FffMuted)};IconButton({menu=true}){Icon(Icons.Rounded.MoreVert,"Действия")};DropdownMenu(menu,{menu=false}){DropdownMenuItem({Text("Переименовать")},{menu=false;title=c.title;modal="rename"},leadingIcon={Icon(Icons.Rounded.Edit,null)});DropdownMenuItem({Text(if(c.archived)"Восстановить" else "В архив")},{menu=false;modal="archive"},leadingIcon={Icon(Icons.Rounded.Archive,null)});DropdownMenuItem({Text("Удалить",color=Color(0xFFFF7C9B))},{menu=false;modal="delete"},leadingIcon={Icon(Icons.Rounded.Delete,null,tint=Color(0xFFFF7C9B))})}};ProviderBar(state,model);FinanceContextBar(state,model,{modal="finance_custom"});if(state.nextBefore!=null)TextButton(model::loadOlder,Modifier.align(Alignment.CenterHorizontally),enabled=!state.loadingOlder){Text(if(state.loadingOlder)"Загрузка…" else "Показать предыдущие")};LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=list,contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){items(state.messages,key={it.id}){m->val mine=m.role=="user";Row(Modifier.fillMaxWidth(),horizontalArrangement=if(mine)Arrangement.End else Arrangement.Start){Column(Modifier.widthIn(max=310.dp).background(if(mine)FffViolet.copy(alpha=.24f) else FffSurface,RoundedCornerShape(16.dp)).padding(12.dp)){Text(messageLabel(m),fontSize=9.sp,fontFamily=FontFamily.Monospace,color=FffMint);Text(m.content)}}};if(state.sending)item("pending"){Row(verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(17.dp),strokeWidth=2.dp,color=FffMint);Text(if(state.provider==HarnessProvider.CODEX)"Codex думает…" else "Агент думает…",color=FffMuted,modifier=Modifier.padding(start=8.dp))}}};state.error?.let{Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){Text(it,color=Color(0xFFFF7C9B),modifier=Modifier.weight(1f));if(state.retryText!=null)TextButton(model::retry){Text("Повторить")}}};Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.Bottom){OutlinedTextField(state.draft,model::updateDraft,Modifier.weight(1f),placeholder={Text("Сообщение")},maxLines=6,isError=state.composerError!=null,supportingText=if(state.composerError!=null){{Text(state.composerError!!)}}else null,shape=RoundedCornerShape(18.dp));IconButton(model::send,enabled=state.draft.isNotBlank()&&!state.sending){Icon(Icons.Rounded.Send,"Отправить",tint=FffMint)}}}
 if(modal=="rename")FffModal("Название диалога",{modal=null},"Сохранить",{model.rename(title);modal=null},confirmEnabled=title.trim().isNotEmpty()){FffTextInput("Название",title,{title=it.take(120)})}
 if(modal=="archive")FffModal(if(c.archived)"Восстановить диалог?" else "Переместить в архив?",{modal=null},if(c.archived)"Восстановить" else "В архив",{model.archive(!c.archived);modal=null}){Text("История сообщений сохранится.",color=FffMuted)}
 if(modal=="delete")FffModal("Удалить диалог навсегда?",{modal=null},"Удалить",{model.delete();modal=null},destructive=true){Text("Диалог и вся его история будут удалены без возможности восстановления.",color=FffMuted)}
 if(modal=="finance_custom")FinanceCustomRangeModal(state,{modal=null}){start,end->model.selectFinanceCustomRange(start,end);modal=null}
}

@Composable private fun FinanceContextBar(state:HarnessState,model:HarnessViewModel,custom:()->Unit){var menu by remember{mutableStateOf(false)};Surface(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=3.dp),shape=RoundedCornerShape(14.dp),color=FffSurface,border=BorderStroke(1.dp,if(state.financeEnabled)FffMint.copy(alpha=.45f) else FffLine)){Column(Modifier.padding(horizontal=10.dp,vertical=7.dp)){Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Rounded.PieChart,null,tint=FffMint,modifier=Modifier.size(18.dp));Text("Finance",Modifier.padding(start=7.dp).weight(1f),fontWeight=FontWeight.Bold,fontSize=13.sp);Switch(state.financeEnabled,model::setFinanceEnabled)};if(state.financeEnabled){Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){Box(Modifier.weight(1f)){OutlinedButton({menu=true},Modifier.fillMaxWidth(),contentPadding=PaddingValues(horizontal=8.dp)){Text(state.financeRangeLabel,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp);Icon(Icons.Rounded.ArrowDropDown,null)};DropdownMenu(menu,{menu=false}){listOf(FinanceAnalyticsPreset.MONTH,FinanceAnalyticsPreset.QUARTER,FinanceAnalyticsPreset.HALF_YEAR,FinanceAnalyticsPreset.YEAR,FinanceAnalyticsPreset.ALL_TIME).forEach{preset->DropdownMenuItem({Text(HarnessViewModel.financePresetLabel(preset))},{model.selectFinancePreset(preset);menu=false})};DropdownMenuItem({Text("Свой период")},{menu=false;custom()})}};IconButton({model.exportFinance(FinanceReportExporter.Format.CSV)},enabled=!state.financeBusy){Icon(Icons.Rounded.TableView,"Экспорт CSV",tint=FffMint)};IconButton({model.exportFinance(FinanceReportExporter.Format.JSON)},enabled=!state.financeBusy){Icon(Icons.Rounded.DataObject,"Экспорт JSON",tint=FffMint)}};Text(financeRangeDescription(state.financeRange),fontSize=10.sp,color=FffMint);Text("В следующий запрос попадёт только агрегированный отчёт; операции и заметки останутся на устройстве.",fontSize=10.sp,color=FffMuted)}}}}

private fun financeRangeDescription(range:FinanceAnalyticsRange)=if(range.startInclusive==null)"Все сохранённые даты" else "${range.startInclusive} — ${range.endInclusive}"

@Composable private fun FinanceCustomRangeModal(state:HarnessState,dismiss:()->Unit,save:(LocalDate,LocalDate)->Unit){var start by remember{mutableStateOf(state.financeRange.startInclusive?.toString()?:LocalDate.now().withDayOfMonth(1).toString())};var end by remember{mutableStateOf(state.financeRange.endInclusive?.toString()?:LocalDate.now().toString())};val parsedStart=runCatching{LocalDate.parse(start)}.getOrNull();val parsedEnd=runCatching{LocalDate.parse(end)}.getOrNull();FffModal("Период Finance",dismiss,"Применить",{save(parsedStart!!,parsedEnd!!)},confirmEnabled=parsedStart!=null&&parsedEnd!=null&&!parsedStart.isAfter(parsedEnd)){Text("Формат: ГГГГ-ММ-ДД",fontSize=12.sp,color=FffMuted);FffTextInput("Начало",start,{start=it.take(10)});FffTextInput("Конец",end,{end=it.take(10)})}}

private fun shareHarnessFinanceExport(context:android.content.Context,value:HarnessFinanceExport){val directory=File(context.cacheDir,"finance-reports").apply{mkdirs()};val safeName=value.fileName.replace(Regex("[^a-zA-Z0-9._-]"),"_");val file=File(directory,safeName);file.writeText(value.content,Charsets.UTF_8);val uri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file);context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type=value.mimeType;putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Поделиться отчётом Finance"))}

@Composable private fun CodexAccountCard(state:HarnessState,model:HarnessViewModel){
 val context=LocalContext.current;val clipboard=LocalClipboardManager.current;val auth=state.codexAuth;var confirmLogout by remember{mutableStateOf(false)}
 val verificationUrl=auth.verificationUrl?.takeIf(::isOfficialCodexVerificationUrl)
 Surface(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp),shape=RoundedCornerShape(18.dp),color=FffSurface,border=BorderStroke(1.dp,if(auth.state==CodexAuthState.CONNECTED)FffMint.copy(alpha=.45f) else FffLine)){
  Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Rounded.Terminal,null,tint=FffMint);Column(Modifier.weight(1f).padding(horizontal=10.dp)){Text("Codex по подписке",fontWeight=FontWeight.Bold);Text(when(auth.state){CodexAuthState.CONNECTED->"Подключён на сервере";CodexAuthState.PENDING->"Ожидает подтверждения";CodexAuthState.DISCONNECTED->"Не подключён";CodexAuthState.UNKNOWN->"Проверяю подключение…"},fontSize=12.sp,color=FffMuted)};if(state.codexBusy||auth.state==CodexAuthState.UNKNOWN)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp,color=FffMint)}
   if(auth.state==CodexAuthState.PENDING){if(verificationUrl!=null){auth.userCode?.let{code->Surface(shape=RoundedCornerShape(12.dp),color=FffBackground){Row(Modifier.fillMaxWidth().clickable{clipboard.setText(AnnotatedString(code))}.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text(code,Modifier.weight(1f),fontFamily=FontFamily.Monospace,fontSize=20.sp,fontWeight=FontWeight.Bold,color=FffMint);Icon(Icons.Rounded.ContentCopy,"Скопировать код")}}};OutlinedButton({openSafeHttps(context,verificationUrl)},Modifier.fillMaxWidth()){Icon(Icons.Rounded.OpenInBrowser,null);Spacer(Modifier.width(8.dp));Text("Открыть страницу входа")}}else Text("Сервер вернул неподдерживаемый адрес входа. Запросите новый код.",fontSize=12.sp,color=Color(0xFFFF7C9B))}
   when(auth.state){CodexAuthState.CONNECTED->OutlinedButton({confirmLogout=true},Modifier.fillMaxWidth(),enabled=!state.codexBusy){Text("Отключить Codex")};CodexAuthState.DISCONNECTED->Button(model::startCodexAuth,Modifier.fillMaxWidth(),enabled=!state.codexBusy){Text("Подключить подписку")};CodexAuthState.PENDING->{Text("Скопируйте код, откройте страницу и подтвердите вход. Проверка завершится автоматически.",fontSize=12.sp,color=FffMuted);OutlinedButton(model::restartCodexAuth,Modifier.fillMaxWidth(),enabled=!state.codexBusy){Text("Получить новый код")}};CodexAuthState.UNKNOWN->Unit}
  }
 }
 if(confirmLogout)FffModal("Отключить Codex?",{confirmLogout=false},"Отключить",{confirmLogout=false;model.logoutCodex()},destructive=true){Text("Сервер завершит сессию подписки. Подключить её снова можно через новый код.",color=FffMuted)}
}

@Composable private fun ProviderBar(state:HarnessState,model:HarnessViewModel){
 var menu by remember{mutableStateOf(false)}
 val codexReady=state.codexAuth.state==CodexAuthState.CONNECTED&&state.codexModels.isNotEmpty()
 Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=2.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
  FilterChip(state.provider==HarnessProvider.AGENT,{model.selectProvider(HarnessProvider.AGENT)},{Text("Агент")},leadingIcon={Icon(Icons.Rounded.AutoAwesome,null,Modifier.size(17.dp))})
  FilterChip(state.provider==HarnessProvider.CODEX,{if(codexReady)model.selectProvider(HarnessProvider.CODEX)},{Text("Codex")},enabled=codexReady,leadingIcon={Icon(Icons.Rounded.Terminal,null,Modifier.size(17.dp))})
  if(state.provider==HarnessProvider.CODEX){
   Box(Modifier.weight(1f)){
    OutlinedButton({menu=true},Modifier.fillMaxWidth(),contentPadding=PaddingValues(horizontal=10.dp)){Text(state.selectedModel?:"Модель",Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis);Icon(Icons.Rounded.ArrowDropDown,null)}
    DropdownMenu(menu,{menu=false}){state.codexModels.forEach{item->DropdownMenuItem({Text(item)},{model.selectModel(item);menu=false},trailingIcon=if(item==state.selectedModel){{Icon(Icons.Rounded.Check,null,tint=FffMint)}}else null)}}
   }
  }
 }
}

internal fun isOfficialCodexVerificationUrl(value:String):Boolean=runCatching{val uri=URI(value);uri.isAbsolute&&!uri.isOpaque&&uri.scheme.equals("https",true)&&uri.userInfo==null&&uri.port==-1&&uri.host.equals("auth.openai.com",true)&&uri.rawAuthority.equals(uri.host,true)}.getOrDefault(false)
private fun openSafeHttps(context:android.content.Context,value:String){if(!isOfficialCodexVerificationUrl(value))return;context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(value)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}

internal fun messageLabel(m:ServerMessage):String{val role=when(m.role.replace('-','_')){"user"->"USER";"assistant"->"AI";"tool"->"TOOL";else->m.role.uppercase()};val source=when(m.source.replace('-','_')){"harness"->"HARNESS";"agent"->"AGENT";else->"IMPORT"};return listOfNotNull(role,source,m.authorName?.takeIf{it.isNotBlank()}).joinToString(" · ")}
internal fun shouldFollowHarnessTail(lastVisibleIndex:Int?,totalItems:Int,threshold:Int=2):Boolean=totalItems==0||lastVisibleIndex!=null&&lastVisibleIndex>=totalItems-1-threshold
internal fun nextHarnessFollowTail(current:Boolean,wasScrolling:Boolean,isScrolling:Boolean,lastVisibleIndex:Int?,totalItems:Int):Boolean=when{isScrolling->false;wasScrolling->shouldFollowHarnessTail(lastVisibleIndex,totalItems);else->current}
internal fun shouldAutoScrollHarnessTail(followTail:Boolean,isScrollInProgress:Boolean):Boolean=followTail&&!isScrollInProgress
internal fun formatHarnessActivity(value:String):String=runCatching{OffsetDateTime.parse(value).format(DateTimeFormatter.ofPattern("dd.MM.yyyy · HH:mm"))}.getOrElse{value.take(16).ifBlank{"—"}}

@Composable private fun AccessIntro(error:String?,busy:Boolean,connect:(String)->Unit){var key by remember{mutableStateOf("")};Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Rounded.Key,null,tint=FffMint,modifier=Modifier.size(48.dp));Text("Подключить ассистента",fontWeight=FontWeight.Bold,fontSize=22.sp);Text("Введите личный ключ доступа. Он нужен только один раз и не сохраняется на устройстве.",color=FffMuted,modifier=Modifier.padding(vertical=14.dp));OutlinedTextField(key,{key=it.take(512)},Modifier.fillMaxWidth(),label={Text("Ключ доступа")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,keyboardType=KeyboardType.Password),enabled=!busy);Button({connect(key);key=""},Modifier.fillMaxWidth().padding(top=12.dp),enabled=!busy&&key.isNotBlank()){Text(if(busy)"Подключаю…" else "Подключить")};error?.let{Text(it,color=Color(0xFFFF7C9B),modifier=Modifier.padding(top=12.dp))}}}
@Composable private fun Revoking(state:HarnessState,retry:()->Unit)=Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){if(state.loading)CircularProgressIndicator(color=FffMint);Text("Завершаю отключение…",fontWeight=FontWeight.Bold,modifier=Modifier.padding(12.dp));state.error?.let{Text(it,color=Color(0xFFFF7C9B));Button(retry){Text("Повторить")}}}
