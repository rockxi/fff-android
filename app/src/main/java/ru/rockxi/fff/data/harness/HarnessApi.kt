package ru.rockxi.fff.data.harness

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

internal data class PairStart(val pairingId:String,val code:String,val pollSecret:String)
internal sealed interface PollResult { data object Pending:PollResult; data object Delivered:PollResult; data class Token(val value:String):PollResult }
internal class HarnessUnauthorized:Exception("Требуется повторное подключение")
internal enum class ConversationKind { EE, HARNESS }
internal data class HarnessConversation(val id:String,val kind:ConversationKind,val title:String,val pinned:Boolean,val archived:Boolean,val createdAt:String,val updatedAt:String,val lastMessage:String?)
internal data class ServerMessage(val id:String,val sequence:Long,val conversationId:String,val role:String,val content:String,val source:String,val authorName:String?,val createdAt:String)
internal data class MessagePage(val items:List<ServerMessage>,val nextBefore:Long?)
internal data class SendResult(val message:ServerMessage,val answer:ServerMessage?,val pending:Boolean)

internal object HarnessJson {
 private val json=Json{ignoreUnknownKeys=true}
 fun start(text:String):PairStart{val o=obj(text);return PairStart(o.req("pairingId"),o.req("code"),o.req("pollSecret"))}
 fun poll(text:String):PollResult{val o=obj(text);return when(o.req("status")){"pending"->PollResult.Pending;"paired"->o["token"]?.jsonPrimitive?.content?.let{PollResult.Token(it)}?:PollResult.Delivered;else->error("Invalid pairing status")}}
 fun conversations(text:String)=obj(text)["items"]!!.jsonArray.map(::conversation)
 fun conversation(text:String)=conversation(obj(text))
 fun messages(text:String):MessagePage{val o=obj(text);return MessagePage(o["items"]!!.jsonArray.map(::message),o["nextBefore"]?.let{if(it is JsonNull)null else it.jsonPrimitive.long})}
 fun send(text:String):SendResult{val o=obj(text);return SendResult(message(o["message"]!!),o["answer"]?.let{if(it is JsonNull)null else message(it)},o["pending"]?.jsonPrimitive?.booleanOrNull?:false)}
 private fun conversation(e:JsonElement):HarnessConversation{val o=e.jsonObject;return HarnessConversation(o.req("id"),if(o.req("kind")=="ee")ConversationKind.EE else ConversationKind.HARNESS,o.req("title"),o["pinned"]!!.jsonPrimitive.boolean,o["archived"]!!.jsonPrimitive.boolean,o.req("createdAt"),o.req("updatedAt"),o["lastMessage"]?.jsonPrimitive?.contentOrNull)}
 private fun message(e:JsonElement):ServerMessage{val o=e.jsonObject;return ServerMessage(o.req("id"),o["sequence"]!!.jsonPrimitive.long,o.req("conversationId"),o.req("role"),o.req("content"),o.req("source"),o["authorName"]?.jsonPrimitive?.contentOrNull,o.req("createdAt"))}
 private fun obj(text:String)=json.parseToJsonElement(text).jsonObject
 private fun JsonObject.req(name:String)=this[name]?.jsonPrimitive?.content?:error("Missing $name")
}

internal interface HarnessApi{
 suspend fun start(deviceId:String):PairStart; suspend fun poll(pairingId:String,pollSecret:String):PollResult; suspend fun revoke(token:String)
 suspend fun listConversations(token:String,archived:Boolean=false):List<HarnessConversation>;suspend fun createConversation(token:String,title:String):HarnessConversation
 suspend fun updateConversation(token:String,id:String,title:String?=null,archived:Boolean?=null):HarnessConversation;suspend fun deleteConversation(token:String,id:String)
 suspend fun messages(token:String,id:String,before:Long?=null):MessagePage;suspend fun send(token:String,id:String,message:String,clientMessageId:String):SendResult
}

internal class HttpHarnessApi:HarnessApi{
 companion object{const val BASE_URL="https://fff.rockxi.ru/api/harness";private const val MAX_BODY=32*1024}
 override suspend fun start(deviceId:String)=HarnessJson.start(request("POST","pair/start",buildJsonObject{put("deviceId",deviceId)}.toString()).second)
 override suspend fun poll(pairingId:String,pollSecret:String)=HarnessJson.poll(request("POST","pair/status",buildJsonObject{put("pairingId",pairingId);put("pollSecret",pollSecret)}.toString()).second)
 override suspend fun revoke(token:String){request("POST","revoke","{}",token)}
 override suspend fun listConversations(token:String,archived:Boolean)=HarnessJson.conversations(request("GET","conversations?archived=$archived",token=token).second)
 override suspend fun createConversation(token:String,title:String)=HarnessJson.conversation(request("POST","conversations",buildJsonObject{put("title",title)}.toString(),token).second)
 override suspend fun updateConversation(token:String,id:String,title:String?,archived:Boolean?):HarnessConversation{val body=buildJsonObject{title?.let{put("title",it)};archived?.let{put("archived",it)}};return HarnessJson.conversation(request("PATCH","conversations/${segment(id)}",body.toString(),token).second)}
 override suspend fun deleteConversation(token:String,id:String){request("DELETE","conversations/${segment(id)}",token=token)}
 override suspend fun messages(token:String,id:String,before:Long?):MessagePage{val q=before?.let{"?limit=50&before=$it"}?:"?limit=50";return HarnessJson.messages(request("GET","conversations/${segment(id)}/messages$q",token=token).second)}
 override suspend fun send(token:String,id:String,message:String,clientMessageId:String):SendResult{require(message.toByteArray().size<=20_000);val body=buildJsonObject{put("message",message);put("clientMessageId",clientMessageId)};return HarnessJson.send(request("POST","conversations/${segment(id)}/messages",body.toString(),token,125_000).second)}
 private fun segment(value:String)=URLEncoder.encode(value,Charsets.UTF_8.name())
 private fun request(method:String,path:String,body:String="",token:String?=null,timeout:Int=10_000):Pair<Int,String>{require(body.toByteArray().size<=MAX_BODY);val c=(URL("$BASE_URL/$path").openConnection() as HttpURLConnection).apply{requestMethod=method;instanceFollowRedirects=false;connectTimeout=10_000;readTimeout=timeout;doOutput=body.isNotEmpty();setRequestProperty("Content-Type","application/json");token?.let{setRequestProperty("Authorization","Bearer $it")}};try{if(body.isNotEmpty())c.outputStream.use{it.write(body.toByteArray())};val status=c.responseCode;val stream=if(status in 200..299)c.inputStream else c.errorStream;val text=stream?.use{String(readBounded(it,c.contentLengthLong,256*1024),Charsets.UTF_8)}?:"{}";if(status==401)throw HarnessUnauthorized();if(status !in 200..299)throw IllegalStateException(runCatching{Json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content}.getOrNull()?:"Ошибка сервера: $status");return status to text}finally{c.disconnect()}}
}

internal fun readBounded(input:InputStream,contentLength:Long,maxBytes:Int):ByteArray{if(contentLength>maxBytes)throw IllegalStateException("Response too large");val out=ByteArrayOutputStream(minOf(if(contentLength>=0)contentLength.toInt() else 8192,maxBytes));val buffer=ByteArray(8192);var total=0;while(true){val read=input.read(buffer);if(read<0)break;total+=read;if(total>maxBytes)throw IllegalStateException("Response too large");out.write(buffer,0,read)};return out.toByteArray()}
