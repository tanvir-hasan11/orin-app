package com.orin.app.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** A numbered snapshot of visible text and interactive elements on the current screen. */
data class UiNode(
    val index: Int,
    val role: String,
    val text: String,
    val label: String,
    val className: String,
    val resourceId: String,
    val clickable: Boolean,
    val editable: Boolean,
    val value: String = "",
) {
    val display: String
        get() = label.ifBlank { text }.ifBlank { resourceId.substringAfterLast('/') }
            .ifBlank { className.substringAfterLast('.') }
}

/** Opt-in accessibility backend for screen reading, clicking, typing, and tap-by-sight. */
class OrinControlService : AccessibilityService() {

    @Volatile
    private var lastNodes: Map<Int, AccessibilityNodeInfo> = emptyMap()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        lastNodes = emptyMap()
        super.onDestroy()
    }

    fun isEnabled(): Boolean = isEnabled(this)

    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    /**
     * Reads the active window into a numbered list of buttons, fields, switches, and visible text.
     * Blocked apps return an empty list.
     */
    fun snapshot(limit: Int = MAX_ELEMENTS): List<UiNode> {
        val root = rootInActiveWindow ?: return emptyList()
        val pkg = root.packageName?.toString().orEmpty()
        if (ControlRules.isBlocked(pkg)) {
            lastNodes = emptyMap()
            return emptyList()
        }
        val elements = mutableListOf<UiNode>()
        val nodes = mutableMapOf<Int, AccessibilityNodeInfo>()

        fun visit(node: AccessibilityNodeInfo, insideTappable: Boolean) {
            if (!node.isVisibleToUser || elements.size >= limit) return
            val tappable = node.isClickable || node.isEditable || node.isCheckable
            val own = ownLabel(node)
            val include = when {
                tappable -> true
                own.isNotEmpty() && !insideTappable -> true
                else -> false
            }
            if (include && elements.size < limit) {
                val label = own.ifEmpty { childText(node) }
                    .ifEmpty { node.viewIdResourceName?.substringAfterLast('/').orEmpty() }
                if (label.isNotEmpty() || node.isEditable) {
                    val idx = elements.size + 1
                    elements += UiNode(
                        index = idx,
                        role = role(node),
                        text = node.text?.toString().orEmpty().trim(),
                        label = label,
                        className = node.className?.toString().orEmpty(),
                        resourceId = node.viewIdResourceName.orEmpty(),
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        value = if (node.isEditable) node.text?.toString().orEmpty() else "",
                    )
                    nodes[idx] = node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { visit(it, insideTappable || tappable) }
            }
        }

        visit(root, false)
        lastNodes = nodes
        return elements
    }

    fun node(index: Int): AccessibilityNodeInfo? = lastNodes[index]?.takeIf { it.refresh() }

    fun labelOf(node: AccessibilityNodeInfo): String = ownLabel(node).ifEmpty { childText(node) }

    fun click(index: Int): String {
        val node = node(index) ?: return "No element numbered $index. Call ui_read first."
        return if (clickNode(node)) "Tapped element $index." else "Element $index could not be tapped."
    }

    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        val bounds = Rect().also(node::getBoundsInScreen)
        return tapAt(bounds.exactCenterX(), bounds.exactCenterY())
    }

    fun type(index: Int, text: String): String {
        val node = (if (index > 0) node(index) else focusedField())
            ?: return "No element numbered $index. Call ui_read first."
        if (!node.isEditable) return "Element $index is not a text field."
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val arguments = Bundle().apply {
            putCharSequence(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            "Entered text in element $index."
        } else {
            "Could not enter text in element $index."
        }
    }

    fun focusedField(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    fun scrollForward(): String = scroll(down = true)

    fun scrollBackward(): String = scroll(down = false)

    private fun scroll(down: Boolean): String {
        val root = rootInActiveWindow ?: return "No active screen."
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isScrollable && node.isVisibleToUser) {
                val r = Rect().also(node::getBoundsInScreen)
                if (r.width() * r.height() > bestArea) {
                    best = node
                    bestArea = r.width() * r.height()
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        visit(root)
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (best?.performAction(action) == true) return "Scrolled."
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val (from, to) = if (down) metrics.heightPixels * 0.7f to metrics.heightPixels * 0.3f else metrics.heightPixels * 0.3f to metrics.heightPixels * 0.7f
        return if (swipe(x, from, x, to)) "Scrolled." else "Nothing scrollable here."
    }

    fun globalAction(action: Int): String =
        if (performGlobalAction(action)) "Done." else "That action isn't available."

    /** Software bitmap screenshot on Android 11+; returns null for protected windows or API < 30. */
    suspend fun screenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { done ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val shot = result.hardwareBuffer.use { buffer ->
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        }
                        done.resume(shot)
                    }

                    override fun onFailure(errorCode: Int) {
                        done.resume(null)
                    }
                },
            )
        }
    }

    fun nodeAt(x: Float, y: Float): AccessibilityNodeInfo? {
        val point = Rect()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.isVisibleToUser) return null
            node.getBoundsInScreen(point)
            if (!point.contains(x.toInt(), y.toInt())) return null
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(::visit)?.let { return it }
            return node
        }
        return windows.sortedByDescending { it.layer }.firstNotNullOfOrNull { it.root?.let(::visit) }
    }

    fun tapAt(x: Float, y: Float): Boolean = swipe(x, y, x, y, durationMs = 60L)

    fun findFirst(test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.isVisibleToUser) return null
            if (test(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)?.let { return it }
            return null
        }
        return visit(root)
    }

    suspend fun waitFor(packageName: String, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (currentPackage() == packageName) return true
            delay(250)
        }
        return false
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun ownLabel(node: AccessibilityNodeInfo): String =
        (node.text ?: node.contentDescription ?: node.hintText)?.toString()?.trim().orEmpty()

    private fun childText(node: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        fun visit(n: AccessibilityNodeInfo) {
            if (parts.size >= 3) return
            ownLabel(n).takeIf { it.isNotEmpty() && n !== node }?.let { parts += it }
            for (i in 0 until n.childCount) n.getChild(i)?.let(::visit)
        }
        visit(node)
        return parts.joinToString(" · ")
    }

    private fun role(node: AccessibilityNodeInfo): String = when {
        node.isEditable -> "field"
        node.isCheckable -> if (node.isChecked) "switch on" else "switch off"
        node.isClickable -> "button"
        else -> "text"
    }

    companion object {
        const val MAX_ELEMENTS = 60

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
