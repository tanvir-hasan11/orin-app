package com.orin.app.security

/**
 * A conservative blocklist for the workspace shell. This is defence in depth:
 * every shell command also requires explicit user approval, and the process is
 * started inside the app's private workspace.
 */
object CommandPolicy {
    private val blocked = listOf(
        Regex("""\brm\s+(-[a-zA-Z]*[rf][a-zA-Z]*\s+)+/(\*|\s|$)"""),
        Regex("""\bmkfs(\.\w+)?\b"""),
        Regex("""\bdd\b[^\n]*\bof=/dev/"""),
        Regex(""":\(\)\s*\{"""),
        Regex("""\b(su|sudo)\b"""),
        Regex("""\b(reboot|shutdown|halt|poweroff)\b"""),
        Regex("""\bpm\s+(uninstall|clear|disable)\b"""),
        Regex("""\bam\s+(broadcast|startservice|force-stop|instrument)\b"""),
        Regex("""\bcontent\s+(delete|update|insert)\b"""),
        Regex("""\bsettings\s+(put|delete)\b"""),
        Regex("""\b(curl|wget|nc|ncat|netcat|ssh|scp|sftp|telnet)\b"""),
        Regex("""/data/(data|system|adb|misc)"""),
        Regex("""/sdcard/Android/(data|obb)"""),
        Regex("""\b(chmod|chown)\s+[^\n]*\s+/(\s|$)"""),
        Regex("""\b(insmod|rmmod|mount|umount)\b"""),
    )

    fun rejectionReason(command: String): String? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return "Empty command."
        if (trimmed.length > 2000) return "Command is too long (max 2000 characters)."
        if (blocked.any { it.containsMatchIn(trimmed) }) {
            return "Command blocked by Orin's safety policy."
        }
        return null
    }
}
