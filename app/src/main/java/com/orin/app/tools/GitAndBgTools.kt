package com.orin.app.tools

import android.content.Context
import com.orin.app.agent.OrinTool
import com.orin.app.agent.ToolApprovalRequest
import com.orin.app.data.AuditLogEntity
import com.orin.app.data.KeyStoreManager
import com.orin.app.data.OrinDao
import com.orin.app.security.CommandPolicy
import com.orin.app.shell.BgProcessManager
import com.orin.app.shell.SecretRedactor
import com.orin.app.shell.ShizukuBridge
import com.orin.app.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

class GitTool(
    private val context: Context,
    private val dao: OrinDao,
    private val keys: KeyStoreManager,
) : OrinTool {
    override val name = "git"
    override val description = "Run git commands in the active workspace: status, diff, log, branch, checkout, commit, pull, push."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") { put("type", "string"); put("description", "status, diff, log, branch, checkout, commit, pull, push") }
            putJsonObject("message") { put("type", "string"); put("description", "Commit message for 'commit'") }
            putJsonObject("ref") { put("type", "string"); put("description", "Branch or remote ref for checkout/branch") }
            putJsonObject("path") { put("type", "string"); put("description", "Optional path filter for diff/log") }
            putJsonObject("limit") { put("type", "integer"); put("description", "Log entry count (default 15)") }
        }
    }

    private fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"
    private val gitBase = "git -c 'safe.directory=*' -c gc.auto=0 -c maintenance.auto=false"

    override fun approval(args: JsonObject): ToolApprovalRequest? {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "status"
        if (action in setOf("status", "diff", "log", "branch")) return null
        val ref = args["ref"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val msg = args["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val label = when (action) {
            "push" -> "git push ${ref.ifBlank { "origin" }}"
            "pull" -> "git pull ${ref.ifBlank { "" }}"
            "commit" -> "git commit -m \"$msg\""
            else -> "git $action $ref"
        }
        return ToolApprovalRequest(
            toolName = name,
            title = "Run git $action?",
            detail = label.trim(),
        )
    }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "status"
        val ref = args["ref"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val path = args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val msg = args["message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 15).coerceIn(1, 50)

        val cmd = when (action) {
            "status" -> "$gitBase status -sb"
            "diff" -> "$gitBase diff --stat" + if (path.isNotEmpty()) " -- ${path.shellQuote()}" else ""
            "log" -> "$gitBase log -n $limit --date=short --pretty=format:'%h %ad %s'" +
                if (path.isNotEmpty()) " -- ${path.shellQuote()}" else ""
            "branch" -> if (ref.isEmpty()) "$gitBase branch -vv" else "$gitBase branch ${ref.shellQuote()}"
            "checkout" -> {
                if (ref.isEmpty()) return@withContext "Provide 'ref' for checkout."
                "$gitBase checkout ${ref.shellQuote()}"
            }
            "commit" -> {
                if (msg.isEmpty()) return@withContext "Provide 'message' for commit."
                "$gitBase add -A && $gitBase commit -m ${msg.shellQuote()}"
            }
            "pull" -> "$gitBase pull" + if (ref.isNotEmpty()) " origin ${ref.shellQuote()}" else ""
            "push" -> "$gitBase push" + if (ref.isNotEmpty()) " origin ${ref.shellQuote()}" else ""
            else -> return@withContext "Unknown git action '$action'."
        }

        val root = WorkspaceManager.activeRoot(context, keys)
        val res = ShizukuBridge.exec(context, cmd, root, keys, timeoutSeconds = 30)
        dao.insertAuditLog(
            AuditLogEntity(UUID.randomUUID().toString(), "git", action, "${res.tier}: exit ${res.exitCode}", res.exitCode == 0),
        )
        "Exit ${res.exitCode} (${res.tier}):\n${res.output.ifBlank { "(no output)" }}"
    }
}

class ShellBackgroundTool(
    private val context: Context,
    private val dao: OrinDao,
    private val keys: KeyStoreManager,
) : OrinTool {
    override val name = "shell_background"
    override val description = "Start a long-running background shell process (e.g. dev server, watcher). Use bg_list / bg_kill to manage."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("command") { put("type", "string"); put("description", "Shell command to start in background") }
        }
    }

    override fun approval(args: JsonObject): ToolApprovalRequest? {
        val command = args["command"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return ToolApprovalRequest(
            toolName = name,
            title = "Start background shell process?",
            detail = command.ifBlank { "(empty command)" },
        )
    }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        if (!keys.shellEnabled()) return@withContext "Shell is disabled. Enable it in Settings."
        val command = args["command"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        CommandPolicy.rejectionReason(command)?.let { return@withContext it }

        val root = WorkspaceManager.activeRoot(context, keys)
        val info = runCatching { BgProcessManager.start(command, root) }
            .getOrElse { return@withContext "Failed to start background job: ${it.message}" }
        dao.insertAuditLog(
            AuditLogEntity(UUID.randomUUID().toString(), "shell_background", "start", "${info.id}: $command", true),
        )
        "Started background job [${info.id}]: ${info.command}\nLog: ${info.logFile.name}"
    }
}

class BgListTool(private val keys: KeyStoreManager) : OrinTool {
    override val name = "bg_list"
    override val description = "List active or finished background shell jobs and optionally tail a job log ('id')."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("id") { put("type", "string"); put("description", "Optional job ID to read its log tail") }
        }
    }

    override suspend fun execute(args: JsonObject): String {
        val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (id.isNotEmpty()) {
            return SecretRedactor.redact(BgProcessManager.tailLog(id), keys)
        }
        val jobs = BgProcessManager.list()
        if (jobs.isEmpty()) return "No background jobs in this session."
        return jobs.joinToString("\n") { job ->
            val state = if (job.alive) "RUNNING" else "EXITED"
            "• [${job.id}] $state · ${job.command.take(70)}"
        }
    }
}

class BgKillTool : OrinTool {
    override val name = "bg_kill"
    override val description = "Stop a running background shell job by its ID."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("id") { put("type", "string"); put("description", "Job ID from bg_list") }
        }
    }

    override fun approval(args: JsonObject): ToolApprovalRequest? {
        val id = args["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return ToolApprovalRequest(
            toolName = name,
            title = "Kill background job?",
            detail = "Stop job ID: $id",
        )
    }

    override suspend fun execute(args: JsonObject): String {
        val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (id.isEmpty()) return "Provide 'id'."
        return if (BgProcessManager.kill(id)) "Stopped background job $id."
        else "No active background job found with id '$id'."
    }
}
