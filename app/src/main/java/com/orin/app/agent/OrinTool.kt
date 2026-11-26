package com.orin.app.agent

import kotlinx.serialization.json.JsonObject

/** A human-readable confirmation request shown before a side-effecting tool runs. */
data class ToolApprovalRequest(
    val toolName: String,
    val title: String,
    val detail: String,
)

/**
 * A single capability the model may call.
 *
 * Tools that change the outside world (send an SMS, write a file, run a shell
 * command, launch an app, create a calendar event) must return a
 * [ToolApprovalRequest] from [approval]. The runtime will block on an explicit
 * user decision before calling [execute].
 */
interface OrinTool {
    val name: String
    val description: String
    val parameters: JsonObject

    /** Null means the call is read-only / safe enough to run without a prompt. */
    fun approval(args: JsonObject): ToolApprovalRequest? = null

    suspend fun execute(args: JsonObject): String
}
