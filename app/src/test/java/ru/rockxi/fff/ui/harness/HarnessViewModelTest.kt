package ru.rockxi.fff.ui.harness

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.rockxi.fff.data.harness.*
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class HarnessViewModelTest {
 private val dispatcher=StandardTestDispatcher()
 @Before fun before(){Dispatchers.setMain(dispatcher)}
 @After fun after(){Dispatchers.resetMain()}
 @Test fun `json parsing covers pairing pending token and answer`(){assertEquals("ABC123",HarnessJson.start("""{"pairingId":"p","code":"ABC123","pollSecret":"s"}""").code);assertEquals(PollResult.Pending,HarnessJson.poll("""{"status":"pending"}"""));assertEquals(PollResult.Token("t"),HarnessJson.poll("""{"status":"paired","token":"t"}"""));assertEquals("ok",HarnessJson.answer("""{"answer":"ok"}"""))}
 @Test fun `pair stores received token then chat is single flight`()=runTest(dispatcher){val api=FakeApi();val store=MemoryTokens();val model=HarnessViewModel(api,store,dispatcher);model.pair();advanceTimeBy(1001);advanceUntilIdle();assertEquals("token",store.value);assertEquals(HarnessPhase.Paired,model.state.value.phase);model.send("hi");model.send("duplicate");advanceUntilIdle();assertEquals(listOf("hi"),api.sent);assertEquals(2,model.state.value.messages.size)}
 @Test fun `chat failure retries same bubble without duplicate`()=runTest(dispatcher){val api=FakeApi().apply{failChat=true};val store=MemoryTokens("token");val model=HarnessViewModel(api,store,dispatcher);model.send("again");advanceUntilIdle();val id=model.state.value.retryMessageId!!;assertTrue(model.state.value.messages.single{it.id==id}.failed);api.failChat=false;model.retry();advanceUntilIdle();assertNull(model.state.value.retryMessageId);assertEquals(2,api.sent.size);assertEquals(2,model.state.value.messages.size)}
 @Test fun `unpair retains encrypted pending token until retry confirms revoke`()=runTest(dispatcher){val api=FakeApi().apply{failRevoke=true};val store=MemoryTokens("token");val model=HarnessViewModel(api,store,dispatcher);model.unpair();advanceUntilIdle();assertNull(store.value);assertEquals("token",store.pending);assertEquals(HarnessPhase.Revoking,model.state.value.phase);api.failRevoke=false;model.retryPendingRevoke();advanceUntilIdle();assertNull(store.pending);assertEquals(HarnessPhase.Unpaired,model.state.value.phase)}
 @Test fun `bounded reader rejects content length and chunked overflow`(){val bytes=ByteArray(11);assertFails{readBounded(ByteArrayInputStream(bytes),11,10)};assertFails{readBounded(ByteArrayInputStream(bytes),-1,10)};assertArrayEquals(ByteArray(10),readBounded(ByteArrayInputStream(ByteArray(10)),-1,10))}
 @Test fun `composer accepts exact bytes and rejects utf8 oversize without bubble`()=runTest(dispatcher){val model=HarnessViewModel(FakeApi(),MemoryTokens("token"),dispatcher);val exact="a".repeat(20_000);assertTrue(model.send(exact));advanceUntilIdle();val count=model.state.value.messages.size;val cyrillic="я".repeat(10_001);assertTrue(cyrillic.length<20_000);assertFalse(model.send(cyrillic));assertEquals(count,model.state.value.messages.size);assertTrue(model.state.value.composerError!!.contains("20 000"))}
 @Test fun `blank and busy sends return false with stable error`()=runTest(dispatcher){val model=HarnessViewModel(FakeApi(),MemoryTokens("token"),dispatcher);assertFalse(model.send("   "));assertEquals("Введите сообщение",model.state.value.composerError);assertTrue(model.send("first"));assertFalse(model.send("second"));assertEquals("Дождитесь ответа ассистента",model.state.value.composerError);advanceUntilIdle()}
 private fun assertFails(block:()->Unit){try{block();fail("Expected failure")}catch(_:IllegalStateException){}}
}
private class MemoryTokens(var value:String?=null,var pending:String?=null):HarnessTokenStore{override fun deviceId()="stable-device";override fun load()=value;override fun save(token:String){value=token;pending=null};override fun clear(){value=null};override fun pendingRevoke()=pending;override fun moveToPendingRevoke(token:String){pending=token;value=null};override fun clearPendingRevoke(){pending=null}}
private class FakeApi:HarnessApi{var failChat=false;var failRevoke=false;val sent=mutableListOf<String>();override suspend fun start(deviceId:String)=PairStart("p","ABC123","s");override suspend fun poll(pairingId:String,pollSecret:String)=PollResult.Token("token");override suspend fun chat(token:String,message:String):String{sent+=message;if(failChat)error("offline");return "reply"};override suspend fun revoke(token:String){if(failRevoke)error("offline")}}
