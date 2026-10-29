package com.example.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.MainActivity
import com.example.data.ItemEntity
import com.example.model.LibraryLayout
import com.example.ui.screens.CollectionsScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.theme.PersonalLibraryTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Only owned synthetic runtime: no owner rows, display settings or app-lock policy changes. */
@RunWith(AndroidJUnit4::class)
class ResponsiveLibraryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]
    private val ids = mutableListOf<String>()

    @After fun cleanup() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnUiThread { vm.clearSelection(); vm.setSearchQuery(""); vm.clearFilters(); vm.setLibraryLayout(LibraryLayout.LIST) }
        runBlocking { ids.forEach { vm.repository.deletePermanently(it) } }
    }

    @Test fun compactLargeTextCollectionsLeaveEnoughWidthForCardActions() {
        requireSyntheticRuntime()
        showCollections(compact = true)
        val grid = compose.onNodeWithTag("collections_grid").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("col_card_favorites").fetchSemanticsNode().boundsInRoot
        assertTrue("320dp at 150% font needs one usable collection column", card.width > grid.width * .8f)
        compose.onNodeWithTag("col_card_favorites").assertHasClickAction()
    }

    @Test fun compactLargeTextLibraryUsesOneColumnWithFullWidthSearchHeader() {
        requireSyntheticRuntime()
        val id = UUID.randomUUID().toString().also(ids::add)
        runBlocking { vm.repository.dao.insertItem(ItemEntity(id, "Synthetic responsive $id", "Synthetic body", "TEXT")) }
        compose.runOnUiThread {
            vm.clearFilters(); vm.setSearchQuery(id); vm.setLibraryLayout(LibraryLayout.GRID)
            compose.activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                    PersonalLibraryTheme {
                        Box(Modifier.width(320.dp).fillMaxSize()) { LibraryScreen(vm, {}, {}, {}, {}) }
                    }
                }
            }
        }
        compose.waitUntil(15_000) { vm.resultsReady.value && vm.localSearchResults.value.any { it.item.id == id } }
        val grid = compose.onNodeWithTag("library_grid")
        val viewport = grid.fetchSemanticsNode().boundsInRoot
        assertTrue("Search header spans all adaptive columns", compose.onNodeWithTag("library_search_input").fetchSemanticsNode().boundsInRoot.width > viewport.width * .8f)
        grid.performScrollToNode(hasTestTag("item_grid_card_$id"))
        val card = compose.onNodeWithTag("item_grid_card_$id").fetchSemanticsNode().boundsInRoot
        assertTrue("320dp at 150% font needs one usable library column", card.width > viewport.width * .8f)
    }

    @Test fun wideCollectionsUseAvailableWidthForAtLeastThreeCards() {
        requireSyntheticRuntime()
        check(compose.activity.resources.configuration.screenWidthDp >= 700) { "Run this case in the explicit wide emulator configuration" }
        showCollections(compact = false)
        val favorites = compose.onNodeWithTag("col_card_favorites").fetchSemanticsNode().boundsInRoot
        val inbox = compose.onNodeWithTag("col_card_unfiled").fetchSemanticsNode().boundsInRoot
        val due = compose.onNodeWithTag("col_card_due").fetchSemanticsNode().boundsInRoot
        assertEquals("Three smart collections should fit on one wide row", favorites.top, inbox.top, 1f)
        assertEquals("Three smart collections should fit on one wide row", favorites.top, due.top, 1f)
    }

    @Test fun collectionsReleaseScreenSubscriptionsWhileStoppedAndResumeCurrentState() {
        requireSyntheticRuntime()
        showCollections(compact = true)
        // Observe the real screen's source, without adding a production-only subscription hook.
        val source = MainViewModel::class.java.getDeclaredField("_collections").apply { isAccessible = true }.get(vm) as MutableStateFlow<*>
        compose.waitUntil(5_000) { source.subscriptionCount.value > 0 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        while (source.subscriptionCount.value > 0 && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(50)
        assertEquals("Stopped collection screen must release upstream subscriptions", 0, source.subscriptionCount.value)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5_000) { source.subscriptionCount.value > 0 }
        compose.onNodeWithTag("col_card_favorites").assertIsDisplayed()
    }

    private fun showCollections(compact: Boolean) {
        compose.runOnUiThread {
            compose.activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, if (compact) 1.5f else 1f)) {
                    PersonalLibraryTheme {
                        Box(if (compact) Modifier.width(320.dp).fillMaxSize() else Modifier.fillMaxSize()) {
                            CollectionsScreen(vm, {})
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("collections_grid").assertIsDisplayed()
    }

    private fun requireSyntheticRuntime() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowSyntheticLayoutFixture") == "true")
    }
}
