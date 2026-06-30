# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 1.0.x   | ✅ Yes    |

## Reporting a Vulnerability

If you discover a security vulnerability, **please do not open a public GitHub Issue**.

Instead, open a [GitHub Security Advisory](../../security/advisories/new) or email the maintainers privately. We aim to respond within 72 hours and will coordinate disclosure.

## API Key Security

- The Gemini API key entered by the user is **obfuscated** before being written to Android DataStore (not encrypted with a full keystore — users should treat their key as a shared secret).
- The key is **never written to Logcat** at any log level in release builds.
- ProGuard/R8 strips all debug log calls in release builds.
- The `network_security_config.xml` restricts all network traffic to HTTPS, preventing accidental cleartext transmission.
- DataStore files are excluded from Android cloud backup (`data_extraction_rules.xml`).

## Permissions

TypiAI requests only the minimum necessary permissions:

| Permission | Reason |
|---|---|
| `INTERNET` | Gemini API requests |
| `ACCESS_NETWORK_STATE` | Check connectivity before making requests |
| `BIND_ACCESSIBILITY_SERVICE` | Required by the Android OS for AccessibilityService components |

No location, contacts, camera, microphone, or storage permissions are requested.

## AccessibilityService Notes

TypiAI's `TypiAccessibilityService` reads text from the active text field **only when a trigger command is detected**. It does not:
- Store or transmit the content of text fields that don't contain a trigger command
- Log keystrokes, passwords, or sensitive data
- Access clipboard contents on Android 13+ (OS restriction)
- Read notifications or window content outside of active editable views

Text sent to the Gemini API is subject to [Google's Privacy Policy](https://policies.google.com/privacy). Do not use TypiAI to process personally identifiable or sensitive information.
