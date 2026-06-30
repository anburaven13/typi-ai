# Contributing to TypiAI

Thank you for considering a contribution! Below are guidelines to keep the codebase consistent and the review process smooth.

---

## Development setup

1. **Fork** the repository and clone your fork:
   ```bash
   git clone https://github.com/<your-username>/TypiAI.git
   ```
2. Open the project in **Android Studio** (Hedgehog 2023.1.1+).
3. Let Gradle sync complete.
4. Create a feature branch:
   ```bash
   git checkout -b feature/my-awesome-feature
   ```

---

## Code style

- **Kotlin** — follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).
- **Compose** — one composable per file for screens; extract reusable components into shared files.
- **No `print()` / `System.out`** — use `Log.d/i/w/e(TAG, ...)` with a class-level `TAG` constant.
- **No hardcoded strings** — use `strings.xml` for user-visible text.
- **No API key in code or logs** — obfuscate before storage; strip from all log statements.

---

## Architecture rules

- New screens → add a `composable()` destination in `NavGraph.kt` and a `Screen` object.
- New AI commands → add an entry to `TriggerCommand.kt` (trigger + prompt + icon).
- Business logic → goes in `TypiRepository` or a dedicated use-case class.
- UI state → exposed as `StateFlow<XxxUiState>` from the ViewModel; immutable data class.

---

## Testing

- Run unit tests: `./gradlew test`
- Run UI tests (connected device/emulator): `./gradlew connectedAndroidTest`
- At minimum, add a unit test for any new `domain` or `repository` class.

---

## Pull request checklist

- [ ] `./gradlew assembleDebug` succeeds without errors.
- [ ] No new lint warnings (check with `./gradlew lint`).
- [ ] New feature is covered by at least one test.
- [ ] `CHANGELOG.md` updated under `[Unreleased]`.
- [ ] PR description explains *what* and *why*.

---

## Reporting bugs

Open a GitHub Issue with:
- Android version and device model.
- Steps to reproduce.
- Expected vs actual behaviour.
- Logcat output (redact any personal data).
