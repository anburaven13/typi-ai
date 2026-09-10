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
 *  @bullet, @improve, @rephrase, @old).
 *
 * Key fixes (v1.1):
 * ─────────────────
 * 1. Text extraction — reads BOTH event.text AND node.text so apps like
 *    WhatsApp/Telegram/Chrome (which don't populate event.text) are covered.
 * 2. isProcessing flag — now always reset in `finally`, including cancellation.
 * 3. Dedup key — cleared after successful apply so the same command can be
 *    reused after editing.
 * 4. Node snapshot — text is copied from the node immediately (before the
 *    debounce delay) so the node reference can be recycled safely.
 * 5. Service flags — FLAG_INCLUDE_NOT_IMPORTANT_VIEWS added so hidden/custom
 *    input views in apps like WhatsApp are covered.
 * 6. Separate node acquisition — we re-acquire the focused node AFTER the
 *    Gemini call completes (stale snapshot from the event is gone by then).
 */
class TypiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TypiAccessibility"
        private const val DEBOUNCE_MS = 500L          // Reduced from 650ms for snappier feel
        private const val MAX_TEXT_LENGTH = 4000
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler  = Handler(Looper.getMainLooper())

    private var debounceJob: Job? = null
    private var lastProcessedKey: String = ""
    private var isProcessing: Boolean = false

    private val repository: TypiRepository by lazy { TypiRepository(applicationContext) }

    // ─────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Merge programmatic flags with what the XML config already declares.
        // DO NOT fully replace serviceInfo — that would wipe the XML settings.
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // FLAG_RETRIEVE_INTERACTIVE_WINDOWS — read content from all windows
            // FLAG_REQUEST_FILTER_KEY_EVENTS    — needed for key-event filtering
            // FLAG_INCLUDE_NOT_IMPORTANT_VIEWS  — catches custom/hidden input views
            //   used by WhatsApp, Telegram, Chrome, etc.
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        serviceInfo = info
        Log.i(TAG, "TypiAI connected — Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Event handling
    // ─────────────────────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        if (isProcessing) return

        // ── Snapshot text immediately — BEFORE the debounce delay ────────────
        // The event's source node will be recycled by Android shortly after
        // this callback returns. We read the text NOW and pass the String.
        //
        // Priority: node.text (live value) > event.text (may be stale/empty
        // in many third-party apps like WhatsApp, Telegram, Chrome).
        val text = extractTextFromEvent(event)
        if (text.isNullOrBlank() || text.length > MAX_TEXT_LENGTH) return

        scheduleProcessing(text)
    }

    /**
     * Reads text from the event, trying multiple sources so we work across
     * as many apps as possible.
     */
    private fun extractTextFromEvent(event: AccessibilityEvent): String? {
        // Source 1: live node text (most reliable — reflects actual field value)
        try {
            val node = event.source
            if (node != null) {
                val nodeText = node.text?.toString()
                node.recycle()
                if (!nodeText.isNullOrBlank()) return nodeText
            }
        } catch (_: Exception) {}

        // Source 2: event.text list (fallback — populated by many standard views)
        try {
            val eventText = event.text?.firstOrNull()?.toString()
            if (!eventText.isNullOrBlank()) return eventText
        } catch (_: Exception) {}

        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Debounce + dispatch
    // ─────────────────────────────────────────────────────────────────────────

    private fun scheduleProcessing(text: String) {
        debounceJob?.cancel()
        debounceJob = serviceScope.launch {
            delay(DEBOUNCE_MS)
            processTextIfTriggered(text)
        }
    }

    private suspend fun processTextIfTriggered(text: String) {
        val command = TriggerCommand.fromText(text) ?: return
        val inputText = TriggerCommand.extractTextBefore(text, command)

        if (inputText.isBlank()) {
            showToast("Type some text before using ${command.trigger}")
            return
        }

        // Dedup guard — prevent double-firing on the same keystroke burst.
        // Key is cleared after successful apply so the user can re-run.
        val key = "$inputText::${command.trigger}"
        if (key == lastProcessedKey) return
        lastProcessedKey = key
        isProcessing = true

        showToast("TypiAI: Processing ${command.trigger}…")

        try {
            val result = repository.processText(inputText, command)
            withContext(Dispatchers.Main) {
                when (result) {
                    is GeminiResult.Success -> {
                        val applied = applyResult(result.text)
                        if (!applied) {
                            // Couldn't inject — show result + copy to clipboard
                            copyToClipboardAndNotify(result.text)
                        }
                        // Clear dedup key so user can run the same command again
                        lastProcessedKey = ""
                    }
                    is GeminiResult.Error -> {
                        showToast("TypiAI Error: ${result.message}", long = true)
                        Log.e(TAG, "Gemini error: ${result.message}")
                        // Also clear key on error so user can retry
                        lastProcessedKey = ""
                    }
                    else -> {}
                }
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Processing cancelled")
            // Don't clear lastProcessedKey — cancelled = didn't finish
        } catch (e: Exception) {
            Log.e(TAG, "Processing error: ${e.message}")
            showToast("TypiAI: An error occurred — please try again", long = true)
            lastProcessedKey = ""
        } finally {
            // CRITICAL: always release the processing lock, even on cancellation
            isProcessing = false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Apply result — re-acquires the focused node AFTER the Gemini call
    // (the original event source is long gone by this point)
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns true if the text was successfully injected into the target field. */
    private fun applyResult(newText: String): Boolean {
        // ── Strategy 1: focused node via input-focus API ──────────────────────
        val focusNode = findFocusedEditText()
        if (focusNode != null) {
            val ok = attemptSetText(focusNode, newText)
            if (ok) {
                recycleNode(focusNode)
                showToast("TypiAI: Done! ✓")
                return true
            }

            // ── Strategy 2: clipboard paste (API 30–32 only) ─────────────────
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val pasted = useClipboardPaste(focusNode, newText)
                recycleNode(focusNode)
                return pasted
            }
            recycleNode(focusNode)
        }

        // ── Strategy 3: root window depth-first search ────────────────────────
        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            val editNode = findEditableNode(rootNode)
            if (editNode != null) {
                val ok = attemptSetText(editNode, newText)
                recycleNode(editNode)
                recycleNode(rootNode)
                if (ok) {
                    showToast("TypiAI: Done! ✓")
                    return true
                }
            }
            recycleNode(rootNode)
        }

        return false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Strategy 1 — ACTION_SET_TEXT (API 21+; works on all our minSdk=30+)
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
    // ─────────────────────────────────────────────────────────────────────────

    private fun useClipboardPaste(node: AccessibilityNodeInfo, newText: String): Boolean {
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("TypiAI result", newText))

            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Int.MAX_VALUE)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
            val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (pasted) showToast("TypiAI: Done! ✓")
            pasted
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard paste failed: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Strategy 3 — clipboard + notify user (API 33+, no background write)
    // ─────────────────────────────────────────────────────────────────────────

    private fun copyToClipboardAndNotify(text: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("TypiAI result", text))
                showToast("TypiAI: Result copied — long-press to paste", long = true)
            } catch (e: Exception) {
                showToast("TypiAI result: $text", long = true)
            }
        } else {
            // Android 13+: background clipboard write is silently dropped.
            // Best we can do is show the text; user can copy it from the toast.
            showToast("TypiAI: $text", long = true)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Node helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun findFocusedEditText(): AccessibilityNodeInfo? {
        return try {
            val root = rootInActiveWindow ?: return null
            // Fast path: input-focus API
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) {
                recycleNode(root)
                return focused
            }
            recycleNode(focused)
            // Slow path: accessibility-focus API
            val a11yFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (a11yFocused != null && a11yFocused.isEditable) {
                recycleNode(root)
                return a11yFocused
            }
            recycleNode(a11yFocused)
            // Depth-first search as last resort
            val found = findEditableNode(root)
            recycleNode(root)
            found
        } catch (e: Exception) {
            Log.w(TAG, "findFocusedEditText: ${e.message}")
            null
        }
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) {
                // Recycle intermediate children that weren't returned
                if (found !== child) recycleNode(child)
                return found
            }
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
        isProcessing = false
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.i(TAG, "Accessibility service destroyed")
    }
}
