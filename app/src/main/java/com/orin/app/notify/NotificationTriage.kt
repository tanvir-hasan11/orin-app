package com.orin.app.notify

/**
 * Turns raw notifications into a short, privacy-safe summary.
 *
 * One-time codes are masked and links are flagged instead of being rendered
 * clickable, so a message can never trick the assistant into acting on it.
 */
object NotificationTriage {

    data class Item(
        val sender: String,
        val text: String,
        val timestampMs: Long,
        val isCode: Boolean,
        val hasLink: Boolean,
        val group: Boolean = false,
        val replyId: Int? = null,
    )

    private val codePattern = Regex("""\b(\d{4,6})\b""")
    private val linkPattern = Regex("""(https?://\S+|www\.\S+)""", RegexOption.IGNORE_CASE)

    fun codeLike(text: String): Boolean = codePattern.containsMatchIn(text)

    fun linkLike(text: String): Boolean = linkPattern.containsMatchIn(text)

    fun summarise(items: List<Item>, limit: Int = 12): String {
        if (items.isEmpty()) return "No recent notifications."
        val people = LinkedHashSet<String>()
        val lines = mutableListOf<String>()
        var codes = 0
        var links = 0

        for (item in items.take(limit)) {
            people.add(item.sender)
            val safeText = codePattern.replace(item.text) { match ->
                codes++
                "〈code hidden〉"
            }
            val checkedLinks = linkPattern.findAll(safeText).count()
            links += checkedLinks
            val cleaned = linkPattern.replace(safeText, "[link hidden]")
                .replace('\n', ' ')
                .take(140)
            val replyTag = item.replyId?.let { " [reply $it]" }.orEmpty()
            lines.add("• ${item.sender}: $cleaned$replyTag")
        }

        return buildString {
            append("You have ${items.size} recent notification${if (items.size == 1) "" else "s"} from ")
            append(people.joinToString(", "))
            append(".\n")
            append(lines.joinToString("\n"))
            if (codes > 0) append("\n\n$codes one-time code${if (codes == 1) "" else "s"} hidden.")
            if (links > 0) append("\n$links link${if (links == 1) "" else "s"} hidden — don't tap unknown links.")
        }.trim()
    }
}
