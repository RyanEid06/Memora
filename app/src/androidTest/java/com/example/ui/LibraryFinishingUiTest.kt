package com.example.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.MainActivity
import com.example.data.CollectionEntity
import com.example.data.ItemCollectionCrossRef
import com.example.data.ItemEntity
import com.example.model.DateFilter
import com.example.model.LibraryLayout
import com.example.ui.screens.LibraryScreen
import com.example.ui.theme.PersonalLibraryTheme
import com.personallibrary.core.model.ItemKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File

/** Own synthetic rows only; viewport/font changes are confined to composition. */
@RunWith(AndroidJUnit4::class)
class LibraryFinishingUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]
    private val token = "finishing${UUID.randomUUID().toString().replace("-", "")}"
    private val ids = mutableListOf<String>()
    private val collectionId = "finishing-collection-${UUID.randomUUID()}"
    private var collectionCreated = false

    @After fun cleanup() {
        compose.runOnUiThread {
            vm.clearSelection(); vm.activateBrowseContext(null); vm.clearFilters()
            vm.setSearchQuery(""); vm.setLibraryLayout(LibraryLayout.LIST)
        }
        runBlocking {
            ids.forEach { vm.repository.deletePermanently(it) }
            if (collectionCreated) {
                val deletion = vm.repository.deleteCollection(collectionId, undoMillis = 0)
                check(vm.repository.finalizeCollectionDeletion(deletion.operationId))
            }
        }
    }

    @Test fun scopedCollapsedAndExpandedFiltersStayCompactAndClearPreservesScopeAndQuery() {
        runBlocking { vm.repository.dao.insertCollection(CollectionEntity(collectionId, "Uni")) }
        collectionCreated = true
        val inside = seed("inside", collectionId)
        seed("outside")
        compose.runOnUiThread { vm.activateBrowseContext(collectionId); vm.setSearchQuery(token) }
        showLibrary(LibraryLayout.LIST, scope = collectionId, fontScale = 1.5f)
        waitForRows(setOf(inside))
        compose.onNodeWithTag("clear_filters_button").assertDoesNotExist()
        compose.onNodeWithTag("locked_collection_scope").assertDoesNotExist()
        for (layout in listOf(LibraryLayout.LIST, LibraryLayout.GRID)) {
            compose.runOnUiThread { vm.setLibraryLayout(layout); vm.selectKind(ItemKind.TEXT); vm.setDateFilter(DateFilter.THIS_MONTH) }
            waitForRows(setOf(inside))
            val host = host(layout)
            compose.waitUntil(10_000) { host.isDisplayed() }
            host.performScrollToNode(hasTestTag("active_filter_chips"))
            compose.onNodeWithTag("active_filter_chips").performScrollToNode(hasTestTag("active_type_chip"))
            compose.onNodeWithTag("active_type_chip").assertIsDisplayed()
            val label = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("Type: Notes & Text", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(label) }
            assertFalse("The full type label remains readable in $layout", label.single().hasVisualOverflow)
            compose.onNodeWithTag("clear_filters_button").assertIsDisplayed()
            saveRender("${layout.name.lowercase()}-collapsed-320dp-150pct")
            host.performScrollToNode(hasTestTag("filters_sort_button"))
            compose.onNodeWithTag("filters_sort_button").performClick()
            compose.onNodeWithTag("active_type_chip").assertDoesNotExist()
            host.performScrollToNode(hasTestTag("type_filter_strip"))
            compose.onNodeWithTag("type_filter_strip").performScrollToNode(hasTestTag("filter_chip_text"))
            compose.onNodeWithTag("filter_chip_text").assertIsSelected()
            host.performScrollToNode(hasTestTag("date_filter_button"))
            compose.onNodeWithTag("date_filter_button").assertIsDisplayed()
            saveRender("${layout.name.lowercase()}-expanded-320dp-150pct")
            compose.onNodeWithTag("sort_order_button").performScrollTo().assertIsDisplayed()
            host.performScrollToNode(hasTestTag("clear_filters_button"))
            compose.onNodeWithTag("clear_filters_button").performClick()
            waitForRows(setOf(inside))
            compose.runOnIdle {
                assertEquals(collectionId, vm.selectedCollectionId.value)
                assertEquals(token, vm.searchQuery.value)
                assertNull(vm.selectedKind.value)
                assertEquals(DateFilter.ALL_TIME, vm.dateFilter.value)
            }
            compose.onNodeWithTag("clear_filters_button").assertDoesNotExist()
            host.performScrollToNode(hasTestTag("filters_sort_button"))
            compose.onNodeWithTag("filters_sort_button").performClick()
        }
    }

    @Test fun compactFabHidesDuringSelectionAndLastCardScrollsAboveItInListAndGrid() {
        val seeded = (1..4).map { seed("item $it") }
        compose.runOnUiThread { vm.activateBrowseContext(null); vm.clearFilters(); vm.setSearchQuery(token) }
        showLibrary(LibraryLayout.LIST, fontScale = 1f)
        waitForRows(seeded.toSet())
        for (layout in listOf(LibraryLayout.LIST, LibraryLayout.GRID)) {
            compose.runOnUiThread { vm.setLibraryLayout(layout) }
            val host = host(layout)
            compose.waitUntil(10_000) { host.isDisplayed() }
            val fab = compose.onNodeWithTag("fab_add").assertIsDisplayed()
            val fabBounds = fab.getUnclippedBoundsInRoot()
            assertEquals("Compact Add uses a square footprint",
                (fabBounds.right - fabBounds.left).value, (fabBounds.bottom - fabBounds.top).value, 1f)
            fab.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            compose.runOnUiThread { vm.beginSelection() }
            fab.assertDoesNotExist()
            saveRender("${layout.name.lowercase()}-selection-320dp")
            compose.runOnUiThread { vm.clearSelection() }
            fab.assertIsDisplayed()
            val lastId = vm.localSearchResults.value.last().item.id
            val lastTag = if (layout == LibraryLayout.LIST) "item_card_$lastId" else "item_grid_card_$lastId"
            host.performScrollToNode(hasTestTag(lastTag))
            repeat(2) { host.performTouchInput { swipeUp() } }
            val last = compose.onNodeWithTag(lastTag).getUnclippedBoundsInRoot()
            val floating = fab.getUnclippedBoundsInRoot()
            assertTrue("The last card including actions scrolls above Add", last.bottom <= floating.top)
            saveRender("${layout.name.lowercase()}-last-card-320dp")
        }
    }

    private fun showLibrary(layout: LibraryLayout, scope: String? = null, fontScale: Float) {
        compose.runOnUiThread {
            vm.setLibraryLayout(layout)
            compose.activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                    PersonalLibraryTheme {
                        Box(Modifier.width(320.dp).fillMaxSize()) { LibraryScreen(vm, {}, {}, {}, {}, collectionScope = scope) }
                    }
                }
            }
        }
    }

    private fun host(layout: LibraryLayout) = compose.onNodeWithTag(if (layout == LibraryLayout.LIST) "library_list" else "library_grid")

    private fun waitForRows(expected: Set<String>) {
        compose.waitUntil(15_000) { vm.resultsReady.value && vm.localSearchResults.value.map { it.item.id }.toSet() == expected }
        compose.waitForIdle()
    }

    private fun saveRender(state: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "finishing-library-$state.png")
                .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    private fun seed(label: String, scope: String? = null): String {
        val id = "finishing-item-${UUID.randomUUID()}".also(ids::add)
        runBlocking {
            vm.repository.dao.insertItem(ItemEntity(id, "Synthetic $label", token, "TEXT"))
            if (scope != null) vm.repository.dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(id, scope))
        }
        return id
    }
}
