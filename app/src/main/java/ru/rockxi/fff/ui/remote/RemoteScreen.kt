package ru.rockxi.fff.ui.remote

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.rockxi.fff.data.harness.KeystoreHarnessTokenStore
import ru.rockxi.fff.data.remote.*
import ru.rockxi.fff.ui.components.FffModal
import ru.rockxi.fff.ui.components.FffTextInput
import ru.rockxi.fff.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RemoteScreen(onBack:()->Unit){val context=LocalContext.current;val vm:RemoteViewModel=viewModel(factory=RemoteViewModel.factory(HttpRemoteApi(),KeystoreHarnessTokenStore(context)));val s by vm.state.collectAsStateWithLifecycle();var add by remember{mutableStateOf(false)};var agent by remember{mutableStateOf(false)}
 Scaffold(containerColor=FffBackground,topBar={TopAppBar(title={Text("Remote Control",fontWeight=FontWeight.Bold)},navigationIcon={IconButton(onBack){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}},actions={IconButton(vm::reload){Icon(Icons.Rounded.Refresh,"Обновить")}},colors=TopAppBarDefaults.topAppBarColors(containerColor=FffBackground))}){p->
  if(!s.paired)Box(Modifier.fillMaxSize().padding(p).navigationBarsPadding().padding(24.dp),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){Icon(Icons.Rounded.DevicesOther,null,tint=FffMint,modifier=Modifier.size(40.dp));Text("Подключения недоступны",style=MaterialTheme.typography.titleLarge);Text(s.error?:"Сначала подключите AI Harness, чтобы управлять SSH-хостами.",color=FffMuted,style=MaterialTheme.typography.bodyMedium)}} else LazyColumn(Modifier.fillMaxSize().padding(p).navigationBarsPadding(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
   item{Header("SSH-ХОСТЫ",{add=true},"Добавить")}
   items(s.hosts,key={it.id}){h->Card(colors=CardDefaults.cardColors(containerColor=if(s.selectedHostId==h.id)FffMint.copy(.12f) else FffSurface),modifier=Modifier.fillMaxWidth().clickable{vm.selectHost(h.id)}){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(h.name,fontWeight=FontWeight.Bold);Text("${h.username}@${h.hostname}:${h.port} · ${if(h.authType=="key")"ключ" else "пароль"}",color=FffMuted);h.jumpHostId?.let{Text("ProxyJump: ${s.hosts.firstOrNull{x->x.id==it}?.name?:it}",color=FffMuted)}};IconButton({vm.deleteHost(h.id)}){Icon(Icons.Rounded.Delete,"Удалить")}}}}
   item{Terminal(s,vm::runCommand)}
   item{Header("CODEX CLI",{agent=true},"Запустить")}
   items(s.sessions,key={it.id}){x->Card(colors=CardDefaults.cardColors(containerColor=FffSurface),modifier=Modifier.fillMaxWidth().clickable{vm.openSession(x.id)}){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(x.title?:x.prompt.take(48),fontWeight=FontWeight.Bold);Text(x.status+(x.exitCode?.let{" · exit $it"}?:""),color=FffMuted)};if(x.status in listOf("starting","running"))IconButton({vm.stopSession(x.id)}){Icon(Icons.Rounded.Stop,"Остановить")}}}}
   if(s.error!=null)item{Text(s.error!!,color=Color(0xFFFF7C9B))};if(s.busy)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
  }
 }
 if(add)HostDialog(s.hosts,{add=false}){add=false;vm.createHost(it)};if(agent)AgentDialog(s.selectedHostId!=null,s.busy,{agent=false}){p,t,d->agent=false;vm.startSession(p,t,d)};s.selectedSessionId?.let{id->SessionDialog(s,s.sessions.firstOrNull{it.id==id},{vm.stopSession(id)},vm::closeSession)}
}
@Composable private fun Header(title:String,action:()->Unit,label:String)=Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,color=FffText);Button(action,modifier=Modifier.heightIn(min=48.dp)){Text(label)}}
@Composable private fun Terminal(s:RemoteState,run:(String,Int)->Unit){var command by remember{mutableStateOf("")};var timeout by remember{mutableStateOf("30")};Card(colors=CardDefaults.cardColors(containerColor=FffSurface)){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("КОМАНДНАЯ СТРОКА",color=FffMint,fontFamily=FontFamily.Monospace);OutlinedTextField(command,{command=it.take(8000)},Modifier.fillMaxWidth(),label={Text("Команда")},minLines=2);OutlinedTextField(timeout,{timeout=it.filter(Char::isDigit).take(3)},label={Text("Таймаут, сек")});Button({run(command,(timeout.toIntOrNull()?:30).coerceIn(1,120))},enabled=s.selectedHostId!=null&&command.isNotBlank()&&!s.busy){Text("Выполнить")};if(s.commandOutput.isNotEmpty()||s.commandExitCode!=null){Text("exit: ${s.commandExitCode?:"—"}${if(s.commandTruncated)" · вывод обрезан" else ""}",color=FffMuted);Text(s.commandOutput,Modifier.fillMaxWidth().background(Color.Black,RoundedCornerShape(8.dp)).padding(10.dp),fontFamily=FontFamily.Monospace,color=Color(0xFFD8FFE9))}}}}
@Composable private fun HostDialog(hosts:List<RemoteHost>,dismiss:()->Unit,save:(NewHost)->Unit){
 var name by remember{mutableStateOf("")};var hostname by remember{mutableStateOf("")};var port by remember{mutableStateOf("22")};var username by remember{mutableStateOf("")};var auth by remember{mutableStateOf("password")};var secret by remember{mutableStateOf("")};var passphrase by remember{mutableStateOf("")};var jump by remember{mutableStateOf<String?>(null)}
 val parsedPort=port.toIntOrNull()
 FffModal("Новое SSH-подключение",dismiss,"Сохранить",{
  save(NewHost(name.trim(),hostname.trim(),parsedPort!!,username.trim(),auth,secret,passphrase.takeIf{it.isNotBlank()},jump))
 },confirmEnabled=name.isNotBlank()&&hostname.isNotBlank()&&username.isNotBlank()&&secret.isNotBlank()&&parsedPort!=null&&parsedPort in 1..65535){
  FffTextInput("Название",name,{name=it.take(120)})
  FffTextInput("Адрес/IP",hostname,{hostname=it.trim().take(255)})
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
   OutlinedTextField(port,{port=it.filter(Char::isDigit).take(5)},Modifier.width(100.dp),label={Text("Порт")},singleLine=true)
   OutlinedTextField(username,{username=it.take(120)},Modifier.weight(1f),label={Text("Логин")},singleLine=true)
  }
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
   FilterChip(auth=="password",{auth="password"},{Text("Пароль")})
   FilterChip(auth=="key",{auth="key"},{Text("Ключ")})
  }
  OutlinedTextField(secret,{secret=it},Modifier.fillMaxWidth(),label={Text(if(auth=="key")"Приватный ключ" else "Пароль")},visualTransformation=if(auth=="password")PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,minLines=if(auth=="key")4 else 1)
  if(auth=="key")OutlinedTextField(passphrase,{passphrase=it},Modifier.fillMaxWidth(),label={Text("Passphrase (необязательно)")},visualTransformation=PasswordVisualTransformation())
  if(hosts.isNotEmpty()){
   Text("ProxyJump",color=FffMuted)
   Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
    FilterChip(jump==null,{jump=null},{Text("Нет")})
    hosts.forEach{h->FilterChip(jump==h.id,{jump=h.id},{Text(h.name,maxLines=1)})}
   }
  }
  Text("Секрет отправляется один раз и не сохраняется на телефоне.",color=FffMuted,style=MaterialTheme.typography.bodyMedium)
 }
}
internal fun canStartRemoteAgent(hasHost:Boolean,busy:Boolean,prompt:String)=hasHost&&!busy&&prompt.isNotBlank()
@Composable private fun AgentDialog(hasAvailableHost:Boolean,busy:Boolean,dismiss:()->Unit,start:(String,String?,String?)->Unit){
 var prompt by remember{mutableStateOf("")};var title by remember{mutableStateOf("")};var dir by remember{mutableStateOf("")}
 FffModal("Запустить Codex CLI",dismiss,"Запустить в фоне",{start(prompt,title.takeIf{it.isNotBlank()},dir.takeIf{it.isNotBlank()})},confirmEnabled=canStartRemoteAgent(hasAvailableHost,busy,prompt)){
  if(!hasAvailableHost)Text("Сначала выберите или добавьте SSH-хост.",color=Color(0xFFFFB86B))
  OutlinedTextField(prompt,{prompt=it},Modifier.fillMaxWidth(),label={Text("Задача")},minLines=4)
  FffTextInput("Название (необязательно)",title,{title=it.take(120)})
  FffTextInput("Рабочая папка",dir,{dir=it})
 }
}
@Composable private fun SessionDialog(s:RemoteState,session:RemoteSession?,stop:()->Unit,dismiss:()->Unit){
 val running=session?.status in listOf("starting","running")
 FffModal(session?.title?:"Codex-сессия",dismiss,if(running)"Остановить" else null,if(running)stop else null,destructive=running){
  Text(session?.status?:"",color=FffMuted)
  s.sessionOutputTruncatedBefore?.let{Text("Начало вывода удалено до позиции $it",color=Color(0xFFFFB86B))}
  Box(Modifier.fillMaxWidth().heightIn(min=220.dp,max=420.dp).background(Color.Black,RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState()).padding(12.dp)){
   Text(s.sessionOutput.ifEmpty{"Ожидание вывода…"},fontFamily=FontFamily.Monospace,color=Color(0xFFD8FFE9))
  }
 }
}
