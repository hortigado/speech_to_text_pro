import FlutterMacOS
import Foundation
import Speech
import AVFoundation

public class SpeechToTextProPlugin: NSObject, FlutterPlugin, SFSpeechRecognizerDelegate {
  private var channel: FlutterMethodChannel?
  private var eventChannel: FlutterEventChannel?
  private var eventSink: FlutterEventSink?

  private var speechRecognizer: SFSpeechRecognizer?
  private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
  private var recognitionTask: SFSpeechRecognitionTask?
  private let audioEngine = AVAudioEngine()

  private var isListening = false
  private var shouldBeListening = false
  private var isContinuous = false
  private var isPaused = false
  private var currentLocale = "en-US"

  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: "speech_to_text_pro", binaryMessenger: registrar.messenger)
    let eventChannel = FlutterEventChannel(name: "speech_to_text_pro_events", binaryMessenger: registrar.messenger)

    let instance = SpeechToTextProPlugin()
    instance.channel = channel
    instance.eventChannel = eventChannel

    registrar.addMethodCallDelegate(instance, channel: channel)
    eventChannel.setStreamHandler(instance)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "initialize":
      result(SFSpeechRecognizer.authorizationStatus() == .authorized || SFSpeechRecognizer.authorizationStatus() == .notDetermined)
    case "hasPermission":
      let speechAuthorized = SFSpeechRecognizer.authorizationStatus() == .authorized
      // On macOS, microphone permission is handled via entitlements
      result(speechAuthorized)
    case "start":
      let args = call.arguments as? [String: Any]
      let locale = args?["localeId"] as? String ?? "en-US"
      let continuous = args?["continuous"] as? Bool ?? false
      startListening(locale: locale, continuous: continuous)
      result(nil)
    case "stop":
      stopListening()
      result(nil)
    case "cancel":
      cancelListening()
      result(nil)
    case "pause":
      pauseListening()
      result(nil)
    case "resume":
      resumeListening()
      result(nil)
    case "getLocales":
      let locales = SFSpeechRecognizer.supportedLocales()
      let localeIdentifiers = locales.map { $0.identifier }
      result(localeIdentifiers)
    default:
      result(FlutterMethodNotImplemented)
    }
  }

  private func startListening(locale: String, continuous: Bool) {
    currentLocale = locale
    shouldBeListening = true
    isContinuous = continuous
    isPaused = false

    SFSpeechRecognizer.requestAuthorization { authStatus in
      DispatchQueue.main.async {
        switch authStatus {
        case .authorized:
          self.doStartListening()
        case .denied, .restricted, .notDetermined:
          self.sendEvent(type: "error", data: ["message": "Speech recognition authorization denied"])
        @unknown default:
          break
        }
      }
    }
  }

  private func doStartListening() {
    guard !audioEngine.isRunning else { return }

    do {
      speechRecognizer = SFSpeechRecognizer(locale: Locale(identifier: currentLocale))
      speechRecognizer?.delegate = self
      try startAudioEngine()
      updateListeningState(true)
    } catch {
      sendEvent(type: "error", data: ["message": "Failed to start audio engine: \(error.localizedDescription)"])
    }
  }

  private func startAudioEngine() throws {
    recognitionTask?.cancel()
    recognitionTask = nil

    recognitionRequest = SFSpeechAudioBufferRecognitionRequest()
    guard let recognitionRequest = recognitionRequest else { return }
    recognitionRequest.shouldReportPartialResults = true

    let inputNode = audioEngine.inputNode
    let recordingFormat = inputNode.outputFormat(forBus: 0)

    inputNode.installTap(onBus: 0, bufferSize: 1024, format: recordingFormat) { (buffer, when) in
      self.recognitionRequest?.append(buffer)
      self.calculateVolume(buffer: buffer)
    }

    audioEngine.prepare()
    try audioEngine.start()

    recognitionTask = speechRecognizer?.recognitionTask(with: recognitionRequest) { result, error in
      var isFinal = false

      if let result = result {
        isFinal = result.isFinal
        if isFinal {
          self.sendEvent(type: "finalResults", data: ["text": result.bestTranscription.formattedString])
        } else {
          self.sendEvent(type: "partialResults", data: ["text": result.bestTranscription.formattedString])
        }
      }

      if error != nil || isFinal {
        self.audioEngine.stop()
        inputNode.removeTap(onBus: 0)
        self.recognitionRequest = nil
        self.recognitionTask = nil

        if self.isContinuous && self.shouldBeListening && !self.isPaused {
          self.restartListening()
        } else {
          self.updateListeningState(false)
          if let error = error {
             self.sendEvent(type: "error", data: ["message": error.localizedDescription])
          }
        }
      }
    }

    sendEvent(type: "speechStarted", data: nil)
  }

  private func restartListening() {
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
        if self.shouldBeListening && !self.isPaused {
            self.doStartListening()
        }
    }
  }

  private func stopListening() {
    shouldBeListening = false
    isPaused = false
    audioEngine.stop()
    audioEngine.inputNode.removeTap(onBus: 0)
    recognitionRequest?.endAudio()
    recognitionTask?.cancel()
    updateListeningState(false)
  }

  private func cancelListening() {
    shouldBeListening = false
    isPaused = false
    audioEngine.stop()
    audioEngine.inputNode.removeTap(onBus: 0)
    recognitionRequest = nil
    recognitionTask?.cancel()
    recognitionTask = nil
    updateListeningState(false)
  }

  private func pauseListening() {
    isPaused = true
    audioEngine.stop()
    audioEngine.inputNode.removeTap(onBus: 0)
    updateListeningState(false)
  }

  private func resumeListening() {
    if shouldBeListening {
      isPaused = false
      doStartListening()
    }
  }

  private func updateListeningState(_ listening: Bool) {
    if isListening != listening {
      isListening = listening
      sendEvent(type: "listeningStateChanged", data: ["isListening": isListening])
    }
  }

  private func sendEvent(type: String, data: [String: Any]?) {
    var event: [String: Any] = ["type": type]
    if let data = data {
      for (key, value) in data {
        event[key] = value
      }
    }
    eventSink?(event)
  }

  private func calculateVolume(buffer: AVAudioPCMBuffer) {
    guard let channelData = buffer.floatChannelData?[0] else { return }
    let channelDataArray = Array(UnsafeBufferPointer(start: channelData, count: Int(buffer.frameLength)))

    var rms: Float = 0.0
    if !channelDataArray.isEmpty {
      for value in channelDataArray {
        rms += value * value
      }
      rms = sqrt(rms / Float(channelDataArray.count))
    }

    let volume = min(1.0, max(0.0, rms * 5.0))
    sendEvent(type: "volumeChanged", data: ["volume": volume])
  }
}

extension SpeechToTextProPlugin: FlutterStreamHandler {
  public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
    self.eventSink = events
    return nil
  }

  public func onCancel(withArguments arguments: Any?) -> FlutterError? {
    self.eventSink = nil
    return nil
  }
}
