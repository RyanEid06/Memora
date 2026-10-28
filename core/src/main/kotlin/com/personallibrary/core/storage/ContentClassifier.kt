package com.personallibrary.core.storage

import com.personallibrary.core.model.ItemKind

object ContentClassifier {
    /** The public Video category includes GIFs; their original container still chooses the renderer. */
    fun isAnimatedGif(mimeType: String?, fileName: String?): Boolean {
        val mime = resolveMimeType(mimeType, fileName).lowercase().substringBefore(";").trim()
        return mime == "image/gif" || (!mime.startsWith("video/") && fileName?.trim()?.endsWith(".gif", ignoreCase = true) == true)
    }

    fun normalizeLegacyKind(kind: String, mimeType: String?, fileName: String?): String =
        if (kind == "GIF" || (kind in setOf("PHOTO", "VIDEO", "FILE") && isAnimatedGif(mimeType, fileName))) "VIDEO" else kind


    fun resolveMimeType(mimeType: String?, fileName: String?): String {
        val declared = mimeType?.lowercase()?.substringBefore(";")?.trim().orEmpty()
        // WhatsApp streams have no provider MIME; preserve the original's known media type for opening/indexing.
        return if (declared.isEmpty() || declared == "application/octet-stream") {
            fileName?.let { java.net.URLConnection.guessContentTypeFromName(it.lowercase()) }
                ?: "application/octet-stream"
        } else checkNotNull(mimeType)
    }

    fun classify(
        mimeType: String?,
        fileName: String?,
        explicitSticker: Boolean = false
    ): ItemKind {
        if (explicitSticker) return ItemKind.STICKER

        val normalizedMime = resolveMimeType(mimeType, fileName).lowercase().substringBefore(";").trim()
        val normalizedName = fileName?.lowercase()?.trim() ?: ""
        val extension = normalizedName.substringAfterLast('.', "")

        // Explicit sticker filenames or webp sticker detection
        if ((normalizedName.contains("sticker") || normalizedName.startsWith("stk-")) &&
            (normalizedMime.contains("webp") || extension == "webp")) {
            return ItemKind.STICKER
        }

        if (isAnimatedGif(mimeType, fileName)) return ItemKind.VIDEO

        // WebP without explicit sticker hint is classified as PHOTO (spec requirement)
        if (normalizedMime == "image/webp" || extension == "webp") {
            return ItemKind.PHOTO
        }

        if (normalizedMime.startsWith("image/")) {
            return ItemKind.PHOTO
        }

        if (normalizedMime.startsWith("video/")) {
            return ItemKind.VIDEO
        }

        if (normalizedMime.startsWith("audio/")) {
            return ItemKind.AUDIO
        }

        // Documents and text files (including octet-stream with json/txt extension)
        val docExtensions = setOf("pdf", "doc", "docx", "txt", "json", "csv", "rtf", "md", "epub")
        if (extension in docExtensions) {
            return ItemKind.DOCUMENT
        }

        if (normalizedMime == "application/pdf" ||
            normalizedMime == "text/plain" ||
            normalizedMime == "application/json" ||
            normalizedMime == "text/csv" ||
            normalizedMime.contains("officedocument") ||
            normalizedMime.contains("msword")
        ) {
            return ItemKind.DOCUMENT
        }

        // Link detection
        if (normalizedMime == "text/uri-list" || normalizedName.endsWith(".url")) {
            return ItemKind.LINK
        }

        return ItemKind.FILE
    }
}
