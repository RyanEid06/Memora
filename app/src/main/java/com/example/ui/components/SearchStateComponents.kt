package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.CollectionEntity
import com.example.model.DateBasis
import com.example.model.DateFilter
import com.personallibrary.core.model.ItemKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun hasSearchFilters(collectionId: String?, kind: ItemKind?, dateFilter: DateFilter, collectionLocked: Boolean = false): Boolean =
    (collectionId != null && !collectionLocked) || kind != null || dateFilter != DateFilter.ALL_TIME

/** Browsing scope is shown by the title; only user-clearable constraints belong here. */
@Composable
fun ActiveSearchFilters(
    collectionId: String?,
    collections: List<CollectionEntity>,
    kind: ItemKind?,
    dateFilter: DateFilter,
    dateBasis: DateBasis,
    customStart: Long?,
    customEnd: Long?,
    onClearCollection: () -> Unit,
    onClearKind: () -> Unit,
    onClearDate: () -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier.padding(horizontal = 16.dp),
    collectionLocked: Boolean = false
) {
    if (!hasSearchFilters(collectionId, kind, dateFilter, collectionLocked)) return
    Row(modifier.fillMaxWidth().testTag("active_search_filters"),
        verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(Modifier.weight(1f)) {
            val chipWidth = maxWidth
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().testTag("active_filter_chips")
            ) {
                if (collectionId != null && !collectionLocked) item {
                    val name = when (collectionId) {
                        "computed_favorites" -> "Favorites"
                        "computed_unfiled" -> "Inbox"
                        "computed_due" -> "Reminders"
                        else -> collections.find { it.id == collectionId }?.name ?: "Collection"
                    }
                    ActiveFilterChip("Collection: $name", "active_collection_chip", "Clear collection", chipWidth, onClearCollection)
                }
                if (kind != null) item {
                    ActiveFilterChip("Type: ${kind.displayName}", "active_type_chip", "Clear type", chipWidth, onClearKind)
                }
                if (dateFilter != DateFilter.ALL_TIME) item {
                    val range = if (dateFilter == DateFilter.CUSTOM && customStart != null && customEnd != null) {
                        val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
                        val zone = ZoneId.systemDefault()
                        "${formatter.format(Instant.ofEpochMilli(customStart).atZone(zone))} – ${formatter.format(Instant.ofEpochMilli(customEnd).atZone(zone))}"
                    } else dateFilter.label
                    ActiveFilterChip("${dateBasis.label}: $range", "active_date_chip", "Clear date", chipWidth, onClearDate)
                }
            }
        }
        ClearSearchFiltersButton(onClearFilters)
    }
}

@Composable
internal fun ClearSearchFiltersButton(onClear: () -> Unit) {
    TextButton(
        onClick = onClear,
        modifier = Modifier.widthIn(max = 112.dp).testTag("clear_filters_button"),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text("Clear Filters", textAlign = TextAlign.Center)
    }
}

@Composable
private fun ActiveFilterChip(label: String, tag: String, clearDescription: String, maxWidth: Dp, onClear: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onClear,
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = clearDescription, modifier = Modifier.size(16.dp)) },
        modifier = Modifier.widthIn(max = maxWidth).testTag(tag)
    )
}

/** Loading and errors take precedence over empty results; no false-empty flash on refresh. */
@Composable
fun LibraryResultState(
    resultsReady: Boolean,
    error: String?,
    libraryEmpty: Boolean,
    query: String,
    filtersActive: Boolean,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
    collectionScope: String? = null
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp).testTag("library_result_state"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            error != null -> Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.testTag("local_search_error"))
            !resultsReady -> {
                CircularProgressIndicator(modifier = Modifier.testTag("local_search_loading"))
                Text("Searching your library…")
            }
            else -> {
                Text(
                    when {
                        query.isNotBlank() -> "No results found"
                        filtersActive -> "No items match these filters"
                        collectionScope == "computed_favorites" -> "No favorites yet"
                        collectionScope == "computed_unfiled" -> "Inbox is empty"
                        collectionScope == "computed_due" -> "No reminders yet"
                        collectionScope != null -> "This collection is empty"
                        libraryEmpty -> "Your library is empty"
                        else -> "No results found"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Text(
                    when {
                        query.isNotBlank() && filtersActive -> "Try different keywords or clear your filters."
                        query.isNotBlank() -> "Try different keywords."
                        filtersActive -> "Clear your filters to see more items."
                        collectionScope == "computed_favorites" -> "Mark an item as a favorite to find it here."
                        collectionScope == "computed_unfiled" -> "Items without a collection appear here."
                        collectionScope == "computed_due" -> "Set a reminder on an item to find it here."
                        collectionScope != null -> "Add items to this collection to see them here."
                        else -> "Add a note, save a link, or import files to get started."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                if (filtersActive) TextButton(onClick = onClearFilters, modifier = Modifier.testTag("empty_clear_filters_button")) { Text("Clear Filters", maxLines = 1, softWrap = false) }
            }
        }
    }
}
