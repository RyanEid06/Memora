package com.example.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.MainActivity
import com.example.MainAppScaffold
import com.example.data.ItemEntity
import com.example.model.LibraryLayout
import com.example.ui.theme.PersonalLibraryTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicit emulator display and system-font configurations, independent of Compose density overrides. */
@RunWith(AndroidJUnit4::class)
class SystemWindowLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]
    private var ownedId: String? = null

    @After fun cleanup() {
        compose.runOnUiThread { vm.setSearchQuery(""); vm.clearFilters(); vm.setLibraryLayout(LibraryLayout.LIST) }
        runBlocking { ownedId?.let { vm.repository.deletePermanently(it) } }
    }

    @Test fun systemFontAndWindowKeepCollectionActionsAndLibraryCardsReachable() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("allowSyntheticLayoutFixture") == "true")
        val mode = requireNotNull(arguments.getString("layoutMode"))
        val columns = when (mode) { "phone" -> 1; "landscape" -> 3; "tablet" -> 5; else -> error("Unknown synthetic window") }
        val configuration = compose.activity.resources.configuration
        assertEquals("Runner must select actual150% Android font scale", 1.5f, configuration.fontScale, .01f)
        val density = compose.activity.resources.displayMetrics.density
        val id = UUID.randomUUID().toString().also { ownedId = it }
        runBlocking { vm.repository.dao.insertItem(ItemEntity(id, "Synthetic window item", "Synthetic layout evidence $id", "TEXT")) }
        compose.runOnUiThread { compose.activity.setContent { PersonalLibraryTheme { MainAppScaffold(vm, null) } } }
        for ((destination, label) in listOf("library" to "Library", "collections" to "Collections", "settings" to "Settings")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("nav_item_$destination")), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("Navigation label $label must stay on one line at150% font", 1, layouts.single().lineCount)
        }
        compose.onNodeWithTag("nav_item_collections").performClick()
        val collections = compose.onNodeWithTag("collections_grid")
        val collectionWidth = collections.fetchSemanticsNode().boundsInRoot.width / density
        val firstCard = compose.onNodeWithTag("col_card_favorites").fetchSemanticsNode().boundsInRoot.width / density
        assertEquals("Collection columns must follow actual window/font scale", (collectionWidth - 32 - (columns - 1) * 12) / columns, firstCard, 2f)
        saveRender("phase8-system-$mode-collections.png")
        for (tag in listOf("col_card_favorites", "col_card_unfiled", "col_card_due")) {
            collections.performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
        }
        compose.onNodeWithTag("nav_item_library").performClick()
        compose.runOnUiThread { vm.clearFilters(); vm.setSearchQuery(id); vm.setLibraryLayout(LibraryLayout.GRID) }
        compose.waitUntil(15_000) { vm.resultsReady.value && vm.localSearchResults.value.singleOrNull()?.item?.id == id }
        val library = compose.onNodeWithTag("library_grid")
        val libraryWidth = library.fetchSemanticsNode().boundsInRoot.width / density
        library.performScrollToNode(hasTestTag("item_grid_card_$id"))
        val cardWidth = compose.onNodeWithTag("item_grid_card_$id").fetchSemanticsNode().boundsInRoot.width / density
        assertEquals("Library columns must follow actual window/font scale", (libraryWidth - (columns - 1) * 8) / columns, cardWidth, 2f)
        compose.onNodeWithTag("item_grid_card_$id").assertIsDisplayed().assertHasClickAction()
        saveRender("phase8-system-$mode-library.png")
    }

    private fun saveRender(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
