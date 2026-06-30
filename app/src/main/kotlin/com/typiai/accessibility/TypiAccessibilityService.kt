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

/**
 * TypiAccessibilityService
 *
 * Monitors text input across all apps and intercepts TypiAI trigger commands
 * (@fix, @emoji, @typi, @translate, @summ, @polite, @casual, @expand,
 *  @bullet, @improve, @rephrase).
 *
 * Android-version strategy
 * ────────────────────────
 * API 30 (Android 11) — minSdk; full feature set:
 *   • ACTION_SET_TEXT works in all editable fields.
 *   • Clipboard write from an accessibility service is still permitted
 *     (background clipboard restriction was introduced in API 33).
 *   • android:exported="true" is enforced by the OS for components with
 *     intent-filters — this is set in the manifest.
 *
 * API 31–32 (Android 12/12L) — same as API 30 for our use-case.
 *
 * API 33+ (Android 13+) — clipboard write from background is silently
 *   dropped; we fall back to showing a Toast with the result text.
 */
class TypiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TypiAccessibility"
        private const val DEBOUNCE_MS = 650L
        private const val MAX_TEXT_LENGTH = 4000
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var debounceJob: Job? = null
    private var lastProcessedKey: String = ""
    private var isProcessing: Boolean = false
    private val repository: TypiRepository by lazy { TypiRepository(applicationContext) }

    // ─────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType  = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags         = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 100
        }
        Log.i(TAG, "TypiAI connected — Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Event handling
    // ─────────────────────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isProcessing) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return

        val text = event.text?.firstOrNull()?.toString() ?: return
        if (text.length > MAX_TEXT_LENGTH) return

        // Grab the source node reference while the event is still valid;
        // the caller must recycle it when done.
        val sourceNode: AccessibilityNodeInfo? = event.source
        scheduleProcessing(text, sourceNode)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Debounce + dispatch
    // ─────────────────────────────────────────────────────────────────────────

    private fun scheduleProcessing(text: String, sourceNode: AccessibilityNodeInfo?) {
        debounceJob?.cancel()
        debounceJob = serviceScope.launch {
            delay(DEBOUNCE_MS)
            processTextIfTriggered(text, sourceNode)
        }
    }

    private suspend fun processTextIfTriggered(
        text: String,
        sourceNode: AccessibilityNodeInfo?
    ) {
        val command = TriggerCommand.fromText(text) ?: run {
            recycleNode(sourceNode); return
        }
        val inputText = TriggerCommand.extractTextBefore(text, command)

        if (inputText.isBlank()) {
            recycleNode(sourceNode)
            showToast("Type some text before using ${command.trigger}")
            return
        }

        // Duplicate-processing guard
        val key = "$inputText::${command.trigger}"
        if (key == lastProcessedKey) { recycleNode(sourceNode); return }
        lastProcessedKey = key
        isProcessing     = true

        showToast("TypiAI: Processing ${command.trigger}…")

        try {
            val result = repository.processText(inputText, command)
            withContext(Dispatchers.Main) {
                when (result) {
                    is GeminiResult.Success -> applyResult(result.text, sourceNode)
                    is GeminiResult.Error   -> {
                        showToast("TypiAI Error: ${result.message}")
                        Log.e(TAG, "Gemini error: ${result.message}")
                        recycleNode(sourceNode)
                    }
                    else -> recycleNode(sourceNode)
                }
            }
        } catch (e: CancellationException) {
            recycleNode(sourceNode)
            Log.d(TAG, "Processing cancelled")
        } catch (e: Exception) {
            recycleNode(sourceNode)
            Log.e(TAG, "Processing error: ${e.message}")
            showToast("TypiAI: An error occurred — please try again")
        } finally {
            isProcessing = false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Apply result — layered strategy (most reliable → least reliable)
    // ─────────────────────────────────────────────────────────────────────────

    private fun applyResult(newText: String, sourceNode: AccessibilityNodeInfo?) {
        // ── Strategy 1: use the event's own source node (fastest path) ────────
        if (sourceNode != null) {
            if (attemptSetText(sourceNode, newText)) {
                recycleNode(sourceNode)
                showToast("TypiAI: Done! ✓")
                return
            }
            recycleNode(sourceNode)
        }

        // ── Strategy 2: find focused editable via input-focus API ─────────────
        val focusNode = findFocusedEditText()
        if (focusNode != null) {
            if (attemptSetText(focusNode, newText)) {
                recycleNode(focusNode)
                showToast("TypiAI: Done! ✓")
                return
            }
            // Strategy 3: clipboard paste — safe on API 30–32 (Android 11/12)
            //   On API 33+ the system silently blocks background clipboard writes
            //   so we just show the result in a longer toast instead.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                useClipboardPaste(focusNode, newText)
            } else {
                // API 33+: inform user; result is ready to copy manually
                showToast("TypiAI result: $newText", long = true)
            }
            recycleNode(focusNode)
            return
        }

        showToast("TypiAI: Cannot reach text field — paste manually", long = true)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Strategy 1 — ACTION_SET_TEXT (API 21+; reliable on all our minSdk=30+)
    // ─────────────────────────────────────────────────────────────────────────

    private fun attemptSetText(node: AccessibilityNodeInfo, newText: String): Boolean {
        return try {
            if (!node.isEditable) return false
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    newText
                )
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) moveCursorToEnd(node, newText.length)
            ok
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_SET_TEXT failed: ${e.message}")
            false
        }
    }

    private fun moveCursorToEnd(node: AccessibilityNodeInfo, length: Int) {
        try {
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        } catch (_: Exception) { /* cursor placement is non-critical */ }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Strategy 2 — Clipboard + paste (API 30–32 only)
    //   Uses ACTION_SET_SELECTION(0..MAX) + ACTION_PASTE for a clean replace.
    // ─────────────────────────────────────────────────────────────────────────

    private fun useClipboardPaste(node: AccessibilityNodeInfo, newText: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("TypiAI result", newText))

            // Select everything in the field
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Int.MAX_VALUE)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            showToast("TypiAI: Done! ✓ (via clipboard)")
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard paste failed: ${e.message}")
            showToast("TypiAI: Result is in your clipboard — paste manually")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Node helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun findFocusedEditText(): AccessibilityNodeInfo? {
        return try {
            val root = rootInActiveWindow ?: return null
            // Fast path: use the accessibility-focus API
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) return focused
            recycleNode(focused)
            // Slow path: depth-first search
            findEditableNode(root)
        } catch (e: Exception) {
            Log.w(TAG, "findFocusedEditText: ${e.message}")
            null
        }
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
            recycleNode(child)
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun recycleNode(node: AccessibilityNodeInfo?) {
        try { node?.recycle() } catch (_: Exception) {}
    }

    private fun showToast(message: String, long: Boolean = false) {
        mainHandler.post {
            Toast.makeText(
                applicationContext,
                message,
                if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        debounceJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.i(TAG, "Accessibility service destroyed")
    }
}
