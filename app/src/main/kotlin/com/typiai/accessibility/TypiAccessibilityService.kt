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
 * TypiAccessibilityService  — v1.2  (threading fix)
 *
 * Root causes of "works once then stops" fixed in this version:
 *
 * BUG 1 — Missing @Volatile / AtomicBoolean on isProcessing
 *   isProcessing was a plain Kotlin Boolean written on Dispatchers.IO but
 *   read on the Main thread (onAccessibilityEvent). Without a memory barrier,
 *   the Main thread CPU cache can hold a stale TRUE value indefinitely even
 *   after the IO thread set it to false.  All subsequent events were silently
 *   dropped because isProcessing appeared permanently true.
 *   FIX: AtomicBoolean provides the required happens-before guarantee.
 *
 * BUG 2 — Cross-thread state mutation (isProcessing + lastProcessedKey)
 *   withContext(Dispatchers.Main) ran applyResult() on the Main thread and
 *   cleared lastProcessedKey there, while the finally{} block ran isProcessing
 *   = false on Dispatchers.IO.  The two threads mutated shared state with no
 *   synchronisation.
 *   FIX: ENTIRE coroutine pipeline now runs on Main (serviceScope uses
 *   Dispatchers.Main).  IO-bound work (Gemini HTTP call) is wrapped in its
 *   own withContext(Dispatchers.IO) inside the repository/helper — where it
 *   already is — so no extra wrapping is needed here.  All state reads and
 *   writes happen on a single thread, eliminating every race.
 *
 * BUG 3 — ACTION_SET_TEXT re-triggers processing loop
 *   When we called ACTION_SET_TEXT the target app fired a new
 *   TYPE_VIEW_TEXT_CHANGED event containing the AI result text.  If that
 *   event arrived after isProcessing was reset to false, scheduleProcessing
 *   would start a new 500 ms debounce job.  500 ms later
 *   processTextIfTriggered would run on the AI-result text, find no trigger,
 *   and return — but the debounceJob slot was occupied for those 500 ms,
 *   silently eating the user's next real keystroke burst.
 *   FIX: We record the last text we injected (lastInjectedText) and skip
 *   any event whose text matches it exactly, so the injection echo is
 *   ignored without starting a debounce timer.
 *
 * Architecture after fix:
 *   serviceScope  → Dispatchers.Main (single-threaded, matches callback thread)
 *   Gemini/HTTP   → withContext(Dispatchers.IO) inside GeminiHelper (unchanged)
 *   State vars    → all read/written on Main; AtomicBoolean as safety net
 */
class TypiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG          = "TypiAccessibility"
        private const val DEBOUNCE_MS  = 600L
        private const val MAX_TEXT_LEN = 4000
    }

    // ── All coroutines run on Main so state mutations are single-threaded ────
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler  = Handler(Looper.getMainLooper())

    private var debounceJob: Job? = null

    // AtomicBoolean as an extra safety net (reads on Main, but belt-and-suspenders)
    private val isProcessing = AtomicBoolean(false)

    // Dedup: prevents re-processing the same trigger text twice in a row
    private var lastProcessedKey = ""

    // Injection echo guard: skip events caused by our own ACTION_SET_TEXT
    private var lastInjectedText = ""

    private val repository: TypiRepository by lazy { TypiRepository(applicationContext) }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes        = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                                 AccessibilityEvent.TYPE_VIEW_FOCUSED
        info.feedbackType      = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags             = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                                 AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS   or
                                 AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        info.notificationTimeout = 100
        serviceInfo = info
        Log.i(TAG, "TypiAI v1.2 connected — Android ${Build.VERSION.RELEASE} " +
                   "(API ${Build.VERSION.SDK_INT})")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Event entry point  (always called on Main thread by the OS)
    // ─────────────────────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        if (isProcessing.get()) return          // already handling a command

        val text = extractText(event) ?: return
        if (text.length > MAX_TEXT_LEN) return

        // ── Injection echo guard ─────────────────────────────────────────────
        // ACTION_SET_TEXT fires a TYPE_VIEW_TEXT_CHANGED event with the text
        // we just wrote.  Skip it so we don't start a phantom debounce cycle.
        if (text == lastInjectedText) {
            lastInjectedText = ""   // consume the guard once
            return
        }

        scheduleProcessing(text)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Text extraction  — tries multiple sources for cross-app compatibility
    // ─────────────────────────────────────────────────────────────────────────

    private fun extractText(event: AccessibilityEvent): String? {
        // 1. Live node text — most reliable; reflects the actual field content.
        //    Many modern apps (WhatsApp, Telegram, Chrome) don't fill event.text.
        try {
            val node = event.source
            if (node != null) {
                val t = node.text?.toString()
                node.recycle()
                if (!t.isNullOrBlank()) return t
            }
        } catch (_: Exception) {}

        // 2. event.text[] list — standard views fill this; use as fallback.
        try {
            val t = event.text?.firstOrNull()?.toString()
            if (!t.isNullOrBlank()) return t
        } catch (_: Exception) {}

        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Debounce  (runs on Main — no cross-thread issues)
    // ─────────────────────────────────────────────────────────────────────────

    private fun scheduleProcessing(text: String) {
        debounceJob?.cancel()
        debounceJob = serviceScope.launch {
            delay(DEBOUNCE_MS)
            processIfTriggered(text)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core pipeline  (runs on Main after debounce)
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun processIfTriggered(text: String) {
        val command   = TriggerCommand.fromText(text) ?: return
        val inputText = TriggerCommand.extractTextBefore(text, command)

        if (inputText.isBlank()) {
            toast("Type some text before using ${command.trigger}")
            return
        }

        // Dedup guard
        val key = "$inputText::${command.trigger}"
        if (key == lastProcessedKey) return

        if (!isProcessing.compareAndSet(false, true)) return   // already locked

        lastProcessedKey = key
        toast("TypiAI: Processing ${command.trigger}…")

        try {
            // repository.processText uses withContext(Dispatchers.IO) internally
            val result = repository.processText(inputText, command)

            when (result) {
                is GeminiResult.Success -> {
                    val applied = injectText(result.text)
                    if (!applied) copyToClipboard(result.text)
                    lastProcessedKey = ""   // allow re-running same command
                }
                is GeminiResult.Error -> {
                    toast("TypiAI: ${result.message}", long = true)
                    Log.e(TAG, "Gemini error: ${result.message}")
                    lastProcessedKey = ""   // allow retry
                }
                else -> {}
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Cancelled")
            throw e   // MUST re-throw CancellationException for coroutine machinery
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            toast("TypiAI: Something went wrong — please try again", long = true)
            lastProcessedKey = ""
        } finally {
            isProcessing.set(false)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Text injection  — three strategies, best → least reliable
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if the result was successfully written into a text field.
     * Records [lastInjectedText] so the resulting change-event is ignored.
     */
    private fun injectText(newText: String): Boolean {
        val node = findFocusedEditText()

        // Strategy A — ACTION_SET_TEXT (API 21+, works on all our minSdk=30+)
        if (node != null) {
            if (setTextOnNode(node, newText)) {
                lastInjectedText = newText      // arm echo guard
                recycleNode(node)
                toast("TypiAI: Done ✓")
                return true
            }

            // Strategy B — clipboard paste (API 30–32 only)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val ok = clipboardPaste(node, newText)
                recycleNode(node)
                if (ok) {
                    lastInjectedText = newText
                    return true
                }
            } else {
                recycleNode(node)
            }
        }

        // Strategy C — root-window depth-first search
        val root = rootInActiveWindow
        if (root != null) {
            val editNode = findEditableNode(root)
            recycleNode(root)
            if (editNode != null) {
                val ok = setTextOnNode(editNode, newText)
                recycleNode(editNode)
                if (ok) {
                    lastInjectedText = newText
                    toast("TypiAI: Done ✓")
                    return true
                }
            }
        }

        return false
    }

    private fun setTextOnNode(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            if (!node.isEditable) return false
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) {
                // Move cursor to end
                try {
                    val sel = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,   text.length)
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
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,   Int.MAX_VALUE)
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

    // ─────────────────────────────────────────────────────────────────────────
    // Clipboard fallback when injection fails entirely
    // ─────────────────────────────────────────────────────────────────────────

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
            // Android 13+: background clipboard writes are silently dropped
            toast("TypiAI result:\n$text", long = true)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Node finding
    // ─────────────────────────────────────────────────────────────────────────

    private fun findFocusedEditText(): AccessibilityNodeInfo? {
        return try {
            val root = rootInActiveWindow ?: return null

            // Try input-focus first (fastest)
            val inp = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (inp != null && inp.isEditable) { recycleNode(root); return inp }
            recycleNode(inp)

            // Try accessibility-focus (some apps only register this)
            val a11y = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (a11y != null && a11y.isEditable) { recycleNode(root); return a11y }
            recycleNode(a11y)

            // Depth-first fallback
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

    private fun toast(msg: String, long: Boolean = false) {
        mainHandler.post {
            Toast.makeText(applicationContext, msg,
                if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
        debounceJob?.cancel()
        isProcessing.set(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.i(TAG, "Service destroyed")
    }
}
