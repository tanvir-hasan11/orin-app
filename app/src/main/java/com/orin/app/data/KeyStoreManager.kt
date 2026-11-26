package com.orin.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Hardware-backed encrypted credential store for Orin API keys and tokens.
 */
class KeyStoreManager(context: Context) {
    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "orin_secure_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getSecret(key: String): String? =
        prefs.getString(key, null)?.takeIf { it.isNotBlank() }

    fun putSecret(key: String, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, value.trim()).apply()
        }
    }

    fun activeProviderId(): String = getSecret("active_provider") ?: "gemini"
    fun setActiveProviderId(id: String) = putSecret("active_provider", id)

    fun defaultModel(provider: String): String = when (provider) {
        "anthropic" -> "claude-sonnet-4-20250514"
        "openai" -> "gpt-4o"
        "deepseek" -> "deepseek-chat"
        else -> "gemini-2.5-flash"
    }

    fun activeModel(): String = getSecret("active_model") ?: defaultModel(activeProviderId())

    fun setActiveModel(model: String) = putSecret("active_model", model)

    fun hasApiKey(): Boolean = getSecret("api_key_${activeProviderId()}") != null

    fun shellEnabled(): Boolean = getSecret("shell_enabled") != "false"

    fun setShellEnabled(enabled: Boolean) = putSecret("shell_enabled", enabled.toString())

    fun biometricGateEnabled(): Boolean = getSecret("biometric_gate") == "true"
    fun setBiometricGateEnabled(enabled: Boolean) = putSecret("biometric_gate", enabled.toString())
}
