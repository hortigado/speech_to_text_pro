import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'speech_to_text_pro_platform_interface.dart';

/// An implementation of [SpeechToTextProPlatform] that uses method channels.
class MethodChannelSpeechToTextPro extends SpeechToTextProPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('speech_to_text_pro');

  /// The event channel used to receive events from the native platform.
  @visibleForTesting
  final eventChannel = const EventChannel('speech_to_text_pro_events');

  late final Stream<Map<dynamic, dynamic>> _eventStream = eventChannel
      .receiveBroadcastStream()
      .cast<Map<dynamic, dynamic>>();

  @override
  Future<bool> initialize() async {
    final result = await methodChannel.invokeMethod<bool>('initialize');
    return result ?? false;
  }

  @override
  Future<bool> hasPermission() async {
    final result = await methodChannel.invokeMethod<bool>('hasPermission');
    return result ?? false;
  }

  @override
  Future<void> start({String localeId = 'en-US', bool continuous = false}) async {
    await methodChannel.invokeMethod('start', {'localeId': localeId, 'continuous': continuous});
  }

  @override
  Future<void> cancel() async {
    await methodChannel.invokeMethod('cancel');
  }

  @override
  Future<void> stop() async {
    await methodChannel.invokeMethod('stop');
  }

  @override
  Future<void> pause() async {
    await methodChannel.invokeMethod('pause');
  }

  @override
  Future<void> resume() async {
    await methodChannel.invokeMethod('resume');
  }

  @override
  Future<List<String>> getLocales() async {
    final locales = await methodChannel.invokeListMethod<String>('getLocales');
    return locales ?? [];
  }

  @override
  Stream<String> get onPartialResults => _eventStream
      .where((event) => event['type'] == 'partialResults')
      .map((event) => event['text'] as String);

  @override
  Stream<String> get onFinalResults => _eventStream
      .where((event) => event['type'] == 'finalResults')
      .map((event) => event['text'] as String);

  @override
  Stream<bool> get onListeningStateChanged => _eventStream
      .where((event) => event['type'] == 'listeningStateChanged')
      .map((event) => event['isListening'] as bool);

  @override
  Stream<void> get onSpeechStarted => _eventStream
      .where((event) => event['type'] == 'speechStarted')
      .map((_) {});

  @override
  Stream<void> get onSpeechEnded => _eventStream
      .where((event) => event['type'] == 'speechEnded')
      .map((_) {});

  @override
  Stream<double> get onVolumeChanged => _eventStream
      .where((event) => event['type'] == 'volumeChanged')
      .map((event) => (event['volume'] as num).toDouble());

  @override
  Stream<String> get onError => _eventStream
      .where((event) => event['type'] == 'error')
      .map((event) => event['message'] as String);
}
