package com.typiai.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.typiai.domain.GeminiResult
import com.typiai.domain.TriggerCommand
import com.typiai.repository.TypiRepository
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TypiAccessibilityService — Production Resilient Implementation
 *
 * Fixes for "works a few times then stops in other apps":
 *
 * 1. Target Node Retention & Refresh:
 *    Retains a direct reference to [currentTargetNode] from the originating
 *    AccessibilityEvent so text injection can apply directly via target.refresh()
 *    without relying exclusively on rootInActiveWindow (which is frequently null
 *    or displaced by transient system windows like Toasts).
 *
 * 2. Multi-Window Traversal:
 *    If rootInActiveWindow is null or lacks the focused field, queries the
 *    entire [windows] collection to locate the user's active input field
 *    across third-party app boundaries.
 *
 * 3. Removal of Destructive Manual Node Recycling:
 *    Eliminated manual AccessibilityNodeInfo.recycle() calls. On API 33+ recycle()
 *    is deprecated/no-op; on API 30–32, premature recycling corrupts the system
 *    object pool, causing IllegalStateExceptions that crash the service and prompt
 *    Android to automatically disable the accessibility service toggle.
 *
 * 4. Resilient Top-Level Event Guard:
 *    All onAccessibilityEvent logic is wrapped in a top-level try/catch to ensure
 *    unexpected view tree exceptions NEVER crash the service process.
 *
 * 5. Time-Limited Injection Echo Guard:
 *    Guards against self-triggered change events using a 2-second timestamp
 *    window so [lastInjectedText] never becomes permanently stuck if an app
 *    slightly alters or suppresses the echo event.
 *
 * 6. Non-Strict Field Editable Check:
 *    Tries ACTION_SET_TEXT safely without bailing prematurely on views (e.g.,
 *    custom editors, WebViews, Compose fields) that support text replacement
 *    but do not expose isEditable = true.
 */
class TypiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TypiAccessibility"
        private const val DEBOUNCE_MS = 600L
        private const val MAX_TEXT_LEN = 4000
        private const val ECHO_GUARD_TIMEOUT_MS = 2000L
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var debounceJob: Job? = null
    private val isProcessing = AtomicBoolean(false)

    // Deduplication key
    private var lastProcessedKey = ""

    // Injected text echo guard
    private var lastInjectedText = ""
    private var lastInjectionTime = 0L

    // Live reference to the node where the event originated
    private var currentTargetNode: AccessibilityNodeInfo? = null

    private val repository: TypiRepository by lazy { TypiRepository(applicationContext) }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            info.notificationTimeout = 100
            serviceInfo = info
            Log.i(TAG, "TypiAI connected — Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onServiceConnected: ${e.message}", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Event Entry Point
    // ─────────────────────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
            if (isProcessing.get()) return

            // Capture the exact input node from the event
            val source = event.source
            if (source != null) {
                currentTargetNode = source
            }

            val text = extractText(event) ?: return
            if (text.length > MAX_TEXT_LEN) return

            // Injection echo guard with auto-expiry
            val now = System.currentTimeMillis()
            if (text == lastInjectedText && (now - lastInjectionTime) < ECHO_GUARD_TIMEOUT_MS) {
                lastInjectedText = ""
                return
            }

            scheduleProcessing(text)
        } catch (t: Throwable) {
            Log.e(TAG, "Exception in onAccessibilityEvent: ${t.message}", t)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Text Extraction
    // ─────────────────────────────────────────────────────────────────────────

    private fun extractText(event: AccessibilityEvent): String? {
        // 1. Live node text
        try {
            val node = event.source
            if (node != null) {
                val t = node.text?.toString()
                if (!t.isNullOrBlank()) return t
            }
        } catch (_: Exception) {}

        // 2. Event text list fallback
        try {
            val t = event.text?.firstOrNull()?.toString()
            if (!t.isNullOrBlank()) return t
        } catch (_: Exception) {}

        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Debounce
    // ─────────────────────────────────────────────────────────────────────────

    private fun scheduleProcessing(text: String) {
        debounceJob?.cancel()
        debounceJob = serviceScope.launch {
            delay(DEBOUNCE_MS)
            processIfTriggered(text)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core Pipeline
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun processIfTriggered(text: String) {
        val command = TriggerCommand.fromText(text) ?: return
        val inputText = TriggerCommand.extractTextBefore(text, command)

        if (inputText.isBlank()) {
            toast("Type some text before using ${command.trigger}")
            return
        }

        val key = "$inputText::${command.trigger}"
        if (key == lastProcessedKey) return

        if (!isProcessing.compareAndSet(false, true)) return

        lastProcessedKey = key
        toast("TypiAI: Processing ${command.trigger}…")

        try {
            val result = repository.processText(inputText, command)

            when (result) {
                is GeminiResult.Success -> {
                    val applied = injectText(result.text)
                    if (!applied) {
                        copyToClipboard(result.text)
                    }
                    lastProcessedKey = ""
                }
                is GeminiResult.Error -> {
                    toast("TypiAI: ${result.message}", long = true)
                    Log.e(TAG, "Gemini error: ${result.message}")
                    lastProcessedKey = ""
                }
                else -> {
                    lastProcessedKey = ""
                }
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}", e)
            toast("TypiAI: Something went wrong — please try again", long = true)
            lastProcessedKey = ""
        } finally {
            isProcessing.set(false)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Text Injection
    // ─────────────────────────────────────────────────────────────────────────

    private fun injectText(newText: String): Boolean {
        // Strategy 1 — Direct node from event (the exact field the user typed in)
        val directNode = currentTargetNode
        if (directNode != null) {
            try {
                if (directNode.refresh() && setTextOnNode(directNode, newText)) {
                    lastInjectedText = newText
                    lastInjectionTime = System.currentTimeMillis()
                    toast("TypiAI: Done ✓")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct node injection failed: ${e.message}")
            }
        }

        // Strategy 2 — Focused input lookup (active window + interactive windows)
        val focusedNode = findFocusedEditText()
        if (focusedNode != null) {
            if (setTextOnNode(focusedNode, newText)) {
                lastInjectedText = newText
                lastInjectionTime = System.currentTimeMillis()
                toast("TypiAI: Done ✓")
                return true
            }

            // Clipboard paste fallback on API 30–32
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val ok = clipboardPaste(focusedNode, newText)
                if (ok) {
                    lastInjectedText = newText
                    lastInjectionTime = System.currentTimeMillis()
                    return true
                }
            }
        }

        // Strategy 3 — Multi-window depth-first traversal
        val allRoots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { allRoots.add(it) }
        try {
            for (window in windows) {
                window.root?.let { if (!allRoots.contains(it)) allRoots.add(it) }
            }
        } catch (_: Exception) {}

        for (root in allRoots) {
            val editableNode = findEditableNode(root)
            if (editableNode != null) {
                if (setTextOnNode(editableNode, newText)) {
                    lastInjectedText = newText
                    lastInjectionTime = System.currentTimeMillis()
                    toast("TypiAI: Done ✓")
                    return true
                }
            }
        }

        return false
    }

    private fun setTextOnNode(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                )
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) {
                try {
                    val sel = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
                } catch (_: Exception) {}
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_SET_TEXT failed: ${e.message}")
            false
        }
    }

    private fun clipboardPaste(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("TypiAI", text))
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Int.MAX_VALUE)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (ok) toast("TypiAI: Done ✓ (via clipboard)")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard paste failed: ${e.message}")
            false
        }
    }

    private fun copyToClipboard(text: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            try {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("TypiAI", text))
                toast("TypiAI: Copied — long-press field to paste", long = true)
            } catch (_: Exception) {
                toast("TypiAI result: $text", long = true)
            }
        } else {
            toast("TypiAI result:\n$text", long = true)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Node Finding Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun findFocusedEditText(): AccessibilityNodeInfo? {
        return try {
            // 1. Try rootInActiveWindow
            val activeRoot = rootInActiveWindow
            if (activeRoot != null) {
                findInHierarchy(activeRoot)?.let { return it }
            }

            // 2. Iterate interactive windows
            val windowList = windows
            for (window in windowList) {
                val windowRoot = window.root ?: continue
                findInHierarchy(windowRoot)?.let { return it }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "findFocusedEditText: ${e.message}")
            null
        }
    }

    private fun findInHierarchy(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return try {
            val inp = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (inp != null) return inp

            val a11y = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (a11y != null) return a11y

            findEditableNode(root)
        } catch (_: Exception) {
            null
        }
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable || node.className?.contains("EditText", ignoreCase = true) == true) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private fun toast(msg: String, long: Boolean = false) {
        mainHandler.post {
            Toast.makeText(
                applicationContext,
                msg,
                if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
        debounceJob?.cancel()
        isProcessing.set(false)
        currentTargetNode = null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        currentTargetNode = null
        Log.i(TAG, "Service destroyed")
    }
}
