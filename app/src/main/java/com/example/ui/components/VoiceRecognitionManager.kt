package com.example.ui.components

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.Locale

internal data class VoiceRecognitionState(
    val status: String = "Starting microphone…",
    val listening: Boolean = false,
    val microphoneAvailable: Boolean = true,
    val checking: Boolean = false,
    val downloadAvailable: Boolean = false,
    val downloading: Boolean = false,
    val defaultAvailable: Boolean = false,
    val systemAvailable: Boolean = false
)

/** Main-thread, dialog-scoped speech session. Never retains or logs audio/transcripts. */
internal class VoiceRecognitionManager(
    private val context: Context,
    private val onState: (VoiceRecognitionState) -> Unit,
    private val onResult: (String) -> Unit
) {
    var state = VoiceRecognitionState()
        private set
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0
    private var disposed = false
    private var onDevice = false
    private var serviceFailures = 0
    private var supportTimeout: Runnable? = null
    private var intent = englishSpeechIntent()

    fun hasMicrophoneService(): Boolean =
        (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) ||
            SpeechRecognizer.isRecognitionAvailable(context)

    fun start(useDefault: Boolean = false) {
        if (disposed) return
        release()
        onDevice = !useDefault && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
            update(VoiceRecognitionState(
                status = "No speech recognition service is installed. Type your query below.",
                microphoneAvailable = false, systemAvailable = systemResolvable()
            ))
            return
        }
        val attempt = generation
        intent = englishSpeechIntent()
        update(VoiceRecognitionState(status = "Checking English voice support…", checking = true))
        val instance = try {
            if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (_: SecurityException) {
            permissionDenied()
            return
        } catch (_: Exception) {
            if (onDevice && SpeechRecognizer.isRecognitionAvailable(context)) start(useDefault = true)
            else fail(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        recognizer = instance
        diagnostic("selected")
        instance.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = current(attempt) {
                update(state.copy(listening = true, status = if (onDevice) "Listening on device…" else "Listening with default speech service (may use internet)…"))
            }
            override fun onBeginningOfSpeech() = current(attempt) { update(state.copy(status = "Hearing speech…")) }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = current(attempt) { update(state.copy(listening = false, status = "Processing speech…")) }
            override fun onError(error: Int) = current(attempt) {
                when (error) {
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> { diagnostic("recognition_error", error); englishUnavailable() }
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> { diagnostic("recognition_error", error); missingEnglishModel() }
                    else -> fail(error)
                }
            }
            override fun onResults(results: Bundle?) = current(attempt) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }
                if (text == null) fail(SpeechRecognizer.ERROR_NO_MATCH)
                else {
                    serviceFailures = 0
                    release()
                    onResult(text)
                    update(VoiceRecognitionState(status = "Speech recognized. Edit your query or tap Search."))
                }
            }
            override fun onPartialResults(partialResults: Bundle?) = current(attempt) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }?.let(onResult)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        if (Build.VERSION.SDK_INT >= 33) checkSupport(instance, attempt) else listen(instance, attempt)
    }

    @RequiresApi(33)
    private fun checkSupport(instance: SpeechRecognizer, attempt: Int) {
        var completed = false
        fun finish(action: () -> Unit) = current(attempt) {
            if (!completed) {
                completed = true
                clearSupportTimeout()
                action()
            }
        }
        // A broken/older service may never answer the optional probe. Try recognition once.
        supportTimeout = Runnable { finish { diagnostic("support_timeout"); listen(instance, attempt) } }
            .also { handler.postDelayed(it, 3000) }
        try {
            instance.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) = finish {
                    val installed = englishTag(support.installedOnDeviceLanguages)
                    val online = if (!onDevice) englishTag(support.onlineLanguages) else null
                    when {
                        installed != null || online != null -> {
                            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, installed ?: online)
                            listen(instance, attempt)
                        }
                        englishTag(support.pendingOnDeviceLanguages) != null -> update(state.copy(
                            checking = false, status = "English voice model download is pending. Tap the microphone to check again, or type your query.",
                            defaultAvailable = canUseDefault()
                        ))
                        englishTag(support.supportedOnDeviceLanguages) != null -> {
                            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, englishTag(support.supportedOnDeviceLanguages))
                            update(state.copy(checking = false, downloadAvailable = true,
                                status = "English voice needs a model download. Download it below, or type your query.", defaultAvailable = canUseDefault()))
                        }
                        else -> englishUnavailable()
                    }
                }
                override fun onError(error: Int) = finish {
                    diagnostic("support_error", error)
                    if (error == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT) listen(instance, attempt)
                    else if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) englishUnavailable()
                    else if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) missingEnglishModel()
                    else fail(error)
                }
            })
        } catch (_: UnsupportedOperationException) {
            finish { listen(instance, attempt) }
        } catch (_: SecurityException) {
            finish { permissionDenied() }
        } catch (_: Exception) {
            finish { fail(SpeechRecognizer.ERROR_CLIENT) }
        }
    }

    private fun englishUnavailable(reason: String = "The installed speech service cannot recognize English. Type your query below.") {
        if (canUseDefault()) start(useDefault = true)
        else {
            release()
            update(VoiceRecognitionState(status = reason,
                microphoneAvailable = false, systemAvailable = systemResolvable()))
        }
    }

    private fun missingEnglishModel() {
        if (canUseDefault()) start(useDefault = true)
        else if (Build.VERSION.SDK_INT >= 33) {
            // Keep this instance only for its explicit model request. Its recording has
            // ended; invalidate all recording callbacks. Any retry creates a new instance.
            generation++
            clearSupportTimeout()
            update(state.copy(listening = false, checking = false, downloadAvailable = true,
                status = "English voice needs a model download. Download it below, or type your query."))
        } else {
            englishUnavailable("The English voice model is unavailable. Download English in your system speech settings, or type your query below.")
        }
    }

    private fun listen(instance: SpeechRecognizer, attempt: Int) = current(attempt) {
        update(state.copy(checking = false, listening = true, downloadAvailable = false,
            status = if (onDevice) "Starting on-device microphone…" else "Starting speech service (may use internet)…"))
        try { instance.startListening(intent) }
        catch (_: SecurityException) { fail(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
        catch (_: Exception) { fail(SpeechRecognizer.ERROR_CLIENT) }
    }

    fun stop() {
        if (disposed || !state.listening) return
        update(state.copy(listening = false, status = "Finishing speech… Tap the microphone to record again."))
        try { recognizer?.stopListening() } catch (_: Exception) { fail(SpeechRecognizer.ERROR_CLIENT) }
    }

    fun downloadEnglishModel() {
        if (disposed || !state.downloadAvailable || Build.VERSION.SDK_INT < 33) return
        val instance = recognizer ?: return
        val attempt = generation
        update(state.copy(downloadAvailable = false, downloading = true, status = "Requesting English voice model download…"))
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                instance.triggerModelDownload(intent, context.mainExecutor, object : ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) = current(attempt) {
                        update(state.copy(status = "Downloading English voice model… ${completedPercent.coerceIn(0, 100)}%"))
                    }
                    override fun onSuccess() = current(attempt) {
                        release()
                        update(VoiceRecognitionState(status = "English voice model is ready. Tap the microphone to record."))
                    }
                    override fun onScheduled() = current(attempt) { downloadScheduled() }
                    override fun onError(error: Int) = current(attempt) {
                        diagnostic("download_error", error)
                        if (error == SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS) {
                            requestBasicDownload(instance)
                        } else downloadFailed()
                    }
                })
            } else {
                requestBasicDownload(instance)
            }
        } catch (_: UnsupportedOperationException) {
            requestBasicDownload(instance)
        } catch (_: Exception) {
            downloadFailed()
        }
    }

    @RequiresApi(33)
    private fun requestBasicDownload(instance: SpeechRecognizer) {
        try {
            instance.triggerModelDownload(intent)
            downloadScheduled()
        } catch (_: Exception) { downloadFailed() }
    }

    private fun downloadFailed() {
        update(state.copy(downloading = false, downloadAvailable = true,
            status = "English voice model download failed. Try the download again, or type your query."))
    }

    private fun downloadScheduled() {
        update(state.copy(downloading = false, status = "English voice model download requested. Tap the microphone to check again, or type your query."))
    }

    fun permissionDenied() {
        release()
        update(VoiceRecognitionState(status = "Microphone permission denied. Tap the microphone to retry, or type your query."))
    }

    fun pause() {
        if (disposed) return
        release()
        update(state.copy(listening = false, checking = false, downloading = false, downloadAvailable = false,
            defaultAvailable = false, status = "Microphone paused. Tap the microphone to retry, or type your query."))
    }

    fun prepareSystemRecognition(): Intent? {
        if (disposed || !state.systemAvailable || !systemResolvable()) return null
        release()
        val component = englishSpeechIntent().resolveActivity(context.packageManager)?.flattenToShortString() ?: "unresolved"
        Log.i("MemoraVoice", "event=selected path=system_activity component=$component error=none")
        update(state.copy(listening = false, checking = false, downloading = false, downloadAvailable = false,
            status = "Opening system voice search (may use internet)…"))
        return englishSpeechIntent()
    }

    fun acceptSystemResult(text: String?) {
        if (disposed) return
        if (!text.isNullOrBlank()) onResult(text)
        update(state.copy(status = if (text.isNullOrBlank()) "System voice search cancelled. Your query is unchanged." else "Speech recognized. Edit your query or tap Search."))
    }

    fun systemLaunchFailed() {
        update(state.copy(systemAvailable = false, status = "System voice search could not open. Type your query below."))
    }

    fun dispose() {
        release()
        disposed = true
    }

    private fun fail(error: Int) {
        diagnostic("recognition_error", error)
        if (error in serviceErrors) serviceFailures++
        val defaultAvailable = canUseDefault()
        release()
        update(VoiceRecognitionState(status = speechRecognitionErrorMessage(error), defaultAvailable = defaultAvailable,
            systemAvailable = serviceFailures >= 2 && systemResolvable()))
    }

    private fun canUseDefault() = onDevice && SpeechRecognizer.isRecognitionAvailable(context)
    private fun systemResolvable() = englishSpeechIntent().resolveActivity(context.packageManager) != null
    private fun update(next: VoiceRecognitionState) { if (!disposed) { state = next; onState(next) } }
    private inline fun current(attempt: Int, action: () -> Unit) { if (!disposed && generation == attempt) action() }
    private fun clearSupportTimeout() { supportTimeout?.let(handler::removeCallbacks); supportTimeout = null }
    private fun release() {
        generation++
        clearSupportTimeout()
        val previous = recognizer
        recognizer = null
        try { previous?.cancel() } catch (_: Exception) { /* Already disconnected. */ }
        try { previous?.destroy() } catch (_: Exception) { /* Release must not interrupt lifecycle teardown. */ }
    }

    private fun diagnostic(event: String, error: Int? = null) {
        // Service identifiers and numeric codes only: no recognized text, intent extras or exception payload.
        val configured = if (onDevice) null else try {
            Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
        } catch (_: SecurityException) { null }
        val component = configured?.let(ComponentName::unflattenFromString)?.flattenToShortString() ?: "system_selected"
        Log.i("MemoraVoice", "event=$event path=${if (onDevice) "on_device" else "default"} component=$component error=${error ?: "none"}")
    }

    private companion object {
        val serviceErrors = setOf(SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT) + if (Build.VERSION.SDK_INT >= 31)
            setOf(SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS) else emptySet()
        fun englishTag(languages: List<String>): String? = languages.firstOrNull { it.equals("en-US", true) }
            ?: languages.firstOrNull { Locale.forLanguageTag(it).language == "en" }
    }
}

internal fun englishSpeechIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
}

internal fun speechRecognitionErrorMessage(error: Int): String {
    val reason = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio could not be recorded."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
        SpeechRecognizer.ERROR_NETWORK -> "Speech service network connection failed."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service network connection timed out."
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognized."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was heard."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service is busy."
        SpeechRecognizer.ERROR_SERVER -> "Speech service reported a server error."
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Speech service disconnected and is unavailable."
        SpeechRecognizer.ERROR_CLIENT -> "Speech service could not complete this request."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "This speech service does not support English."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "The English speech model is unavailable."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech service received too many requests. Try again shortly."
        else -> "Speech recognition could not finish."
    }
    return "$reason Tap the microphone to retry, or type your query."
}
