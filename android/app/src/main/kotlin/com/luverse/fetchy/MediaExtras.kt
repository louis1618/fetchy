package com.luverse.fetchy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

internal class MediaExtras(private val engine: Engine) {
    private fun fetch(source: JSONObject, target: File, a: Engine.Active, limit: Int) {
        val connection = engine.connect(source.getString("url"), source.optJSONObject("headers") ?: JSONObject(), a, 5000)
        try {
            if (connection.responseCode != 200) throw IOException("HTTP Error ${connection.responseCode}")
            require(connection.contentLengthLong <= limit) { "Extra file exceeds size limit" }
            connection.inputStream.use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(16384); var total = 0
                while (true) {
                    engine.checkCanceled(a); val count = input.read(buffer); if (count < 0) break
                    total += count; require(total <= limit) { "Extra file exceeds size limit" }; output.write(buffer, 0, count)
                }
            } }
        } finally { connection.disconnect(); a.connection = null }
    }

    fun audio(source: File, job: JSONObject, a: Engine.Active, progress: (String, Int, Long, Long) -> Unit): File {
        val asset = job.getJSONObject("asset")
        val settings = job.getJSONObject("settings")
        val supplied = job.optJSONObject("audioTags") ?: JSONObject().put("title", asset.optString("track", job.optString("title", asset.optString("title"))))
            .put("artist", asset.optString("artist", asset.optString("creator"))).put("album", asset.optString("album"))
        val tags = JSONObject()
        if (settings.optBoolean("audioMetadata", true)) for (key in listOf("title", "artist", "album")) supplied.optString(key).takeIf { it.isNotBlank() && it != "null" }?.let {
            tags.put(key, it.replace(Regex("[\\p{Cntrl}]"), " ").take(240))
        }
        var cover: File? = null
        if (settings.optBoolean("audioCover", true) && asset.optString("thumbnail").startsWith("https://")) {
            progress("downloading", 98, source.length(), source.length())
            try {
                val raw = File(source.parentFile, "cover-source")
                fetch(JSONObject().put("url", asset.getString("thumbnail")), raw, a, 8388608)
                cover = jpegCover(raw, File(source.parentFile, "cover.jpg"))
            } catch (_: Exception) { engine.checkCanceled(a); warning(job, "커버를 가져오지 못했습니다. 오디오와 태그는 저장합니다.") }
        }
        if (tags.length() == 0 && cover == null) return source
        progress("converting", 98, source.length(), source.length())
        return writeAudio(source, tags, cover, a)
    }

    internal fun jpegCover(source: File, target: File): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384) { "Invalid cover image" }
        val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 256) }
        val bitmap = BitmapFactory.decodeFile(source.path, options) ?: error("Cover decode failed")
        try { target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it)) } }
        finally { bitmap.recycle() }
        require(target.length() in 1..65536) { "Cover image too large" }
        return target
    }

    internal fun writeAudio(source: File, tags: JSONObject, cover: File?, a: Engine.Active): File {
        val ext = source.extension.lowercase(); require(ext in setOf("mp3", "m4a", "opus")) { "Unsupported tagged audio format" }
        val target = File(source.parentFile, "tagged.$ext")
        val args = mutableListOf("-v", "error", "-nostdin", "-y", "-i", source.path)
        if (cover != null && ext != "opus") args.addAll(listOf("-i", cover.path))
        args.addAll(listOf("-map", "0:a:0", "-map_metadata", "0", "-c:a", "copy"))
        if (cover != null && ext != "opus") args.addAll(listOf("-map", "1:v:0", "-c:v", "mjpeg", "-disposition:v:0", "attached_pic"))
        else if (ext != "opus") args.addAll(listOf("-map", "0:v:0?", "-c:v", "copy"))
        for (key in listOf("title", "artist", "album")) if (tags.has(key)) args.addAll(listOf("-metadata", "$key=${tags.getString(key)}"))
        if (cover != null && ext == "opus") {
            val bytes = cover.readBytes(); val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val picture = ByteArrayOutputStream()
            DataOutputStream(picture).use { data ->
                val mime = "image/jpeg".toByteArray(); data.writeInt(3); data.writeInt(mime.size); data.write(mime); data.writeInt(0)
                data.writeInt(bounds.outWidth); data.writeInt(bounds.outHeight); data.writeInt(24); data.writeInt(0); data.writeInt(bytes.size); data.write(bytes)
            }
            args.addAll(listOf("-metadata", "METADATA_BLOCK_PICTURE=${Base64.encodeToString(picture.toByteArray(), Base64.NO_WRAP)}"))
        }
        if (ext == "mp3") args.addAll(listOf("-id3v2_version", "3"))
        args.add(target.path)
        if (engine.native("libffmpeg.so", args, a, 120000).first != 0) throw IOException("Audio metadata write failed")
        return target
    }

    fun subtitles(job: JSONObject, dir: File, a: Engine.Active, progress: (String, Int, Long, Long) -> Unit) {
        val option = job.optJSONObject("subtitle") ?: return
        val language = option.getString("language"); val ext = option.getString("format")
        require(language.matches(Regex("[A-Za-z0-9_.-]{1,64}")) && ext in setOf("srt", "vtt"))
        progress("downloading", 99, 0, -1)
        try {
            var source = try { engine.subtitleSource(job, a) } catch (_: Exception) { engine.checkCanceled(a); engine.subtitleSource(job, a, true) }
            var raw = File(dir, "subtitle-source.${source.getString("ext")}")
            try { fetch(source, raw, a, 8388608) }
            catch (_: Exception) { engine.checkCanceled(a); source = engine.subtitleSource(job, a, true); raw = File(dir, "subtitle-source.${source.getString("ext")}"); fetch(source, raw, a, 8388608) }
            val target = File(dir, "subtitle.$language.$ext")
            convertSubtitle(raw, target, a)
            job.put("sidecars", JSONArray().put(JSONObject().put("path", target.path).put("language", language).put("ext", ext)
                .put("automatic", option.getBoolean("automatic"))))
        } catch (_: Exception) { engine.checkCanceled(a); warning(job, "선택한 자막을 저장하지 못했습니다. 미디어 파일은 저장합니다.") }
    }
    internal fun convertSubtitle(raw: File, target: File, a: Engine.Active) {
        require(raw.length() in 1..8388608 && target.extension in setOf("srt", "vtt"))
        val text = raw.readText(Charsets.UTF_8)
        require(text.isNotBlank() && !text.contains('\u0000') && !text.trimStart().startsWith('<') && !text.trimStart().startsWith('{')) { "Invalid subtitle response" }
        if (raw.extension == target.extension) raw.copyTo(target, overwrite = true)
        else if (engine.native("libffmpeg.so", listOf("-v", "error", "-nostdin", "-y", "-i", raw.path, target.path), a, 30000).first != 0)
            throw IOException("Subtitle conversion failed")
        val output = target.readText(Charsets.UTF_8)
        require(if (target.extension == "vtt") output.trimStart().startsWith("WEBVTT") else Regex("\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}\\s+-->").containsMatchIn(output)) { "Subtitle validation failed" }
    }
    private fun warning(job: JSONObject, message: String) {
        val values = job.optJSONArray("warnings") ?: JSONArray().also { job.put("warnings", it) }; values.put(message)
    }
}
