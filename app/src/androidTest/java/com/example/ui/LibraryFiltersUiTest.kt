package com.example.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.MainActivity
import com.example.data.CollectionEntity
import com.example.model.DateBasis
import com.example.model.DateFilter
import com.example.ui.components.ActiveSearchFilters
import com.example.ui.components.LibraryResultState
import com.example.ui.theme.PersonalLibraryTheme
import com.personallibrary.core.model.ItemKind
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryFiltersUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun lockedCollectionScopeIsNotAFilter() {
        showFilters(kind = null, date = DateFilter.ALL_TIME, locked = true)
        compose.onNodeWithTag("active_search_filters").assertDoesNotExist()
        compose.onNodeWithTag("locked_collection_scope").assertDoesNotExist()
        compose.onNodeWithTag("clear_filters_button").assertDoesNotExist()
    }

    @Test fun collapsedChipsAndClearShareOneRowAtNarrowLargeFont() {
        showFilters(kind = ItemKind.TEXT, date = DateFilter.THIS_MONTH, locked = true)
        val chips = compose.onNodeWithTag("active_filter_chips")
        val clear = compose.onNodeWithTag("clear_filters_button").assertIsDisplayed()
        val chipBounds = chips.fetchSemanticsNode().boundsInRoot
        val clearBounds = clear.fetchSemanticsNode().boundsInRoot
        assertTrue("Chips use the left of the same row", chipBounds.right <= clearBounds.left + 1f)
        assertTrue("Clear must vertically overlap the chips", clearBounds.top < chipBounds.bottom && chipBounds.top < clearBounds.bottom)
        val type = compose.onNodeWithTag("active_type_chip").getUnclippedBoundsInRoot()
        assertTrue("A full chip, including its remove action, fits the scrolling viewport",
            type.right <= chips.getUnclippedBoundsInRoot().right + 1.dp)
        val label = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Type: Notes & Text", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(label) }
        assertTrue("The selected type remains readable at 150% font", !label.single().hasVisualOverflow)
        chips.performScrollToNode(hasTestTag("active_date_chip"))
        compose.onNodeWithTag("active_date_chip").assertIsDisplayed()
        clear.assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(cleared) }
    }

    @Test fun zeroSearchResultsTakePrecedenceOverEmptyLibraryAndFilters() {
        compose.runOnUiThread {
            compose.activity.setContent {
                PersonalLibraryTheme { LibraryResultState(true, null, true, "missing query", true, {}) }
            }
        }
        compose.onNodeWithText("No results found").assertIsDisplayed()
        compose.onNodeWithText("Your library is empty").assertDoesNotExist()
        compose.onNodeWithTag("empty_clear_filters_button").assertIsDisplayed()
    }

    @Test fun emptyScopesUseTheirOwnMessageAndNeverSuggestClearing() {
        var scope by mutableStateOf<String?>(null)
        compose.runOnUiThread {
            compose.activity.setContent {
                PersonalLibraryTheme { LibraryResultState(true, null, true, "", false, {}, collectionScope = scope) }
            }
        }
        for ((id, title) in listOf(
            null to "Your library is empty",
            "synthetic-uni" to "This collection is empty",
            "computed_favorites" to "No favorites yet",
            "computed_unfiled" to "Inbox is empty",
            "computed_due" to "No reminders yet"
        )) {
            compose.runOnIdle { scope = id }
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.onNodeWithTag("empty_clear_filters_button").assertDoesNotExist()
            compose.onNodeWithText("Clear your filters to see more items.").assertDoesNotExist()
        }
    }

    @Test fun clearingActualFiltersReturnsToCollectionEmptyState() {
        var filtered by mutableStateOf(true)
        compose.runOnUiThread {
            compose.activity.setContent {
                PersonalLibraryTheme {
                    LibraryResultState(true, null, false, "", filtered, { filtered = false }, collectionScope = "synthetic-uni")
                }
            }
        }
        compose.onNodeWithText("No items match these filters").assertIsDisplayed()
        compose.onNodeWithTag("empty_clear_filters_button").performClick()
        compose.onNodeWithText("This collection is empty").assertIsDisplayed()
        compose.onNodeWithTag("empty_clear_filters_button").assertDoesNotExist()
    }

    @Test fun queryWithoutFiltersNeverSuggestsClearing() {
        compose.runOnUiThread {
            compose.activity.setContent {
                PersonalLibraryTheme { LibraryResultState(true, null, false, "missing", false, {}) }
            }
        }
        compose.onNodeWithText("No results found").assertIsDisplayed()
        compose.onNodeWithText("Try different keywords.").assertIsDisplayed()
        compose.onNodeWithTag("empty_clear_filters_button").assertDoesNotExist()
    }

    private var cleared = false

    private fun showFilters(kind: ItemKind?, date: DateFilter, locked: Boolean) {
        compose.runOnUiThread {
            compose.activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                    PersonalLibraryTheme {
                        Box(Modifier.width(320.dp)) {
                            ActiveSearchFilters("synthetic-uni", listOf(CollectionEntity("synthetic-uni", "Uni")),
                                kind, date, DateBasis.SAVED_DATE, null, null, {}, {}, {},
                                { cleared = true }, collectionLocked = locked)
                        }
                    }
                }
            }
        }
    }
}
