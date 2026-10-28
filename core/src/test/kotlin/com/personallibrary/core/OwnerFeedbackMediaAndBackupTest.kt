package com.personallibrary.core

import com.personallibrary.core.backup.EncryptedBackupEngine
import com.personallibrary.core.backup.BackupManifest
import com.personallibrary.core.storage.ContentClassifier
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class OwnerFeedbackMediaAndBackupTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun whatsAppStickerNamesAreNotOrdinaryPhotos() {
        assertEquals("STICKER", ContentClassifier.classify("image/webp", "STK-20261024-WA0019.webp").name)
        assertEquals("STICKER", ContentClassifier.classify(null, "stk-20261024-wa0019.WEBP").name)
        assertEquals("PHOTO", ContentClassifier.classify("image/webp", "holiday.webp").name)
    }

    @Test fun animatedGifAndWhatsAppMp4ShareTheVideoCategory() {
        assertEquals("VIDEO", ContentClassifier.classify("image/gif", "animation.gif").name)
        assertEquals("VIDEO", ContentClassifier.classify(null, "GIF-20261024.gif").name)
        assertEquals("VIDEO", ContentClassifier.classify("video/mp4", "animation.mp4").name)
        assertEquals("VIDEO", ContentClassifier.classify("IMAGE/GIF; charset=binary", "extensionless").name)
        assertEquals("VIDEO", ContentClassifier.classify("application/octet-stream", "ANIMATION.GIF").name)
        assertEquals("PHOTO", ContentClassifier.classify("image/png", "animation.png").name)
    }

    @Test fun gifWireValueRemainsReadableButIsAbsentFromPublicKinds() {
        assertEquals("GIF", com.personallibrary.core.model.ItemKind.valueOf("GIF").name)
        assertFalse(com.personallibrary.core.model.ItemKind.publicKinds.any { it.name == "GIF" })
        assertEquals("VIDEO", com.personallibrary.core.model.ItemKind.canonicalName("GIF"))
    }

    @Test fun videoCategoryChoosesItsRendererFromTheOriginalContainer() {
        assertTrue(ContentClassifier.isAnimatedGif("IMAGE/GIF; charset=binary", "unknown"))
        assertTrue(ContentClassifier.isAnimatedGif(null, "animation.GIF"))
        assertTrue(ContentClassifier.isAnimatedGif("application/octet-stream", "animation.gif"))
        assertFalse(ContentClassifier.isAnimatedGif("video/mp4", "whatsapp-gif.mp4"))
        assertFalse(ContentClassifier.isAnimatedGif("video/mp4", "misnamed.gif"))
        assertEquals("VIDEO", ContentClassifier.normalizeLegacyKind("GIF", "image/gif", "animation.gif"))
        assertEquals("VIDEO", ContentClassifier.normalizeLegacyKind("PHOTO", "IMAGE/GIF; charset=binary", "unknown"))
        assertEquals("STICKER", ContentClassifier.normalizeLegacyKind("STICKER", "image/webp", "sticker.webp"))
    }

    @Test fun eightCharacterPasswordEncryptsAndSevenDoesNot() {
        val engine = EncryptedBackupEngine()
        val output = ByteArrayOutputStream()
        val manifest = BackupManifest(items = emptyList(), collections = emptyList(), memberships = emptyList(), reminders = emptyList(), assets = emptyList())
        engine.exportArchive(output, "12345678".toCharArray(), manifest) { null }
        assertTrue(output.size() > 0)
        try {
            engine.exportArchive(ByteArrayOutputStream(), "1234567".toCharArray(), manifest) { null }
            fail("Seven-character passwords must remain rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
