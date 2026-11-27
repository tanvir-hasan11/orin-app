package com.orin.app.tools

import android.accessibilityservice.AccessibilityService
import android.content.Context
import com.orin.app.agent.OrinTool
import com.orin.app.agent.ToolApprovalRequest
import com.orin.app.control.ApprovalCard
import com.orin.app.control.ControlRules
import com.orin.app.control.OrinControlService
import com.orin.app.data.AuditLogEntity
import com.orin.app.data.OrinDao
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * Opt-in screen control. Every action that changes another app needs a finger
 * tap on an approval card; text that looks like a secret is refused outright.
 */
class ScreenControlTool(
    private val context: Context,
    private val dao: OrinDao,
) : OrinTool {
    override val name = "screen_control"
    override val description =
        "Read the current screen ('ui_read'), tap a numbered element ('ui_click'), type into a field ('ui_type'), scroll ('ui_scroll'), press back/home ('ui_key'), or hand control back to the user ('hand_to_user')."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") { put("type", "string"); put("description", "ui_read | ui_click | ui_type | ui_scroll | ui_key | hand_to_user") }
            putJsonObject("index") { put("type", "string"); put("description", "Element number from the last ui_read") }
            putJsonObject("text") { put("type", "string"); put("description", "Text for ui_type") }
            putJsonObject("direction") { put("type", "string"); put("description", "down or up for ui_scroll") }
            putJsonObject("key") { put("type", "string"); put("description", "back, home or recents for ui_key") }
        }
    }

    private val card = ApprovalCard(context)

    override fun approval(args: JsonObject): ToolApprovalRequest? = when (action(args)) {
        "ui_click" -> ToolApprovalRequest(
            name,
            "Tap element ${args["index"]?.jsonPrimitive?.contentOrNull ?: "?"}?",
            "Only your finger can allow this.",
        )
        "ui_type" -> ToolApprovalRequest(
            name,
            "Type this text?",
            args["text"]?.jsonPrimitive?.contentOrNull.orEmpty().take(400),
        )
        "ui_key" -> ToolApprovalRequest(name, "Press ${args["key"]?.jsonPrimitive?.contentOrNull ?: "back"}?", "This changes the current screen.")
        else -> null
    }

    override suspend fun execute(args: JsonObject): String {
        val service = OrinControlService.instance
            ?: return "Screen control is off. Turn it on in Settings → Accessibility, then try again."
        val currentPackage = service.currentPackage().orEmpty()
        ControlRules.rejectionReason(currentPackage)?.let { return it }

        val result = when (action(args)) {
            "ui_read" -> {
                val nodes = service.snapshot()
                if (nodes.isEmpty()) {
                    "Nothing interactive found on this screen."
                } else {
                    nodes.joinToString("\n") { n ->
                        "${n.index}. ${n.display.take(120)}${if (n.editable) " [input]" else ""}"
                    }
                }
            }
            "ui_click" -> {
                val index = index(args) ?: return "Provide an 'index' from ui_read."
                val node = service.snapshot().getOrNull(index)
                    ?: return "No element numbered $index."
                val risk = ControlRules.riskOf(node.display)
                if (risk == ControlRules.Risk.MONEY || risk == ControlRules.Risk.SECRET) {
                    return ControlRules.explanation(risk)
                }
                if (!card.confirm("Tap this element?", "${node.display.take(200)}\n\nOnly your finger can allow this.")) {
                    return "You denied the tap. Nothing was pressed."
                }
                service.click(index)
            }
            "ui_type" -> {
                val index = index(args) ?: return "Provide an 'index' from ui_read."
                val text = args["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (!ControlRules.canType(text)) {
                    return ControlRules.explanation(ControlRules.riskOf(text))
                }
                if (!card.confirm("Type this text?", "$text\n\nInto element $index")) {
                    return "You denied typing. Nothing was entered."
                }
                service.type(index, text)
            }
            "ui_scroll" -> {
                if (args["direction"]?.jsonPrimitive?.contentOrNull?.lowercase() == "up") {
                    service.scrollBackward()
                } else {
                    service.scrollForward()
                }
            }
            "ui_key" -> when (args["key"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                "home" -> service.globalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                "recents" -> service.globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                else -> service.globalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
            "hand_to_user" -> "Your turn — I've stopped controlling the screen."
            else -> "Unknown action: ${action(args)}"
        }

        dao.insertAuditLog(
            AuditLogEntity(
                UUID.randomUUID().toString(),
                name,
                action(args),
                "$currentPackage · ${result.take(160)}",
                true,
            ),
        )
        return result
    }

    private fun action(args: JsonObject): String =
        args["action"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()

    private fun index(args: JsonObject): Int? =
        args["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
}
