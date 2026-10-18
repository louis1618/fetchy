package com.luverse.fetchy

import android.content.Context
import android.webkit.MimeTypeMap
import android.util.Log
import android.os.SystemClock
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// Morit media.analysis.engines.runtime._analyze_as_playlist: watch/mix URLs are one video.
internal fun analyzeAsPlaylist(uri: URI): Boolean {
    val host = uri.host.orEmpty().lowercase()
    val path = uri.path.orEmpty()
    if (isInstagramStory(uri) && !path.startsWith("/stories/highlights/") && Regex("/\\d+/?$").containsMatchIn(path)) return false
    return !(host == "youtu.be" || host.endsWith(".youtu.be") ||
        ((host == "youtube.com" || host.endsWith(".youtube.com")) &&
            (path.trimEnd('/') == "/watch" || listOf("/shorts/", "/live/", "/embed/", "/v/").any { path.startsWith(it) })))
}

/** Blocking API: the runner supplies a worker thread and owns persistent job state. */
class Engine(private val context: Context, private val cookies: CookieVault) {
    internal class Active {
        val canceled = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        @Volatile var connection: HttpURLConnection? = null
        @Volatile var processId: String? = null
        @Volatile var nativeProcess: Process? = null
    }
    private val active = ConcurrentHashMap<String, Active>()
    private var bundledHash = ""
    private var version = ""
    private var ffmpegVersion = ""
    @Volatile private var initialized = false
    private val store = Store(context)

    private fun initialize() {
        if (initialized) return
        synchronized(INIT_LOCK) {
            if (initialized) return@synchronized
            YoutubeDL.init(context.applicationContext)
            FFmpeg.init(context.applicationContext)
            require(File(context.applicationInfo.nativeLibraryDir, "libfetchyqjs.so").canExecute()) { "Bundled QuickJS executable is missing" }
            val target = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open("yt-dlp").use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            bundledHash = digest.digest().joinToString("") { "%02x".format(it) }
            BundledPython.install(context, target, bundledHash)
            for ((asset, module) in mapOf("fetchy_instagram_story.py" to "fetchy_story.py", "fetchy_photos.py" to "fetchy_photos.py")) {
                val extractor = File(context.noBackupFilesDir, "fetchy-extractors/instagram/yt_dlp_plugins/extractor/$module")
                val code = context.assets.open(asset).use { it.readBytes() }
                if (!extractor.isFile || !extractor.readBytes().contentEquals(code)) {
                    check(extractor.parentFile!!.isDirectory || extractor.parentFile!!.mkdirs())
                    val atomic = android.util.AtomicFile(extractor)
                    val stream = atomic.startWrite()
                    try { stream.write(code); atomic.finishWrite(stream) }
                    catch (error: Exception) { atomic.failWrite(stream); throw error }
                }
            }
            initialized = true
        }
    }

    private fun versions() = synchronized(INIT_LOCK) {
        if (version.isEmpty()) {
            val detected = YoutubeDL.execute(YoutubeDLRequest(emptyList<String>()).addOption("--version")).out.trim()
            ffmpegVersion = native("libffmpeg.so", listOf("-version"), Active(), 20000).second.lineSequence().firstOrNull().orEmpty()
            version = detected
        }
    }

    fun info(): JSONObject = try {
        initialize()
        versions()
        JSONObject().put("status", "ready").put("engine", "local").put("ytDlp", version)
            .put("python", "3.12").put("wrapper", "0.18.1").put("bundleSha256", bundledHash).put("ffmpegVersion", ffmpegVersion).put("quickjsVersion", "QuickJS-NG 0.17.0")
            .put("ffmpeg", File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").canExecute())
            .put("quickjs", File(context.applicationInfo.nativeLibraryDir, "libfetchyqjs.so").canExecute())
            .put("ffprobe", File(context.applicationInfo.nativeLibraryDir, "libffprobe.so").canExecute())
    } catch (_: Exception) { JSONObject().put("status", "unavailable").put("error", "Bundled media engine could not initialize") }

    private fun request(url: String?, analysis: Boolean = false): YoutubeDLRequest {
        val cache = File(context.noBackupFilesDir, "extractor-cache")
        // The wrapper disables yt-dlp's player/EJS cache unless --cache-dir is explicit.
        // Keep reusable public player processing, without reducing clients or formats.
        synchronized(INFO_LOCK) {
            val files = cache.walkTopDown().filter { it.isFile }.sortedByDescending { it.lastModified() }.toList()
            var kept = 0L
            for (file in files) {
                kept += file.length()
                if (kept > 33554432 || System.currentTimeMillis() - file.lastModified() > 7 * 86400000L) file.delete()
            }
        }
        val source = url?.let(::canonicalMediaUrl)
        val req = (if (source == null) YoutubeDLRequest(emptyList<String>()) else YoutubeDLRequest(source)).addOption("--ignore-config").addOption("--no-update")
            .addOption("--cache-dir", cache.absolutePath)
            .addOption("--plugin-dirs", File(context.noBackupFilesDir, "fetchy-extractors").absolutePath)
            .addOption("--socket-timeout", if (analysis) 8 else 20).addOption("--retries", if (analysis) 1 else 3)
            .addOption("--extractor-retries", if (analysis) 0 else 3)
            .addOption("--js-runtimes", "quickjs:${File(context.applicationInfo.nativeLibraryDir, "libfetchyqjs.so").absolutePath}")
        return req
    }

    private fun cookieFile(url: String): File? {
        val id = CookieVault.platformForUrl(url) ?: return null
        return cookies.temporary(id, UUID.randomUUID().toString())
    }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    /** Public metadata is independent of Python, format extraction and the download queue. */
    fun preview(rawUrl: String, id: String = "preview-${UUID.randomUUID()}"): JSONObject? {
        val a = active.computeIfAbsent(id) { Active() }
        check(a.started.compareAndSet(false, true)) { "Preview is already running" }
        val start = SystemClock.elapsedRealtime()
        val finished = AtomicBoolean(false)
        val deadline = Thread {
            try { Thread.sleep(8000); if (!finished.get()) cancel(id) } catch (_: InterruptedException) { }
        }.apply { isDaemon = true; start() }
        try {
            checkCanceled(a)
            val canonical = canonicalMediaUrl(rawUrl)
            val key = hash(canonical)
            val cached = store.get("previewCache", key)
            if (cached != null && System.currentTimeMillis() - cached.optLong("createdAt") in 0 until 600000) {
                return cached.getJSONObject("preview").put("sourceUrl", rawUrl.trim())
            }
            val uri = checkedUrl(canonical)
            val platform = mediaPlatform(uri)
            // Stories use authenticated extraction. Public post embeds cannot preview them.
            if (isInstagramStory(uri)) return null
            val preview = when {
                extension(uri.path) in MEDIA_EXTENSIONS -> JSONObject().put("title", File(uri.path).name)
                    .put("thumbnail", if (kind(extension(uri.path)) == "image") uri.toString() else "")
                platform == "YouTube" && !analyzeAsPlaylist(uri) -> {
                    val body = textResponse("https://www.youtube.com/oembed?format=json&url=${URLEncoder.encode(canonical, "UTF-8")}", a, 65536)
                    val data = JSONObject(body)
                    JSONObject().put("title", data.optString("title")).put("thumbnail", PreviewMetadata.safeImage(data.optString("thumbnail_url")))
                }
                platform in setOf("TikTok", "Vimeo") -> {
                    val endpoint = if (platform == "TikTok") "https://www.tiktok.com/oembed" else "https://vimeo.com/api/oembed.json"
                    try {
                        val data = JSONObject(textResponse("$endpoint?url=${URLEncoder.encode(canonical, "UTF-8")}", a, 65536))
                        JSONObject().put("title", data.optString("title").ifBlank { data.optString("author_name") })
                            .put("thumbnail", PreviewMetadata.safeImage(data.optString("thumbnail_url")))
                    } catch (_: Exception) {
                        checkCanceled(a)
                        PreviewMetadata.fromHtml(textResponse(canonical, a, 1048576)) ?: return null
                    }
                }
                platform == "Instagram" -> {
                    try {
                        val model = analyzeInstagram(uri, a)
                        JSONObject().put("title", model.optString("title")).put("thumbnail", model.getJSONArray("assets").optJSONObject(0)?.optString("thumbnail"))
                    } catch (_: Exception) {
                        checkCanceled(a)
                        PreviewMetadata.fromHtml(textResponse(canonical, a, 1048576)) ?: return null
                    }
                }
                platform == "X (Twitter)" && Regex("/status(?:es)?/\\d+").containsMatchIn(uri.path) -> {
                    val model = analyzeX(uri, a)
                    JSONObject().put("title", model.optString("title")).put("thumbnail", model.getJSONArray("assets").optJSONObject(0)?.optString("thumbnail"))
                }
                else -> PreviewMetadata.fromHtml(textResponse(canonical, a, 1048576)) ?: return null
            }
            checkCanceled(a)
            val image = preview.optString("thumbnail")
            if (image.isNotBlank()) runCatching { checkedUrl(image) }.onFailure { preview.put("thumbnail", "") }
            preview.put("sourceUrl", rawUrl.trim()).put("platform", platform)
            if (preview.optString("title").isBlank() && preview.optString("thumbnail").isBlank()) return null
            store.put("previewCache", JSONObject().put("id", key).put("createdAt", System.currentTimeMillis()).put("preview", preview))
            store.list("previewCache").sortedByDescending { it.optLong("createdAt") }.drop(32).forEach { store.delete("previewCache", it.getString("id")) }
            Log.i("FetchyPreview", "ready platform=$platform elapsedMs=${SystemClock.elapsedRealtime() - start}")
            return preview
        } catch (_: Exception) { return null }
        finally { finished.set(true); deadline.interrupt(); a.connection?.disconnect(); active.remove(id, a) }
    }

    private fun textResponse(url: String, a: Active, limit: Int): String {
        val conn = connect(url, JSONObject().put("User-Agent", "Mozilla/5.0"), a, 3000)
        try {
            if (conn.responseCode != 200) throw IOException("Public metadata is unavailable")
            require(!URI(conn.url.toString()).path.startsWith("/accounts/login")) { "Login redirect is not a preview" }
            return conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16384)
                while (out.size() < limit) {
                    checkCanceled(a)
                    val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    // OG metadata is normally in the head. Avoid downloading the rest of a large page.
                    if (!url.endsWith("/embed/") && out.size() >= 16384 && out.toString("UTF-8").contains("</head>", true)) break
                }
                out.toString("UTF-8")
            }
        } finally { conn.disconnect(); a.connection = null }
    }

    fun analyze(rawUrl: String, id: String = "analysis-${UUID.randomUUID()}", fullFormats: Boolean = false): JSONObject {
        val a = active.computeIfAbsent(id) { Active() }
        check(a.started.compareAndSet(false, true)) { "Analysis is already running" }
        val start = SystemClock.elapsedRealtime()
        try {
            checkCanceled(a)
            val url = rawUrl.trim()
            val revision = cookies.revision()
            val key = hash("6:${canonicalMediaUrl(url)}:$revision:$fullFormats")
            val cached = store.get("analysisCache", key)
            if (cached != null && System.currentTimeMillis() - cached.optLong("createdAt") in 0 until 120000) {
                Log.i("FetchyAnalysis", "cache_hit elapsedMs=${SystemClock.elapsedRealtime() - start}")
                return cached.getJSONObject("analysis").put("sourceUrl", url)
            }
            val result = analyzeInternal(url, a, fullFormats)
            checkCanceled(a)
            // Bound both row count and response size; cached CDN URLs are reanalyzed on expiry.
            if (result.toString().toByteArray().size <= 524288) {
                store.put("analysisCache", JSONObject().put("id", key).put("createdAt", System.currentTimeMillis()).put("analysis", result))
                store.list("analysisCache").sortedByDescending { it.optLong("createdAt") }.drop(8).forEach { store.delete("analysisCache", it.getString("id")) }
            }
            Log.i("FetchyAnalysis", "analyzed platform=${result.optString("platform")} elapsedMs=${SystemClock.elapsedRealtime() - start} assets=${result.getJSONArray("assets").length()}")
            return result
        } finally { a.connection?.disconnect(); active.remove(id, a) }
    }

    fun clearAnalysisCache() {
        for (type in listOf("analysisCache", "previewCache")) store.list(type).forEach { store.delete(type, it.getString("id")) }
        File(context.noBackupFilesDir, "resolved-media").deleteRecursively()
        File(context.noBackupFilesDir, "extractor-cache").deleteRecursively()
        File(context.noBackupFilesDir, "subtitle-sources").deleteRecursively()
    }

    private fun analyzeInternal(rawUrl: String, running: Active?, fullFormats: Boolean = false): JSONObject {
        var uri = checkedUrl(canonicalMediaUrl(rawUrl))
        var platform = mediaPlatform(uri)
        if ((platform == "Instagram" && uri.path.startsWith("/share/")) || uri.host == "v.douyin.com") {
            val a = running ?: Active()
            val connection = connect(uri.toString(), JSONObject().put("User-Agent", "Mozilla/5.0"), a, 3000)
            try {
                uri = checkedUrl(canonicalMediaUrl(connection.url.toString()))
                platform = mediaPlatform(uri)
                require(!uri.path.startsWith("/share/") && uri.host != "v.douyin.com") { "Shared post redirect is unavailable" }
            } finally { connection.disconnect(); a.connection = null }
        }
        if (extension(uri.path) in MEDIA_EXTENSIONS) {
            val a = running ?: Active()
            val conn = connect(uri.toString(), JSONObject(), a)
            try {
                if (conn.responseCode !in 200..299) throw IOException("Media server refused the request")
                val head = conn.inputStream.use { it.readBytesLimited() }
                verify(head, conn.contentType ?: "", kind(extension(uri.path)))
                val ext = extension(uri.path)
                val fmt = format("direct", "Original", ext, kind(ext), 0, conn.contentLengthLong, false)
                    .put("directUrl", uri.toString())
                val formats = JSONArray().put(fmt)
                if (kind(ext) != "image") for (audioExt in listOf("m4a", "mp3", "opus")) {
                    formats.put(format("direct-audio-$audioExt", audioExt.uppercase(), audioExt, "audio", 0, 0, false)
                        .put("directUrl", uri.toString()).put("sourceExt", ext).put("inputKind", kind(ext)).put("extractAudio", true))
                }
                return result(uri.toString(), platform, File(uri.path).name, JSONArray().put(
                    JSONObject().put("id", "direct").put("title", File(uri.path).name).put("kind", kind(ext))
                        .put("thumbnail", if (kind(ext) == "image") uri.toString() else "").put("formats", formats)))
            } finally { conn.disconnect(); a.connection = null }
        }
        // Morit tries FxTwitter first. Only the public post ID goes to this API;
        // cookies remain local and every download still uses the original CDN.
        val xPost = platform == "X (Twitter)" && Regex("/status(?:es)?/\\d+").containsMatchIn(uri.path)
        val authenticatedX = xPost && cookies.hasXSession()
        if (xPost) {
            val a = running ?: Active()
            try { return analyzeX(uri, a) }
            catch (_: Exception) { checkCanceled(a); Log.i("FetchyAnalysis", "x_metadata_fallback") }
        }
        if (platform == "Instagram" && !isInstagramStory(uri) && !fullFormats) {
            try { return analyzeInstagram(uri, running ?: Active()) }
            catch (_: Exception) { running?.let(::checkCanceled); Log.i("FetchyAnalysis", "instagram_embed_fallback") }
        }
        initialize()
        fun extract(api: String = ""): JSONObject {
            val req = request(uri.toString(), analysis = true).addOption("--dump-single-json").addOption("--skip-download")
            if (!xPost) req.addOption("--ignore-no-formats-error")
            if (analyzeAsPlaylist(uri)) req.addOption("--yes-playlist").addOption("--playlist-end", 100)
            else req.addOption("--no-playlist")
            if (api.isNotEmpty()) req.addOption("--extractor-args", "twitter:api=$api")
            return JSONObject(execute(req, running ?: Active(), "analysis-${UUID.randomUUID()}", uri.toString(),
                useCookies = api != "syndication", timeoutMs = 30000).out)
        }
        if (xPost && !authenticatedX) {
            try { return parseExtractedMedia(uri, platform, extract("syndication"), "syndication") }
            catch (_: Exception) { running?.let(::checkCanceled) }
        }
        val root = try {
            extract()
        } catch (e: Exception) { throw safeError(e, running) }
        return parseExtractedMedia(uri, platform, root)
    }

    @JvmName("parseExtractedMedia")
    internal fun parseExtractedMedia(uri: URI, platform: String, root: JSONObject, extractorApi: String = ""): JSONObject {
        val entries = root.optJSONArray("entries") ?: JSONArray().put(root)
        val assets = JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val fs = JSONArray()
            val rawFormats = entry.optJSONArray("formats") ?: JSONArray()
            val image = instagramImage(root, entry)
            if (image != null) {
                fs.put(format("original", "Original", extension(URI(image).path), "image", 0, 0, false)
                    .put("directUrl", image).put("headers", JSONObject().put("Referer", "https://www.instagram.com/")))
            } else {
                fun streams(f: JSONObject): Pair<Boolean, Boolean> {
                    val v = f.optString("vcodec").takeUnless { it in setOf("", "null") }
                    val ac = f.optString("acodec").takeUnless { it in setOf("", "null") }
                    val ext = f.optString("ext")
                    // Naver exposes dimensions/bitrates but omits codec names. Explicit "none" still wins.
                    val video = if (v != null) v != "none" else ext in setOf("mp4", "webm", "mkv", "mov", "ts", "avi") && f.optInt("height") > 0
                    val audio = if (ac != null) ac != "none" else f.optDouble("abr", 0.0) > 0 || (v == "none" && ext in setOf("m4a", "mp3", "opus", "ogg", "wav", "flac", "aac"))
                    return video to audio
                }
                val audio = (0 until rawFormats.length()).mapNotNull { rawFormats.optJSONObject(it) }
                    .filter { streams(it) == (false to true) && !it.optBoolean("has_drm") }
                    .maxByOrNull { it.optDouble("abr", 0.0) }
                for (j in 0 until rawFormats.length()) {
                    val f = rawFormats.optJSONObject(j) ?: continue
                    if (f.optBoolean("has_drm") || f.optString("format_id").isEmpty()) continue
                    if (isInstagramStory(uri) && entry.optBoolean("fetchy_story_image") && f.optString("format_id") == "fetchy-story-image") {
                        val source = instagramMediaUri(f.getString("url"))
                        val ext = extension(source.path)
                        require(ext in IMAGE_EXTENSIONS) { "Invalid Instagram story image extension" }
                        fs.put(format("fetchy-story-image", "Original", ext, "image", f.optInt("height"), f.optLong("filesize"), false)
                            .put("width", f.optInt("width")).put("directUrl", source.toString())
                            .put("headers", JSONObject().put("Referer", "https://www.instagram.com/")))
                        continue
                    }
                    if (entry.optBoolean("fetchy_original_image") && f.optString("format_id") == "fetchy-image") {
                        val source = originalPhotoUrl(platform, f.getString("url"))
                        val ext = f.getString("ext")
                        require(ext in IMAGE_EXTENSIONS) { "Unsupported original image format" }
                        fs.put(format("fetchy-image", "Original", ext, "image", f.optInt("height"), f.optLong("filesize"), false)
                            .put("width", f.optInt("width")).put("directUrl", source)
                            .put("headers", publicHeaders(f.optJSONObject("http_headers") ?: entry.optJSONObject("http_headers"))))
                        continue
                    }
                    val (isVideo, isAudio) = streams(f)
                    if (!isVideo && !isAudio) continue
                    val id = f.getString("format_id")
                    if (!id.matches(Regex("[A-Za-z0-9_.-]+"))) continue
                    val merge = isVideo && f.optString("acodec") == "none" && audio != null && audio.optString("format_id").matches(Regex("[A-Za-z0-9_.-]+"))
                    val selector = if (merge) "$id+${audio!!.getString("format_id")}" else id
                    val ext = if (merge) "mkv" else f.optString("ext", "mp4")
                    val h = if (f.optInt("width") > 0 && f.optInt("height") > 0) minOf(f.getInt("width"), f.getInt("height")) else f.optInt("height", 0)
                    val size = f.optLong("filesize", f.optLong("filesize_approx", 0))
                    fs.put(format(id, if (isVideo) (if (h > 0) "${h}p" else "Original") + (if (f.optString("acodec") == "none" && !merge) " (silent)" else "") else "${f.optInt("abr")} kbps",
                        ext, if (isVideo) "video" else "audio", h, if (merge) 0 else size, merge).put("selector", selector).put("fps", f.optDouble("fps", 0.0)).put("width", f.optInt("width", 0)))
                    val source = httpSource(f, entry, if (isVideo) "video" else "audio", platform)
                    val audioSource = if (merge) httpSource(audio!!, entry, "audio", platform) else null
                    if (source != null) {
                        val selected = fs.getJSONObject(fs.length() - 1)
                        if (!merge) selected.put("directUrl", source.getString("directUrl")).put("headers", source.getJSONObject("headers"))
                        else if (audioSource != null) selected.put("directSources", JSONArray().put(source).put(audioSource))
                    }
                    if (!isVideo || (audio == null && isVideo && isAudio)) for (out in listOf("m4a", "mp3", "opus")) {
                        val converted = format("$id-$out", out.uppercase(), out, "audio", 0, 0, false)
                            .put("selector", id).put("extractAudio", true)
                        if (source != null) converted.put("directUrl", source.getString("directUrl")).put("headers", source.getJSONObject("headers"))
                            .put("sourceExt", source.getString("ext")).put("inputKind", if (isVideo) "video" else "audio")
                        fs.put(converted)
                    }
                }
                if (fs.length() == 0 && rawFormats.length() == 0 && entry.optString("url").isNotEmpty()) {
                    val ext = entry.optString("ext", extension(URI(entry.getString("url")).path))
                    if (ext in MEDIA_EXTENSIONS) fs.put(format("direct", "Original", ext, kind(ext), 0, 0, false)
                        .put("directUrl", checkedUrl(entry.getString("url")).toString()).put("headers", publicHeaders(entry.optJSONObject("http_headers"))))
                }
            }
            if (fs.length() == 0) continue
            if (extractorApi.isNotEmpty()) for (j in 0 until fs.length()) fs.getJSONObject(j).put("extractorApi", extractorApi)
            val title = entry.optString("title", root.optString("title", "Media"))
            // TikTok's anonymous session cookies belong to extraction+transfer in one process.
            val resolved = if (platform == "TikTok") "" else saveResolvedInfo(uri.toString(), entry)
            val asset = JSONObject().put("id", "${entry.optString("id", "item")}:${if (root.has("entries")) entry.optInt("playlist_index", i + 1) else 0}")
                .put("extractorId", entry.optString("id"))
                .put("title", title).put("kind", fs.getJSONObject(0).getString("kind"))
                .put("thumbnail", entry.optString("thumbnail", image ?: ""))
                .put("duration", entry.optDouble("duration", 0.0)).put("width", entry.optInt("width", 0)).put("height", entry.optInt("height", 0)).put("fps", entry.optDouble("fps", 0.0))
                .put("playlistIndex", if (root.has("entries")) entry.optInt("playlist_index", i + 1) else 0).put("formats", fs)
                .put("resolvedInfo", resolved)
            sourceMediaDate(entry.optDouble("timestamp", root.optDouble("timestamp", Double.NaN)), entry.optString("upload_date", root.optString("upload_date")))?.let { asset.put("sourceTimestamp", it) }
            attachExtras(uri.toString(), entry, asset)
            assets.put(asset)
        }
        if (assets.length() == 0) throw IOException("No supported original media formats")
        return result(uri.toString(), platform, root.optString("title", "Media"), assets)
    }

    private fun attachExtras(url: String, entry: JSONObject, asset: JSONObject) {
        for (key in listOf("artist", "album", "track", "creator")) entry.optString(key).takeIf { it.isNotBlank() && it != "null" }?.let { asset.put(key, it.take(240)) }
        val sources = JSONArray(); val choices = JSONArray()
        for ((key, automatic) in listOf("subtitles" to false, "automatic_captions" to true)) {
            val languages = entry.optJSONObject(key) ?: continue
            for (language in languages.keys().asSequence().toList().sorted()) {
                if (!language.matches(Regex("[A-Za-z0-9_.-]{1,64}")) || language == "live_chat") continue
                val formats = languages.optJSONArray(language) ?: continue
                val source = (0 until formats.length()).mapNotNull { formats.optJSONObject(it) }
                    .filter { it.optString("ext") in setOf("vtt", "srt", "ass", "ssa") }
                    .sortedBy { if (it.optString("ext") == "vtt") 0 else 1 }.firstOrNull() ?: continue
                val uri = runCatching { URI(source.getString("url")) }.getOrNull() ?: continue
                if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) continue
                choices.put(JSONObject().put("language", language).put("name", source.optString("name", language)).put("automatic", automatic))
                sources.put(JSONObject().put("language", language).put("automatic", automatic).put("url", uri.toString()).put("ext", source.getString("ext"))
                    .put("headers", publicHeaders(entry.optJSONObject("http_headers"))))
            }
        }
        asset.put("subtitles", choices)
        if (sources.length() == 0) return
        val wrapper = JSONObject().put("createdAt", System.currentTimeMillis()).put("source", hash(canonicalMediaUrl(url)))
            .put("revision", cookies.revision()).put("assetId", asset.getString("id")).put("sources", sources)
        val bytes = wrapper.toString().toByteArray(); if (bytes.size > 2097152) return
        val directory = File(context.noBackupFilesDir, "subtitle-sources").apply { mkdirs() }
        val key = hash("${canonicalMediaUrl(url)}:${asset.getString("id")}:${cookies.revision()}")
        val atomic = android.util.AtomicFile(File(directory, "$key.json")); val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) } catch (error: Exception) { atomic.failWrite(stream); throw error }
        directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }?.drop(31)?.forEach { it.delete() }
        asset.put("subtitleSource", key)
    }

    internal fun subtitleSource(job: JSONObject, a: Active, refresh: Boolean = false): JSONObject {
        val selection = job.getJSONObject("subtitle")
        var asset = job.getJSONObject("asset")
        if (refresh) {
            val list = analyzeInternal(job.getString("sourceUrl"), a, fullFormats = true).getJSONArray("assets")
            asset = (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.getString("id") == asset.getString("id") }
                ?: throw IOException("Selected asset is no longer available")
        }
        val key = asset.optString("subtitleSource"); require(key.matches(Regex("[a-f0-9]{64}"))) { "Subtitle source unavailable" }
        val wrapper = JSONObject(File(context.noBackupFilesDir, "subtitle-sources/$key.json").readText())
        require(wrapper.getString("source") == hash(canonicalMediaUrl(job.getString("sourceUrl"))) && wrapper.getString("revision") == cookies.revision() && wrapper.getString("assetId") == asset.getString("id"))
        require(System.currentTimeMillis() - wrapper.getLong("createdAt") in 0 until 3600000) { "Subtitle source expired" }
        val sources = wrapper.getJSONArray("sources")
        return (0 until sources.length()).map { sources.getJSONObject(it) }.firstOrNull {
            it.getString("language") == selection.getString("language") && it.getBoolean("automatic") == selection.getBoolean("automatic")
        } ?: throw IOException("Selected subtitle language is unavailable")
    }

    private fun httpSource(format: JSONObject, entry: JSONObject, kind: String, platform: String): JSONObject? {
        // GVS request rules and TikTok's session cookies require the existing extractor transport.
        // Public previews remain fast; do not substitute an unreliable plain HTTP transfer.
        if (platform in setOf("YouTube", "TikTok")) return null
        if (format.optString("cookies").isNotBlank() || entry.optString("cookies").isNotBlank()) return null
        if (format.optString("protocol") !in setOf("http", "https") || format.optString("ext") !in MEDIA_EXTENSIONS) return null
        val headers = format.optJSONObject("http_headers") ?: entry.optJSONObject("http_headers") ?: JSONObject()
        if (headers.keys().asSequence().any { it.lowercase() in setOf("cookie", "authorization", "proxy-authorization") }) return null
        val url = runCatching { URI(format.getString("url")) }.getOrNull() ?: return null
        if (url.scheme !in setOf("http", "https") || url.host.isNullOrBlank() || url.rawUserInfo != null || url.port !in listOf(-1, 80, 443)) return null
        return JSONObject().put("directUrl", url.toString()).put("headers", publicHeaders(headers)).put("kind", kind)
            .put("ext", format.getString("ext")).put("sizeBytes", format.optLong("filesize", -1))
    }

    private fun analyzeInstagram(uri: URI, a: Active): JSONObject {
        val shortcode = uri.path.trim('/').substringAfter('/').substringBefore('/')
        val html = textResponse("https://www.instagram.com/p/$shortcode/embed/", a, 1048576)
        return parseInstagramEmbed(uri, html)
    }

    @JvmName("parseInstagramEmbed")
    internal fun parseInstagramEmbed(uri: URI, html: String): JSONObject {
        val shortcode = uri.path.trim('/').substringAfter('/').substringBefore('/')
        val encoded = Regex("\"contextJSON\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\")").find(html)?.groupValues?.get(1)
            ?: throw IOException("Instagram embed has no media data")
        val context = JSONObject(org.json.JSONTokener(encoded).nextValue() as String)
        require(context.optJSONObject("context")?.optBoolean("copyright_blocked", false) != true) { "Instagram media is restricted" }
        val media = context.getJSONObject("gql_data").getJSONObject("shortcode_media")
        require(media.getString("shortcode") == shortcode) { "Instagram embed belongs to another post" }
        val children = media.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
        require(children == null || children.length() in 1..100) { "Invalid Instagram carousel" }
        val nodes = if (children == null) listOf(media) else (0 until children.length()).map { children.getJSONObject(it).getJSONObject("node") }
        val assets = JSONArray()
        val caption = media.optJSONObject("edge_media_to_caption")?.optJSONArray("edges")?.optJSONObject(0)?.optJSONObject("node")?.optString("text")
        val title = caption?.takeIf { it.isNotBlank() }?.take(300)
            ?: media.optJSONObject("owner")?.optString("username")?.takeIf { it.isNotBlank() }?.let { "Instagram · $it" } ?: "Instagram"
        for ((index, node) in nodes.withIndex()) {
            val video = node.optBoolean("is_video") && node.optString("__typename") == "GraphVideo"
            val dimensions = node.optJSONObject("dimensions") ?: JSONObject()
            val width = dimensions.optInt("width")
            val height = dimensions.optInt("height")
            val fs = JSONArray()
            val headers = JSONObject().put("Referer", "https://www.instagram.com/")
            if (video) {
                val url = instagramMediaUri(node.getString("video_url"))
                // Graph dimensions describe the display canvas, not this encoded stream.
                // Leave resolution unknown until ffprobe; otherwise portrait posts lose the default selection.
                fs.put(format("embed-original", "Original", "mp4", "video", 0, 0, false)
                    .put("directUrl", url.toString()).put("headers", headers))
                fs.put(format("automatic", "설정 화질 자동 선택", "mp4", "video", 0, 0, true).put("automatic", true)
                    .put("originalFallback", JSONObject(fs.getJSONObject(0).toString())))
                for (ext in listOf("m4a", "mp3", "opus")) fs.put(format("embed-audio-$ext", ext.uppercase(), ext, "audio", 0, 0, false)
                    .put("directUrl", url.toString()).put("headers", headers).put("extractAudio", true).put("sourceExt", "mp4").put("inputKind", "video"))
            } else {
                require(node.optString("__typename") == "GraphImage" && !node.optBoolean("is_video")) { "Unsupported Instagram media" }
                // Explicit GraphImage original only. Never promote an OG/video thumbnail to a download.
                val image = originalInstagram(node.optString("display_url")) ?: throw IOException("Instagram image is not an original")
                fs.put(format("original", "Original", extension(URI(image).path), "image", height, 0, false).put("width", width)
                    .put("directUrl", image).put("headers", headers))
            }
            val assetId = node.optString("shortcode", shortcode).takeIf { it.isNotEmpty() } ?: shortcode
            assets.put(JSONObject().put("id", "$assetId:${if (children == null) 0 else index + 1}")
                .put("extractorId", node.optString("shortcode").takeIf { it.isNotBlank() }
                    ?: instagramExtractorId(node.optString("id")).ifBlank { if (children == null) shortcode else "" })
                .put("title", title).put("kind", if (video) "video" else "image").put("formats", fs)
                .put("thumbnail", PreviewMetadata.safeImage(node.optString("display_url"))).put("duration", node.optDouble("video_duration", 0.0))
                .put("playlistIndex", if (children == null) 0 else index + 1)
                .put("width", if (video) 0 else width).put("height", if (video) 0 else height).also { asset ->
                    sourceMediaDate(node.optDouble("taken_at_timestamp", media.optDouble("taken_at_timestamp", Double.NaN)))?.let { asset.put("sourceTimestamp", it) }
                })
        }
        return result(uri.toString(), "Instagram", title, assets).put("analysisProvider", "instagram_embed")
    }

    /** Resolved extractor data is private, bounded, short-lived and never sent over the bridge. */
    private fun saveResolvedInfo(url: String, entry: JSONObject): String {
        if (entry.optBoolean("is_live") || (entry.optJSONArray("formats")?.length() ?: 0) == 0) return ""
        val info = JSONObject(entry.toString())
        fun scrub(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().asSequence().toList().forEach { key ->
                    if (key.lowercase() in setOf("cookie", "cookies", "cookiejar", "cookie_jar", "authorization", "proxy-authorization") || key.startsWith("__")) value.remove(key)
                    else scrub(value.opt(key))
                }
                is JSONArray -> for (i in 0 until value.length()) scrub(value.opt(i))
            }
        }
        scrub(info)
        listOf("requested_downloads", "requested_formats", "requested_subtitles", "requested_entries", "url", "format_id", "format", "filepath", "_filename").forEach { info.remove(it) }
        val wrapper = JSONObject().put("createdAt", System.currentTimeMillis()).put("source", hash(canonicalMediaUrl(url)))
            .put("cookies", cookies.revision()).put("info", info)
        val bytes = wrapper.toString().toByteArray()
        if (bytes.size > 2097152) return ""
        val directory = File(context.noBackupFilesDir, "resolved-media").apply { mkdirs() }
        val key = hash("${canonicalMediaUrl(url)}:${info.optString("id")}:${cookies.revision()}")
        synchronized(INFO_LOCK) {
            val temporary = File(directory, "$key.tmp")
            temporary.writeBytes(bytes)
            check(temporary.renameTo(File(directory, "$key.json"))) { "Cannot cache resolved media" }
            directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }?.drop(8)?.forEach { it.delete() }
        }
        return key
    }

    private fun resolvedInfo(job: JSONObject): JSONObject? = runCatching {
        val key = job.getJSONObject("asset").optString("resolvedInfo")
        if (!key.matches(Regex("[a-f0-9]{64}"))) return null
        val file = File(context.noBackupFilesDir, "resolved-media/$key.json")
        val wrapper = synchronized(INFO_LOCK) { JSONObject(file.readText()) }
        require(System.currentTimeMillis() - wrapper.getLong("createdAt") in 0 until 120000)
        require(wrapper.getString("source") == hash(canonicalMediaUrl(job.getString("sourceUrl"))) && wrapper.getString("cookies") == cookies.revision())
        val info = wrapper.getJSONObject("info")
        require(info.getString("id") == job.getJSONObject("asset").getString("extractorId"))
        val formats = info.getJSONArray("formats")
        val ids = (0 until formats.length()).map { formats.getJSONObject(it).optString("format_id") }.toSet()
        require(job.getJSONObject("selection").getString("selector").split('+').all { it in ids })
        info.remove("webpage_url") // A stale cached URL must return to our exact asset/format validation.
        info
    }.getOrNull()

    private fun analyzeX(uri: URI, a: Active): JSONObject {
        val status = Regex("/status(?:es)?/(\\d+)(?:/|$)").find(uri.path)?.groupValues?.get(1)
            ?: throw IOException("X post ID is missing")
        val conn = connect("https://api.fxtwitter.com/i/status/$status",
            JSONObject().put("Accept", "application/json").put("User-Agent", "Fetchy/1.0"), a, 5000)
        try {
            if (conn.responseCode != 200) throw IOException("X metadata HTTP request failed (${conn.responseCode})")
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkCanceled(a)
                    val n = input.read(buffer); if (n < 0) break
                    require(out.size() + n <= 1048576) { "X metadata response is too large" }
                    out.write(buffer, 0, n)
                }
                out.toString("UTF-8")
            }
            return parseXMedia(uri, JSONObject(bytes))
        } finally { conn.disconnect(); a.connection = null }
    }

    /** Morit's FxTwitter media.all parser, with CDN validation and stable selection IDs. */
    @JvmName("parseXMedia") // Instrumentation also runs against the release APK.
    internal fun parseXMedia(uri: URI, root: JSONObject): JSONObject {
        require(root.optInt("code") == 200) { "X metadata is unavailable" }
        val tweet = root.getJSONObject("tweet")
        val status = Regex("/status(?:es)?/(\\d+)(?:/|$)").find(uri.path)?.groupValues?.get(1)
            ?: throw IOException("X post ID is missing")
        require(tweet.optString("id") == status) { "X metadata post ID does not match" }
        val title = tweet.optString("text").ifBlank { "X $status" }
        val all = tweet.optJSONObject("media")?.optJSONArray("all") ?: JSONArray()
        val requested = Regex("/(video|photo)/(\\d+)/?$").find(uri.path)
        val items = (0 until all.length()).mapNotNull { all.optJSONObject(it) }.filter {
            when (requested?.groupValues?.get(1)) {
                "photo" -> it.optString("type") == "photo"
                "video" -> it.optString("type") in setOf("video", "gif")
                else -> true
            }
        }.let { list -> if (requested == null) list else listOfNotNull(list.getOrNull(requested.groupValues[2].toInt() - 1)) }
        val assets = JSONArray()
        for (item in items) {
            val type = item.optString("type")
            if (type !in setOf("photo", "video", "gif")) continue
            val fs = JSONArray()
            val variants = LinkedHashMap<String, JSONObject>()
            for (key in listOf("formats", "variants")) {
                val entries = item.optJSONArray(key) ?: continue
                for (j in 0 until entries.length()) {
                    val variant = entries.optJSONObject(j) ?: continue
                    if (variant.optString("container") == "mp4" || variant.optString("content_type") == "video/mp4") {
                        xCdnUrl(variant.optString("url"), false)?.let { variants.putIfAbsent(it, variant) }
                    }
                }
            }
            if (type == "photo") {
                val image = xCdnUrl(item.optString("url"), true) ?: continue
                val ext = extension(URI(image).path).takeIf { it in IMAGE_EXTENSIONS }
                    ?: query(URI(image))["format"]?.takeIf { it in IMAGE_EXTENSIONS } ?: continue
                fs.put(format("original", "Original", ext, "image", item.optInt("height"), 0, false).put("directUrl", image))
            } else {
                if (variants.isEmpty()) xCdnUrl(item.optString("url"), false)?.let { variants[it] = JSONObject() }
                val videos = variants.map { (url, variant) ->
                    val size = Regex("/vid/(?:[A-Za-z0-9_-]+/)?(\\d+)x(\\d+)/").find(URI(url).path)
                    val width = size?.groupValues?.get(1)?.toIntOrNull() ?: item.optInt("width")
                    val height = size?.groupValues?.get(2)?.toIntOrNull() ?: item.optInt("height")
                    format("mp4-${shortHash(URI(url).path)}", if (height > 0) "${height}p" else "Original", "mp4", "video", height, 0, false)
                        .put("width", width).put("bitrate", variant.optLong("bitrate")).put("directUrl", url)
                }.sortedWith(compareByDescending<JSONObject> { it.optInt("height") }.thenByDescending { it.optLong("bitrate") })
                videos.forEach(fs::put)
                if (type == "video" && videos.isNotEmpty()) for (ext in listOf("m4a", "mp3", "opus")) {
                    fs.put(format("audio-$ext", ext.uppercase(), ext, "audio", 0, 0, false)
                        .put("directUrl", videos.first().getString("directUrl")).put("sourceExt", "mp4")
                        .put("inputKind", "video").put("extractAudio", true))
                }
            }
            if (fs.length() == 0) continue
            val identity = item.optString("id").ifBlank { URI(fs.getJSONObject(0).getString("directUrl")).path }
            assets.put(JSONObject().put("id", "x-$status-${shortHash(identity)}").put("title", title)
                .put("kind", if (type == "photo") "image" else "video").put("formats", fs)
                .put("thumbnail", xCdnUrl(item.optString("thumbnail_url"), null) ?: if (type == "photo") fs.getJSONObject(0).getString("directUrl") else "")
                .put("width", item.optInt("width")).put("height", item.optInt("height")).put("duration", item.optDouble("duration", 0.0)).also { asset ->
                    sourceMediaDate(tweet.optDouble("created_timestamp", Double.NaN))?.let { asset.put("sourceTimestamp", it) }
                })
        }
        if (assets.length() == 0) throw IOException("No supported X media")
        return result(uri.toString(), "X (Twitter)", title, assets).put("analysisProvider", "fxtwitter")
    }

    private fun shortHash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .take(12).joinToString("") { "%02x".format(it) }

    private fun xCdnUrl(raw: String, image: Boolean?): String? = runCatching {
        val u = URI(raw)
        require(u.scheme == "https" && u.rawUserInfo == null && u.port in listOf(-1, 443))
        require(when (image) {
            true -> u.host == "pbs.twimg.com" && u.path.startsWith("/media/")
            false -> u.host == "video.twimg.com" && extension(u.path) == "mp4"
            null -> u.host == "pbs.twimg.com"
        })
        raw
    }.getOrNull()

    fun download(job: JSONObject, workDir: File, onProgress: (String, Int, Long, Long) -> Unit): File {
        val id = job.getString("id")
        val a = active.computeIfAbsent(id) { Active() }
        check(a.started.compareAndSet(false, true)) { "Job is already running" }
        try {
            checkCanceled(a)
            checkedUrl(job.getString("sourceUrl"))
            check(workDir.isDirectory || workDir.mkdirs()) { "Cannot create download directory" }
            val settings = job.optJSONObject("settings") ?: JSONObject()
            if (!settings.optBoolean("resume", true)) {
                workDir.listFiles()?.filter { it.name.startsWith("media.") || it.name.startsWith("converted.") }?.forEach { it.deleteRecursively() }
                File(workDir, "streams").deleteRecursively()
            }
            val selection = job.getJSONObject("selection")
            require(selection.getString("id") == job.getString("formatId")) { "Selected format does not match job" }
            require(job.getJSONObject("asset").getString("id") == job.getString("assetId")) { "Selected asset does not match job" }
            var selected = selection
            if (selected.optBoolean("automatic")) {
                require(job.optString("platform") == "Instagram" && selected.optString("kind") == "video")
                initialize()
                val fallback = selected.optJSONObject("originalFallback")
                val resolved = try {
                    val req = request(job.getString("sourceUrl"), true).addOption("--dump-single-json").addOption("--skip-download")
                        .addOption("-f", automaticVideoSelector(settings.optInt("quality", 1080)))
                        .addOption("--format-sort", automaticVideoSort(settings.optInt("quality", 1080))).addOption("--format-sort-force")
                    val asset = job.getJSONObject("asset"); val index = asset.optInt("playlistIndex")
                    if (index > 0) req.addOption("--playlist-items", index) else req.addOption("--no-playlist")
                    asset.optString("extractorId").takeIf { it.matches(Regex("[A-Za-z0-9_.:-]+")) }?.let { req.addOption("--match-filter", "id = '$it'") }
                    val root = JSONObject(execute(req, a, "auto-${UUID.randomUUID()}", job.getString("sourceUrl"), timeoutMs = 10000).out)
                    val entries = root.optJSONArray("entries")
                    val entry = if (entries == null) root else (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }.firstOrNull() ?: error("Selected asset unavailable")
                    val chosen = entry.optJSONArray("requested_formats")?.optJSONObject(0)?.optString("format_id") ?: entry.getString("format_id")
                    val fresh = parseExtractedMedia(checkedUrl(job.getString("sourceUrl")), "Instagram", entry).getJSONArray("assets").getJSONObject(0)
                    asset.put("resolvedInfo", fresh.optString("resolvedInfo")).put("extractorId", fresh.optString("extractorId"))
                    val fs = fresh.getJSONArray("formats")
                    (0 until fs.length()).map { fs.getJSONObject(it) }.first { it.optString("kind") == "video" && it.optString("id") == chosen }
                } catch (error: Exception) {
                    checkCanceled(a); if (fallback == null) throw error
                    job.put("warnings", JSONArray().put("자동 화질을 확인하지 못해 제공된 원본을 저장합니다. 실제 해상도는 완료 후 표시됩니다."))
                    fallback
                }
                selection.keys().asSequence().toList().forEach { selection.remove(it) }
                resolved.keys().forEach { selection.put(it, resolved.get(it)) }
                selection.put("id", job.getString("formatId")); selected = selection
            }
            fun completed(source: File): File {
                val output = finish(source, selected, settings, a, onProgress, job)
                MediaExtras(this).subtitles(job, workDir, a, onProgress)
                return output
            }
            if (selected.optString("directUrl").isNotEmpty() || selected.has("directSources")) {
                try { return completed(downloadSources(selected, workDir, a, onProgress)) }
                catch (e: Expired) {
                    checkCanceled(a)
                    val fresh = analyzeInternal(job.getString("sourceUrl"), a, fullFormats = selection.has("selector")).getJSONArray("assets")
                    val asset = (0 until fresh.length()).map { fresh.getJSONObject(it) }.firstOrNull { it.getString("id") == job.getString("assetId") }
                        ?: throw IOException("Selected asset is no longer available")
                    val formats = asset.getJSONArray("formats")
                    selected = (0 until formats.length()).map { formats.getJSONObject(it) }.firstOrNull { it.getString("id") == job.getString("formatId") }
                        ?: throw IOException("Selected format is no longer available")
                    require(selected.optString("kind") == selection.optString("kind") && selected.optString("ext") == selection.optString("ext")) { "Selected format changed" }
                    // Keep the original reference: Runtime reads its validated metadata.
                    selection.keys().asSequence().toList().forEach { selection.remove(it) }
                    selected.keys().forEach { selection.put(it, selected.get(it)) }
                    selected = selection
                    return completed(downloadSources(selected, workDir, a, onProgress))
                }
            }
            initialize()
            val automatic = selected.optBoolean("automatic")
            require(!automatic || (selected.optString("kind") == "video" && job.optString("platform") == "Instagram")) { "Invalid automatic format" }
            val selector = if (automatic) automaticVideoSelector(settings.optInt("quality", 1080)) else selected.getString("selector")
            require(automatic || selector.matches(Regex("[A-Za-z0-9_.-]+(?:\\+[A-Za-z0-9_.-]+)*"))) { "Invalid exact format selector" }
            val anonymousX = selected.optString("extractorApi") == "syndication" && !cookies.hasXSession()
            val ext = selected.getString("ext")
            fun downloadRequest(info: File?): YoutubeDLRequest {
                val req = request(if (info == null) job.getString("sourceUrl") else null).addOption("-f", selector)
                .addOption("--newline").addOption("--progress").addOption("--continue").addOption("--no-overwrites")
                .addOption("--concurrent-fragments", 3).addOption("--abort-on-unavailable-fragments").addOption("--fragment-retries", 3)
                .addOption("-o", File(workDir, "media.%(ext)s").absolutePath)
                .addOption("--print", "after_move:FETCHY_FILE:%(filepath)s")
                .addOption("--progress-template", "download:FETCHY_BYTES:%(progress.downloaded_bytes)s:%(progress.total_bytes)s:%(progress.total_bytes_estimate)s")
                if (automatic) req.addOption("--format-sort", automaticVideoSort(settings.optInt("quality", 1080))).addOption("--format-sort-force")
                if (anonymousX) req.addOption("--extractor-args", "twitter:api=syndication")
                val index = job.getJSONObject("asset").optInt("playlistIndex", 0)
                val extractorId = job.getJSONObject("asset").optString("extractorId")
                if (extractorId.isNotEmpty()) {
                    require(extractorId.matches(Regex("[A-Za-z0-9_.:-]+"))) { "Unsupported extractor asset ID" }
                    req.addOption("--match-filter", "id = '$extractorId'")
                }
                if (index > 0) req.addOption("--yes-playlist").addOption("--playlist-items", index) else req.addOption("--no-playlist")
                require(ext.matches(Regex("[a-z0-9]{2,5}"))) { "Invalid extension" }
                if (selected.optBoolean("extractAudio")) {
                    require(ext in setOf("m4a", "mp3", "opus")) { "Unsupported audio output" }
                    req.addOption("--extract-audio").addOption("--audio-format", ext)
                } else if (selected.optBoolean("needsMerge")) req.addOption("--merge-output-format", ext)
                if (info != null) req.addOption("--load-info-json", info.absolutePath)
                return req
            }
            val info = resolvedInfo(job)
            val infoFile = info?.let { File(workDir, "resolved-info.json").apply { writeText(it.toString()) } }
            var bytes = 0L; var total = selected.optLong("sizeBytes", -1).takeIf { it > 0 } ?: -1L
            var stage = "running"
            fun runDownload(source: File?) = execute(downloadRequest(source), a, "fetchy-${UUID.randomUUID()}", job.getString("sourceUrl"), useCookies = !anonymousX) { _, _, line ->
                if (line.startsWith("FETCHY_BYTES:")) {
                    val p = line.split(':'); bytes = p.getOrNull(1)?.toLongOrNull() ?: bytes
                    total = p.getOrNull(2)?.toLongOrNull() ?: p.getOrNull(3)?.toLongOrNull() ?: total
                }
                if (line.startsWith("[Merger]") || line.startsWith("[VideoRemuxer]") || line.startsWith("[Fixup")) stage = "merging"
                if (line.startsWith("[ExtractAudio]") || line.startsWith("[VideoConvertor]")) stage = "converting"
                onProgress(stage, if (total > 0) ((bytes * 100 / total).toInt()).coerceIn(0, 99) else -1, bytes, total)
            }
            val downloadStart = SystemClock.elapsedRealtime()
            val response = try {
                try {
                    runDownload(infoFile).also { if (infoFile != null) Log.i("FetchyDownload", "resolved_info_reused elapsedMs=${SystemClock.elapsedRealtime() - downloadStart}") }
                } catch (error: Exception) {
                    checkCanceled(a)
                    if (infoFile == null || !Regex("(?i)(HTTP Error (401|403|404|410)|requested format.*not available)").containsMatchIn(error.message.orEmpty())) throw error
                    Log.i("FetchyDownload", "resolved_info_expired_exact_format_retry")
                    runDownload(null)
                }
            } finally { infoFile?.delete() }
            checkCanceled(a)
            val path = response.out.lineSequence().lastOrNull { it.startsWith("FETCHY_FILE:") }?.removePrefix("FETCHY_FILE:")
                ?: throw IOException("Media engine did not produce a final file")
            val file = File(path).canonicalFile
            require(file.parentFile == workDir.canonicalFile && file.isFile && file.length() > 0) { "Invalid output file" }
            // yt-dlp ignores merge-output-format when the selected source already contains both streams.
            require(automatic || file.extension.lowercase() == ext ||
                (selected.optBoolean("needsMerge") && file.extension.lowercase() in setOf("mp4", "mkv", "webm", "mov"))) { "Media engine produced a different output format" }
            onProgress("saving", 99, file.length(), file.length())
            verify(file.inputStream().use { it.readBytesLimited() }, mime(file, selected.getString("kind")), selected.getString("kind"))
            return completed(file)
        } catch (e: Exception) { throw safeError(e, a) }
        finally { a.connection?.disconnect(); active.remove(id, a) }
    }

    private fun execute(req: YoutubeDLRequest, a: Active, id: String, url: String, useCookies: Boolean = true,
        timeoutMs: Long = 0, callback: ((Float, Long, String) -> Unit)? = null): com.yausername.youtubedl_android.YoutubeDLResponse {
        a.processId = id
        var cookie: File? = null
        val done = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val lastError = AtomicReference("")
        val startedAt = SystemClock.elapsedRealtime()
        val watcher = Thread {
            while (!done.get()) {
                if (timeoutMs > 0 && SystemClock.elapsedRealtime() - startedAt >= timeoutMs) timedOut.set(true)
                if (a.canceled.get() || timedOut.get()) destroy(id)
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        try {
            checkCanceled(a)
            cookie = if (useCookies) cookieFile(url) else null
            cookie?.let { req.addOption("--cookies", it.absolutePath) }
            // A unique argv token lets cancellation identify our Python process in /proc.
            req.addOption("--progress-template", "postprocess:FETCHY_PROCESS:$id")
            val response = YoutubeDL.execute(req, id, callback != null) { p, eta, line ->
                // With redirected stderr the wrapper can throw an empty message.
                // Keep only the last error for classification/expiry retry; never log raw extractor output.
                if (line.startsWith("ERROR:")) lastError.set(line.take(4096))
                if (a.canceled.get()) destroy(id) else callback?.invoke(p, eta, line)
            }
            checkCanceled(a)
            if (timedOut.get()) throw IOException("Media analysis timed out")
            return response
        } catch (e: Exception) {
            if (timedOut.get() && !a.canceled.get()) throw IOException("Media analysis timed out")
            if (e.message.isNullOrBlank() && lastError.get().isNotEmpty()) throw IOException(lastError.get())
            throw e
        } finally { done.set(true); watcher.interrupt(); a.processId = null; cookie?.delete() }
    }

    fun reserve(id: String) { check(active.putIfAbsent(id, Active()) == null) { "Job is already reserved" } }
    fun discardReservation(id: String) { active[id]?.takeIf { !it.started.get() }?.let { active.remove(id, it) } }

    fun cancel(id: String) {
        active[id]?.let { it.canceled.set(true); it.connection?.disconnect(); it.nativeProcess?.destroy(); it.processId?.let(::destroy) }
    }

    private fun destroy(id: String) {
        // The wrapper's pstree shell command uses a job ID and is unreliable on Android.
        // /proc identifies only our uniquely tagged command, then kills descendants first.
        runCatching {
            val processes = File("/proc").listFiles()?.mapNotNull { dir ->
                val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
                val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@mapNotNull null
                val parent = stat.substringAfterLast(") ").split(' ').getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                Triple(pid, parent, runCatching { File(dir, "cmdline").readText() }.getOrDefault(""))
            } ?: emptyList()
            fun killTree(pid: Int) {
                processes.filter { it.second == pid }.forEach { killTree(it.first) }
                android.os.Process.killProcess(pid)
            }
            processes.filter { it.third.split('\u0000').contains("postprocess:FETCHY_PROCESS:$id") }.forEach { killTree(it.first) }
        }
        YoutubeDL.destroyProcessById(id)
    }

    private fun downloadSources(selection: JSONObject, dir: File, a: Active, progress: (String, Int, Long, Long) -> Unit): File {
        val sources = selection.optJSONArray("directSources") ?: return direct(selection, dir, a, progress)
        require(sources.length() == 2 && selection.optBoolean("needsMerge") && selection.getString("kind") == "video") { "Invalid selected stream pair" }
        require(sources.getJSONObject(0).getString("kind") == "video" && sources.getJSONObject(1).getString("kind") == "audio") { "Invalid selected stream types" }
        val rows = (0 until sources.length()).map { sources.getJSONObject(it) }
        val total = if (rows.all { it.optLong("sizeBytes") > 0 }) rows.sumOf { it.getLong("sizeBytes") } else -1L
        var downloaded = 0L
        val files = rows.mapIndexed { index, source ->
            val folder = File(dir, "streams/$index").apply { check(isDirectory || mkdirs()) }
            direct(source, folder, a) { _, _, bytes, _ ->
                val sum = downloaded + bytes
                progress("running", if (total > 0) (sum * 100 / total).toInt().coerceIn(0, 99) else -1, sum, total)
            }.also { downloaded += it.length() }
        }
        checkCanceled(a)
        initialize()
        val ext = selection.getString("ext")
        require(ext in setOf("mp4", "mkv", "webm")) { "Invalid selected merge container" }
        val output = File(dir, "media.$ext")
        progress("merging", 98, downloaded, downloaded)
        if (native("libffmpeg.so", listOf("-v", "error", "-nostdin", "-y", "-i", files[0].absolutePath,
                "-i", files[1].absolutePath, "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", output.absolutePath), a, 3600000).first != 0)
            throw IOException("Selected media stream merge failed")
        Log.i("FetchyDownload", "resolved_http_streams_merged")
        return output
    }

    private class Expired : IOException("Media link expired")
    private fun direct(s: JSONObject, dir: File, a: Active, progress: (String, Int, Long, Long) -> Unit): File {
        val ext = s.optString("sourceExt", s.getString("ext")); require(ext in MEDIA_EXTENSIONS) { "Unsupported direct media extension" }
        val partial = File(dir, "media.$ext.part")
        val output = File(dir, "media.$ext")
        val meta = File(dir, "media.$ext.resume.json")
        if (output.isFile && output.length() > 0 && !meta.exists()) {
            progress("saving", 99, output.length(), output.length())
            return output
        }
        val url = s.getString("directUrl")
        if (originalInstagram(url) != null) checkedUrl(url)
        val old = runCatching { JSONObject(meta.readText()) }.getOrNull()
        val offset = if (old != null && old.optString("url") == url && old.optString("etag").isNotEmpty() && !old.optString("etag").startsWith("W/")) partial.length() else 0L
        val headers = JSONObject((s.optJSONObject("headers") ?: JSONObject()).toString())
        if (offset > 0) { headers.put("Range", "bytes=$offset-").put("If-Range", old!!.getString("etag")) }
        val conn = connect(url, headers, a)
        try {
            val code = conn.responseCode
            if (code in setOf(401, 403, 404, 410)) throw Expired()
            if (code != 200 && code != 206) throw IOException("Media HTTP request failed ($code)")
            val etag = conn.getHeaderField("ETag") ?: ""
            var total = conn.contentLengthLong
            val append = code == 206 && offset > 0
            if (code == 206) {
                val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(conn.getHeaderField("Content-Range") ?: "")
                    ?: throw IOException("Invalid partial response")
                val start = match.groupValues[1].toLong(); val end = match.groupValues[2].toLong(); total = match.groupValues[3].toLong()
                require(start == offset && end >= start && end < total && (conn.contentLengthLong < 0 || conn.contentLengthLong == end - start + 1)) { "Invalid resume range" }
                if (append) require(etag.isNotEmpty() && etag == old!!.optString("etag")) { "Media changed during resume" }
            }
            if (!append) partial.outputStream().close()
            meta.writeText(JSONObject().put("url", url).put("etag", etag).toString())
            val type = conn.contentType ?: ""
            if (type.contains("html", true) || type.contains("json", true)) throw IOException("Server returned a page instead of media")
            var bytes = if (append) offset else 0L
            conn.inputStream.use { input ->
                java.io.FileOutputStream(partial, append).use { out ->
                    val buffer = ByteArray(65536)
                    var first = true
                    while (true) {
                        checkCanceled(a); val n = input.read(buffer); if (n < 0) break
                        if (first && !append && n >= 16) verify(buffer.copyOf(n.coerceAtMost(512)), type, s.optString("inputKind", s.getString("kind")))
                        first = false; out.write(buffer, 0, n); bytes += n
                        progress("running", if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 99) else -1, bytes, total)
                    }
                    out.fd.sync()
                }
            }
            checkCanceled(a)
            if (total >= 0 && bytes != total) throw IOException("Incomplete media response; partial file retained")
            verify(partial.inputStream().use { it.readBytesLimited() }, type, s.optString("inputKind", s.getString("kind")))
            progress("saving", 99, bytes, bytes)
            if (!partial.renameTo(output)) throw IOException("Cannot finalize media file")
            meta.delete(); progress("saving", 99, bytes, bytes); return output
        } finally { conn.disconnect(); a.connection = null }
    }

    /** Output conversion preserves the exact downloaded streams; no quality fallback. */
    private fun finish(source: File, selection: JSONObject, settings: JSONObject, a: Active,
                       progress: (String, Int, Long, Long) -> Unit, job: JSONObject): File {
        initialize()
        var file = source
        val expected = selection.getString("kind")
        if (expected == "video") {
            val ext = settings.optString("videoFormat", "mp4")
            require(ext in setOf("mp4", "mkv", "webm")) { "Unsupported video container" }
            if (file.extension != ext) {
                progress("merging", 95, file.length(), file.length())
                val target = File(file.parentFile, "converted.$ext")
                val prefix = listOf("-v", "error", "-nostdin", "-y", "-i", file.absolutePath, "-map", "0:v:0", "-map", "0:a:0?")
                val remux = native("libffmpeg.so", prefix + listOf("-c", "copy", target.absolutePath), a, 3600000)
                if (remux.first != 0) {
                    checkCanceled(a)
                    progress("converting", 95, file.length(), file.length())
                    val codecs = if (ext == "webm") listOf("-c:v", "libvpx-vp9", "-c:a", "libopus") else listOf("-c:v", "libx264", "-c:a", "aac")
                    if (native("libffmpeg.so", prefix + codecs + listOf(target.absolutePath), a, 3600000).first != 0) throw IOException("Video conversion failed")
                }
                file = target
            }
        }
        if (expected == "audio" && selection.optBoolean("extractAudio") &&
            (selection.optString("inputKind") == "video" || file.extension != selection.getString("ext"))) {
            val ext = selection.getString("ext")
            val codec = when (ext) { "m4a" -> "aac"; "mp3" -> "libmp3lame"; "opus" -> "libopus"; else -> error("Unsupported audio conversion") }
            val target = File(file.parentFile, "converted.$ext")
            progress("converting", 95, file.length(), file.length())
            if (native("libffmpeg.so", listOf("-v", "error", "-nostdin", "-y", "-i", file.absolutePath,
                    "-map", "0:a:0", "-vn", "-c:a", codec, target.absolutePath), a, 3600000).first != 0)
                throw IOException("Audio conversion failed; source has no supported audio track")
            file = target
        }
        checkCanceled(a)
        progress("saving", 99, file.length(), file.length())
        if (expected == "audio" && (settings.optBoolean("audioMetadata", true) || settings.optBoolean("audioCover", true))) {
            try { file = MediaExtras(this).audio(file, job, a, progress) }
            catch (_: Exception) {
                checkCanceled(a)
                val warnings = job.optJSONArray("warnings") ?: JSONArray().also { job.put("warnings", it) }
                warnings.put("오디오 정보를 적용하지 못했습니다. 원본 오디오는 저장합니다.")
            }
        }
        val probe = native("libffprobe.so", listOf("-v", "error", "-show_streams", "-show_format", "-of", "json", file.absolutePath), a, 120000)
        if (probe.first != 0) throw IOException("Media stream validation failed")
        val info = JSONObject(probe.second)
        val streams = info.optJSONArray("streams") ?: throw IOException("Media has no streams")
        val rows = (0 until streams.length()).map { streams.getJSONObject(it) }
        val video = rows.firstOrNull { it.optString("codec_type") == "video" }
        val metadata = JSONObject().put("duration", info.optJSONObject("format")?.optDouble("duration", 0.0) ?: 0.0)
        val tags = info.optJSONObject("format")?.optJSONObject("tags")
        val audioTags = rows.firstOrNull { it.optString("codec_type") == "audio" }?.optJSONObject("tags")
        for (key in listOf("title", "artist", "album")) (tags?.optString(key)?.takeIf { it.isNotBlank() } ?: audioTags?.optString(key)?.takeIf { it.isNotBlank() })?.let { metadata.put(key, it) }
        video?.let { metadata.put("width", it.optInt("width")).put("height", it.optInt("height"))
            val rate = it.optString("avg_frame_rate").split('/')
            val fps = if (rate.size == 2) (rate[0].toDoubleOrNull() ?: 0.0) / (rate[1].toDoubleOrNull() ?: 0.0) else 0.0
            if (fps.isFinite() && fps > 0) metadata.put("fps", fps)
        }
        selection.put("mediaMetadata", metadata)
        val hasAudio = rows.any { it.optString("codec_type") == "audio" }
        val videos = rows.filter { it.optString("codec_type") == "video" }
        val moving = videos.any { it.optJSONObject("disposition")?.optInt("attached_pic", 0) != 1 }
        val containers = info.optJSONObject("format")?.optString("format_name").orEmpty().split(',').toSet()
        val still = containers.any { it in setOf("apng", "avif", "gif", "heif", "image2", "image2pipe", "jpeg_pipe", "png_pipe", "webp_pipe") }
        require(when (expected) {
            "audio" -> hasAudio && !moving
            "image" -> videos.any { it.optString("codec_name") in setOf("mjpeg", "png", "webp", "gif", "av1", "hevc") } && !hasAudio
            "video" -> moving && !still && (!selection.optBoolean("needsMerge") || hasAudio)
            else -> false
        }) { "Completed media does not contain the selected streams" }
        // Same whole-file packet integrity check used by Morit, plus a short decode.
        val integrity = listOf("-v", "error", "-nostdin", "-xerror", "-i", file.absolutePath,
            "-map", "0:v:0?", "-map", "0:a:0?", "-c", "copy", "-f", "null", "-")
        if (native("libffmpeg.so", integrity, a, 120000).first != 0) throw IOException("Media integrity validation failed")
        val decode = listOf("-v", "error", "-nostdin", "-xerror", "-i", file.absolutePath,
            "-t", "3", "-map", "0:v:0?", "-map", "0:a:0?", "-f", "null", "-")
        if (native("libffmpeg.so", decode, a, 120000).first != 0) throw IOException("Media decode validation failed")
        checkCanceled(a)
        progress("saving", 99, file.length(), file.length())
        return file
    }

    internal fun native(binary: String, args: List<String>, a: Active, timeoutMs: Long): Pair<Int, String> {
        checkCanceled(a)
        val executable = File(context.applicationInfo.nativeLibraryDir, binary)
        require(executable.canExecute()) { "Bundled media validation executable is missing" }
        val builder = ProcessBuilder(listOf(executable.absolutePath) + args).redirectErrorStream(true)
        val root = File(context.noBackupFilesDir, "youtubedl-android/packages")
        builder.environment()["LD_LIBRARY_PATH"] = listOf("ffmpeg", "python").joinToString(":") { File(root, "$it/usr/lib").absolutePath }
        val process = builder.start()
        a.nativeProcess = process
        val output = java.io.ByteArrayOutputStream()
        val reader = Thread {
            runCatching { process.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; synchronized(output) {
                    val keep = n.coerceAtMost((1024 * 1024 - output.size()).coerceAtLeast(0)); output.write(buffer, 0, keep)
                } }
            } }
        }.apply { isDaemon = true; start() }
        val start = android.os.SystemClock.elapsedRealtime()
        try {
            while (true) {
                checkCanceled(a)
                val code = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
                if (code != null) { reader.join(2000); return code to synchronized(output) { output.toString("UTF-8") } }
                if (android.os.SystemClock.elapsedRealtime() - start > timeoutMs) throw IOException("Media processing timed out")
                Thread.sleep(100)
            }
        } finally { process.destroy(); a.nativeProcess = null; reader.join(1000) }
    }

    internal fun connect(raw: String, supplied: JSONObject, a: Active, timeoutMs: Int = 20000): HttpURLConnection {
        var uri = checkedUrl(raw)
        val headers = mutableMapOf<String, String>()
        supplied.keys().forEach { key ->
            val value = supplied.optString(key)
            require(!key.contains('\r') && !key.contains('\n') && !value.contains('\r') && !value.contains('\n')) { "Invalid media headers" }
            if (key.lowercase() !in setOf("host", "content-length", "connection")) headers[key] = value
        }
        for (hop in 0..5) {
            checkCanceled(a)
            val conn = uri.toURL().openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false; conn.connectTimeout = timeoutMs; conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }; a.connection = conn
            try {
                checkCanceled(a)
                if (conn.responseCode !in setOf(301, 302, 303, 307, 308)) return conn
                if (hop == 5) throw IOException("Too many media redirects")
                val next = checkedUrl(uri.resolve(conn.getHeaderField("Location") ?: throw IOException("Missing redirect location")).toString())
                if (!uri.host.equals(next.host, true) || uri.port != next.port || uri.scheme != next.scheme) {
                    headers.keys.removeAll { it.equals("Authorization", true) || it.equals("Cookie", true) || it.equals("Proxy-Authorization", true) }
                }
                if (originalInstagram(raw) != null && originalInstagram(next.toString()) == null) throw IOException("Instagram redirect is not an original image")
                uri = next
            } catch (e: Exception) { conn.disconnect(); throw e }
            conn.disconnect()
        }
        throw IOException("Too many media redirects")
    }

    internal fun checkedUrl(raw: String): URI {
        val uri = try { URI(raw) } catch (_: Exception) { throw IOException("Invalid media URL") }
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null) { "Only HTTP(S) URLs without credentials are supported" }
        require(uri.port in listOf(-1, 80, 443)) { "Unsupported URL port" }
        val addresses = InetAddress.getAllByName(uri.host)
        require(addresses.isNotEmpty() && addresses.none { ip ->
            val b = ip.address
            ip.isAnyLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isSiteLocalAddress || ip.isMulticastAddress ||
                (b.size == 4 && ((b[0].toInt() and 255) == 0 || (b[0].toInt() and 255) >= 224 || ((b[0].toInt() and 255) == 100 && (b[1].toInt() and 255) in 64..127))) ||
                (b.size == 16 && (b[0].toInt() and 254) == 252)
        }) { "Local and private network URLs are not supported" }
        return uri
    }

    private fun instagramMediaUri(raw: String): URI {
        val url = URI(raw)
        require(url.scheme == "https" && url.rawUserInfo == null && url.port in listOf(-1, 443) &&
            (url.host.orEmpty().endsWith(".cdninstagram.com") || url.host.orEmpty().matches(Regex("instagram\\.[a-z0-9-]+\\.fna\\.fbcdn\\.net")))) { "Invalid Instagram media host" }
        return url
    }

    private fun instagramImage(root: JSONObject, e: JSONObject): String? {
        fun extractor(j: JSONObject) = j.optString("extractor_key", j.optString("extractor")).lowercase()
        if (extractor(root) != "instagram" || extractor(e) != "instagram" || e.optJSONArray("formats")?.length()?.let { it > 0 } == true ||
            (!e.isNull("duration") && e.has("duration")) || e.optString("url").isNotEmpty() || e.optString("ext").isNotEmpty()) return null
        val thumbs = e.optJSONArray("thumbnails") ?: return null
        val candidates = (0 until thumbs.length()).mapNotNull { originalInstagram(thumbs.optJSONObject(it)?.optString("url") ?: "") }
        val preferred = candidates.filter { !query(URI(it)).containsKey("stp") }.ifEmpty { candidates }
        return preferred.singleOrNull()
    }
    private fun originalInstagram(raw: String): String? = runCatching {
        val u = URI(raw); val q = query(u)
        require(u.scheme == "https" && u.rawUserInfo == null && u.port in listOf(-1, 443) &&
            u.host.matches(Regex("instagram\\.[a-z0-9-]+\\.fna\\.fbcdn\\.net")) && extension(u.path) in setOf("jpg", "jpeg", "png", "webp"))
        require(listOf("oh", "oe", "ig_cache_key").all { !q[it].isNullOrEmpty() })
        q["stp"]?.takeIf { it.isNotEmpty() }?.let {
            require(it.matches(Regex("dst-(?:jpg|webp)(?:_[a-z0-9.]+)*")) && !Regex("(?:^|_)(?:c\\d|[sp]\\d+x\\d+)").containsMatchIn(it))
        }
        raw.substringBefore('#')
    }.getOrNull()
    private fun query(u: URI): Map<String, String> {
        val pairs = u.rawQuery?.split('&')?.map { p ->
            URLDecoder.decode(p.substringBefore('='), "UTF-8") to URLDecoder.decode(p.substringAfter('=', ""), "UTF-8")
        } ?: emptyList()
        require(pairs.map { it.first }.distinct().size == pairs.size) { "Duplicate query keys" }
        return pairs.toMap()
    }

    fun mime(file: File, kind: String): String = when (file.extension.lowercase()) {
        "webm" -> if (kind == "audio") "audio/webm" else "video/webm"
        "m4a" -> "audio/mp4"; "opus" -> "audio/ogg"; "mkv" -> "video/x-matroska"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: when (kind) {
            "image" -> "image/jpeg"; "audio" -> "audio/mpeg"; "video" -> "video/mp4"; else -> "application/octet-stream"
        }
    }
    private fun java.io.InputStream.readBytesLimited(): ByteArray {
        val buffer = ByteArray(512)
        var count = 0
        while (count < buffer.size) { val n = read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
        return buffer.copyOf(count)
    }
    private fun verify(b: ByteArray, type: String, kind: String) {
        require(b.isNotEmpty()) { "Empty media response" }
        val text = b.toString(Charsets.UTF_8).trimStart().lowercase()
        require(!type.contains("html", true) && !type.contains("json", true) && !text.startsWith("<") && !text.startsWith("{") && !text.startsWith("[")) { "Server returned a page instead of media" }
        fun at(offset: Int, value: String) = b.size >= offset + value.length && String(b, offset, value.length, Charsets.ISO_8859_1) == value
        val image = (b.size >= 3 && b[0] == 0xff.toByte() && b[1] == 0xd8.toByte() && b[2] == 0xff.toByte()) ||
            (b.size >= 8 && b[0] == 0x89.toByte() && at(1, "PNG\r\n\u001a\n")) || at(0, "GIF8") || (at(0, "RIFF") && at(8, "WEBP")) ||
            (at(4, "ftyp") && (at(8, "avif") || at(8, "heic") || at(8, "mif1")))
        val media = at(4, "ftyp") || at(0, "OggS") || at(0, "ID3") || at(0, "fLaC") || (at(0, "RIFF") && (at(8, "WAVE") || at(8, "AVI "))) ||
            (b.size >= 4 && b[0] == 0x1a.toByte() && b[1] == 0x45.toByte() && b[2] == 0xdf.toByte() && b[3] == 0xa3.toByte()) ||
            (b.size >= 2 && b[0] == 0xff.toByte() && (b[1].toInt() and 0xe0) == 0xe0) || (b.size >= 189 && b[0] == 0x47.toByte() && b[188] == 0x47.toByte())
        require(if (kind == "image") image else media) { "Unrecognized media signature" }
        val base = type.substringBefore(';').lowercase()
        require(base.isEmpty() || base in setOf("application/octet-stream", "binary/octet-stream", "application/ogg") || base.startsWith("$kind/") || (kind == "audio" && base == "video/mp4")) { "Unexpected media content type" }
    }
    internal fun checkCanceled(a: Active) { if (a.canceled.get() || Thread.currentThread().isInterrupted) throw DownloadFailure(FailureKind.CANCELED) }
    private fun safeError(e: Exception, a: Active?): IOException = DownloadFailure(
        if (a?.canceled?.get() == true || e is YoutubeDL.CanceledException || e is InterruptedException) FailureKind.CANCELED else FailureKind.from(e))
    private fun publicHeaders(input: JSONObject?): JSONObject {
        val output = JSONObject()
        input?.keys()?.forEach { key ->
            if (key.lowercase() in setOf("user-agent", "referer", "accept", "origin")) output.put(key, input.getString(key))
        }
        return output
    }
    private fun extension(path: String) = path.substringAfterLast('.', "").lowercase()
    private fun kind(ext: String) = when (ext) { in IMAGE_EXTENSIONS -> "image"; in setOf("mp3", "m4a", "opus", "ogg", "wav", "flac", "aac") -> "audio"; else -> "video" }
    private fun format(id: String, label: String, ext: String, kind: String, height: Int, size: Long, merge: Boolean) =
        JSONObject().put("id", id).put("label", label).put("ext", ext).put("kind", kind).put("height", height).put("sizeBytes", size.coerceAtLeast(0)).put("needsMerge", merge)
    private fun result(url: String, platform: String, title: String, assets: JSONArray) = JSONObject().put("sourceUrl", url).put("platform", platform).put("title", title).put("assets", assets)

    companion object {
        private val INIT_LOCK = Any()
        private val INFO_LOCK = Any()

    }
}
