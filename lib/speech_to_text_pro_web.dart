import 'dart:async';
import 'package:flutter_web_plugins/flutter_web_plugins.dart';
import 'dart:js_interop';

import 'speech_to_text_pro_platform_interface.dart';

@JS('webkitSpeechRecognition')
external JSAny get _webkitSpeechRecognition;

@JS('SpeechRecognition')
external JSAny get _speechRecognition;

/// A web implementation of the SpeechToTextProPlatform of the SpeechToTextPro plugin.
class SpeechToTextProWeb extends SpeechToTextProPlatform {
  final _partialController = StreamController<String>.broadcast();
  final _finalController = StreamController<String>.broadcast();
  final _volumeController = StreamController<double>.broadcast();
  final _listeningController = StreamController<bool>.broadcast();
  final _errorController = StreamController<String>.broadcast();
  
  SpeechToTextProWeb();

  static void registerWith(Registrar registrar) {
    SpeechToTextProPlatform.instance = SpeechToTextProWeb();
  }

  @override
  Future<bool> initialize() async {
    try {
      final recognitionClass = _getRecognitionClass();
      if (recognitionClass == null) return false;
      
      _initRecognition();
      return true;
    } catch (e) {
      return false;
    }
  }
  
  dynamic _getRecognitionClass() {
    try {
      return _speechRecognition;
    } catch (_) {
      try {
        return _webkitSpeechRecognition;
      } catch (_) {
        return null;
      }
    }
  }

  void _initRecognition() {
    // In a real implementation, we would use package:web's SpeechRecognition if available
    // or JS interop to create the object.
    // For now, we provide the infrastructure.
  }

  @override
  Future<void> start({String localeId = 'en-US', bool continuous = false}) async {
    _listeningController.add(true);
    // Start Web Speech API logic here
  }

  @override
  Future<void> stop() async {
    _listeningController.add(false);
  }

  @override
  Stream<String> get onPartialResults => _partialController.stream;

  @override
  Stream<String> get onFinalResults => _finalController.stream;

  @override
  Stream<double> get onVolumeChanged => _volumeController.stream;

  @override
  Stream<bool> get onListeningStateChanged => _listeningController.stream;

  @override
  Stream<String> get onError => _errorController.stream;
}
