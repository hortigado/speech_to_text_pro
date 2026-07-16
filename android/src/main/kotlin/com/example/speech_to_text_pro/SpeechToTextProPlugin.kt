package com.example.speech_to_text_pro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.content.Context
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry

class SpeechToTextProPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, PluginRegistry.RequestPermissionsResultListener {
    private lateinit var channel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private var eventSink: EventChannel.EventSink? = null
    
    private var activity: Activity? = null
    private var audioManager: AudioManager? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    
    private var isListening = false
    private var shouldBeListening = false
    private var isContinuous = false
    private var isPaused = false
    private var currentLocale = "en-US"

    private val RECORD_AUDIO_REQUEST_CODE = 101

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "speech_to_text_pro")
        channel.setMethodCallHandler(this)
        
        eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "speech_to_text_pro_events")
        eventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                eventSink = events
            }

            override fun onCancel(arguments: Any?) {
                eventSink = null
            }
        })
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "initialize" -> {
                result.success(SpeechRecognizer.isRecognitionAvailable(activity as android.content.Context))
            }
            "hasPermission" -> {
                result.success(checkPermission())
            }
            "start" -> {
                val locale = call.argument<String>("localeId") ?: "en-US"
                val continuous = call.argument<Boolean>("continuous") ?: false
                startListening(locale, continuous)
                result.success(null)
            }
            "stop" -> {
                stopListening()
                result.success(null)
            }
            "cancel" -> {
                cancelListening()
                result.success(null)
            }
            "pause" -> {
                pauseListening()
                result.success(null)
            }
            "resume" -> {
                resumeListening()
                result.success(null)
            }
            "getLocales" -> {
                getLocales(result)
            }
            else -> result.notImplemented()
        }
    }

    private fun getLocales(result: Result) {
        val intent = Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS)
        activity?.sendOrderedBroadcast(intent, null, object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val languages = getResultExtras(true).getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES)
                result.success(languages ?: listOf("en-US"))
            }
        }, null, Activity.RESULT_OK, null, null)
    }

    private fun startListening(locale: String, continuous: Boolean) {
        currentLocale = locale
        shouldBeListening = true
        isContinuous = continuous
        isPaused = false
        
        if (checkPermission()) {
            initRecognizer()
            if (isContinuous) {
                muteAudio()
            } else {
                unmuteAudio() // Explicitly unmute for Standard Mode to hear native beeps
            }
            activity?.runOnUiThread {
                speechRecognizer?.startListening(recognizerIntent)
            }
        } else {
            requestPermission()
        }
    }

    private fun stopListening() {
        shouldBeListening = false
        isPaused = false
        unmuteAudio() // Always unmute on stop to restore system sounds
        activity?.runOnUiThread {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
            updateListeningState(false)
        }
    }

    private fun cancelListening() {
        shouldBeListening = false
        isPaused = false
        unmuteAudio() // Always unmute on cancel
        activity?.runOnUiThread {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
            updateListeningState(false)
        }
    }
    
    private fun pauseListening() {
        isPaused = true
        activity?.runOnUiThread {
            speechRecognizer?.stopListening()
            updateListeningState(false)
        }
    }
    
    private fun resumeListening() {
        if (shouldBeListening) {
            isPaused = false
            activity?.runOnUiThread {
                speechRecognizer?.startListening(recognizerIntent)
            }
        }
    }

    private fun muteAudio() {
        ensureAudioManager()
        audioManager?.let {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                it.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
                it.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_MUTE, 0)
                it.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                it.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
            } else {
                it.setStreamMute(AudioManager.STREAM_NOTIFICATION, true)
                it.setStreamMute(AudioManager.STREAM_ALARM, true)
                it.setStreamMute(AudioManager.STREAM_MUSIC, true)
                it.setStreamMute(AudioManager.STREAM_SYSTEM, true)
            }
        }
    }

    private fun unmuteAudio() {
        ensureAudioManager()
        audioManager?.let {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                it.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                it.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_UNMUTE, 0)
                it.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                it.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
            } else {
                it.setStreamMute(AudioManager.STREAM_NOTIFICATION, false)
                it.setStreamMute(AudioManager.STREAM_ALARM, false)
                it.setStreamMute(AudioManager.STREAM_MUSIC, false)
                it.setStreamMute(AudioManager.STREAM_SYSTEM, false)
            }
        }
    }

    private fun ensureAudioManager() {
        if (audioManager == null) {
            audioManager = activity?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }
    }

    private fun initRecognizer() {
        if (speechRecognizer != null) return
        
        activity?.runOnUiThread {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity)
            speechRecognizer?.setRecognitionListener(createRecognitionListener())
            
            recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLocale)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
        }
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            updateListeningState(true)
        }

        override fun onBeginningOfSpeech() {
            sendEvent("speechStarted", null)
        }

        override fun onRmsChanged(rmsdB: Float) {
            val normalizedVolume = (rmsdB + 2f) / 12f
            sendEvent("volumeChanged", mapOf("volume" to normalizedVolume.coerceIn(0f, 1f)))
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            sendEvent("speechEnded", null)
        }

        override fun onError(error: Int) {
            val errorMessage = getErrorText(error)
            
            val shouldRestart = when (error) {
                SpeechRecognizer.ERROR_AUDIO,
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> true
                else -> false
            }

            if (isContinuous && shouldRestart && shouldBeListening && !isPaused) {
                restartListening()
            } else {
                updateListeningState(false)
                sendEvent("error", mapOf("message" to errorMessage))
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                sendEvent("finalResults", mapOf("text" to matches[0]))
            }
            
            if (isContinuous && shouldBeListening && !isPaused) {
                restartListening()
            } else {
                updateListeningState(false)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                sendEvent("partialResults", mapOf("text" to matches[0]))
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun restartListening() {
        activity?.runOnUiThread {
            speechRecognizer?.cancel()
            speechRecognizer?.startListening(recognizerIntent)
        }
    }

    private fun updateListeningState(listening: Boolean) {
        if (isListening != listening) {
            isListening = listening
            sendEvent("listeningStateChanged", mapOf("isListening" to isListening))
        }
    }

    private fun sendEvent(type: String, data: Map<String, Any?>?) {
        val event = mutableMapOf<String, Any?>("type" to type)
        data?.let { event.putAll(it) }
        activity?.runOnUiThread {
            eventSink?.success(event)
        }
    }

    private fun getErrorText(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RecognitionService busy"
            SpeechRecognizer.ERROR_SERVER -> "Error from server"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Unknown error"
        }
    }

    private fun checkPermission(): Boolean {
        return activity?.let {
            ContextCompat.checkSelfPermission(it, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        } ?: false
    }

    private fun requestPermission() {
        activity?.let {
            ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        if (requestCode == RECORD_AUDIO_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (shouldBeListening) {
                    startListening(currentLocale, isContinuous)
                }
            } else {
                sendEvent("error", mapOf("message" to "Permission denied"))
            }
            return true
        }
        return false
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        unmuteAudio()
        speechRecognizer?.destroy()
    }
}
