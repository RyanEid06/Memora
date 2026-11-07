package com.example.security

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.room.MultiInstanceInvalidationService
import androidx.test.core.app.ApplicationProvider
import com.example.MainActivity
import com.example.ui.MainViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.android.controller.ServiceController
import java.util.concurrent.Executor

/** The prompt boundary is simulated; these tests do not authenticate a real fingerprint. */
@OptIn(androidx.room.ExperimentalRoomApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], instrumentedPackages = ["androidx.biometric"], shadows = [AppLockAuthenticationTest.PromptShadow::class])
class AppLockAuthenticationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var invalidation: ServiceController<MultiInstanceInvalidationService>

    @Before fun reset() {
        val app = context as android.app.Application
        invalidation = Robolectric.buildService(MultiInstanceInvalidationService::class.java).create()
        val binding = Intent(app, MultiInstanceInvalidationService::class.java)
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
            binding, checkNotNull(binding.component), checkNotNull(invalidation.get().onBind(binding)))
        context.getSharedPreferences("app_lock_prefs", Context.MODE_PRIVATE).edit().putBoolean("lock_enabled", false).commit()
        PromptShadow.attempts = 0
        PromptShadow.cancellations = 0
        PromptShadow.callback = null
        PromptShadow.promptInfo = null
        PromptShadow.launchError = null
    }

    @After fun close() {
        shadowOf(Looper.getMainLooper()).idle()
        invalidation.destroy()
    }

    private fun launch(locked: Boolean) = Robolectric.buildActivity(MainActivity::class.java).create().apply {
        // Seed the exact SharedPreferences owner used by this Activity's ViewModel.
        ViewModelProvider(get())[MainViewModel::class.java].appLockManager.setLockEnabled(locked)
    }.start().resume().visible()

    @Test fun foregroundLockedLaunchPromptsOnceAndCancelAllowsManualRetry() {
        val controller = launch(locked = true)
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val activity = controller.get()
            val manager = ViewModelProvider(activity)[MainViewModel::class.java].appLockManager
            assertEquals(1, PromptShadow.attempts)
            activity.onTopResumedActivityChanged(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Repeated foreground callbacks must not stack sheets", 1, PromptShadow.attempts)
            PromptShadow.callback!!.onAuthenticationError(BiometricPrompt.ERROR_USER_CANCELED, "Cancelled")
            activity.onTopResumedActivityChanged(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Cancellation must leave the retry screen without reopening", 1, PromptShadow.attempts)
            assertTrue(manager.isLocked.value)
            manager.authenticate(activity, onSuccess = {}, onError = {})
            assertEquals(2, PromptShadow.attempts)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun promptHandoffDoesNotResetCycleAndLaterBackgroundDoes() {
        val controller = launch(locked = true)
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val activity = controller.get()
            activity.onTopResumedActivityChanged(false)
            controller.pause()
            PromptShadow.callback!!.onAuthenticationError(BiometricPrompt.ERROR_CANCELED, "Dismissed")
            controller.resume()
            activity.onTopResumedActivityChanged(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("The native credential handoff is part of the same attempt", 1, PromptShadow.attempts)
            controller.pause().stop().start().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("A subsequent real background cycle permits an automatic retry", 2, PromptShadow.attempts)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun disabledAppLockNeverPromptsAutomatically() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val manager = ViewModelProvider(controller.get())[MainViewModel::class.java].appLockManager
            manager.setLockEnabled(false)
            assertFalse("App Lock must be disabled before entering the foreground", manager.isLockEnabled())
            assertFalse(manager.isLocked.value)
            controller.start().resume().visible()
            shadowOf(Looper.getMainLooper()).idle()
            controller.get().onTopResumedActivityChanged(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, PromptShadow.attempts)
        } finally {
            if (controller.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                controller.pause().stop()
            }
            controller.destroy()
        }
    }

    @Test fun terminalErrorBetweenPauseAndStopDoesNotReopenOnReturn() {
        val controller = launch(locked = true)
        try {
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause()
            PromptShadow.callback!!.onAuthenticationError(BiometricPrompt.ERROR_CANCELED, "App backgrounded")
            controller.stop().start().resume()
            controller.get().onTopResumedActivityChanged(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Trailing stop must not turn a native cancellation into a new cycle", 1, PromptShadow.attempts)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun terminalErrorBeforeTopLossDoesNotReopenOnReturn() {
        val controller = launch(locked = true)
        try {
            shadowOf(Looper.getMainLooper()).idle()
            PromptShadow.callback!!.onAuthenticationError(BiometricPrompt.ERROR_USER_CANCELED, "Cancelled before lifecycle handoff")
            controller.get().onTopResumedActivityChanged(true)
            controller.get().onTopResumedActivityChanged(false)
            controller.pause().stop().start().resume()
            controller.get().onTopResumedActivityChanged(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, PromptShadow.attempts)
            assertTrue(ViewModelProvider(controller.get())[MainViewModel::class.java].appLockManager.isLocked.value)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun cancelledPromptThatRestoresFocusAllowsNextBackgroundCycle() {
        val controller = launch(locked = true)
        try {
            shadowOf(Looper.getMainLooper()).idle()
            PromptShadow.callback!!.onAuthenticationError(BiometricPrompt.ERROR_USER_CANCELED, "Cancelled")
            controller.get().onWindowFocusChanged(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, PromptShadow.attempts)
            // Screen off can pause and stop the Activity without a user-leave callback.
            controller.pause().stop().start().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, PromptShadow.attempts)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun homeInvalidatesBiometricSuccessAndDelayedCancelFromOldAttempt() = assertHomeInvalidatesAttempt(BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC)

    @Test fun homeInvalidatesCredentialSuccessAndDelayedCancelFromOldAttempt() = assertHomeInvalidatesAttempt(BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL)

    @Test fun homeReturnWaitsForOldTerminalCallbackBeforeStartingFreshSheet() {
        val controller = launch(locked = true)
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val oldCallback = PromptShadow.callback!!
            controller.userLeaving().pause().stop().start().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Native cancellation is asynchronous; never overlap its old sheet", 1, PromptShadow.attempts)
            oldCallback.onAuthenticationError(BiometricPrompt.ERROR_CANCELED, "Background cancellation delivered after resume")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Only a confirmed background cancellation permits this automatic retry", 2, PromptShadow.attempts)
        } finally { controller.pause().stop().destroy() }
    }

    private fun assertHomeInvalidatesAttempt(type: Int) {
        val controller = launch(locked = true)
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val oldCallback = PromptShadow.callback!!
            val manager = ViewModelProvider(controller.get())[MainViewModel::class.java].appLockManager
            controller.userLeaving().pause().stop()
            assertEquals("Home must cancel the old sheet before another can be opened", 1, PromptShadow.cancellations)
            oldCallback.onAuthenticationSucceeded(result(type))
            assertTrue("A stale success cannot unlock a backgrounded Library", manager.isLocked.value)
            controller.start().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("A Home return starts one fresh attempt", 2, PromptShadow.attempts)
            oldCallback.onAuthenticationError(BiometricPrompt.ERROR_CANCELED, "Old request cancelled late")
            manager.authenticate(controller.get(), onSuccess = {}, onError = {})
            assertEquals("A late old callback must not release the new attempt's guard", 2, PromptShadow.attempts)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun credentialSuccessWhileStoppedStaysLockedUntilForegroundReturns() {
        val controller = launch(locked = true)
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val manager = ViewModelProvider(controller.get())[MainViewModel::class.java].appLockManager
            controller.pause().stop()
            PromptShadow.callback!!.onAuthenticationSucceeded(result(BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL))
            assertTrue("A credential activity result must not expose Library in the background", manager.isLocked.value)
            controller.start().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(manager.isLocked.value)
            assertEquals("Successful credential handoff must not launch a second sheet", 1, PromptShadow.attempts)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun credentialCancelAfterStopDoesNotReopenOnReturn() {
        val controller = launch(locked = true)
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val manager = ViewModelProvider(controller.get())[MainViewModel::class.java].appLockManager
            controller.pause().stop()
            PromptShadow.callback!!.onAuthenticationError(BiometricPrompt.ERROR_USER_CANCELED, "Credential cancelled")
            controller.start().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(manager.isLocked.value)
            assertEquals("Returning from cancelled credentials is still the same foreground attempt", 1, PromptShadow.attempts)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun credentialHandoffIsMarkedSystemDrivenWithoutChangingOtherLaunches() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            activity.startActivityForResult(Intent("android.app.action.CONFIRM_DEVICE_CREDENTIAL"), 123, null)
            val credential = shadowOf(activity).nextStartedActivityForResult.intent
            assertTrue(credential.flags and Intent.FLAG_ACTIVITY_NO_USER_ACTION != 0)
            activity.startActivityForResult(Intent(Intent.ACTION_VIEW), 124, null)
            val unrelated = shadowOf(activity).nextStartedActivityForResult.intent
            assertEquals(0, unrelated.flags and Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun enablingAppLockInForegroundStartsFirstAttempt() {
        val controller = launch(locked = false)
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val manager = ViewModelProvider(controller.get())[MainViewModel::class.java].appLockManager
            manager.setLockEnabled(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(manager.isLocked.value)
            assertEquals(1, PromptShadow.attempts)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun recreationReconnectsSuccessWithoutAnotherSheet() {
        val controller = launch(locked = true)
        try {
            shadowOf(Looper.getMainLooper()).idle()
            controller.recreate()
            shadowOf(Looper.getMainLooper()).idle()
            val manager = ViewModelProvider(controller.get())[MainViewModel::class.java].appLockManager
            assertEquals("Recreation must reconnect to the active native attempt", 1, PromptShadow.attempts)
            PromptShadow.callback!!.onAuthenticationSucceeded(result(BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL))
            assertFalse(manager.isLocked.value)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun biometricMismatchKeepsNativeAttemptActiveUntilSuccess() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val manager = AppLockManager(context).apply { setLockEnabled(true) }
            var successes = 0
            var errors = 0
            manager.authenticate(controller.get(), onSuccess = { successes++ }, onError = { errors++ })
            assertEquals(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                PromptShadow.promptInfo!!.allowedAuthenticators)
            PromptShadow.callback!!.onAuthenticationFailed()
            assertTrue(manager.isLocked.value)
            assertEquals("A mismatch is nonterminal; Android continues the same attempt", 0, errors)
            manager.authenticate(controller.get(), onSuccess = { successes++ }, onError = { errors++ })
            assertEquals("Retry taps cannot stack an active prompt", 1, PromptShadow.attempts)
            PromptShadow.callback!!.onAuthenticationSucceeded(result(BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC))
            assertFalse(manager.isLocked.value)
            assertEquals(1, successes)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun unavailablePromptLeavesLockedAndReportsErrorWithoutCrashing() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val manager = AppLockManager(context).apply { setLockEnabled(true) }
            var errors = 0
            PromptShadow.launchError = IllegalArgumentException("Unsupported authentication configuration")
            manager.authenticate(controller.get(), onSuccess = { fail("No authentication succeeded") }, onError = { errors++ })
            assertTrue(manager.isLocked.value)
            assertEquals(1, errors)
            PromptShadow.launchError = null
            manager.authenticate(controller.get(), onSuccess = {}, onError = {})
            assertEquals("A launch error must allow a manual retry", 2, PromptShadow.attempts)
        } finally { controller.pause().stop().destroy() }
    }

    @Implements(BiometricPrompt::class, isInAndroidSdk = false)
    class PromptShadow {
        companion object {
            var attempts = 0
            var cancellations = 0
            var callback: BiometricPrompt.AuthenticationCallback? = null
            var promptInfo: BiometricPrompt.PromptInfo? = null
            var launchError: RuntimeException? = null
        }

        @Implementation protected fun __constructor__(activity: FragmentActivity, executor: Executor, authenticationCallback: BiometricPrompt.AuthenticationCallback) {
            callback = authenticationCallback
        }

        @Implementation protected fun authenticate(info: BiometricPrompt.PromptInfo) {
            attempts++
            promptInfo = info
            launchError?.let { throw it }
        }

        @Implementation protected fun cancelAuthentication() { cancellations++ }
    }

    private fun result(type: Int): BiometricPrompt.AuthenticationResult =
        BiometricPrompt.AuthenticationResult::class.java
            .getDeclaredConstructor(BiometricPrompt.CryptoObject::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(null, type)
}
