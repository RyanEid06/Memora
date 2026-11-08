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
    @Test fun installedAdaptiveIconFillsMaskWithoutBlackBorder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue("This platform must exercise the packaged adaptive icon", icon is AdaptiveIconDrawable)
        val bitmap = Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 720, 720)
        icon.draw(Canvas(bitmap))
        var coloredPixels = 0
        var blackBorderPixels = 0
        for (y in 0 until 720) for (x in 0 until 720) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.alpha(pixel) > 128 && maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 100) {
                coloredPixels++
            }
            if (Color.alpha(pixel) > 250 && maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) <= 8) {
                // Follow the device's actual mask so the emblem's interior shadows are excluded.
                val onRim = x < 4 || y < 4 || x >= 716 || y >= 716 ||
                    Color.alpha(bitmap.getPixel(x - 4, y)) < 128 ||
                    Color.alpha(bitmap.getPixel(x + 4, y)) < 128 ||
                    Color.alpha(bitmap.getPixel(x, y - 4)) < 128 ||
                    Color.alpha(bitmap.getPixel(x, y + 4)) < 128
                if (onRim) blackBorderPixels++
            }
        }
        File(context.cacheDir, "launcher-fullbleed-native.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("Packaged icon must contain the original artwork", coloredPixels > 30_000)
        assertTrue("Only isolated shadow pixels are allowed, never the black export border: $blackBorderPixels pixels", blackBorderPixels <= 720 * 720 / 10000)
        bitmap.recycle()
    }
}
