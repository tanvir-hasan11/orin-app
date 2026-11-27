package com.orin.app.security

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Hardware-backed Biometric / Device Credential gate for sensitive Orin actions.
 */
object BiometricGate {

    private fun allowedAuthenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }

    fun canAuthenticate(context: Context): Boolean {
        val status = BiometricManager.from(context).canAuthenticate(allowedAuthenticators())
        return status == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun availabilityLabel(context: Context): String =
        when (BiometricManager.from(context).canAuthenticate(allowedAuthenticators())) {
            BiometricManager.BIOMETRIC_SUCCESS -> "Ready (fingerprint, face, or device PIN)"
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "No fingerprint or screen lock enrolled on this device"
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "No biometric hardware; set a screen lock PIN/pattern"
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Biometric sensor temporarily unavailable"
            else -> "Biometric authentication unavailable"
        }

    /**
     * Pure classification of whether an approval prompt should require hardware auth
     * when the user has enabled Biometric Gate in Settings.
     */
    fun requiresHardwareAuth(title: String, detail: String): Boolean {
        val hay = "$title $detail".lowercase()
        val keywords = listOf(
            "sms",
            "shell",
            "screen",
            "tap",
            "control",
            "reply",
            "git push",
            "git reset",
            "force",
            "payment",
            "transfer",
            "meta_ads",
            "pause campaign",
            "budget",
            "delete",
        )
        return keywords.any { hay.contains(it) }
    }

    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
    ): Boolean = withContext(Dispatchers.Main) {
        if (!canAuthenticate(activity)) return@withContext false
        suspendCancellableCoroutine { cont ->
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (cont.isActive) cont.resume(false)
                    }

                    override fun onAuthenticationFailed() {
                        // BiometricPrompt handles retries internally until error/cancel.
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title.take(64))
                .setSubtitle(subtitle.take(120))
                .setAllowedAuthenticators(allowedAuthenticators())
                .build()
            prompt.authenticate(info)
            cont.invokeOnCancellation {
                runCatching { prompt.cancelAuthentication() }
            }
        }
    }
}
