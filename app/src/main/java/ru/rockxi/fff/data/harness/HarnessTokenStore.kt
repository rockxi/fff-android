package ru.rockxi.fff.data.harness

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import ru.rockxi.fff.data.finance.FinanceAnalyticsPreset
import ru.rockxi.fff.data.finance.FinanceAnalyticsRange
import ru.rockxi.fff.data.finance.FinanceHarnessContext
import ru.rockxi.fff.data.finance.FinanceHarnessContextCodec

internal data class PendingHarnessMessage(val clientId:String,val text:String,val provider:HarnessProvider=HarnessProvider.AGENT,val model:String?=null,val financeContext:FinanceHarnessContext?=null,val financeRange:FinanceAnalyticsRange?=null)
internal interface HarnessTokenStore { fun deviceId():String; fun load():String?; fun save(token:String); fun clear(); fun pendingRevoke():String?; fun moveToPendingRevoke(token:String); fun clearPendingRevoke(); fun draft(id:String):String=""; fun saveDraft(id:String,value:String){}; fun pendingMessage(id:String):PendingHarnessMessage?=null; fun savePendingMessage(id:String,value:PendingHarnessMessage?){}; fun provider(id:String):HarnessProvider=HarnessProvider.AGENT;fun saveProvider(id:String,value:HarnessProvider){};fun resetCodexProviders(){};fun model(id:String):String?=null;fun saveModel(id:String,value:String?){} }

internal class KeystoreHarnessTokenStore(context:Context):HarnessTokenStore {
    private val prefs=context.applicationContext.getSharedPreferences("harness_device",Context.MODE_PRIVATE)
    override fun deviceId():String=prefs.getString("device_id",null)?:UUID.randomUUID().toString().also{prefs.edit().putString("device_id",it).apply()}
    override fun save(token:String){prefs.edit().putString("token",encrypt(token)).remove("pending_revoke").apply()}
    override fun load():String?=decryptSlot("token")
    override fun clear(){prefs.edit().remove("token").commit()}
    override fun pendingRevoke():String?=decryptSlot("pending_revoke")
    override fun moveToPendingRevoke(token:String){prefs.edit().putString("pending_revoke",encrypt(token)).remove("token").commit()}
    override fun clearPendingRevoke(){prefs.edit().remove("pending_revoke").commit()}
    override fun draft(id:String)=prefs.getString("draft_$id","")?:""
    override fun saveDraft(id:String,value:String){prefs.edit().putString("draft_$id",value).apply()}
    override fun pendingMessage(id:String):PendingHarnessMessage?{val client=prefs.getString("pending_message_id_$id",null)?:return null;val text=prefs.getString("pending_message_text_$id",null)?:return null;val provider=runCatching{HarnessProvider.valueOf(prefs.getString("pending_message_provider_$id",null)?:"AGENT")}.getOrDefault(HarnessProvider.AGENT);val json=prefs.getString("pending_message_finance_context_$id",null);val binding=if(json==null)null else runCatching{val preset=FinanceAnalyticsPreset.valueOf(prefs.getString("pending_message_finance_preset_$id","")?:"");val start=prefs.getString("pending_message_finance_start_$id",null)?.let(LocalDate::parse);val end=prefs.getString("pending_message_finance_end_$id",null)?.let(LocalDate::parse);val range=FinanceAnalyticsRange(start,end,preset);FinanceHarnessContextCodec.restore(json,range)?.let{it to range}}.getOrNull();return PendingHarnessMessage(client,text,provider,prefs.getString("pending_message_model_$id",null),binding?.first,binding?.second)}
    override fun savePendingMessage(id:String,value:PendingHarnessMessage?){prefs.edit().let{edit->val keys=listOf("pending_message_id_$id","pending_message_text_$id","pending_message_provider_$id","pending_message_model_$id","pending_message_finance_context_$id","pending_message_finance_label_$id","pending_message_finance_preset_$id","pending_message_finance_start_$id","pending_message_finance_end_$id");if(value==null)keys.forEach(edit::remove)else{edit.putString(keys[0],value.clientId).putString(keys[1],value.text).putString(keys[2],value.provider.name);if(value.model==null)edit.remove(keys[3])else edit.putString(keys[3],value.model);val context=value.financeContext;val range=value.financeRange;if(context==null||range==null){keys.drop(4).forEach(edit::remove)}else{edit.putString(keys[4],context.json).putString(keys[5],context.rangeLabel).putString(keys[6],range.preset.name);range.startInclusive?.let{edit.putString(keys[7],it.toString())}?:edit.remove(keys[7]);range.endInclusive?.let{edit.putString(keys[8],it.toString())}?:edit.remove(keys[8])}};edit}.commit()}
    override fun provider(id:String)=runCatching{HarnessProvider.valueOf(prefs.getString("provider_$id",null)?:"AGENT")}.getOrDefault(HarnessProvider.AGENT)
    override fun saveProvider(id:String,value:HarnessProvider){prefs.edit().putString("provider_$id",value.name).apply()}
    override fun resetCodexProviders(){val edit=prefs.edit();prefs.all.keys.filter{it.startsWith("provider_")}.forEach(edit::remove);edit.commit()}
    override fun model(id:String)=prefs.getString("model_$id",null)
    override fun saveModel(id:String,value:String?){prefs.edit().let{if(value==null)it.remove("model_$id")else it.putString("model_$id",value)}.apply()}
    private fun encrypt(value:String):String{val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());return Base64.encodeToString(cipher.iv+cipher.doFinal(value.toByteArray()),Base64.NO_WRAP)}
    private fun decryptSlot(slot:String):String?=prefs.getString(slot,null)?.let { encoded -> runCatching { val packed=Base64.decode(encoded,Base64.NO_WRAP);require(packed.size>12);val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,packed.copyOfRange(0,12)));String(cipher.doFinal(packed.copyOfRange(12,packed.size))) }.getOrElse{prefs.edit().remove(slot).commit();null} }
    private fun key():SecretKey { val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};(store.getKey(ALIAS,null) as? SecretKey)?.let{return it};return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())}.generateKey() }
    companion object { private const val ALIAS="fff_harness_bearer_v1" }
}
