import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'speech_to_text_pro_method_channel.dart';

abstract class SpeechToTextProPlatform extends PlatformInterface {
  /// Constructs a SpeechToTextProPlatform.
  SpeechToTextProPlatform() : super(token: _token);

  static final Object _token = Object();

  static SpeechToTextProPlatform _instance = MethodChannelSpeechToTextPro();

  /// The default instance of [SpeechToTextProPlatform] to use.
  ///
  /// Defaults to [MethodChannelSpeechToTextPro].
  static SpeechToTextProPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [SpeechToTextProPlatform] when
  /// they register themselves.
  static set instance(SpeechToTextProPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<bool> initialize() {
    throw UnimplementedError('initialize() has not been implemented.');
  }

  Future<bool> hasPermission() {
    throw UnimplementedError('hasPermission() has not been implemented.');
  }

  Future<void> start({String localeId = 'en-US', bool continuous = false}) {
    throw UnimplementedError('start() has not been implemented.');
  }

  Future<void> cancel() {
    throw UnimplementedError('cancel() has not been implemented.');
  }

  Future<void> stop() {
    throw UnimplementedError('stop() has not been implemented.');
  }

  Future<void> pause() {
    throw UnimplementedError('pause() has not been implemented.');
  }

  Future<void> resume() {
    throw UnimplementedError('resume() has not been implemented.');
  }

  Future<List<String>> getLocales() {
    throw UnimplementedError('getLocales() has not been implemented.');
  }

  Stream<String> get onPartialResults {
    throw UnimplementedError('onPartialResults has not been implemented.');
  }

  Stream<String> get onFinalResults {
    throw UnimplementedError('onFinalResults has not been implemented.');
  }

  Stream<bool> get onListeningStateChanged {
    throw UnimplementedError('onListeningStateChanged has not been implemented.');
  }

  Stream<void> get onSpeechStarted {
    throw UnimplementedError('onSpeechStarted has not been implemented.');
  }

  Stream<void> get onSpeechEnded {
    throw UnimplementedError('onSpeechEnded has not been implemented.');
  }

  Stream<double> get onVolumeChanged {
    throw UnimplementedError('onVolumeChanged has not been implemented.');
  }

  Stream<String> get onError {
    throw UnimplementedError('onError has not been implemented.');
  }
}
