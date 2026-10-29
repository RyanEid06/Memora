package com.example.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Renders the installed package icon through Android's real adaptive drawable implementation. */
@RunWith(AndroidJUnit4::class)
class LauncherAssetTest {
    @Test fun installedAdaptiveIconKeepsMeaningfulArtworkInsideSafeZone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue("This platform must exercise the packaged adaptive icon", icon is AdaptiveIconDrawable)
        val bitmap = Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 720, 720)
        icon.draw(Canvas(bitmap))
        var coloredPixels = 0
        var maxRadiusSquared = 0.0
        for (y in 0 until 720) for (x in 0 until 720) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.alpha(pixel) > 128 && maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 100) {
                coloredPixels++
                maxRadiusSquared = maxOf(maxRadiusSquared, (x - 359.5) * (x - 359.5) + (y - 359.5) * (y - 359.5))
            }
        }
        assertTrue("Packaged icon must contain the original artwork", coloredPixels > 30_000)
        assertTrue("Meaningful artwork must stay within the central66dp circle at a72dp viewport", maxRadiusSquared <= 335.0 * 335.0)
        File(context.cacheDir, "phase8-icon-native.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
