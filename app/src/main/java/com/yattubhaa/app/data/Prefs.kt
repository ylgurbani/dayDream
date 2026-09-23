package com.yattubhaa.app.data

import android.content.Context
import android.content.SharedPreferences
import com.yattubhaa.app.BuildConfig
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Small local store for settings. Nothing here is a secret worth defending against someone
 * holding the unlocked phone: the helper PIN only keeps Helper mode out of the way of the
 * person who needs help. Pairing secrets live in [com.yattubhaa.app.pairing.PairingStore].
 */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences("yattu_bhaa", Context.MODE_PRIVATE)
    }

    var onboardingDone: Boolean
        get() = sp.getBoolean("onboarding_done", false)
        set(v) = sp.edit().putBoolean("onboarding_done", v).apply()

    /** Whether the always-available HOME button overlay is switched on. Off by default. */
    var panicButtonOn: Boolean
        get() = sp.getBoolean("panic_button_on", false)
        set(v) = sp.edit().putBoolean("panic_button_on", v).apply()

    /** The helper's own name, shown on the phone of the person they help. */
    var helperName: String
        get() = sp.getString("helper_name", "") ?: ""
        set(v) = sp.edit().putString("helper_name", v).apply()

    /** The relay the helper's pairing links point at. */
    var relayUrl: String
        get() = sp.getString("relay_url", null) ?: BuildConfig.DEFAULT_RELAY_URL
        set(v) = sp.edit().putString("relay_url", v).apply()

    val hasHelperPin: Boolean get() = sp.contains("pin_hash")

    fun setHelperPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp.edit()
            .putString("pin_salt", salt.toHex())
            .putString("pin_hash", hash(salt, pin).toHex())
            .apply()
    }

    fun checkHelperPin(pin: String): Boolean {
        val salt = sp.getString("pin_salt", null)?.fromHex() ?: return false
        val expected = sp.getString("pin_hash", null) ?: return false
        return hash(salt, pin).toHex() == expected
    }

    private fun hash(salt: ByteArray, pin: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(salt + pin.toByteArray())

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
