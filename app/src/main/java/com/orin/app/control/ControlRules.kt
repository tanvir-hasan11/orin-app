package com.orin.app.control

/**
 * Code-level safety boundary for screen control. These rules are enforced
 * before any gesture or text is sent to another app, independent of what the
 * model says.
 */
object ControlRules {

    enum class Risk { SAFE, OUTGOING, DESTRUCTIVE, MONEY, SECRET }

    private val blockedPackages = listOf(
        "com.orin.app",
        "com.android.settings",
        "com.android.vending",
        "com.google.android.apps.nbu.paisa.user",
        "com.phonepe.app",
        "com.paytm.app",
        "net.one97.paytm",
        "in.org.npci.upiapp",
        "in.amazon.mShop.android.shopping.pay",
        "com.mobikwik_new",
        "com.sbi.lotusintouch",
        "com.sbi.SBIFreedomPlus",
        "com.csam.icici.bank.imobile",
        "com.snapwork.hdfc",
        "com.axis.mobile",
        "com.msf.kbank.mobile",
        "com.bankofbaroda.mconnect",
        "com.x8bit.bitwarden",
        "com.lastpass.lpandroid",
        "com.onepassword.android",
        "com.keepassdroid",
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
    )

    private val blockedNameHints = listOf(
        "bank", "upi", "wallet", "paytm", "phonepe", "gpay", "google pay", "bkash", "nagad", "rocket",
        "lastpass", "1password", "keepass", "bitwarden", "password manager", "authenticator", "vault",
    )

    private val moneyWords = Regex(
        """\b(pay|payment|checkout|place order|order now|transfer|donate|subscribe|upi|bank|account|card|cvv|withdraw|send money|amount|buy|purchase)\b|भुगतान|ट्रांसफर|पैसे|खरीद""",
        RegexOption.IGNORE_CASE,
    )
    private val destructiveWords = Regex(
        """\b(delete|remove|trash|erase|discard|uninstall|format|reset|clear all|empty|sign out|log ?out|block|report|wipe|factory reset|disable)\b|हटा|डिलीट|मिटा|अनइंस्टॉल""",
        RegexOption.IGNORE_CASE,
    )
    private val outgoingWords = Regex(
        """\b(send|call|dial|submit|post|publish|share|book|order|reply|forward|upload|confirm|accept|agree|allow|invite|schedule|install|bhejo|bhejein)\b|भेज|पोस्ट|कॉल|बुक|ऑर्डर""",
        RegexOption.IGNORE_CASE,
    )
    private val secretPatterns = listOf(
        Regex("""\b\d{4,6}\b"""),
        Regex("""\b(?:\d[ -]?){13,19}\b"""),
        Regex("""\b(?:password|pin|otp|cvv|passcode)\b\s*[:=]?\s*\S+""", RegexOption.IGNORE_CASE),
    )

    fun isBlocked(packageName: String?, label: String? = null): Boolean {
        val pkg = packageName.orEmpty().lowercase()
        val text = "$pkg ${label.orEmpty().lowercase()}"
        return blockedPackages.any { pkg == it || pkg.startsWith("$it.") } ||
            blockedNameHints.any { text.contains(it) }
    }

    fun rejectionReason(packageName: String?, label: String? = null): String? =
        if (isBlocked(packageName, label)) {
            "I can't control ${label ?: packageName ?: "this app"}. Banking, payment, password-manager, Play Store and Settings apps are always off-limits."
        } else {
            null
        }

    fun riskOf(text: String, viewId: String = ""): Risk {
        val combined = if (viewId.isBlank()) text else "$text ${viewId.substringAfterLast('/').replace('_', ' ')}"
        return when {
            secretPatterns.any { it.containsMatchIn(combined) } -> Risk.SECRET
            moneyWords.containsMatchIn(combined) -> Risk.MONEY
            destructiveWords.containsMatchIn(combined) -> Risk.DESTRUCTIVE
            outgoingWords.containsMatchIn(combined) -> Risk.OUTGOING
            else -> Risk.SAFE
        }
    }

    fun canType(text: String): Boolean = riskOf(text) != Risk.SECRET

    fun explanation(risk: Risk): String = when (risk) {
        Risk.MONEY -> "This looks like a payment or money action. I won't do it — please complete payment yourself."
        Risk.SECRET -> "This looks like a password, PIN, OTP or card number. I won't type it."
        Risk.DESTRUCTIVE -> "This may delete or disable something. Your approval is needed."
        Risk.OUTGOING -> "This sends or submits something. Your approval is needed."
        Risk.SAFE -> "Your approval is needed before I touch this app."
    }
}
