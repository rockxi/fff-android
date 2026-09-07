package ru.rockxi.fff.data.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.io.InputStream
import java.io.ByteArrayOutputStream

internal data class PairStart(val pairingId:String,val code:String,val pollSecret:String)
internal sealed interface PollResult { data object Pending:PollResult; data object Delivered:PollResult; data class Token(val value:String):PollResult }
internal class HarnessUnauthorized:Exception("Требуется повторное подключение")
internal object HarnessJson {
    private val json=Json { ignoreUnknownKeys=true }
    fun start(text:String):PairStart { val o=json.parseToJsonElement(text).jsonObject;return PairStart(o.req("pairingId"),o.req("code"),o.req("pollSecret")) }
    fun poll(text:String):PollResult { val o=json.parseToJsonElement(text).jsonObject;return when(o.req("status")){"pending"->PollResult.Pending;"paired"->o["token"]?.jsonPrimitive?.content?.let{PollResult.Token(it)}?:PollResult.Delivered;else->error("Invalid pairing status")} }
    fun answer(text:String)=json.parseToJsonElement(text).jsonObject.req("answer")
    private fun kotlinx.serialization.json.JsonObject.req(name:String)=this[name]?.jsonPrimitive?.content?:error("Missing $name")
}

internal interface HarnessApi {
    suspend fun start(deviceId:String):PairStart
    suspend fun poll(pairingId:String,pollSecret:String):PollResult
    suspend fun chat(token:String,message:String):String
    suspend fun revoke(token:String)
}

internal class HttpHarnessApi:HarnessApi {
    companion object { const val BASE_URL="https://fff.rockxi.ru/api/harness"; private const val MAX_BODY=32*1024 }
    private val json=Json { ignoreUnknownKeys=true }
    override suspend fun start(deviceId:String)=HarnessJson.start(request("pair/start",buildJsonObject{put("deviceId",deviceId)}.toString()).toString())
    override suspend fun poll(pairingId:String,pollSecret:String)=HarnessJson.poll(request("pair/status",buildJsonObject{put("pairingId",pairingId);put("pollSecret",pollSecret)}.toString()).toString())
    override suspend fun chat(token:String,message:String):String { require(message.toByteArray().size<=20_000);return HarnessJson.answer(request("chat",buildJsonObject{put("message",message)}.toString(),token).toString()) }
    override suspend fun revoke(token:String){request("revoke","{}",token)}
    private fun request(path:String,body:String,token:String?=null):kotlinx.serialization.json.JsonElement {
        require(body.toByteArray().size<=MAX_BODY)
        val connection=(URL("$BASE_URL/$path").openConnection() as HttpURLConnection).apply { requestMethod="POST";instanceFollowRedirects=false;connectTimeout=10_000;readTimeout=if(path=="chat")125_000 else 10_000;doOutput=true;setRequestProperty("Content-Type","application/json");token?.let{setRequestProperty("Authorization","Bearer $it")} }
        try { connection.outputStream.use{it.write(body.toByteArray())};val status=connection.responseCode;val stream=if(status in 200..299) connection.inputStream else connection.errorStream;val text=stream?.use{String(readBounded(it,connection.contentLengthLong,128*1024),Charsets.UTF_8)}?:"{}";if(status==401)throw HarnessUnauthorized();if(status !in 200..299)throw IllegalStateException(runCatching{json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content}.getOrNull()?:"Ошибка сервера: $status");return json.parseToJsonElement(text) } finally { connection.disconnect() }
    }
}

internal fun readBounded(input:InputStream,contentLength:Long,maxBytes:Int):ByteArray {
    if(contentLength>maxBytes) throw IllegalStateException("Response too large")
    val output=ByteArrayOutputStream(minOf(if(contentLength>=0)contentLength.toInt() else 8192,maxBytes))
    val buffer=ByteArray(8192);var total=0
    while(true){val read=input.read(buffer);if(read<0)break;total+=read;if(total>maxBytes)throw IllegalStateException("Response too large");output.write(buffer,0,read)}
    return output.toByteArray()
}
