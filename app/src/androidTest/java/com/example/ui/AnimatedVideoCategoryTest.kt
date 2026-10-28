package com.example.ui

import android.graphics.drawable.Animatable
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.MainActivity
import com.example.data.ItemEntity
import com.example.data.ItemWithCollections
import com.example.ui.components.*
import com.example.ui.theme.PersonalLibraryTheme
import com.personallibrary.core.model.ItemKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Only on the explicitly selected offline synthetic runtime; never on an owner library. */
@RunWith(AndroidJUnit4::class)
class AnimatedVideoCategoryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun realAnimatedGifUsesExistingDecoderAndAnimatesInListGridAndDetails() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaFixture") == "true")
        val context = compose.activity
        val file = File.createTempFile("synthetic-animation-", ".gif", context.cacheDir)
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("qa/whatsapp/Media_Example/GIF-20261017-WA0003.gif").use { input -> file.outputStream().use { input.copyTo(it) } }
            val decoded = runBlocking { context.imageLoader.execute(ImageRequest.Builder(context).data(file).build()) }
            assertTrue("Existing Coil decoder must return animated media", decoded is SuccessResult && decoded.drawable is Animatable)
            val item = ItemWithCollections(ItemEntity("synthetic-gif", "Synthetic animated original", "", "VIDEO", contentHash = "synthetic-gif", mimeType = "image/gif", originalFileName = "original.gif"), emptyList())
            var view by mutableIntStateOf(0)
            compose.runOnUiThread {
                context.setContent {
                    PersonalLibraryTheme {
                        when (view) {
                            0 -> ItemCard(item, { file }, {}, {}, {})
                            1 -> ItemGridCard(item, { file }, {}, {}, {})
                            else -> ItemDetailsDialog(item, { file }, emptyList(), {}, {}, {}, {}, { _, _, _, _ -> })
                        }
                    }
                }
            }
            for (layout in 0..2) {
                compose.runOnIdle { view = layout }
                if (layout == 2) compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
                    .performSemanticsAction(SemanticsActions.Expand) { assertTrue(it()) }
                val image = compose.onNodeWithContentDescription(item.item.title, useUnmergedTree = true)
                image.assertExists()
                val colors = mutableSetOf<Int>()
                // Real drawable time, independent of Compose's virtual animation clock.
                for (sample in 0..19) {
                    val bitmap = image.captureToImage().asAndroidBitmap()
                    val color = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    // Count only the fixture's three real frame colors, never loading -> first frame.
                    if (color in setOf(0xfff0f2ff.toInt(), 0xffe8f9f1.toInt(), 0xfffff4e7.toInt())) colors += color
                    if (colors.size >= 2) break
                    android.os.SystemClock.sleep(120)
                }
                assertTrue("GIF must visibly advance frames in layout $layout", colors.size >= 2)
                compose.onNodeWithText("Saved Video Recording").assertDoesNotExist()
            }
        } finally { file.delete() }
    }

    @Test fun onlyVideoFilterIsExposedForAnimations() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaFixture") == "true")
        var selected by mutableStateOf<ItemKind?>(null)
        compose.runOnUiThread { compose.activity.setContent { PersonalLibraryTheme { TypeFilterChipStrip(selected, { selected = it }) } } }
        compose.onNodeWithTag("filter_chip_gif").assertDoesNotExist()
        compose.onNodeWithTag("filter_chip_video").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(ItemKind.VIDEO, selected) }
    }
}
