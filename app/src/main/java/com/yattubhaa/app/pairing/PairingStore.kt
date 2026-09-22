package com.yattubhaa.app.pairing

import android.content.Context
import android.content.SharedPreferences
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.yattubhaa.app.net.Crypto
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Where paired phones are remembered. The list is encrypted with a key that lives in the
 * Android Keystore, so the pairing secrets are never sitting readable in the app's files.
 *
 * On the phone of the person being helped there is at most one entry (the family member who
 * helps them). On the helper's phone there is one entry per person they look after.
 */
object PairingStore {
    @Serializable
    data class Record(val id: String, val name: String, val relay: String, val secret: String) {
        val secretBytes: ByteArray get() = Crypto.fromB64url(secret)
    }

    private const val KEY = "pairings"
    private val aad = "yattu-pairings-v1".toByteArray()
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(Record.serializer())

    private lateinit var sp: SharedPreferences
    private lateinit var aead: Aead

    fun init(context: Context) {
        val app = context.applicationContext
        AeadConfig.register()
        aead = AndroidKeysetManager.Builder()
            .withSharedPref(app, "yattu_keyset", "yattu_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://yattu_master_key")
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
        sp = app.getSharedPreferences("yattu_pairings", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun all(): List<Record> {
        val stored = sp.getString(KEY, null) ?: return emptyList()
        return try {
            val plain = aead.decrypt(Crypto.fromB64url(stored), aad)
            json.decodeFromString(listSerializer, String(plain))
        } catch (e: Exception) {
            emptyList() // unreadable (for example after a Keystore reset): treat as not paired
        }
    }

    fun find(id: String): Record? = all().firstOrNull { it.id == id }

    @Synchronized
    fun add(record: Record) = save(all().filterNot { it.id == record.id } + record)

    @Synchronized
    fun remove(id: String) = save(all().filterNot { it.id == id })

    @Synchronized
    fun clear() = save(emptyList())

    private fun save(list: List<Record>) {
        val plain = json.encodeToString(listSerializer, list).toByteArray()
        sp.edit().putString(KEY, Crypto.b64url(aead.encrypt(plain, aad))).apply()
    }

    fun newRecord(name: String, relay: String, secret: ByteArray) = Record(
        id = Crypto.b64url(Crypto.randomBytes(9)),
        name = name,
        relay = relay,
        secret = Crypto.b64url(secret),
    )
}
