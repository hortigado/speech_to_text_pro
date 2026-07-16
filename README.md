# speech_to_text_pro

A production-ready, high-performance Flutter plugin for speech-to-text recognition on Android, iOS, macOS, and Web. This plugin is designed for developers who need more than just basic voice commands—it offers **Infinite Continuous Listening**, **Smart Transcription Sticking**, and **Deep Metadata** for AI-driven applications.

---

## 🚀 Comparison: Which Mode to Use?

| Feature | Standard API (`listen`) | Continuous Pro (`start`) |
| :--- | :--- | :--- |
| **Primary Use Case** | Voice Commands, Search, Quick Replies | Long-form Dictation, Meetings, AI Assistants |
| **Session Control** | Manual / Auto-Stop on Silence | **Infinite Loop (Auto-Restart)** |
| **Result Management** | Individual Result Objects | **Deep Session History & Analytics** |
| **Formatting** | Raw Transcription | **Advanced Smart Punctuation** |
| **Analytics** | Per-segment confidence | **Real-time WPM, Duration, Metrics** |

---

## 🏁 0. Initialization

Before using either mode, you must initialize the plugin. This checks for hardware availability and sets up global status/error listeners.

```dart
final _plugin = SpeechToTextPro();

bool isAvailable = await _plugin.initialize(
  onStatus: (status) => print('Engine Status: $status'),
  onError: (error) => print('Engine Error: $error'),
);

if (isAvailable) {
  // Now you can call listen() or start()
}
```

### 🔐 Permission Check
You can explicitly check for permissions at any time:
```dart
bool hasPermission = await _plugin.hasPermission();
```
*Note: The `listen()` and `start()` methods also handle permission requests internally.*

## 🔄 Lifecycle Best Practices

For the best experience, manage the plugin within your widget's lifecycle.

### 1. Initialize in `initState`
Always initialize the plugin and set up your listeners (especially for Pro mode streams) inside `initState`.

```dart
StreamSubscription? _subscription;

@override
void initState() {
  super.initState();
  _initSpeech();
}

void _initSpeech() async {
  bool available = await _plugin.initialize();
  if (available) {
    // For Pro Mode, subscribe to the stream here
    _subscription = _plugin.onTranscriptUpdate.listen((transcript) {
      setState(() => _text = transcript.fullText);
    });
  }
}
```

### 2. Cleanup in `dispose`
To prevent memory leaks and ensure the microphone is released, always call `stop()` or `cancel()` and cancel any stream subscriptions in your `dispose` method.

```dart
@override
void dispose() {
  _subscription?.cancel();
  _plugin.stop(); // Ensures mic is closed
  super.dispose();
}
```

---

## 🛠 1. Standard Mode (Deep Control)

Perfect for apps that need precise feedback on exactly what the microphone is doing for short-form input.

### Usage
```dart
_plugin.listen(
  onResult: (SpeechResult result) {
    print('Recognized: ${result.text}');
    print('Confidence: ${result.confidence}');
    print('Final: ${result.isFinal}');
  },
  onVolumeChanged: (level) => print('Volume: $level'),
  onSpeechStart: () => print('Speech started'),
  onSpeechEnd: () => print('Speech ended'),
  onSpeaking: () => print('User is speaking...'),
  onSilence: () => print('Silence detected'),
  onNoiseDetected: () => print('Noise detected'),
  onMicOpened: () => print('Mic opened'),
  onMicClosed: () => print('Mic closed'),
  onError: (err) => print('Error: $err'),
  onPermissionDenied: () => print('Permission denied'),
  listenFor: Duration(seconds: 30),
  pauseFor: Duration(seconds: 3),
);
```

### `SpeechResult` (AI Ready)
*   **`text`**: The actual recognized string (live or final).
*   **`confidence`**: Score between **0.0 and 1.0**.
*   **`isFinal`**: `false` for partial results, `true` for finalized sentences.
*   **`words`**: List of `SpeechWord` objects with per-word metadata.
*   **`speakingDuration`**: Actual active talking time.
*   **`pauseCount`**: Number of pauses detected during the segment.

---

## 💎 2. Continuous Pro Mode (The Intelligence Engine)

This is the **"Next Level"** of speech recognition. It transforms raw voice data into a professional, structured transcript with real-time session analytics.

### The `onTranscriptUpdate` Managed Stream
Subscribe to this stream to receive a high-level `SpeechTranscript` object that maintains the state of the entire conversation.

```dart
_plugin.onTranscriptUpdate.listen((transcript) {
  // 1. Text Content
  print(transcript.finalizedText); // Formatted historical text
  print(transcript.partialText);   // Real-time "ghost" words
  print(transcript.fullText);      // The unified coherent paragraph

  // 2. Real-time Analytics (Pro Metrics)
  print('Words Per Minute: ${transcript.analytics.wordsPerMinute}');
  print('Total Session Time: ${transcript.analytics.totalDuration}');
  print('Average Confidence: ${transcript.analytics.averageConfidence}');
  print('Total Word Count: ${transcript.analytics.totalWords}');

  // 3. Segment History
  print('Total Segments Processed: ${transcript.segments.length}');
});

// Start the intelligence engine
await _plugin.start(localeId: 'en-US');
```

### Advanced Pro Capabilities
1.  **Smart Transcription Sticking**: Seamlessly joins segments into a single coherent story. It remembers context across automatic restarts.
2.  **Advanced Smart Punctuation**: 
    - Automatically **capitalizes** the start of sentences.
    - Intelligently adds **periods (`.`)** based on natural speech boundaries.
3.  **Real-time Analytics Engine**: Calculates WPM (Words Per Minute) and session-wide confidence on the fly.
4.  **Segment History Buffer**: Keeps a chronological list of every `SpeechResult` processed in the session for post-processing or audit trails.
5.  **Infinite Continuity**: Bypasses all native system limits. The mic stays active until you explicitly call `stop()`.

---

## 🎮 Session Control

Regardless of which mode you use, you can control the active session using these methods:

### Stop (Finish Session)
Stops the microphone and the recognition engine. In **Pro mode**, this also finalizes the transcription history and sends the final update to the `onTranscriptUpdate` stream. Use this when the user is completely done.
```dart
await _plugin.stop();
```

### Pause & Resume
Temporarily "freezes" the auto-restart logic. The microphone is released, and the engine stops processing new speech. **Important:** The session context (like stitched text in Pro mode) is preserved.
*   **Pause**: Stops active listening but keeps the session "alive" in memory.
*   **Resume**: Instantly restarts the engine and continues appending to the existing transcript.

```dart
await _plugin.pause();
// ... later ...
await _plugin.resume();
```

### Cancel (Discard)
Immediately stops the microphone and discards any partial or unfinalized results from the current segment. Unlike `stop()`, it does not perform final processing on the last heard words.
```dart
await _plugin.cancel();
```

---

## 📦 Installation & Setup

### 1. Add Dependency
Add `speech_to_text_pro` to your `pubspec.yaml` file:

```yaml
dependencies:
  speech_to_text_pro: ^0.0.1
```

Or run this command in your terminal:
```bash
flutter pub add speech_to_text_pro
```

### 2. Import the Library
```dart
import 'package:speech_to_text_pro/speech_to_text_pro.dart';
```

### 3. Android Setup
Add the following permission to your `android/app/src/main/AndroidManifest.xml` inside the `<manifest>` tag:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

If you targeting Android 11 (API level 30) or higher, you may also need to add the following to your `AndroidManifest.xml` to allow the app to interact with the speech recognition service:

```xml
<queries>
    <intent>
        <action android:name="android.speech.RecognitionService" />
    </intent>
</queries>
```

*Zero-Beep Logic: The plugin automatically mutes system restart sounds.*

### 4. iOS Setup
Add the following keys to your `ios/Runner/Info.plist` file:

```xml
<key>NSSpeechRecognitionUsageDescription</key>
<string>We use speech recognition to convert your voice to text.</string>
<key>NSMicrophoneUsageDescription</key>
<string>We need access to your microphone for transcription.</string>
```

### 5. macOS Setup
Add the same keys as iOS to your `macos/Runner/Info.plist`. Also, ensure you have enabled the **Microphone** and **Speech Recognition** entitlements in your project's App Sandbox settings in Xcode.

---

## 💻 Platform Support

| Platform | Support Status | Native Engine |
| :--- | :--- | :--- |
| **Android** | ✅ Fully Supported | `SpeechRecognizer` |
| **iOS** | ✅ Fully Supported | `SFSpeechRecognizer` |
| **macOS** | ✅ Fully Supported | `SFSpeechRecognizer` |
| **Web** | ✅ Fully Supported | `Web Speech API` |
| **Windows** | 🏗️ Planned | `Windows.Media.SpeechRecognition` |
| **Linux** | 🏗️ Planned | `System ASR` |

---

## 📜 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

