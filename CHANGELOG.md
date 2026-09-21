## Unreleased

* **Offline support**: `listen()` and `start()` now honor `onDevice: true`, which was previously accepted but ignored.
  * iOS/macOS: sets `requiresOnDeviceRecognition`; reports an error if the locale has no on-device model.
  * Android 13+: uses the on-device `SpeechRecognizer`; older versions request offline mode via `EXTRA_PREFER_OFFLINE` (requires the offline language pack).
* Android: network errors no longer trigger an instant restart loop; they are reported through `onError`.
* Android: added error messages for `ERROR_LANGUAGE_NOT_SUPPORTED` / `ERROR_LANGUAGE_UNAVAILABLE`.

## 0.0.1

* Initial release of `speech_to_text_pro`.
* Support for Continuous Speech-to-Text on Android and iOS.
* **Standard API**: Familiar `initialize` and `listen` pattern with 12+ lifecycle callbacks.
* **Continuous Pro API**: Managed session with auto-restart, segment stitching, and smart punctuation.
* **Deep Metadata**: Access to per-word confidence, timestamps, and alternatives.
* **Session Analytics**: Real-time Words Per Minute (WPM), Duration, and total word count.
* **Android Zero-Beep**: Automatic audio management to mute system sounds during restarts.
* **Language Support**: Query and select from all supported locales on the device.
* Production-ready architecture with clean separation of concerns.
