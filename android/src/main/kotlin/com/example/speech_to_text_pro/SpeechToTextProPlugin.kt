package com.example.speech_to_text_pro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.NonNull
import androidx.annotation.RequiresApi
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
    private var onDevice = false
    // The API 33+ on-device recognizer depends on a system service that many
    // non-Pixel devices declare but cannot bind. Once it fails we stay on the
    // regular recognizer (asked to prefer offline), and the failure is
    // persisted (see markOnDeviceFailed) so later launches skip the wait.
    private var usingOnDeviceRecognizer = false
    private var onDeviceRecognizerFailed = false
    private var recognizerReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val onDeviceWatchdog = Runnable { fallbackToStandardRecognizer() }
    private val unmuteRunnable = Runnable { unmuteAudio() }
    private var currentLocale = "en-US"

    private val TAG = "SpeechToTextPro"
    private val RECORD_AUDIO_REQUEST_CODE = 101
    // A failed bind of the on-device service is only logged by the framework
    // ("Bind to system recognition service failed with error 10"), so this
    // watchdog is what the user actually waits for before the mic opens.
    private val ON_DEVICE_READY_TIMEOUT_MS = 2000L
    private val PREFS_NAME = "speech_to_text_pro"
    private val PREF_ON_DEVICE_FAILED_AT = "on_device_failed_at"
    // Retry the on-device recognizer after this long in case the failure was
    // transient (service busy, device just booted, ...).
    private val ON_DEVICE_FAILURE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private val SUPPORT_ERROR_GRACE_MS = 2000L
    // The recognizer plays its end-of-listening sound while it stops, so the
    // streams must stay muted a little longer than the stop call itself.
    private val UNMUTE_DELAY_MS = 600L

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
                val onDevice = call.argument<Boolean>("onDevice") ?: false
                startListening(locale, continuous, onDevice)
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
            "getOnDeviceLocales" -> {
                getOnDeviceLocales(result)
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

    /**
     * Languages the recognition service reports for offline use (API 33+).
     * Completes with null when the platform cannot answer.
     */
    private fun getOnDeviceLocales(result: Result) {
        val context = activity
        if (context == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            result.success(null)
            return
        }
        context.runOnUiThread { checkRecognitionSupport(context, result) }
    }

    @RequiresApi(android.os.Build.VERSION_CODES.TIRAMISU)
    private fun checkRecognitionSupport(context: Context, result: Result) {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        }
        var replied = false
        var lastError = 0
        var errorReply: Runnable? = null

        // The channel accepts a single reply, and some services report an error
        // and then still deliver the result, so only the first outcome counts.
        fun reply(block: () -> Unit) {
            if (replied) return
            replied = true
            errorReply?.let { mainHandler.removeCallbacks(it) }
            recognizer.destroy()
            block()
        }
        errorReply = Runnable {
            reply { result.error("recognition_support_error", getErrorText(lastError), lastError) }
        }

        recognizer.checkRecognitionSupport(intent, ContextCompat.getMainExecutor(context), object : RecognitionSupportCallback {
            override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                val support = mapOf(
                    "installed" to recognitionSupport.installedOnDeviceLanguages,
                    "pending" to recognitionSupport.pendingOnDeviceLanguages,
                    "supported" to recognitionSupport.supportedOnDeviceLanguages,
                    "online" to recognitionSupport.onlineLanguages
                )
                Log.i(TAG, "checkRecognitionSupport: $support")
                reply { result.success(support) }
            }

            override fun onError(error: Int) {
                Log.w(TAG, "checkRecognitionSupport failed: ${getErrorText(error)} ($error)")
                lastError = error
                errorReply?.let {
                    mainHandler.removeCallbacks(it)
                    mainHandler.postDelayed(it, SUPPORT_ERROR_GRACE_MS)
                }
            }
        })
    }

    private fun startListening(locale: String, continuous: Boolean, onDevice: Boolean) {
        currentLocale = locale
        this.onDevice = onDevice
        shouldBeListening = true
        isContinuous = continuous
        isPaused = false
        
        if (checkPermission()) {
            mainHandler.removeCallbacks(unmuteRunnable)
            initRecognizer()
            if (isContinuous) {
                muteAudio()
            } else {
                unmuteAudio() // Explicitly unmute for Standard Mode to hear native beeps
            }
            activity?.runOnUiThread { beginRecognition() }
        } else {
            requestPermission()
        }
    }

    private fun stopListening() {
        shouldBeListening = false
        isPaused = false
        unmuteAudioSoon() // Restore system sounds once the end-of-listening sound is over
        activity?.runOnUiThread {
            mainHandler.removeCallbacks(onDeviceWatchdog)
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
            updateListeningState(false)
        }
    }

    private fun cancelListening() {
        shouldBeListening = false
        isPaused = false
        unmuteAudioSoon() // Restore system sounds once the end-of-listening sound is over
        activity?.runOnUiThread {
            mainHandler.removeCallbacks(onDeviceWatchdog)
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
            activity?.runOnUiThread { beginRecognition() }
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

    private fun unmuteAudioSoon() {
        mainHandler.removeCallbacks(unmuteRunnable)
        mainHandler.postDelayed(unmuteRunnable, UNMUTE_DELAY_MS)
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
            val context = activity as Context
            // API 33+ offers a recognizer that never touches the network.
            usingOnDeviceRecognizer = onDevice && !isOnDeviceKnownUnavailable() &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            speechRecognizer = if (usingOnDeviceRecognizer) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            speechRecognizer?.setRecognitionListener(createRecognitionListener())
            
            recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLocale)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Best effort on API < 33: ask the service to use its offline model.
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
            }
        }
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            recognizerReady = true
            mainHandler.removeCallbacks(onDeviceWatchdog)
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
            if (usingOnDeviceRecognizer && !recognizerReady && isRecognizerUnavailableError(error)) {
                fallbackToStandardRecognizer()
                return
            }
            val errorMessage = getErrorText(error)
            
            val shouldRestart = when (error) {
                SpeechRecognizer.ERROR_AUDIO,
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> true
                // Network errors are not retried: restarting immediately just spins while offline.
                else -> false
            }

            if (isContinuous && shouldRestart && shouldBeListening && !isPaused) {
                restartListening()
            } else {
                updateListeningState(false)
                unmuteAudioSoon()
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
            beginRecognition()
        }
    }

    /** Must run on the UI thread. */
    private fun beginRecognition() {
        recognizerReady = false
        speechRecognizer?.startListening(recognizerIntent)
        mainHandler.removeCallbacks(onDeviceWatchdog)
        if (usingOnDeviceRecognizer) {
            // A failed bind of the on-device service is not always reported
            // through onError, so give up if the mic never becomes ready.
            mainHandler.postDelayed(onDeviceWatchdog, ON_DEVICE_READY_TIMEOUT_MS)
        }
    }

    /** Must run on the UI thread. */
    private fun fallbackToStandardRecognizer() {
        mainHandler.removeCallbacks(onDeviceWatchdog)
        if (!usingOnDeviceRecognizer || !shouldBeListening || isPaused) return
        markOnDeviceFailed()
        usingOnDeviceRecognizer = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        initRecognizer()
        beginRecognition()
    }

    private fun isOnDeviceKnownUnavailable(): Boolean {
        if (onDeviceRecognizerFailed) return true
        val failedAt = activity?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getLong(PREF_ON_DEVICE_FAILED_AT, 0L) ?: 0L
        return failedAt > 0L && System.currentTimeMillis() - failedAt < ON_DEVICE_FAILURE_TTL_MS
    }

    private fun markOnDeviceFailed() {
        onDeviceRecognizerFailed = true
        activity?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putLong(PREF_ON_DEVICE_FAILED_AT, System.currentTimeMillis())
            ?.apply()
    }

    private fun isRecognizerUnavailableError(error: Int): Boolean = when (error) {
        SpeechRecognizer.ERROR_CLIENT,
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> true
        else -> false
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
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "Cannot check recognition support"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Recognition service disconnected"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language pack not installed for offline use"
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
                    startListening(currentLocale, isContinuous, onDevice)
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
        mainHandler.removeCallbacks(unmuteRunnable)
        unmuteAudio()
        mainHandler.removeCallbacks(onDeviceWatchdog)
        speechRecognizer?.destroy()
    }
}
