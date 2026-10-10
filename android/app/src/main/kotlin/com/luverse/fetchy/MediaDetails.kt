package com.luverse.fetchy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Own-document previews only. No shared-library permission and no plaintext source-URL cache keys. */
class MediaDetails(private val context: Context, private val store: Store) {
    fun read(id: String): JSONObject {
        val record = store.get("files", id) ?: error("File not found")
        val uri = Uri.parse(record.getString("uri"))
        val mime = record.optString("mime")
        val cache = File(context.cacheDir, "previews").apply { mkdirs() }
        val key = MessageDigest.getInstance("SHA-256").digest(record.getString("uri").toByteArray()).joinToString("") { "%02x".format(it) }
        val preview = File(cache, "$key.jpg")
        if (!record.optBoolean("metadataRead") || record.optInt("metadataVersion") < 2 || !preview.exists()) {
            var bitmap: Bitmap? = null
            try {
                if (mime.startsWith("image/")) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
                    if (bounds.outWidth > 0) record.put("width", bounds.outWidth).put("height", bounds.outHeight)
                    val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 512) }
                    bitmap = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, options) }
                } else if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        if (mime.startsWith("audio/")) {
                            for ((key, code) in listOf("title" to MediaMetadataRetriever.METADATA_KEY_TITLE, "artist" to MediaMetadataRetriever.METADATA_KEY_ARTIST, "album" to MediaMetadataRetriever.METADATA_KEY_ALBUM))
                                retriever.extractMetadata(code)?.takeIf { it.isNotBlank() }?.let { record.put(key, it) }
                        }
                        fun number(key: Int) = retriever.extractMetadata(key)?.toDoubleOrNull()
                        number(MediaMetadataRetriever.METADATA_KEY_DURATION)?.let { record.put("duration", it / 1000) }
                        number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.let { record.put("width", it.toInt()) }
                        number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.let { record.put("height", it.toInt()) }
                        number(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.let { if (it > 0) record.put("fps", it) }
                        if (mime.startsWith("video/")) bitmap = if (android.os.Build.VERSION.SDK_INT >= 27)
                            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512, 512)
                            else retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        else retriever.embeddedPicture?.let { bitmap = BitmapFactory.decodeByteArray(it, 0, it.size) }
                    } finally { retriever.release() }
                }
                bitmap?.let { source ->
                    val scaled = if (maxOf(source.width, source.height) > 512) {
                        val factor = 512.0 / maxOf(source.width, source.height)
                        Bitmap.createScaledBitmap(source, (source.width * factor).toInt().coerceAtLeast(1), (source.height * factor).toInt().coerceAtLeast(1), true)
                    } else source
                    preview.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    if (scaled !== source) scaled.recycle()
                }
                record.put("metadataRead", true).put("metadataVersion", 2)
                store.put("files", record)
            } finally { bitmap?.recycle() }
        }
        if (preview.exists()) record.put("previewPath", preview.absolutePath)
        // Bounded disposable cache; do not evict the preview this response hands to Flutter.
        cache.listFiles()?.filter { it != preview }?.sortedByDescending { it.lastModified() }?.drop(199)?.forEach { it.delete() }
        return record
    }
}
