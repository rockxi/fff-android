package ru.rockxi.fff.ui.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.*
import ru.rockxi.fff.data.harness.HarnessTokenStore
import ru.rockxi.fff.data.remote.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RemoteViewModelTest {
 private val dispatcher=StandardTestDispatcher()
 @Before fun setup(){Dispatchers.setMain(dispatcher)}
 @After fun tearDown(){Dispatchers.resetMain()}
 @Test fun `reload and command update state`()=runTest(dispatcher){val api=FakeApi();val vm=RemoteViewModel(api,Tokens(),dispatcher);advanceUntilIdle();Assert.assertEquals("h1",vm.state.value.selectedHostId);vm.runCommand("pwd");advanceUntilIdle();Assert.assertEquals("/tmp",vm.state.value.commandOutput);Assert.assertEquals(0,vm.state.value.commandExitCode)}
 @Test fun `missing pairing token shows harness requirement`()=runTest(dispatcher){val vm=RemoteViewModel(FakeApi(),Tokens(null),dispatcher);advanceUntilIdle();Assert.assertFalse(vm.state.value.paired);Assert.assertTrue(vm.state.value.error!!.contains("Harness"))}
 @Test fun `agent launch requires selected host and prompt`(){Assert.assertFalse(canStartRemoteAgent(false,false,"keep this prompt"));Assert.assertFalse(canStartRemoteAgent(true,false,"  "));Assert.assertFalse(canStartRemoteAgent(true,true,"task"));Assert.assertTrue(canStartRemoteAgent(true,false,"task"))}
 @Test fun `android command deadline exceeds proxyjump deadline`(){Assert.assertTrue(HttpRemoteApi.COMMAND_READ_TIMEOUT_MS>230_000)}
 private class Tokens(private var token:String?="owner"):HarnessTokenStore{override fun deviceId()="d";override fun load()=token;override fun save(token:String){this.token=token};override fun clear(){token=null};override fun pendingRevoke():String?=null;override fun moveToPendingRevoke(token:String){};override fun clearPendingRevoke(){}}
 private class FakeApi:RemoteApi{val host=RemoteHost("h1","Box","host",22,"me","password",null);override suspend fun hosts(token:String)=listOf(host);override suspend fun createHost(token:String,host:NewHost)=this.host;override suspend fun deleteHost(token:String,id:String){};override suspend fun command(token:String,id:String,command:String,timeoutSeconds:Int)=CommandResult(0,"/tmp","",false);override suspend fun sessions(token:String)= emptyList<RemoteSession>();override suspend fun startSession(token:String,hostId:String,prompt:String,title:String?,workingDirectory:String?)=RemoteSession("s","h1",title,prompt,workingDirectory,"running",null);override suspend fun session(token:String,id:String)=RemoteSession(id,"h1",null,"","/tmp","completed",0);override suspend fun output(token:String,id:String,offset:Long)=OutputPage("done",4,true,null);override suspend fun stop(token:String,id:String){}}
}
