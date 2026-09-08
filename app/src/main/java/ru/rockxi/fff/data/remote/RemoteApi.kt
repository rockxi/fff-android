package ru.rockxi.fff.data.remote

import kotlinx.serialization.json.*
import ru.rockxi.fff.data.harness.HarnessUnauthorized
import ru.rockxi.fff.data.harness.readBounded
import java.net.HttpURLConnection
import java.net.URL

internal data class RemoteHost(val id:String,val name:String,val hostname:String,val port:Int,val username:String,val authType:String,val jumpHostId:String?)
internal data class RemoteSession(val id:String,val hostId:String,val title:String?,val prompt:String,val workingDirectory:String?,val status:String,val exitCode:Int?)
internal data class CommandResult(val exitCode:Int?,val stdout:String,val stderr:String,val truncated:Boolean)
internal data class OutputPage(val output:String,val nextOffset:Long,val complete:Boolean,val truncatedBefore:Long?)
internal data class NewHost(val name:String,val hostname:String,val port:Int,val username:String,val authType:String,val secret:String,val passphrase:String?,val jumpHostId:String?)

internal object RemoteJson {
    private val json=Json { ignoreUnknownKeys=true }
    fun hosts(text:String)=array(text,"hosts").map(::host)
    fun sessions(text:String)=array(text,"sessions").map(::session)
    fun session(text:String)=session(json.parseToJsonElement(text).jsonObject.unwrap("session"))
    fun command(text:String)=json.parseToJsonElement(text).jsonObject.let{CommandResult(it.intOrNull("exitCode"),it.string("stdout",""),it.string("stderr",""),it.bool("truncated"))}
    fun output(text:String)=json.parseToJsonElement(text).jsonObject.let{OutputPage(it.string("output",it.string("chunk","")),it.long("nextOffset"),it.bool("complete"),it.longOrNull("truncatedBefore"))}
    private fun array(text:String,key:String):JsonArray { val root=json.parseToJsonElement(text);return when(root){is JsonArray->root;is JsonObject->root[key]?.jsonArray?:JsonArray(emptyList());else->JsonArray(emptyList())} }
    private fun host(e:JsonElement)=e.jsonObject.let{RemoteHost(it.req("id"),it.req("name"),it.req("hostname"),it.int("port"),it.req("username"),it.req("authType"),it.nullable("jumpHostId"))}
    private fun session(e:JsonElement)=e.jsonObject.let{RemoteSession(it.req("id"),it.req("hostId"),it.nullable("title"),it.string("prompt",""),it.nullable("workingDirectory"),it.req("status"),it.intOrNull("exitCode"))}
    private fun JsonObject.unwrap(key:String)=this[key]?:this
    private fun JsonObject.req(k:String)=this[k]?.jsonPrimitive?.content?:error("Missing $k")
    private fun JsonObject.string(k:String,d:String)=this[k]?.jsonPrimitive?.contentOrNull?:d
    private fun JsonObject.nullable(k:String)=this[k]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(k:String)=this[k]?.jsonPrimitive?.int?:error("Missing $k")
    private fun JsonObject.intOrNull(k:String)=this[k]?.jsonPrimitive?.intOrNull
    private fun JsonObject.long(k:String)=this[k]?.jsonPrimitive?.long?:error("Missing $k")
    private fun JsonObject.longOrNull(k:String)=this[k]?.jsonPrimitive?.longOrNull
    private fun JsonObject.bool(k:String)=this[k]?.jsonPrimitive?.booleanOrNull?:false
}

internal interface RemoteApi {
    suspend fun hosts(token:String):List<RemoteHost>; suspend fun createHost(token:String,host:NewHost):RemoteHost; suspend fun deleteHost(token:String,id:String)
    suspend fun command(token:String,id:String,command:String,timeoutSeconds:Int):CommandResult
    suspend fun sessions(token:String):List<RemoteSession>; suspend fun startSession(token:String,hostId:String,prompt:String,title:String?,workingDirectory:String?):RemoteSession
    suspend fun session(token:String,id:String):RemoteSession; suspend fun output(token:String,id:String,offset:Long):OutputPage; suspend fun stop(token:String,id:String)
}

internal class HttpRemoteApi:RemoteApi {
    companion object { const val BASE_URL="https://fff.rockxi.ru/api/harness/remote"; const val COMMAND_READ_TIMEOUT_MS=240_000; const val DEFAULT_RESPONSE_BYTES=512*1024; const val COMMAND_RESPONSE_BYTES=4*1024*1024 }
    override suspend fun hosts(token:String)=RemoteJson.hosts(request("GET","hosts",token))
    override suspend fun createHost(token:String,host:NewHost)=RemoteJson.hosts("[${request("POST","hosts",token,buildJsonObject{put("name",host.name);put("hostname",host.hostname);put("port",host.port);put("username",host.username);put("authType",host.authType);put("secret",host.secret);host.passphrase?.let{p->put("passphrase",p)};host.jumpHostId?.let{j->put("jumpHostId",j)}}.toString())}]").single()
    override suspend fun deleteHost(token:String,id:String){request("DELETE","hosts/$id",token)}
    override suspend fun command(token:String,id:String,command:String,timeoutSeconds:Int)=RemoteJson.command(request("POST","hosts/$id/commands",token,buildJsonObject{put("command",command);put("timeoutSeconds",timeoutSeconds)}.toString(),COMMAND_READ_TIMEOUT_MS,COMMAND_RESPONSE_BYTES))
    override suspend fun sessions(token:String)=RemoteJson.sessions(request("GET","sessions",token))
    override suspend fun startSession(token:String,hostId:String,prompt:String,title:String?,workingDirectory:String?)=RemoteJson.session(request("POST","sessions",token,buildJsonObject{put("hostId",hostId);put("prompt",prompt);title?.let{put("title",it)};workingDirectory?.let{put("workingDirectory",it)}}.toString()))
    override suspend fun session(token:String,id:String)=RemoteJson.session(request("GET","sessions/$id",token))
    override suspend fun output(token:String,id:String,offset:Long)=RemoteJson.output(request("GET","sessions/$id/output?offset=$offset&limit=65536",token))
    override suspend fun stop(token:String,id:String){request("POST","sessions/$id/stop",token,"{}")}
    private fun request(method:String,path:String,token:String,body:String?=null,timeout:Int=15_000,maxResponseBytes:Int=DEFAULT_RESPONSE_BYTES):String {
        require(body==null||body.toByteArray().size<=32*1024)
        val c=(URL("$BASE_URL/$path").openConnection() as HttpURLConnection).apply{requestMethod=method;instanceFollowRedirects=false;connectTimeout=10_000;readTimeout=timeout;setRequestProperty("Authorization","Bearer $token");setRequestProperty("Content-Type","application/json");if(body!=null)doOutput=true}
        try{body?.let{c.outputStream.use{out->out.write(it.toByteArray())}};val status=c.responseCode;val stream=if(status in 200..299)c.inputStream else c.errorStream;val text=stream?.use{String(readBounded(it,c.contentLengthLong,maxResponseBytes))}?:"{}";if(status==401)throw HarnessUnauthorized();if(status !in 200..299)throw IllegalStateException(runCatching{Json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content}.getOrNull()?:"Ошибка сервера: $status");return text}finally{c.disconnect()}
    }
}
