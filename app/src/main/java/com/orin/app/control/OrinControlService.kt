package com.orin.app.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE

/** A numbered snapshot of the interactive elements on the current screen. */
data class UiNode(
    val index: Int,
    val text: String,
    val label: String,
    val className: String,
    val resourceId: String,
    val clickable: Boolean,
    val editable: Boolean,
) {
    val display: String
        get() = text.ifBlank { label }.ifBlank { resourceId.substringAfterLast('/') }
            .ifBlank { className.substringAfterLast('.') }
}

/** Opt-in accessibility backend for the "use any app" tool. */
class OrinControlService : AccessibilityService() {

    private var lastNodes: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = refresh()

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun isEnabled(): Boolean = try {
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        enabled.contains("$packageName/${javaClass.name}")
    } catch (_: Exception) {
        false
    }

    /** Refreshes and returns a numbered list of interactive elements. */
    fun snapshot(limit: Int = 60): List<UiNode> {
        refresh(limit)
        return lastNodes.mapIndexed { index, node ->
            UiNode(
                index = index,
                text = node.text?.toString().orEmpty().trim(),
                label = node.contentDescription?.toString().orEmpty().trim(),
                className = node.className?.toString().orEmpty(),
                resourceId = node.viewIdResourceName.orEmpty(),
                clickable = node.isClickable,
                editable = node.isEditable,
            )
        }
    }

    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    fun click(index: Int): String {
        val node = nodeAt(index) ?: return "No element numbered $index. Call ui_read first."
        return if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            "Tapped element $index."
        } else {
            "Element $index could not be tapped."
        }
    }

    fun type(index: Int, text: String): String {
        val node = nodeAt(index) ?: return "No element numbered $index. Call ui_read first."
        if (!node.isEditable) return "Element $index is not a text field."
        val arguments = Bundle().apply {
            putCharSequence(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            "Entered text in element $index."
        } else {
            "Could not enter text in element $index."
        }
    }

    fun scrollForward(): String = scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

    fun scrollBackward(): String = scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

    private fun scroll(action: Int): String {
        val target = lastNodes.firstOrNull { it.isScrollable } ?: rootInActiveWindow
        return if (target?.performAction(action) == true) "Scrolled." else "Nothing scrollable here."
    }

    fun globalAction(action: Int): String {
        return if (performGlobalAction(action)) "Done." else "That action isn't available."
    }

    private fun nodeAt(index: Int): AccessibilityNodeInfo? =
        if (index in lastNodes.indices) lastNodes[index] else null

    private fun refresh(limit: Int = 60) {
        lastNodes.forEach { runCatching { it.recycle() } }
        val roots = buildList {
            rootInActiveWindow?.let { add(it) }
            windows.mapNotNull { it.root }.forEach { add(it) }
        }.distinctBy { it.hashCode() }

        val collected = mutableListOf<AccessibilityNodeInfo>()
        for (root in roots) {
            collect(root, collected, limit, depth = 0)
            if (collected.size >= limit) break
        }
        lastNodes = collected
    }

    private fun collect(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>,
        limit: Int,
        depth: Int,
    ) {
        if (node == null || depth > 18 || out.size >= limit) return
        val interesting = node.isClickable || node.isEditable || node.isScrollable ||
            !node.text?.toString().isNullOrBlank() ||
            !node.contentDescription?.toString().isNullOrBlank()
        if (interesting && (node.isClickable || node.isEditable || node.isScrollable)) {
            out += node
        }
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), out, limit, depth + 1)
        }
    }

    companion object {
        @Volatile
        var instance: OrinControlService? = null
            private set

        fun serviceClass(): Class<*> = OrinControlService::class.java

        fun isEnabled(context: Context): Boolean = try {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            enabled.contains("${context.packageName}/${OrinControlService::class.java.name}")
        } catch (_: Exception) {
            false
        }
    }
}
