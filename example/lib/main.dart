import 'package:flutter/material.dart';
import 'dart:async';
import 'package:speech_to_text_pro/speech_to_text_pro.dart';

void main() {
  runApp(const MaterialApp(
    home: SpeechExampleApp(),
    debugShowCheckedModeBanner: false,
  ));
}

class SpeechExampleApp extends StatefulWidget {
  const SpeechExampleApp({super.key});

  @override
  State<SpeechExampleApp> createState() => _SpeechExampleAppState();
}

class _SpeechExampleAppState extends State<SpeechExampleApp> with SingleTickerProviderStateMixin {
  late TabController _tabController;
  final _plugin = SpeechToTextPro();
  
  // State for both modes
  List<String> _availableLocales = [];
  String _selectedLocale = 'en-US';
  bool _isListening = false;
  double _volume = 0.0;
  String _status = 'Idle';

  // Standard Mode State
  String _standardResult = '';
  double _standardConfidence = 0.0;

  // Pro Mode State
  String _finalizedText = '';
  String _partialText = '';
  double _wpm = 0.0;
  int _totalWords = 0;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 2, vsync: this);
    _loadLocales();
    
    // Global Listeners
    _plugin.onListeningStateChanged.listen((listening) {
      setState(() {
        _isListening = listening;
        _status = listening ? 'Listening' : 'Idle';
      });
    });

    _plugin.onVolumeChanged.listen((volume) {
      setState(() => _volume = volume);
    });

    _plugin.onError.listen((error) {
      setState(() => _status = 'Error: $error');
    });
  }

  Future<void> _loadLocales() async {
    final available = await _plugin.initialize();
    if (available) {
      final locales = await _plugin.getLocales();
      setState(() {
        _availableLocales = locales;
        if (locales.contains('en-US')) _selectedLocale = 'en-US';
      });
    }
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  // --- Standard Mode UI ---
  Widget _buildStandardTab() {
    return Column(
      children: [
        const Padding(
          padding: EdgeInsets.all(16.0),
          child: Text(
            'Standard Mode is ideal for short commands or single replies. It stops automatically after a pause.',
            style: TextStyle(fontStyle: FontStyle.italic, color: Colors.grey),
          ),
        ),
        Expanded(
          child: Center(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(_standardResult.isEmpty ? 'Tap mic and speak' : _standardResult,
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 20)),
                if (_standardConfidence > 0)
                  Text('Confidence: ${(_standardConfidence * 100).toStringAsFixed(1)}%',
                      style: const TextStyle(color: Colors.deepPurple)),
              ],
            ),
          ),
        ),
        _buildMicButton(() {
          if (_isListening) {
            _plugin.stop();
          } else {
            setState(() => _standardResult = '');
            _plugin.listen(
              localeId: _selectedLocale,
              onDevice: true,
              onResult: (result) {
                setState(() {
                  _standardResult = result.text;
                  _standardConfidence = result.confidence;
                });
              },
            );
          }
        }),
      ],
    );
  }

  // --- Pro Mode UI ---
  Widget _buildProTab() {
    return Column(
      children: [
        const Padding(
          padding: EdgeInsets.all(16.0),
          child: Text(
            'Pro Mode is for infinite dictation. It stitches sentences, handles auto-restarts, and gives live analytics.',
            style: TextStyle(fontStyle: FontStyle.italic, color: Colors.grey),
          ),
        ),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceEvenly,
          children: [
            _buildStatCard('WPM', _wpm.toStringAsFixed(0)),
            _buildStatCard('Words', _totalWords.toString()),
          ],
        ),
        Expanded(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: RichText(
              text: TextSpan(
                style: const TextStyle(color: Colors.black, fontSize: 18),
                children: [
                  TextSpan(text: _finalizedText),
                  TextSpan(
                    text: _partialText.isNotEmpty ? ' $_partialText' : '',
                    style: TextStyle(color: Colors.deepPurple.withOpacity(0.4)),
                  ),
                ],
              ),
            ),
          ),
        ),
        _buildMicButton(() {
          if (_isListening) {
            _plugin.stop();
          } else {
            setState(() {
              _finalizedText = '';
              _partialText = '';
            });
            // Using Pro Stream
            _plugin.onTranscriptUpdate.listen((transcript) {
              setState(() {
                _finalizedText = transcript.finalizedText;
                _partialText = transcript.partialText;
                _wpm = transcript.analytics.wordsPerMinute;
                _totalWords = transcript.analytics.totalWords;
              });
            });
            _plugin.start(localeId: _selectedLocale, onDevice: true);
          }
        }),
      ],
    );
  }

  Widget _buildStatCard(String label, String value) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        child: Column(
          children: [
            Text(label, style: const TextStyle(fontSize: 12, color: Colors.grey)),
            Text(value, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
          ],
        ),
      ),
    );
  }

  Widget _buildMicButton(VoidCallback onPressed) {
    return Padding(
      padding: const EdgeInsets.all(32.0),
      child: Column(
        children: [
          LinearProgressIndicator(value: _volume),
          const SizedBox(height: 16),
          FloatingActionButton.large(
            onPressed: onPressed,
            backgroundColor: _isListening ? Colors.red : Colors.deepPurple,
            child: Icon(_isListening ? Icons.stop : Icons.mic, color: Colors.white),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Speech To Text Pro'),
        bottom: TabBar(
          controller: _tabController,
          tabs: const [
            Tab(text: 'Standard Mode', icon: Icon(Icons.flash_on)),
            Tab(text: 'Continuous Pro', icon: Icon(Icons.all_inclusive)),
          ],
        ),
        actions: [
          if (_availableLocales.isNotEmpty)
            DropdownButton<String>(
              value: _selectedLocale,
              underline: const SizedBox(),
              icon: const Icon(Icons.language, color: Colors.black),
              onChanged: (val) => setState(() => _selectedLocale = val!),
              items: _availableLocales.map((l) => DropdownMenuItem(value: l, child: Text(l))).toList(),
            ),
          const SizedBox(width: 16),
        ],
      ),
      body: TabBarView(
        controller: _tabController,
        children: [
          _buildStandardTab(),
          _buildProTab(),
        ],
      ),
    );
  }
}
