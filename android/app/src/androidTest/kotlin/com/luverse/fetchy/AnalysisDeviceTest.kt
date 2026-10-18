package com.luverse.fetchy

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AnalysisDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun engine() = Engine(context, CookieVault(context))

    @Test fun missingAudioCodecDoesNotForceAMerge() {
        val uri = URI("https://x.com/i/status/123")
        val formats = JSONArray()
            .put(JSONObject().put("format_id", "http-632").put("ext", "mp4").put("width", 640).put("height", 360).put("vcodec", "h264").put("protocol", "https").put("url", "https://video.twimg.com/fixture.mp4"))
            .put(JSONObject().put("format_id", "hls-audio").put("ext", "m4a").put("vcodec", "none").put("acodec", "aac").put("abr", 128).put("url", "https://video.twimg.com/audio.m3u8"))
            .put(JSONObject().put("format_id", "hls-video").put("ext", "mp4").put("width", 1280).put("height", 720).put("vcodec", "h264").put("acodec", "none").put("url", "https://video.twimg.com/video.m3u8"))
        val asset = engine().parseExtractedMedia(uri, "X (Twitter)", JSONObject().put("id", "codec-fixture").put("timestamp", 1700000000).put("formats", formats)).getJSONArray("assets").getJSONObject(0)
        try {
            val parsed = asset.getJSONArray("formats")
            val rows = (0 until parsed.length()).map { parsed.getJSONObject(it) }
            val http = rows.first { it.getString("id") == "http-632" }
            val hls = rows.first { it.getString("id") == "hls-video" }
            assertFalse(http.getBoolean("needsMerge")); assertEquals("mp4", http.getString("ext")); assertEquals("http-632", http.getString("selector"))
            assertTrue(hls.getBoolean("needsMerge")); assertEquals("hls-video+hls-audio", hls.getString("selector"))
            assertEquals(1700000000000L, asset.getLong("sourceTimestamp"))
        } finally { File(context.noBackupFilesDir, "resolved-media/${asset.getString("resolvedInfo")}.json").delete() }
    }

    @Test fun xParserPreservesOriginalsSelectionsAndSensitiveMedia() {
        val engine = engine()
        val uri = URI("https://x.com/i/web/status/123")
        val video = JSONObject().put("id", "v1").put("type", "video").put("width", 640).put("height", 640)
            .put("duration", 12.9).put("thumbnail_url", "https://pbs.twimg.com/ext_tw_video_thumb/a.jpg")
            .put("variants", JSONArray()
                .put(JSONObject().put("url", "https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/480x480/a.mp4?tag=1").put("content_type", "video/mp4"))
                .put(JSONObject().put("url", "https://video.twimg.com/ext_tw_video/1/pu/vid/240x240/b.mp4").put("content_type", "video/mp4"))
                .put(JSONObject().put("url", "https://video.twimg.com/a.m3u8").put("content_type", "application/x-mpegURL"))
                .put(JSONObject().put("url", "https://evil.example/a.mp4").put("content_type", "video/mp4")))
        val photo = JSONObject().put("id", "p1").put("type", "photo").put("url", "https://pbs.twimg.com/media/original.jpg?name=orig")
            .put("thumbnail_url", "https://pbs.twimg.com/media/preview.jpg?name=small")
        val root = JSONObject().put("code", 200).put("tweet", JSONObject().put("id", "123").put("possibly_sensitive", true).put("created_timestamp", 1700000000)
            .put("media", JSONObject().put("all", JSONArray().put(video).put(photo))))
        val assets = engine.parseXMedia(uri, root).getJSONArray("assets")
        assertEquals(2, assets.length())
        assertEquals(1700000000000L, assets.getJSONObject(0).getLong("sourceTimestamp"))
        val formats = assets.getJSONObject(0).getJSONArray("formats")
        assertEquals(5, formats.length()) // Two original MP4s plus three local audio outputs.
        assertEquals(480, formats.getJSONObject(0).getInt("height"))
        assertEquals("video", formats.getJSONObject(0).getString("kind"))
        assertEquals("audio", formats.getJSONObject(2).getString("kind"))
        assertTrue(formats.getJSONObject(2).getBoolean("extractAudio"))
        val image = assets.getJSONObject(1).getJSONArray("formats").getJSONObject(0)
        assertTrue(image.getString("directUrl").contains("original.jpg"))
        assertFalse(image.getString("directUrl").contains("preview"))
        val selectedId = formats.getJSONObject(0).getString("id")
        val variants = video.getJSONArray("variants")
        variants.getJSONObject(0).put("url", "https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/480x480/a.mp4?tag=2")
        assertEquals(selectedId, engine.parseXMedia(uri, root).getJSONArray("assets").getJSONObject(0).getJSONArray("formats").getJSONObject(0).getString("id"))
        assertEquals(1, engine.parseXMedia(URI("https://x.com/user/status/123/photo/1"), root).getJSONArray("assets").length())
        photo.put("url", "https://pbs.twimg.com/ext_tw_video_thumb/a.jpg")
        assertEquals(1, engine.parseXMedia(uri, root).getJSONArray("assets").length())
    }

    @Test fun canceledQueuedAnalysisDoesNotStartNetworking() {
        val engine = engine(); val id = "analysis-${UUID.randomUUID()}"
        engine.reserve(id); engine.cancel(id)
        val start = SystemClock.elapsedRealtime()
        try { engine.analyze("https://x.com/i/status/643211948184596480", id); fail("Canceled analysis must not run") }
        catch (e: java.io.IOException) { assertEquals("Download canceled", e.message) }
        assertTrue(SystemClock.elapsedRealtime() - start < 1000)
    }

    /** Opt-in live test: an inaccessible site is a failure, never a passing availability probe. */
    @Test fun publicXDownloadAndPersistentCacheWithoutCookies() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("mediaUrl") ?: "https://x.com/i/status/643211948184596480"
        val kind = args.getString("mediaKind") ?: "video"
        assertTrue("Anonymous test requires no X cookie; existing cookies are not deleted", CookieVault(context).list().none { it.optString("platform") == "x" })
        val engine = engine(); engine.clearAnalysisCache()
        val coldStart = SystemClock.elapsedRealtime()
        val analysis = engine.analyze(url)
        val coldMs = SystemClock.elapsedRealtime() - coldStart
        val warmStart = SystemClock.elapsedRealtime()
        val cached = engine().analyze(url) // New Engine proves the cache is stored in Room.
        val warmMs = SystemClock.elapsedRealtime() - warmStart
        assertEquals("fxtwitter", analysis.getString("analysisProvider"))
        assertEquals(analysis.toString(), cached.toString())
        assertTrue("Warm cache must avoid network/extractor work", warmMs < 1000)
        val assets = analysis.getJSONArray("assets")
        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.first { candidate ->
            val fs = candidate.getJSONArray("formats")
            (0 until fs.length()).any { fs.getJSONObject(it).getString("kind") == kind }
        }
        val formats = asset.getJSONArray("formats")
        val chosen = (0 until formats.length()).map { formats.getJSONObject(it) }.filter { it.getString("kind") == kind }
            .minByOrNull { it.optInt("height") }!!
        val expire = args.getString("expiredUrl") == "true"
        val originalUrl = chosen.getString("directUrl")
        if (expire) {
            require(URI(originalUrl).path.endsWith(".mp4")) { "Expiry fixture uses an MP4 source" }
            // A valid CDN path with a missing file returns 404, unlike an invalid root path (400).
            chosen.put("directUrl", originalUrl.substringBefore('?').removeSuffix(".mp4") + "_missing.mp4")
        }
        val id = UUID.randomUUID().toString()
        val work = File(context.cacheDir, "analysis-test/$id").apply { mkdirs() }
        try {
            val job = JSONObject().put("id", id).put("sourceUrl", url).put("asset", asset).put("selection", chosen)
                .put("assetId", asset.getString("id")).put("formatId", chosen.getString("id"))
                .put("settings", Store(context).settings())
            val file = engine.download(job, work) { _, _, _, _ -> }
            assertTrue(file.length() > 1000)
            assertEquals(chosen.getString("ext"), file.extension)
            assertTrue(job.getJSONObject("selection").has("mediaMetadata"))
            if (kind == "video" && chosen.optInt("height") > 0) assertEquals(chosen.getInt("height"), job.getJSONObject("selection").getJSONObject("mediaMetadata").getInt("height"))
            if (expire) assertEquals(URI(originalUrl).path, URI(job.getJSONObject("selection").getString("directUrl")).path)
            instrumentation.sendStatus(0, Bundle().apply { putString("analysisResult", JSONObject()
                .put("coldMs", coldMs).put("warmMs", warmMs).put("assets", assets.length())
                .put("kind", kind).put("expiredUrl", expire).put("bytes", file.length()).put("ext", file.extension).toString()) })
        } finally { engine.cancel(id); work.deleteRecursively() }
    }
}
