package ru.rockxi.fff.data.harness

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class PendingHarnessMessage(val clientId:String,val text:String)
internal interface HarnessTokenStore { fun deviceId():String; fun load():String?; fun save(token:String); fun clear(); fun pendingRevoke():String?; fun moveToPendingRevoke(token:String); fun clearPendingRevoke(); fun draft(id:String):String=""; fun saveDraft(id:String,value:String){}; fun pendingMessage(id:String):PendingHarnessMessage?=null; fun savePendingMessage(id:String,value:PendingHarnessMessage?){} }

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
    override fun pendingMessage(id:String):PendingHarnessMessage?{val client=prefs.getString("pending_message_id_$id",null)?:return null;val text=prefs.getString("pending_message_text_$id",null)?:return null;return PendingHarnessMessage(client,text)}
    override fun savePendingMessage(id:String,value:PendingHarnessMessage?){prefs.edit().let{if(value==null)it.remove("pending_message_id_$id").remove("pending_message_text_$id") else it.putString("pending_message_id_$id",value.clientId).putString("pending_message_text_$id",value.text)}.commit()}
    private fun encrypt(value:String):String{val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());return Base64.encodeToString(cipher.iv+cipher.doFinal(value.toByteArray()),Base64.NO_WRAP)}
    private fun decryptSlot(slot:String):String?=prefs.getString(slot,null)?.let { encoded -> runCatching { val packed=Base64.decode(encoded,Base64.NO_WRAP);require(packed.size>12);val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,packed.copyOfRange(0,12)));String(cipher.doFinal(packed.copyOfRange(12,packed.size))) }.getOrElse{prefs.edit().remove(slot).commit();null} }
    private fun key():SecretKey { val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};(store.getKey(ALIAS,null) as? SecretKey)?.let{return it};return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())}.generateKey() }
    companion object { private const val ALIAS="fff_harness_bearer_v1" }
}
