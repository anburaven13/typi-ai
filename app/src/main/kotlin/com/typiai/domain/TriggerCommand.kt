package com.typiai.domain

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

enum class TriggerCommand(
    val trigger: String,
    val title: String,
    val description: String,
    val prompt: String,
    val iconName: String,
    /** Gemini temperature (0.0–2.0). Lower = more deterministic / less creative. */
    val temperature: Double = 0.3,
    /** Max output tokens for this command. */
    val maxTokens: Int = 1024,
    /** Optional system instruction sent separately for better instruction following. */
    val systemInstruction: String = ""
) {
    FIX(
        trigger = "@fix",
        title = "Fix Grammar",
        description = "Fixes grammar and spelling errors",
        prompt = "Fix grammar and spelling. Keep the language natural. Return ONLY the fixed text.\n\n",
        iconName = "spellcheck",
        temperature = 0.1,
        maxTokens = 1024,
        systemInstruction = "You are a proofreader. Fix only grammar and spelling mistakes. Do not rephrase or change the meaning. Output only the corrected text."
    ),
    EMOJI(
        trigger = "@emoji",
        title = "Add Emojis",
        description = "Adds relevant emojis to the text",
        prompt = "Add relevant emojis to the following text. Return ONLY the text with emojis.\n\n",
        iconName = "emoji_emotions",
        temperature = 0.5,
        maxTokens = 1024,
        systemInstruction = "You add emojis to text. Keep all original words. Only insert relevant emojis. Output only the text with emojis added."
    ),
    TYPI(
        trigger = "@typi",
        title = "Smart Complete",
        description = "Completes the thought or text naturally",
        prompt = "Complete the thought or text in a natural way. Return ONLY the completed text.\n\n",
        iconName = "auto_fix_high",
        temperature = 0.7,
        maxTokens = 1024,
        systemInstruction = "You are a writing assistant. Complete the user's text naturally. Output only the completed text, nothing else."
    ),
    TRANSLATE(
        trigger = "@translate",
        title = "Translate",
        description = "Translates text to English",
        prompt = "Translate the text to perfect English. Return ONLY the translated text.\n\n",
        iconName = "translate",
        temperature = 0.2,
        maxTokens = 1024,
        systemInstruction = "You are a translator. Translate the given text to perfect English. Output only the translation."
    ),
    SUMM(
        trigger = "@summ",
        title = "Summarize",
        description = "Creates a concise summary",
        prompt = "Summarize the text concisely. Return ONLY the summary.\n\n",
        iconName = "summarize",
        temperature = 0.3,
        maxTokens = 512,
        systemInstruction = "You are a summarizer. Write a brief, clear summary of the text. Output only the summary."
    ),
    POLITE(
        trigger = "@polite",
        title = "Make Polite",
        description = "Rewrites text in a formal, polite tone",
        prompt = "Make the text formal and polite. Return ONLY the revised text.\n\n",
        iconName = "sentiment_satisfied",
        temperature = 0.3,
        maxTokens = 1024,
        systemInstruction = "You rewrite text to be formal and polite. Keep the same meaning. Output only the revised text."
    ),
    CASUAL(
        trigger = "@casual",
        title = "Make Casual",
        description = "Rewrites text in a friendly, casual tone",
        prompt = "Make the text friendly and casual. Return ONLY the revised text.\n\n",
        iconName = "chat_bubble",
        temperature = 0.5,
        maxTokens = 1024,
        systemInstruction = "You rewrite text to be friendly and casual. Keep the same meaning. Output only the revised text."
    ),
    EXPAND(
        trigger = "@expand",
        title = "Expand",
        description = "Expands text with more detail",
        prompt = "Expand on the text, adding details and better vocabulary. Return ONLY the expanded text.\n\n",
        iconName = "expand",
        temperature = 0.6,
        maxTokens = 2048,
        systemInstruction = "You expand text by adding relevant details and richer vocabulary. Do not change the core message. Output only the expanded text."
    ),
    BULLET(
        trigger = "@bullet",
        title = "Bullet Points",
        description = "Converts text to bullet points",
        prompt = "Format the main points as a bulleted list. Return ONLY the list.\n\n",
        iconName = "format_list_bulleted",
        temperature = 0.2,
        maxTokens = 1024,
        systemInstruction = "You convert text into bullet points. Extract the main points. Output only the bullet list."
    ),
    IMPROVE(
        trigger = "@improve",
        title = "Improve",
        description = "Improves overall quality of text",
        prompt = "Elevate the flow and vocabulary of the text. Return ONLY the improved text.\n\n",
        iconName = "trending_up",
        temperature = 0.4,
        maxTokens = 1024,
        systemInstruction = "You improve text by enhancing vocabulary and flow. Preserve the original meaning and length. Output only the improved text."
    ),
    REPHRASE(
        trigger = "@rephrase",
        title = "Rephrase",
        description = "Rephrases text differently",
        prompt = "Say the text differently while keeping the original meaning. Return ONLY the rephrased text.\n\n",
        iconName = "refresh",
        temperature = 0.5,
        maxTokens = 1024,
        systemInstruction = "You rephrase text using different words while keeping the exact same meaning. Output only the rephrased text."
    );

    companion object {
        fun fromText(text: String): TriggerCommand? {
            return values().firstOrNull { cmd ->
                text.trimEnd().endsWith(cmd.trigger)
            }
        }

        fun extractTextBefore(text: String, command: TriggerCommand): String {
            val triggerIndex = text.lastIndexOf(command.trigger)
            return if (triggerIndex > 0) {
                text.substring(0, triggerIndex).trim()
            } else ""
        }

        fun allTriggers(): List<String> = values().map { it.trigger }
    }
}
