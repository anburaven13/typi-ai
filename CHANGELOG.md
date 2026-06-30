# Changelog

All notable changes to TypiAI are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.0] — 2025-06-25

### Added

#### Core
- `TypiAccessibilityService` — monitors text fields across all apps, detects trigger commands, sends text to Gemini AI, and replaces the field content using Accessibility APIs.
- 600 ms debounce on text-change events to avoid excessive API calls.
- Duplicate-processing guard (`processingKey`) prevents the same text+command combination from triggering twice.
- `GeminiHelper` — OkHttp-based Gemini API client with:
  - Model priority list: `gemini-2.5-flash → gemini-2.5-flash-preview-05-20 → gemini-2.0-flash → gemini-1.5-flash`.
  - Automatic model fallback on HTTP 404.
  - Exponential backoff retry on 429 and 503 errors (1 s → 2 s → 4 s, max 3 attempts).
  - Graceful error messages for 400 (bad request), 401/403 (invalid key), and network failures.

#### Commands (11 total)
- `@fix` — grammar, spelling, and punctuation correction.
- `@emoji` — add expressive emojis.
- `@typi` — smart AI rewrite for clarity and impact.
- `@translate` — translate to English (or Spanish if already in English).
- `@summ` — concise summarisation.
- `@polite` — formal, professional tone rewrite.
- `@casual` — friendly, conversational tone rewrite.
- `@expand` — expand text with more detail and context.
- `@bullet` — convert to bullet-point list.
- `@improve` — improve clarity, flow, and overall quality.
- `@rephrase` — rephrase with different words, same meaning.

#### UI — Jetpack Compose Material 3
- **Dashboard screen**: service status card, API key management card (with test + save), AI playground, full command reference list.
- **Usage screen**: 4 stat tiles (today / total / successful / failed), animated success-rate progress bar, details panel, rate limit information.
- Bottom navigation bar with animated transitions.
- Material 3 Dynamic Color support (API 31+); graceful fallback to static TypiAI purple palette on API 30.
- Dark mode support throughout.
- Rounded cards (24 dp), smooth enter/exit animations.

#### Data & Storage
- `PreferencesDataStore` — DataStore-backed persistence for API key (obfuscated), usage statistics, and user preferences.
- `TypiRepository` — single source of truth; manages `GeminiHelper` lifecycle and records usage metrics.
- API key obfuscation: key is reversed and character-shifted before storage; never stored in plaintext; never logged.

#### Android 11 (API 30) Compatibility
- `android:exported` set correctly on all components with `<intent-filter>`.
- `ACTION_SET_TEXT` as primary text-replacement strategy (API 21+).
- Clipboard paste fallback for API 30–32 (Android 11–12); graceful degradation on API 33+.
- `core-splashscreen` 1.0.1 for back-ported splash on API 23+.
- `network_security_config.xml` enforcing HTTPS-only for Gemini API calls.
- Base theme has no API 31+ attributes (`windowSplashScreen*` kept in the compat `Theme.SplashScreen` parent only).

#### Security
- API key obfuscated before DataStore write.
- API key excluded from all log statements.
- `data_extraction_rules.xml` and `backup_rules.xml` exclude the DataStore file from cloud backup.
- HTTPS enforced via `network_security_config.xml`.

#### Build & Tooling
- Gradle 8.12, AGP 8.9.1, Kotlin 2.1.0.
- `libs.versions.toml` version catalog.
- `gradle.properties` with `android.useAndroidX=true`, `android.enableJetifier=true`.
- APK output renamed to `TypiAI-{buildType}-{versionName}.apk`.
- ProGuard rules for OkHttp, Gson, Kotlin Coroutines, and TypiAI classes.
- `.gitignore` covering all Gradle/Android/IDE artifacts.

---

## [Unreleased]

### Planned
- Release (signed) APK workflow via GitHub Actions.
- Per-command language selection for `@translate`.
- History of last N transformations.
- Haptic feedback on transformation complete.
- Floating overlay indicator while AI is processing.
- Widget for quick playground access.
