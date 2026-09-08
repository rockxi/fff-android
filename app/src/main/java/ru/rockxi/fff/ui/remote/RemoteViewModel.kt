package ru.rockxi.fff.ui.remote

import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.rockxi.fff.data.harness.HarnessTokenStore
import ru.rockxi.fff.data.harness.HarnessUnauthorized
import ru.rockxi.fff.data.remote.*

internal data class RemoteState(val paired:Boolean=false,val hosts:List<RemoteHost> = emptyList(),val sessions:List<RemoteSession> = emptyList(),val selectedHostId:String?=null,val selectedSessionId:String?=null,val commandOutput:String="",val commandExitCode:Int?=null,val commandTruncated:Boolean=false,val sessionOutput:String="",val sessionOutputTruncatedBefore:Long?=null,val busy:Boolean=false,val error:String?=null)
internal class RemoteViewModel(private val api:RemoteApi,private val tokens:HarnessTokenStore,private val io:CoroutineDispatcher=Dispatchers.IO):ViewModel(){
    private val mutable=MutableStateFlow(RemoteState(paired=tokens.load()!=null));val state=mutable.asStateFlow();private var poll:Job?=null;private var outputOffset=0L
    init{reload()}
    fun reload()=launch{token()?.let{t->val hosts=api.hosts(t);val sessions=api.sessions(t);mutable.value=mutable.value.copy(hosts=hosts,sessions=sessions,selectedHostId=mutable.value.selectedHostId?.takeIf{x->hosts.any{it.id==x}}?:hosts.firstOrNull()?.id,busy=false,error=null)}}
    fun createHost(host:NewHost)=launch{token()?.let{api.createHost(it,host)};reload()}
    fun deleteHost(id:String)=launch{token()?.let{api.deleteHost(it,id)};reload()}
    fun selectHost(id:String){mutable.value=mutable.value.copy(selectedHostId=id)}
    fun runCommand(command:String,timeout:Int=30)=launch{val result=token()?.let{api.command(it,mutable.value.selectedHostId?:error("Выберите хост"),command,timeout)}?:return@launch;mutable.value=mutable.value.copy(commandOutput=listOf(result.stdout,result.stderr).filter{it.isNotEmpty()}.joinToString("\n"),commandExitCode=result.exitCode,commandTruncated=result.truncated,busy=false,error=null)}
    fun startSession(prompt:String,title:String?,directory:String?)=launch{val s=token()?.let{api.startSession(it,mutable.value.selectedHostId?:error("Выберите хост"),prompt,title?.takeIf(String::isNotBlank),directory?.takeIf(String::isNotBlank))}?:return@launch;openSession(s.id);reload()}
    fun openSession(id:String){poll?.cancel();outputOffset=0;mutable.value=mutable.value.copy(selectedSessionId=id,sessionOutput="",sessionOutputTruncatedBefore=null);poll=viewModelScope.launch{while(isActive){try{val t=token()?:break;val page=withContext(io){api.output(t,id,outputOffset)};outputOffset=page.nextOffset;mutable.value=mutable.value.copy(sessionOutput=mutable.value.sessionOutput+page.output,sessionOutputTruncatedBefore=page.truncatedBefore,error=null);val current=withContext(io){api.session(t,id)};mutable.value=mutable.value.copy(sessions=mutable.value.sessions.map{if(it.id==id)current else it});if(page.complete)break;delay(1500)}catch(e:Throwable){handle(e);break}}}}
    fun closeSession(){poll?.cancel();mutable.value=mutable.value.copy(selectedSessionId=null,sessionOutput="")}
    fun stopSession(id:String)=launch{token()?.let{api.stop(it,id)};if(mutable.value.selectedSessionId==id)openSession(id);reload()}
    private fun token()=tokens.load().also{if(it==null)mutable.value=RemoteState(paired=false,error="Сначала подключите AI Harness")}
    private fun launch(block:suspend()->Unit){mutable.value=mutable.value.copy(busy=true,error=null);viewModelScope.launch{try{withContext(io){block()};mutable.value=mutable.value.copy(busy=false)}catch(e:Throwable){handle(e)}}}
    private fun handle(e:Throwable){if(e is CancellationException)throw e;if(e is HarnessUnauthorized){tokens.clear();mutable.value=RemoteState(error="Сессия истекла. Подключите AI Harness снова.")}else mutable.value=mutable.value.copy(busy=false,error=e.message?:"Ошибка подключения")}
    companion object{fun factory(api:RemoteApi,tokens:HarnessTokenStore)=object:ViewModelProvider.Factory{@Suppress("UNCHECKED_CAST")override fun<T:ViewModel>create(modelClass:Class<T>):T=RemoteViewModel(api,tokens) as T}}
}
