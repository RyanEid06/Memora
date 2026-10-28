package com.example.ui

import android.Manifest
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Owned synthetic emulator UI only; no provider/OEM/microphone acceptance claim. */
@RunWith(AndroidJUnit4::class)
class VoiceSearchUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]
    private var previousLock = false
    private var hadLock = false
    private var initialized = false

    @Before fun prepareSyntheticFixture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Synthetic voice fixture needs explicit opt-in",
            InstrumentationRegistry.getArguments().getString("allowSyntheticVoiceFixture") == "true")
        val prefs = instrumentation.targetContext.getSharedPreferences("app_lock_prefs", Context.MODE_PRIVATE)
        hadLock = prefs.contains("lock_enabled")
        previousLock = prefs.getBoolean("lock_enabled", false)
        initialized = true
        // Only this opt-in emulator fixture receives microphone permission. Network is disabled by the runner.
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
        compose.runOnUiThread { vm.appLockManager.setLockEnabled(false); vm.setSearchQuery("") }
    }

    @After fun restoreFixture() {
        if (!initialized) return
        compose.runOnUiThread { vm.appLockManager.setLockEnabled(previousLock); vm.setSearchQuery("") }
        if (!hadLock) InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("app_lock_prefs", Context.MODE_PRIVATE).edit().remove("lock_enabled").commit()
    }

    @Test fun smallMicOpensEditableQueryAndSearchUsesTheTypedSearchPath() {
        compose.onNodeWithContentDescription("Voice Search").performClick()
        compose.onNodeWithTag("voice_query_text_field").performTextReplacement("Synthetic Chemistry exact text")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("submit_voice_search_button").performScrollTo().performClick()
        compose.onNodeWithTag("voice_query_text_field").assertDoesNotExist()
        compose.runOnIdle { assertEquals("Synthetic Chemistry exact text", vm.searchQuery.value) }
        compose.onNodeWithTag("library_search_input").assertTextContains("Synthetic Chemistry exact text")
    }

    @Test fun immediateLockHidesDialogAndUnlockRetainsDraftWithoutRestartingVoice() {
        compose.onNodeWithContentDescription("Voice Search").performClick()
        compose.onNodeWithTag("voice_query_text_field").performTextReplacement("Synthetic retained voice draft")
        Espresso.closeSoftKeyboard()
        compose.runOnUiThread {
            vm.appLockManager.setLockEnabled(true)
            vm.appLockManager.unlock()
            // Same native boundary as external permission/system activity handoff.
            compose.activity.onTopResumedActivityChanged(false)
            assertTrue(vm.appLockManager.isLocked.value)
        }
        compose.onNodeWithTag("unlock_app_button").assertIsDisplayed()
        compose.onNodeWithTag("voice_query_text_field").assertDoesNotExist()
        compose.runOnUiThread { vm.appLockManager.unlock() }
        compose.onNodeWithTag("voice_query_text_field").assertTextContains("Synthetic retained voice draft")
        compose.onNodeWithText("Microphone paused. Tap the microphone to retry, or type your query.").assertIsDisplayed()
        compose.onNodeWithTag("voice_progress").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
    }

    @Test fun activityRecreationRetainsDraftWithoutAutomaticallyRecording() {
        compose.onNodeWithContentDescription("Voice Search").performClick()
        compose.onNodeWithTag("voice_query_text_field").performTextReplacement("Synthetic recreation draft")
        Espresso.closeSoftKeyboard()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("voice_query_text_field").assertTextContains("Synthetic recreation draft")
        compose.onNodeWithTag("voice_progress").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
    }
}
