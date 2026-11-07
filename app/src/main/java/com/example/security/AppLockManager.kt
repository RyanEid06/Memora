package com.example.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.example.reminder.ReminderReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

class AppLockManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("app_lock_prefs", Context.MODE_PRIVATE)

    private val _isLocked = MutableStateFlow(isLockEnabled())
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()
    private var prompt: BiometricPrompt? = null
    private var boundActivity: WeakReference<FragmentActivity>? = null
    private var authenticationInProgress = false
    private var authenticationInvalidated = false
    private var nativeHandoffPending = false
    private var automaticAttempted = false
    private var authenticationGeneration = 0
    private var isForeground = false
    private var successAwaitingForeground = false
    private var authenticationSuccess: () -> Unit = {}
    private var authenticationError: (String) -> Unit = {}

    fun isLockEnabled(): Boolean {
        return prefs.getBoolean("lock_enabled", false)
    }

    fun setLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("lock_enabled", enabled).apply()
        automaticAttempted = false
        nativeHandoffPending = false
        if (!enabled) invalidateAuthentication()
        _isLocked.value = enabled
        if (enabled) ReminderReceiver.protectPostedNotifications(context)
    }

    fun unlock() {
        authenticationInProgress = false
        authenticationInvalidated = false
        nativeHandoffPending = false
        successAwaitingForeground = false
        _isLocked.value = false
    }

    fun lock() {
        if (isLockEnabled()) {
            _isLocked.value = true
            // Native credential UI can pause/stop the activity during the same attempt.
            if (!authenticationInProgress && !successAwaitingForeground && !nativeHandoffPending) automaticAttempted = false
        }
    }

    internal fun onActivityForegrounded() {
        val returningToForeground = !isForeground
        isForeground = true
        if (returningToForeground && !authenticationInProgress) nativeHandoffPending = false
        if (successAwaitingForeground && isLockEnabled()) finishAuthenticationSuccess()
    }

    internal fun onActivityBackgrounded(changingConfigurations: Boolean) {
        val wasForeground = isForeground
        isForeground = false
        if (!changingConfigurations && isLockEnabled()) {
            // Top-loss, pause and stop are one departure even if a terminal callback lands between them.
            if (wasForeground) lock() else _isLocked.value = true
        }
    }

    internal fun onAuthenticationWindowFocused() {
        if (isForeground && !authenticationInProgress) nativeHandoffPending = false
    }

    internal fun onUserLeftActivity() {
        isForeground = false
        nativeHandoffPending = false
        invalidateAuthentication()
        automaticAttempted = false
        lock()
    }

    private fun invalidateAuthentication() {
        successAwaitingForeground = false
        if (authenticationInProgress) {
            if (!authenticationInvalidated) {
                authenticationInvalidated = true
                prompt?.cancelAuthentication()
            }
        } else {
            authenticationGeneration++
        }
    }

    fun canAuthenticate(): Boolean {
        val biometricManager = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    internal fun bindAuthentication(activity: FragmentActivity) {
        boundActivity = WeakReference(activity)
        val generation = authenticationGeneration
        val executor = ContextCompat.getMainExecutor(activity)
        prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                if (generation != authenticationGeneration || !authenticationInProgress) return
                if (finishInvalidatedAuthentication()) return
                authenticationInProgress = false
                if (isForeground) finishAuthenticationSuccess()
                else successAwaitingForeground = true
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (generation != authenticationGeneration || !authenticationInProgress) return
                if (finishInvalidatedAuthentication()) return
                finishAuthenticationError(errString.toString())
            }
        })
    }

    fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        automatic: Boolean = false
    ) {
        if (!isLockEnabled() || !_isLocked.value || authenticationInProgress || successAwaitingForeground) return
        if (automatic && automaticAttempted) return
        automaticAttempted = true
        authenticationInProgress = true
        authenticationInvalidated = false
        nativeHandoffPending = true
        authenticationGeneration++
        authenticationSuccess = onSuccess
        authenticationError = onError
        isForeground = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        try {
            bindAuthentication(activity)
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Memora")
                .setSubtitle("Authenticate using biometric or device credential")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
            prompt?.authenticate(promptInfo)
        } catch (error: IllegalArgumentException) {
            finishAuthenticationError(error.message ?: "Authentication is unavailable.")
        } catch (error: IllegalStateException) {
            finishAuthenticationError(error.message ?: "Authentication is unavailable.")
        }
    }

    private fun finishAuthenticationSuccess() {
        unlock()
        authenticationSuccess()
    }

    private fun finishInvalidatedAuthentication(): Boolean {
        if (!authenticationInvalidated) return false
        authenticationInProgress = false
        authenticationInvalidated = false
        // AndroidX shares its client callback: drain the old terminal result before rebinding.
        if (isForeground) boundActivity?.get()?.let { activity ->
            activity.window.decorView.post {
                if (isForeground && boundActivity?.get() === activity && !activity.isDestroyed) {
                    authenticate(activity, authenticationSuccess, authenticationError, automatic = true)
                }
            }
        }
        return true
    }

    private fun finishAuthenticationError(message: String) {
        authenticationInProgress = false
        // A prompt can finish on either side of the window-focus callback.
        boundActivity?.get()?.let { activity ->
            activity.window.decorView.post {
                if (boundActivity?.get() === activity && activity.hasWindowFocus()) onAuthenticationWindowFocused()
            }
        }
        authenticationError(message)
    }
}
