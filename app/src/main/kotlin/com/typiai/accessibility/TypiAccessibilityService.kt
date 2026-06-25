package com.typiai.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
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

class TypiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TypiAccessibility"
        private const val DEBOUNCE_MS = 600L
        private const val MAX_TEXT_LENGTH = 4000
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var debounceJob: Job? = null
    private var lastProcessedText: String = ""
    private var isProcessing: Boolean = false
    private val repository: TypiRepository by lazy { TypiRepository(applicationContext) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 100
        }
        Log.i(TAG, "TypiAI Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (isProcessing) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val text = event.text?.firstOrNull()?.toString() ?: return
                if (text.length > MAX_TEXT_LENGTH) return
                scheduleProcessing(event, text)
            }
            else -> {}
        }
    }

    private fun scheduleProcessing(event: AccessibilityEvent, text: String) {
        debounceJob?.cancel()
        debounceJob = serviceScope.launch {
            delay(DEBOUNCE_MS)
            processTextIfTriggered(event, text)
        }
    }

    private suspend fun processTextIfTriggered(event: AccessibilityEvent, text: String) {
        val command = TriggerCommand.fromText(text) ?: return
        val inputText = TriggerCommand.extractTextBefore(text, command)

        if (inputText.isBlank()) {
            showToast("Please type some text before using ${command.trigger}")
            return
        }

        // Prevent duplicate processing of same text+command
        val processingKey = "$inputText::${command.trigger}"
        if (processingKey == lastProcessedText) return
        lastProcessedText = processingKey

        isProcessing = true
        showToast("TypiAI: Processing ${command.trigger}...")

        try {
            val result = repository.processText(inputText, command)
            withContext(Dispatchers.Main) {
                when (result) {
                    is GeminiResult.Success -> {
                        val source = event.source
                        if (source != null) {
                            replaceText(source, result.text)
                            source.recycle()
                        } else {
                            // Fallback: try to find focused node
                            val focusedNode = findFocusedEditText()
                            if (focusedNode != null) {
                                replaceText(focusedNode, result.text)
                                focusedNode.recycle()
                            } else {
                                showToast("TypiAI: Could not apply result to text field")
                            }
                        }
                    }
                    is GeminiResult.Error -> {
                        showToast("TypiAI Error: ${result.message}")
                        Log.e(TAG, "Gemini error: ${result.message}")
                    }
                    else -> {}
                }
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Processing cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Processing error: ${e.message}")
            showToast("TypiAI: An error occurred")
        } finally {
            isProcessing = false
        }
    }

    private fun replaceText(node: AccessibilityNodeInfo, newText: String) {
        try {
            val arguments = Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newText
            )
            val success = node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                arguments
            )
            if (success) {
                // Move cursor to end
                val moveCursorArgs = Bundle()
                moveCursorArgs.putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                    newText.length
                )
                moveCursorArgs.putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                    newText.length
                )
                node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_SELECTION,
                    moveCursorArgs
                )
                showToast("TypiAI: Done!")
                Log.i(TAG, "Text replaced successfully, length=${newText.length}")
            } else {
                Log.w(TAG, "ACTION_SET_TEXT failed, trying clipboard fallback")
                useClipboardFallback(node, newText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "replaceText error: ${e.message}")
            showToast("TypiAI: Failed to replace text")
        }
    }

    private fun useClipboardFallback(node: AccessibilityNodeInfo, newText: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("TypiAI Result", newText)
            clipboard.setPrimaryClip(clip)
            // Select all and paste
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            showToast("TypiAI: Text applied via clipboard")
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard fallback failed: ${e.message}")
        }
    }

    private fun findFocusedEditText(): AccessibilityNodeInfo? {
        return try {
            val root = rootInActiveWindow ?: return null
            findEditableNode(root)
        } catch (e: Exception) {
            null
        }
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        debounceJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        debounceJob?.cancel()
        Log.i(TAG, "Accessibility service destroyed")
    }
}
