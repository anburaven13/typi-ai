# Contributing to TypiAI

Thank you for your interest in contributing! This document explains how to set up the project, follow the coding conventions, and submit high-quality pull requests.

---

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Project Structure](#project-structure)
- [Coding Style](#coding-style)
- [Adding a New Command](#adding-a-new-command)
- [Pull Request Process](#pull-request-process)
- [Release Signing](#release-signing)
- [Reporting Bugs](#reporting-bugs)

---

## Code of Conduct

Be respectful, inclusive, and constructive. Harassment of any kind will not be tolerated.

---

## Getting Started

### 1 — Fork & Clone

```bash
git clone https://github.com/<your-username>/TypiAI.git
cd TypiAI
```

### 2 — Prerequisites

| Tool | Version |
|---|---|
| Android Studio | Hedgehog 2023.1.1 or later |
| JDK | 17 (bundled with Android Studio) |
| Android SDK | API 30 (min) through API 35 (compile) |
| Gemini API Key | [ai.google.dev](https://ai.google.dev) — free tier is sufficient |

### 3 — First Build

```bash
# Sync and build
./gradlew assembleDebug

# Run lint checks
./gradlew lint

# Run unit tests
./gradlew test
```

### 4 — Set Your API Key (for manual testing)

Open the app → Dashboard → API Key field → paste your key → **Save**.  
The key is stored obfuscated in DataStore and is **never** written to logs.

---

## Project Structure

```
app/src/main/kotlin/com/typiai/
├── accessibility/      TypiAccessibilityService — event loop, debounce, text replacement
├── ai/                 GeminiHelper — HTTP client, retry, model fallback
├── data/               PreferencesDataStore — persistence, key obfuscation
├── domain/             Pure Kotlin models (TriggerCommand, GeminiResult, UsageStats)
├── navigation/         NavGraph — type-safe Compose navigation
├── repository/         TypiRepository — aggregates AI + storage
├── theme/              Color, Type, Theme — Material 3
├── ui/
│   ├── dashboard/      DashboardScreen + composable components
│   └── usage/          UsageScreen + composable components
├── utils/              Extension functions
└── viewmodel/          DashboardViewModel, UsageViewModel
```

---

## Coding Style

### Kotlin

- Follow the [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html).
- Use `when` exhaustively on sealed classes — avoid `else` unless truly needed.
- Prefer `StateFlow` / `collectAsState()` over `LiveData`.
- All coroutine launches inside `ViewModel` use `viewModelScope`; inside `AccessibilityService` use a `SupervisorJob`-backed scope cancelled in `onDestroy()`.
- `suspend` functions are always called from a coroutine context — never from a callback thread directly.
- Never log API keys. Use `Log.d(TAG, "...")` patterns that omit sensitive data.

### Compose

- One composable = one responsibility. Split large screens into small `@Composable` functions.
- Pass only what's needed — avoid passing the entire `ViewModel` into nested composables; pass state and lambdas instead.
- Use `remember` / `rememberSaveable` appropriately. Heavy objects go in `ViewModel`, not in composition.
- Preview every composable with `@Preview`.

### XML & Resources

- All strings go in `res/values/strings.xml` — no hardcoded strings in code.
- Dimensions go in `res/values/dimens.xml` if used in more than one place.
- Do **not** commit auto-generated files (`.idea/`, `build/`).

---

## Adding a New Command

1. **Add an enum entry** in `domain/TriggerCommand.kt`:

```kotlin
MY_COMMAND(
    trigger   = "@mycommand",
    title     = "My Command",
    description = "What this command does",
    prompt    = "Transform the following text by …. Return only the transformed text:\n\n",
    iconName  = "icon_name"   // for reference; actual icon chosen in DashboardScreen
)
```

2. **Add the icon mapping** in `ui/dashboard/DashboardScreen.kt` → `CommandItem` composable:

```kotlin
TriggerCommand.MY_COMMAND -> Icons.Default.YourChosenIcon
```

3. **Test it** via the Playground on the Dashboard screen before filing a PR.

4. **Update `README.md`** — add a row to the Command Reference table.

---

## Pull Request Process

1. **Branch naming:** `feature/<short-description>`, `fix/<issue-number>-description`, `docs/<topic>`
2. **Commit messages:** Use [Conventional Commits](https://www.conventionalcommits.org/):
   ```
   feat(ai): add streaming response support
   fix(a11y): handle null source node on Android 13
   docs: update command reference table
   ```
3. **PR checklist before opening:**
   - [ ] `./gradlew assembleDebug` succeeds with **zero errors**
   - [ ] `./gradlew lint` passes (warnings OK, errors not OK)
   - [ ] `./gradlew test` all unit tests pass
   - [ ] No API keys, keystore files, or `local.properties` committed
   - [ ] `README.md` updated if the PR adds/changes user-facing behaviour
   - [ ] New composables have `@Preview` annotations
4. **Reviews:** At least one approval is required. Address all requested changes before merging.
5. **Squash merges** are preferred to keep `main` history clean.

---

## Release Signing

Release builds require a keystore. **Never commit keystore files or passwords.**

Create `key.properties` in the project root (already in `.gitignore`):

```properties
storeFile=../release-key.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=typiai
keyPassword=YOUR_KEY_PASSWORD
```

Then add signing config to `app/build.gradle.kts`:

```kotlin
val keystoreProps = Properties().also { props ->
    val file = rootProject.file("key.properties")
    if (file.exists()) props.load(file.inputStream())
}

android {
    signingConfigs {
        create("release") {
            storeFile     = file(keystoreProps["storeFile"] as String)
            storePassword = keystoreProps["storePassword"] as String
            keyAlias      = keystoreProps["keyAlias"] as String
            keyPassword   = keystoreProps["keyPassword"] as String
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // ... existing config ...
        }
    }
}
```

Then build:

```bash
./gradlew assembleRelease
```

---

## Reporting Bugs

Open a [GitHub Issue](../../issues/new) with:

- Android version & device model
- Steps to reproduce
- Expected vs actual behaviour
- Logcat output (redact any personal data)

---

_Happy coding! If you have questions, open a Discussion or drop a comment on the relevant issue._
