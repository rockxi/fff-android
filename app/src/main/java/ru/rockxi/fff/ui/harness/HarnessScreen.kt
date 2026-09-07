package ru.rockxi.fff.ui.harness

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.rockxi.fff.data.harness.HttpHarnessApi
import ru.rockxi.fff.data.harness.KeystoreHarnessTokenStore
import ru.rockxi.fff.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun HarnessScreen(onBack:()->Unit){val context=LocalContext.current;val model:HarnessViewModel=viewModel(factory=HarnessViewModel.factory(HttpHarnessApi(),KeystoreHarnessTokenStore(context)));val state by model.state.collectAsStateWithLifecycle();Scaffold(containerColor=FffBackground,topBar={TopAppBar(title={Text("AI Harness",fontWeight=FontWeight.Bold)},navigationIcon={IconButton(onBack){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")}},colors=TopAppBarDefaults.topAppBarColors(containerColor=FffBackground))}){padding->Box(Modifier.fillMaxSize().padding(padding)){when(val phase=state.phase){HarnessPhase.Unpaired->PairIntro(state.error,state.busy,model::pair);is HarnessPhase.Pairing->PairCode(phase.code,state.error);HarnessPhase.Paired->Chat(state,model::send,model::retry,model::unpair);HarnessPhase.Revoking->Revoking(state,model::retryPendingRevoke)}}}}
@Composable private fun PairIntro(error:String?,busy:Boolean,pair:()->Unit)=Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Rounded.Link,null,tint=FffMint,modifier=Modifier.size(48.dp));Text("Подключить ассистента",fontWeight=FontWeight.Bold);Text("Подтверждение выполняется лично вами в Telegram.",color=FffMuted,modifier=Modifier.padding(vertical=12.dp));Button(pair,enabled=!busy){Text(if(busy)"Подключаю…" else "Получить код")};error?.let{Text(it,color=Color(0xFFFF7C9B),modifier=Modifier.padding(top=12.dp))}}
@Composable private fun PairCode(code:String,error:String?)=Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){Text("КОД ПОДКЛЮЧЕНИЯ",color=FffMint,fontFamily=FontFamily.Monospace);Text(code,fontSize=36.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(14.dp));Text("Отправьте боту в топике ИИ:");Text("/pair $code",fontFamily=FontFamily.Monospace,color=FffViolet,modifier=Modifier.padding(10.dp));Text("Ожидаю подтверждение…",color=FffMuted);error?.let{Text(it,color=Color(0xFFFF7C9B))}}
@Composable private fun Revoking(state:HarnessState,retry:()->Unit)=Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){if(state.busy)CircularProgressIndicator(color=FffMint);Text("Завершаю отключение…",fontWeight=FontWeight.Bold,modifier=Modifier.padding(12.dp));Text("Чат уже отключён локально. Ключ будет удалён после подтверждения сервера.",color=FffMuted);state.error?.let{Text(it,color=Color(0xFFFF7C9B),modifier=Modifier.padding(8.dp));Button(retry){Text("Повторить")}}}
@Composable private fun Chat(state:HarnessState,send:(String)->Boolean,retry:()->Unit,unpair:()->Unit){var text by remember{mutableStateOf("")};val bytes by remember(text){derivedStateOf{text.toByteArray(Charsets.UTF_8).size}};val oversized=bytes>HarnessViewModel.MAX_MESSAGE_BYTES;Column(Modifier.fillMaxSize()){Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.End){TextButton(unpair,enabled=!state.busy){Text("Отключить")}};LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){items(state.messages,key={it.id}){m->Row(Modifier.fillMaxWidth(),horizontalArrangement=if(m.mine)Arrangement.End else Arrangement.Start){Text(m.text,Modifier.widthIn(max=300.dp).background(if(m.failed)Color(0xFF5A1E31) else if(m.mine)FffViolet.copy(alpha=.25f) else FffSurface,RoundedCornerShape(14.dp)).padding(12.dp))}};if(state.busy)item(key="assistant-loading"){Row(verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp,color=FffMint);Text("Ассистент отвечает…",color=FffMuted,modifier=Modifier.padding(start=8.dp))}}};state.error?.let{Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){Text(it,Modifier.weight(1f),color=Color(0xFFFF7C9B));if(state.retryMessageId!=null)TextButton(retry){Text("Повторить")}}};Column(Modifier.fillMaxWidth().padding(8.dp)){Row(verticalAlignment=Alignment.CenterVertically){OutlinedTextField(text,{text=it.take(40_000)},Modifier.weight(1f),placeholder={Text("Сообщение")},enabled=!state.busy,isError=oversized||state.composerError!=null);IconButton({if(send(text))text=""},enabled=!state.busy&&text.isNotBlank()&&!oversized){Icon(Icons.Rounded.Send,"Отправить",tint=FffMint)}};Text(state.composerError?:"$bytes / ${HarnessViewModel.MAX_MESSAGE_BYTES} байт",color=if(oversized||state.composerError!=null)Color(0xFFFF7C9B) else FffMuted,fontSize=11.sp)}}}
