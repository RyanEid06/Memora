package com.example.ui.components

import android.content.Context
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.time.Duration
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceSearchTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var transcript = ""
    private fun manager() = VoiceRecognitionManager(context, {}, { transcript = it })
    private fun latest() = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
    private fun speech() = shadowOf(latest())
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun installed() {
        speech().triggerSupportResult(RecognitionSupport.Builder().setInstalledOnDeviceLanguages(listOf("en-US")).build())
        idle()
    }
    private fun startReady(manager: VoiceRecognitionManager) {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        manager.start()
        installed()
    }
    private fun addDefaultService() {
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply { packageName = "synthetic.speech"; name = "synthetic.speech.Recognizer" }
        })
    }
    private fun addSystemActivity() {
        shadowOf(context.packageManager).addResolveInfoForIntent(englishSpeechIntent(), ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "synthetic.speech"; name = "synthetic.speech.Activity" }
        })
    }

    @Test fun api33ChecksEnglishSupportBeforeStartingTheMicrophone() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val manager = manager()
        manager.start()
        assertFalse("Capability probing must not claim the microphone is recording", manager.state.listening)
        assertTrue(manager.state.checking)
        installed()
        assertTrue(manager.state.listening)
        assertEquals("en-US", speech().lastRecognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        manager.dispose()
    }

    @Test fun unsupportedCapabilityProbeStillAttemptsRecognition() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val manager = manager()
        manager.start()
        assertFalse(manager.state.listening)
        speech().triggerSupportError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
        idle()
        assertTrue("Unsupported probing must not disable an installed recognizer", manager.state.listening)
        manager.dispose()
    }

    @Test fun unresponsiveOptionalProbeIsBoundedAndLateCallbackCannotDisableRecording() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val manager = manager()
        manager.start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(manager.state.listening)
        speech().triggerSupportResult(RecognitionSupport.Builder().build())
        idle()
        assertTrue(manager.state.listening)
        manager.dispose()
    }

    @Test @Config(sdk = [32], shadows = [Api32SpeechShadow::class]) fun olderPlatformsStartOnDeviceWithoutUsingSupportOrDownloadApis() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val manager = manager()
        manager.start()
        idle()
        assertTrue(manager.state.listening)
        assertFalse(manager.state.checking)
        assertEquals("en-US", speech().lastRecognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        manager.dispose()
        assertTrue(speech().isDestroyed)
    }

    @Test @Config(sdk = [29, 32]) fun defaultServiceIsUsableWithoutOnDeviceRecognition() {
        addDefaultService()
        val manager = manager()
        manager.start()
        idle()
        assertTrue(manager.state.listening)
        assertTrue(manager.state.status.contains("internet"))
        manager.dispose()
    }

    @Test fun onDeviceRecognitionWorksWithoutADefaultServiceAndReturnsEditableEnglishText() {
        assertFalse(SpeechRecognizer.isRecognitionAvailable(context))
        val manager = manager()
        startReady(manager)
        speech().triggerOnReadyForSpeech(Bundle())
        assertTrue(manager.state.listening)
        assertTrue(manager.state.status.contains("on device"))
        val old = speech()
        old.triggerOnResults(result("chemistry grades"))
        assertEquals("chemistry grades", transcript)
        assertFalse(manager.state.listening)
        assertTrue(old.isDestroyed)
        old.triggerOnPartialResults(result("stale text"))
        assertEquals("chemistry grades", transcript)
        manager.dispose()
    }

    @Test fun unavailableRecognitionDisablesMicrophoneWithoutRequestingPermissionOrLosingTypedSearch() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        val manager = manager()
        manager.start()
        assertFalse(manager.state.microphoneAvailable)
        assertFalse(manager.state.listening)
        assertTrue(manager.state.status.contains("No speech recognition service"))
        assertTrue(manager.state.status.contains("Type your query"))
        manager.dispose()
    }

    @Test fun unsupportedEnglishOnDeviceFallsBackToInstalledDefaultAndSupportsOnlineEnglish() {
        addDefaultService()
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val manager = manager()
        manager.start()
        val unusable = speech()
        unusable.triggerSupportResult(RecognitionSupport.Builder().setInstalledOnDeviceLanguages(listOf("fr-FR")).build())
        idle()
        assertTrue(unusable.isDestroyed)
        assertNotSame(unusable, speech())
        speech().triggerSupportResult(RecognitionSupport.Builder().setOnlineLanguages(listOf("en-US")).build())
        idle()
        assertTrue(manager.state.listening)
        assertTrue(manager.state.status.contains("internet"))
        manager.dispose()
    }

    @Test fun unsupportedEnglishReportedDuringRecognitionDisablesTheUnusableMicrophone() {
        addSystemActivity()
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val manager = manager()
        manager.start()
        speech().triggerSupportError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
        idle()
        speech().triggerOnError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
        assertFalse("A confirmed unusable English service must not offer an endless microphone retry", manager.state.microphoneAvailable)
        assertTrue(manager.state.systemAvailable)
        manager.dispose()
    }

    @Test @Config(shadows = [DownloadSpeechShadow::class]) fun unavailableModelAfterUnsupportedProbeOffersExplicitDownloadInsteadOfDeadRetry() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val manager = manager()
        manager.start()
        speech().triggerSupportError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
        idle()
        speech().triggerOnError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        assertFalse(manager.state.listening)
        assertTrue("An older provider may expose its missing model only during recognition", manager.state.downloadAvailable)
        manager.downloadEnglishModel()
        Shadow.extract<DownloadSpeechShadow>(latest()).callback!!.onSuccess()
        assertTrue(manager.state.status.contains("ready"))
        assertFalse(manager.state.listening)
        manager.dispose()
    }

    @Test fun missingEnglishModelRequiresExplicitDownloadAndPendingModelDoesNotRecord() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val manager = manager()
        manager.start()
        speech().triggerSupportResult(RecognitionSupport.Builder().setSupportedOnDeviceLanguages(listOf("en-US")).build())
        idle()
        assertFalse(manager.state.listening)
        assertTrue(manager.state.downloadAvailable)
        assertNull(speech().latestModelDownloadIntent)
        manager.start()
        speech().triggerSupportResult(RecognitionSupport.Builder().setPendingOnDeviceLanguages(listOf("en-US")).build())
        idle()
        assertFalse(manager.state.listening)
        assertFalse(manager.state.downloadAvailable)
        assertTrue(manager.state.status.contains("pending"))
        manager.dispose()
    }

    @Test fun capabilityErrorForMissingEnglishModelStillOffersItsExplicitDownload() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val manager = manager()
        manager.start()
        speech().triggerSupportError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        idle()
        assertTrue(manager.state.downloadAvailable)
        assertFalse(manager.state.listening)
        manager.dispose()
    }

    @Test @Config(sdk = [33]) fun api33ExplicitDownloadUsesSameInstanceAndDoesNotStartRecording() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val manager = manager()
        manager.start()
        val same = speech()
        same.triggerSupportResult(RecognitionSupport.Builder().setSupportedOnDeviceLanguages(listOf("en-US")).build())
        idle()
        manager.downloadEnglishModel()
        assertSame(same, speech())
        assertEquals("en-US", same.latestModelDownloadIntent!!.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertFalse(manager.state.listening)
        assertFalse(manager.state.downloading)
        assertTrue(manager.state.status.contains("requested"))
        manager.dispose()
    }

    @Test @Config(shadows = [DownloadSpeechShadow::class]) fun api34DownloadProgressErrorRetryScheduledAndSuccessNeverAutoRecord() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val manager = manager()
        manager.start()
        speech().triggerSupportResult(RecognitionSupport.Builder().setSupportedOnDeviceLanguages(listOf("en-US")).build())
        idle()
        manager.downloadEnglishModel()
        val shadow = Shadow.extract<DownloadSpeechShadow>(latest())
        assertEquals("en-US", shadow.downloadIntent!!.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        shadow.callback!!.onProgress(40)
        assertTrue(manager.state.status.contains("40%"))
        assertTrue(manager.state.downloading)
        shadow.callback!!.onError(SpeechRecognizer.ERROR_NETWORK)
        assertTrue(manager.state.downloadAvailable)
        assertFalse(manager.state.downloading)
        manager.downloadEnglishModel()
        shadow.callback!!.onScheduled()
        assertFalse(manager.state.downloading)
        manager.start()
        speech().triggerSupportResult(RecognitionSupport.Builder().setSupportedOnDeviceLanguages(listOf("en-US")).build())
        idle()
        manager.downloadEnglishModel()
        Shadow.extract<DownloadSpeechShadow>(latest()).callback!!.onSuccess()
        assertFalse(manager.state.listening)
        assertFalse(manager.state.downloading)
        assertTrue(manager.state.status.contains("ready"))
        assertTrue(speech().isDestroyed)
        manager.dispose()
    }

    @Test @Config(shadows = [DownloadSpeechShadow::class]) fun api34ServiceWithoutDownloadEventsUsesTheBasicDownloadRequest() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val manager = manager()
        manager.start()
        speech().triggerSupportResult(RecognitionSupport.Builder().setSupportedOnDeviceLanguages(listOf("en-US")).build())
        idle()
        manager.downloadEnglishModel()
        Shadow.extract<DownloadSpeechShadow>(latest()).callback!!.onError(SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS)
        assertNotNull("Unsupported progress callbacks must still permit a basic download request", speech().latestModelDownloadIntent)
        assertFalse(manager.state.downloading)
        assertTrue(manager.state.status.contains("requested"))
        manager.dispose()
    }

    @Test fun stopKeepsFinalResultAndRetryUsesFreshInstanceWhileDisposedCallbacksAreIgnored() {
        val manager = manager()
        startReady(manager)
        val old = speech()
        manager.stop()
        assertFalse(manager.state.listening)
        old.triggerOnResults(result("final after stop"))
        assertEquals("final after stop", transcript)
        manager.start()
        val fresh = speech()
        assertNotSame(old, fresh)
        old.triggerOnResults(result("wrong old result"))
        old.triggerOnError(SpeechRecognizer.ERROR_SERVER)
        assertEquals("final after stop", transcript)
        assertTrue(manager.state.checking)
        manager.dispose()
        fresh.triggerSupportError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
        idle()
        assertTrue(fresh.isDestroyed)
    }

    @Test fun partialSpeechIsPreservedOnEmptyFinalErrorPauseAndSystemCancellation() {
        val manager = manager()
        startReady(manager)
        speech().triggerOnPartialResults(result("Ryan birthday"))
        assertEquals("Ryan birthday", transcript)
        speech().triggerOnResults(Bundle())
        assertEquals("Ryan birthday", transcript)
        assertTrue(manager.state.status.contains("No speech was recognized"))
        manager.pause()
        assertFalse(manager.state.listening)
        manager.acceptSystemResult(null)
        assertEquals("Ryan birthday", transcript)
        manager.acceptSystemResult("edited by system")
        assertEquals("edited by system", transcript)
        manager.dispose()
    }

    @Test fun systemFallbackAppearsOnlyAfterRepeatedServiceFailureAndRequiresUserAction() {
        addSystemActivity()
        val manager = manager()
        startReady(manager)
        speech().triggerOnError(SpeechRecognizer.ERROR_SERVER)
        assertFalse(manager.state.systemAvailable)
        assertNull(manager.prepareSystemRecognition())
        manager.start()
        installed()
        speech().triggerOnError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        assertTrue(manager.state.systemAvailable)
        val fallback = manager.prepareSystemRecognition()
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, fallback!!.action)
        assertEquals("en-US", fallback.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertFalse(manager.state.listening)
        manager.pause()
        manager.acceptSystemResult("accepted result")
        assertEquals("accepted result", transcript)
        assertFalse(manager.state.listening)
        manager.dispose()
    }

    @Test fun serviceFailuresHaveDistinctActionableMessages() {
        val errors = listOf(SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        assertEquals(errors.size, errors.map(::speechRecognitionErrorMessage).toSet().size)
        errors.forEach {
            assertTrue(speechRecognitionErrorMessage(it).contains("microphone"))
            assertTrue(speechRecognitionErrorMessage(it).contains("type your query"))
        }
    }

    @Test fun systemResultUsesFirstNonblankMatchAndCancellationIgnoresReturnedText() {
        val result = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf("", "  ", "usable English query"))
        assertEquals("usable English query", systemSpeechResult(Activity.RESULT_OK, result))
        assertNull(systemSpeechResult(Activity.RESULT_CANCELED, result))
    }

    @Test fun diagnosticsContainServicePathAndNumericErrorWithoutTranscript() {
        val manager = manager()
        startReady(manager)
        speech().triggerOnPartialResults(result("Private artificial voice transcript"))
        speech().triggerOnError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
        val logs = org.robolectric.shadows.ShadowLog.getLogsForTag("MemoraVoice").map { it.msg }
        assertTrue(logs.any { it.contains("path=on_device") && it.contains("component=") })
        assertTrue(logs.any { it.contains("error=${SpeechRecognizer.ERROR_SERVER_DISCONNECTED}") })
        assertFalse(logs.any { it.contains("Private artificial voice transcript") })
        manager.dispose()
    }

    private fun result(text: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) }

    /** Robolectric4.14.1 incorrectly gates its on-device factory/availability shadows at33. */
    @Implements(SpeechRecognizer::class, minSdk = 31, maxSdk = 32)
    class Api32SpeechShadow : ShadowSpeechRecognizer() {
        companion object {
            @JvmStatic @Implementation(minSdk = 31, methodName = "isOnDeviceRecognitionAvailable")
            protected fun availableOnDevice(context: Context) = true
            @JvmStatic @Implementation(minSdk = 31, methodName = "createOnDeviceSpeechRecognizer")
            protected fun newOnDeviceRecognizer(context: Context): SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        }
    }

    /** The pinned Robolectric shadow lacks only the API34 listener overload. */
    @Implements(SpeechRecognizer::class, minSdk = 34, maxSdk = 34)
    class DownloadSpeechShadow : ShadowSpeechRecognizer() {
        var callback: ModelDownloadListener? = null
        var downloadIntent: Intent? = null
        @Implementation(minSdk = 34)
        protected fun triggerModelDownload(intent: Intent, executor: Executor, listener: ModelDownloadListener) {
            downloadIntent = intent
            callback = listener
        }
    }
}
