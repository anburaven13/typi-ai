# TypiAI 🤖⌨️

> **AI-powered text transformation directly inside any Android app — triggered by simple keyboard commands.**

TypiAI uses Google Gemini AI and Android's Accessibility API to intercept trigger commands you type (like `@fix` or `@translate`) and instantly replace your text with an AI-transformed version — no copy-paste, no switching apps.

---

## ✨ Features

| Feature | Detail |
|---|---|
| **11 AI commands** | `@fix` `@emoji` `@typi` `@translate` `@summ` `@polite` `@casual` `@expand` `@bullet` `@improve` `@rephrase` |
| **Latest Gemini model** | Auto-selects from priority list: `gemini-2.5-flash → gemini-2.0-flash → gemini-1.5-flash` |
| **Retry with backoff** | Exponential backoff on 429 / 503 errors; automatic model fallback on 404 |
| **Secure key storage** | API key obfuscated in DataStore; never logged |
| **Material Design 3** | Dynamic color (Android 12+), dark mode, rounded cards, smooth animations |
| **Usage dashboard** | Requests today / total / success rate / last command |
| **In-app playground** | Test any command without leaving the app |
| **Android 11+** | Supports API 30 – 35 (Android 11 through 15) |

---

## 📱 Android Version Support

| Android version | API level | Support |
|---|---|---|
| Android 11 | 30 | ✅ Full support (minSdk) |
| Android 12 / 12L | 31 / 32 | ✅ Full + Dynamic Color |
| Android 13 | 33 | ✅ Full (clipboard from background blocked — graceful fallback) |
| Android 14 | 34 | ✅ Full |
| Android 15 | 35 | ✅ Full (targetSdk) |

### Android 11 (API 30) specific notes

- **`android:exported`** — mandatory for all components with `<intent-filter>`; set correctly in the manifest.
- **Clipboard strategy** — `ACTION_SET_TEXT` is the primary method. On Android 11–12, clipboard paste is a fallback. On Android 13+ background clipboard writes are silently blocked; the app shows the result in a toast instead.
- **Dynamic Color** — gracefully degraded on API < 31; falls back to the static TypiAI purple Material 3 palette.
- **SplashScreen** — `core-splashscreen` compat library handles the splash on API 23+.

---

## 🚀 Getting Started

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 35 (with build-tools 35.0.0)
- A free [Google Gemini API key](https://ai.google.dev) (15 req/min on free tier)

### Clone & Build

```bash
git clone https://github.com/<your-org>/TypiAI.git
cd TypiAI
./gradlew assembleDebug
```

The debug APK will be at:
```
app/build/outputs/apk/debug/TypiAI-debug-1.0.0.apk
```

### Install on device

```bash
adb install app/build/outputs/apk/debug/TypiAI-debug-1.0.0.apk
```

---

## 🔑 Setup

1. **Open TypiAI** on your device.
2. Tap **"Save Key"** in the API Key card and enter your Gemini API key.
3. Tap **"Test"** to verify the key works.
4. Tap **"Enable Accessibility Service"** → find **TypiAI** in the list → toggle it on.
5. Open **any app** (WhatsApp, Gmail, Messages, Notes…).
6. Type your text, then append a command:
   ```
   This is my draft email @fix
   ```
7. TypiAI will instantly replace the text with the corrected version.

---

## ⌨️ Commands Reference

| Command | What it does | Example |
|---|---|---|
| `@fix` | Fix grammar, spelling & punctuation | `I recived ur emale @fix` |
| `@emoji` | Add expressive emojis | `Happy birthday! @emoji` |
| `@typi` | Smart AI rewrite for clarity | `meeting tmrw pls come @typi` |
| `@translate` | Translate to English (or Spanish if already EN) | `Bonjour le monde @translate` |
| `@summ` | Summarise long text concisely | `[long paragraph] @summ` |
| `@polite` | Rewrite in formal, professional tone | `fix this now @polite` |
| `@casual` | Rewrite in friendly, casual tone | `Please expedite your response @casual` |
| `@expand` | Expand with more detail & context | `Short note @expand` |
| `@bullet` | Convert to bullet-point list | `First do A then B then C @bullet` |
| `@improve` | Improve clarity, flow & impact | `Draft text @improve` |
| `@rephrase` | Rephrase with different words | `Same meaning @rephrase` |

---

## 🏗️ Architecture

```
com.typiai/
├── accessibility/
│   └── TypiAccessibilityService.kt    # Core service — event monitoring, trigger detection
├── ai/
│   └── GeminiHelper.kt                # OkHttp client, model selection, retry logic
├── data/
│   └── PreferencesDataStore.kt        # DataStore wrapper, secure key storage
├── domain/
│   ├── TriggerCommand.kt              # Enum with trigger string, prompt, icon
│   ├── GeminiResult.kt                # Sealed class: Success / Error / Loading
│   └── UsageStats.kt                  # Data class for usage metrics
├── repository/
│   └── TypiRepository.kt             # Single source of truth; wires AI + DataStore
├── viewmodel/
│   ├── DashboardViewModel.kt          # Dashboard UI state + business logic
│   └── UsageViewModel.kt              # Usage stats state
├── ui/
│   ├── dashboard/DashboardScreen.kt   # Compose: service card, API key, playground
│   └── usage/UsageScreen.kt          # Compose: stat cards, progress bar, details
├── navigation/NavGraph.kt             # Animated NavHost with 2 destinations
├── theme/
│   ├── Color.kt                       # Full light + dark colour palette
│   ├── Theme.kt                       # TypiAITheme with dynamic colour support
│   └── Type.kt                        # Complete Material 3 typography scale
└── utils/Extensions.kt                # Time formatter, truncate helpers
```

### Design patterns

- **MVVM** — ViewModels expose `StateFlow<UiState>` consumed by Composables.
- **Clean Architecture** — `domain` → `repository` → `viewmodel` → `ui` dependency flow.
- **Repository pattern** — `TypiRepository` is the only class that touches `GeminiHelper` and `PreferencesDataStore`.
- **Coroutines + SupervisorJob** — The accessibility service uses a scoped `CoroutineScope(IO + SupervisorJob())` so one failed job doesn't cancel others.

---

## 🤖 Gemini Integration

### Model priority list

```kotlin
private val MODEL_PRIORITY = listOf(
    "gemini-2.5-flash",
    "gemini-2.5-flash-preview-05-20",
    "gemini-2.0-flash",
    "gemini-2.0-flash-001",
    "gemini-1.5-flash",
    "gemini-1.5-flash-latest"
)
```

When a `404` is received the service automatically advances to the next model in the list.

### Retry strategy

| HTTP status | Action |
|---|---|
| `200` | Parse and return result |
| `400` | Return error (no retry) — bad request |
| `401 / 403` | Return error (no retry) — invalid API key |
| `404` | Advance to next model, retry |
| `429` | Exponential backoff: 1 s → 2 s → 4 s |
| `500 / 503` | Exponential backoff: 1 s → 2 s → 4 s |

---

## 🔐 Security

- **API key storage** — Key is obfuscated before writing to DataStore; never stored in plaintext.
- **No logging** — API key is never passed to `Log.*`.
- **HTTPS only** — `network_security_config.xml` disables cleartext traffic.
- **Background isolation** — The accessibility service scope is independent of the UI lifecycle.
- **Clipboard (Android 11–12)** — Used only as a fallback when `ACTION_SET_TEXT` fails; content is overwritten with "TypiAI result" label.

---

## 📦 Dependencies

| Library | Version | Purpose |
|---|---|---|
| Kotlin | 2.1.0 | Language |
| AGP | 8.9.1 | Android Gradle Plugin |
| Compose BOM | 2024.12.01 | Jetpack Compose |
| Material 3 | (BOM) | UI components |
| Navigation Compose | 2.8.5 | Screen navigation |
| Lifecycle / ViewModel | 2.8.7 | MVVM infrastructure |
| Activity Compose | 1.10.1 | Compose Activity integration |
| DataStore Preferences | 1.1.2 | Persistent storage |
| core-splashscreen | 1.0.1 | Splash screen (API 23+) |
| OkHttp | 4.12.0 | HTTP client for Gemini API |
| Gson | 2.11.0 | JSON parsing |
| Kotlinx Coroutines | 1.9.0 | Async/concurrent operations |

---

## 🛠️ Building

### Debug build

```bash
./gradlew assembleDebug
```

### Release build (requires keystore)

```bash
./gradlew assembleRelease
```

### Run unit tests

```bash
./gradlew test
```

### Run connected tests

```bash
./gradlew connectedAndroidTest
```

---

## 📊 Gemini API Rate Limits

| Tier | Requests/min | Requests/day |
|---|---|---|
| Free | 15 | 1,500 |
| Pay-as-you-go | 2,000 | Unlimited |

TypiAI automatically retries with exponential backoff on `429 Too Many Requests`.

---

## 📄 License

```
MIT License

Copyright (c) 2025 TypiAI

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
```

---

## 🙏 Acknowledgements

- [Google Gemini API](https://ai.google.dev) — AI text transformation
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — Modern Android UI
- [Material Design 3](https://m3.material.io) — Design system
- [OkHttp](https://square.github.io/okhttp/) — Networking

---

*Built with ❤️ using Kotlin, Jetpack Compose, and Google Gemini AI*
