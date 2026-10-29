package com.example.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.MainActivity
import com.example.ui.screens.LibraryScreen
import com.example.ui.theme.PersonalLibraryTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    @After fun cleanup() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnUiThread { vm.setSearchQuery(""); vm.clearFilters() }
    }

    @Test fun stoppedLibraryReleasesQuerySubscriptionAndRendersLatestValueOnResume() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowSyntheticLayoutFixture") == "true")
        compose.runOnUiThread {
            compose.activity.setContent { PersonalLibraryTheme { LibraryScreen(vm, {}, {}, {}, {}) } }
        }
        val source = MainViewModel::class.java.getDeclaredField("_searchQuery").apply { isAccessible = true }.get(vm) as MutableStateFlow<*>
        compose.waitUntil(5_000) { source.subscriptionCount.value > 0 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        while (source.subscriptionCount.value > 0 && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(50)
        assertEquals("Stopped Library must release its real query source", 0, source.subscriptionCount.value)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.setSearchQuery("Synthetic resumed library query") }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5_000) { source.subscriptionCount.value > 0 }
        compose.onNodeWithTag("library_search_input").assertTextContains("Synthetic resumed library query")
    }
}
