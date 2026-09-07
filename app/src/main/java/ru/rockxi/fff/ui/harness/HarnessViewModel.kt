package ru.rockxi.fff.ui.harness

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import ru.rockxi.fff.data.harness.*

internal data class HarnessMessage(val id:Long,val mine:Boolean,val text:String,val failed:Boolean=false)
internal sealed interface HarnessPhase { data object Unpaired:HarnessPhase; data class Pairing(val code:String):HarnessPhase; data object Paired:HarnessPhase; data object Revoking:HarnessPhase }
internal data class HarnessState(val phase:HarnessPhase=HarnessPhase.Unpaired,val messages:List<HarnessMessage> = emptyList(),val busy:Boolean=false,val error:String?=null,val retryMessageId:Long?=null,val composerError:String?=null)

internal class HarnessViewModel(private val api:HarnessApi,private val tokens:HarnessTokenStore,private val io:CoroutineDispatcher=Dispatchers.IO):ViewModel(){
    private var token=tokens.load();private var pending=tokens.pendingRevoke();private val mutable=MutableStateFlow(HarnessState(phase=if(pending!=null)HarnessPhase.Revoking else if(token!=null) HarnessPhase.Paired else HarnessPhase.Unpaired));val state=mutable.asStateFlow();private var nextId=1L;private var pairingJob:Job?=null
    init{if(pending!=null)retryPendingRevoke()}
    fun pair(){if(pending!=null){mutable.value=mutable.value.copy(phase=HarnessPhase.Revoking,error="Сначала завершите отключение прошлого устройства");return};if(mutable.value.busy||pairingJob?.isActive==true)return;mutable.value=mutable.value.copy(busy=true,error=null);pairingJob=viewModelScope.launch{try{val started=withContext(io){api.start(tokens.deviceId())};mutable.value=mutable.value.copy(phase=HarnessPhase.Pairing(started.code),busy=false);var pause=1_000L;while(true){delay(pause);when(val result=withContext(io){api.poll(started.pairingId,started.pollSecret)}){PollResult.Pending->pause=(pause*2).coerceAtMost(8_000);PollResult.Delivered->throw IllegalStateException("Ключ уже получен. Начните подключение заново.");is PollResult.Token->{token=result.value;tokens.save(result.value);mutable.value=mutable.value.copy(phase=HarnessPhase.Paired,busy=false,error=null);break}}}}catch(c:CancellationException){throw c}catch(e:Throwable){mutable.value=mutable.value.copy(phase=HarnessPhase.Unpaired,busy=false,error=e.message)}}}
    fun send(text:String):Boolean{val clean=text.trim();val problem=when{clean.isEmpty()->"Введите сообщение";clean.toByteArray(Charsets.UTF_8).size>MAX_MESSAGE_BYTES->"Сообщение превышает 20 000 байт";mutable.value.phase!=HarnessPhase.Paired||token==null->"Сначала подключите ассистента";mutable.value.busy->"Дождитесь ответа ассистента";else->null};if(problem!=null){mutable.value=mutable.value.copy(composerError=problem);return false};val id=nextId++;mutable.value=mutable.value.copy(messages=mutable.value.messages+HarnessMessage(id,true,clean),composerError=null);sendExisting(id,clean);return true}
    private fun sendExisting(id:Long,text:String){val bearer=token?:return;mutable.value=mutable.value.copy(messages=mutable.value.messages.map{if(it.id==id)it.copy(failed=false)else it},busy=true,error=null,retryMessageId=null);viewModelScope.launch{try{val answer=withContext(io){api.chat(bearer,text)};mutable.value=mutable.value.copy(messages=mutable.value.messages+HarnessMessage(nextId++,false,answer),busy=false)}catch(_:HarnessUnauthorized){tokens.clear();token=null;mutable.value=HarnessState(error="Сессия истекла. Подключите устройство снова.")}catch(e:Throwable){mutable.value=mutable.value.copy(messages=mutable.value.messages.map{if(it.id==id)it.copy(failed=true)else it},busy=false,error=e.message?:"Не удалось отправить",retryMessageId=id)}}}
    fun retry(){val id=mutable.value.retryMessageId?:return;mutable.value.messages.firstOrNull{it.id==id}?.let{sendExisting(id,it.text)}}
    fun unpair(){if(mutable.value.busy)return;pairingJob?.cancel();val bearer=token;if(bearer==null){retryPendingRevoke();return};tokens.moveToPendingRevoke(bearer);pending=bearer;token=null;retryPendingRevoke()}
    fun retryPendingRevoke(){val bearer=pending?:tokens.pendingRevoke()?.also{pending=it}?:run{mutable.value=HarnessState();return};mutable.value=HarnessState(phase=HarnessPhase.Revoking,busy=true);viewModelScope.launch{try{withContext(io){api.revoke(bearer)};tokens.clearPendingRevoke();pending=null;mutable.value=HarnessState()}catch(_:HarnessUnauthorized){tokens.clearPendingRevoke();pending=null;mutable.value=HarnessState()}catch(e:Throwable){mutable.value=HarnessState(phase=HarnessPhase.Revoking,error=e.message?:"Не удалось отключить устройство")}}}
    companion object{const val MAX_MESSAGE_BYTES=20_000;fun factory(api:HarnessApi,tokens:HarnessTokenStore)=object:ViewModelProvider.Factory{@Suppress("UNCHECKED_CAST")override fun<T:ViewModel>create(modelClass:Class<T>):T=HarnessViewModel(api,tokens) as T}}
}
