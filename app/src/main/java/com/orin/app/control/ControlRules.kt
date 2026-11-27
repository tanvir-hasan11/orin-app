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
        "in.org.npci.upiapp",
        "com.lastpass.lpandroid",
        "com.onepassword.android",
        "com.keepassdroid",
    )

    private val blockedNameHints = listOf(
        "bank", "upi", "paytm", "phonepe", "gpay", "google pay", "lastpass",
        "1password", "keepass", "bitwarden", "password manager", "hindi password",
    )

    private val moneyWords = Regex(
        """\b(pay|payment|transfer|upi|bank|account|card|cvv|withdraw|send money|amount|भुगतान|ट्रांसफर|पैसे)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val destructiveWords = Regex(
        """\b(delete|remove|uninstall|clear|wipe|factory reset|disable|हटा|मिटा|अनइंस्टॉल)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val outgoingWords = Regex(
        """\b(send|call|submit|post|share|book|order|reply|भेज|कॉल|बुक|ऑर्डर)\b""",
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

    fun riskOf(text: String): Risk = when {
        secretPatterns.any { it.containsMatchIn(text) } -> Risk.SECRET
        moneyWords.containsMatchIn(text) -> Risk.MONEY
        destructiveWords.containsMatchIn(text) -> Risk.DESTRUCTIVE
        outgoingWords.containsMatchIn(text) -> Risk.OUTGOING
        else -> Risk.SAFE
    }

    fun canType(text: String): Boolean = riskOf(text) != Risk.SECRET

    fun explanation(risk: Risk): String = when (risk) {
        Risk.MONEY -> "This looks like a payment or money action. I won't do it."
        Risk.SECRET -> "This looks like a password, PIN, OTP or card number. I won't type it."
        Risk.DESTRUCTIVE -> "This may delete or disable something. Your approval is needed."
        Risk.OUTGOING -> "This sends or submits something. Your approval is needed."
        Risk.SAFE -> "Your approval is needed before I touch this app."
    }
}
