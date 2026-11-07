package com.example.ui.components

import com.example.model.DateFilter
import com.personallibrary.core.model.ItemKind
import org.junit.Assert.*
import org.junit.Test

class SearchFilterStateTest {
    @Test fun noSelectionHasNoClearableFilters() {
        assertFalse(hasSearchFilters(null, null, DateFilter.ALL_TIME))
    }

    @Test fun explicitCollectionTypeAndDateSelectionsAreClearable() {
        assertTrue(hasSearchFilters("chosen-collection", null, DateFilter.ALL_TIME))
        assertTrue(hasSearchFilters(null, ItemKind.TEXT, DateFilter.ALL_TIME))
        assertTrue(hasSearchFilters(null, null, DateFilter.THIS_MONTH))
    }

    @Test fun lockedBrowsingScopeAloneIsNotClearable() {
        for (scope in listOf("uni", "computed_favorites", "computed_unfiled", "computed_due")) {
            assertFalse(hasSearchFilters(scope, null, DateFilter.ALL_TIME, collectionLocked = true))
            assertTrue(hasSearchFilters(scope, ItemKind.TEXT, DateFilter.ALL_TIME, collectionLocked = true))
            assertTrue(hasSearchFilters(scope, null, DateFilter.THIS_MONTH, collectionLocked = true))
        }
    }
}
