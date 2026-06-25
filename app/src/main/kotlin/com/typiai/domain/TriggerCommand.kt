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
    val iconName: String
) {
    FIX(
        trigger = "@fix",
        title = "Fix Grammar",
        description = "Fixes grammar and spelling errors",
        prompt = "Fix the grammar, spelling, and punctuation in the following text. Return only the corrected text without any explanation:\n\n",
        iconName = "spellcheck"
    ),
    EMOJI(
        trigger = "@emoji",
        title = "Add Emojis",
        description = "Adds relevant emojis to the text",
        prompt = "Add relevant and expressive emojis to the following text to make it more engaging. Return only the text with emojis, no explanation:\n\n",
        iconName = "emoji_emotions"
    ),
    TYPI(
        trigger = "@typi",
        title = "Smart Rewrite",
        description = "Intelligently rewrites and improves text",
        prompt = "Intelligently rewrite and improve the following text for clarity, tone, and impact. Return only the improved text:\n\n",
        iconName = "auto_fix_high"
    ),
    TRANSLATE(
        trigger = "@translate",
        title = "Translate",
        description = "Translates text to English",
        prompt = "Translate the following text to English. If it is already in English, translate it to Spanish. Return only the translated text:\n\n",
        iconName = "translate"
    ),
    SUMM(
        trigger = "@summ",
        title = "Summarize",
        description = "Creates a concise summary",
        prompt = "Summarize the following text concisely while preserving the key points. Return only the summary:\n\n",
        iconName = "summarize"
    ),
    POLITE(
        trigger = "@polite",
        title = "Make Polite",
        description = "Rewrites text in a formal, polite tone",
        prompt = "Rewrite the following text in a polite, professional, and formal tone. Return only the rewritten text:\n\n",
        iconName = "sentiment_satisfied"
    ),
    CASUAL(
        trigger = "@casual",
        title = "Make Casual",
        description = "Rewrites text in a friendly, casual tone",
        prompt = "Rewrite the following text in a casual, friendly, and conversational tone. Return only the rewritten text:\n\n",
        iconName = "chat_bubble"
    ),
    EXPAND(
        trigger = "@expand",
        title = "Expand",
        description = "Expands text with more detail",
        prompt = "Expand the following text by adding more detail, context, and supporting information while keeping the same tone. Return only the expanded text:\n\n",
        iconName = "expand"
    ),
    BULLET(
        trigger = "@bullet",
        title = "Bullet Points",
        description = "Converts text to bullet points",
        prompt = "Convert the following text into clear, well-structured bullet points. Return only the bullet points:\n\n",
        iconName = "format_list_bulleted"
    ),
    IMPROVE(
        trigger = "@improve",
        title = "Improve",
        description = "Improves overall quality of text",
        prompt = "Improve the following text for better clarity, flow, and impact. Fix any issues and enhance the writing quality. Return only the improved text:\n\n",
        iconName = "trending_up"
    ),
    REPHRASE(
        trigger = "@rephrase",
        title = "Rephrase",
        description = "Rephrases text differently",
        prompt = "Rephrase the following text using different words while keeping the same meaning. Return only the rephrased text:\n\n",
        iconName = "refresh"
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
