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
class PreviewDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun engine() = Engine(context, CookieVault(context))

    @Test fun htmlMetadataAndInstagramOriginalsAreNotConfused() {
        val preview = PreviewMetadata.fromHtml("<meta content='A &amp; B' property='og:title'><meta name=twitter:image content='https://cdn.example/a.jpg'><meta property='og:image' content=''>")!!
        assertEquals("A & B", preview.getString("title"))
        assertEquals("https://cdn.example/a.jpg", preview.getString("thumbnail"))
        assertEquals("", PreviewMetadata.safeImage("file:///private/a.jpg"))
        val shortcode = "DaupWjVARQ_"
        val node = JSONObject().put("shortcode", shortcode).put("__typename", "GraphVideo").put("is_video", true)
            .put("video_url", "https://instagram.ficn1-1.fna.fbcdn.net/video.mp4")
            .put("display_url", "https://instagram.ficn1-1.fna.fbcdn.net/preview.jpg")
            .put("dimensions", JSONObject().put("width", 1080).put("height", 1920))
        fun html(media: JSONObject) = "{\"contextJSON\":${JSONObject.quote(JSONObject().put("gql_data", JSONObject().put("shortcode_media", media)).toString())}}"
        val result = engine().parseInstagramEmbed(URI("https://www.instagram.com/reel/$shortcode/"), html(node))
        assertEquals("instagram_embed", result.getString("analysisProvider"))
        val formats = result.getJSONArray("assets").getJSONObject(0).getJSONArray("formats")
        assertEquals(5, formats.length())
        assertEquals(0, formats.getJSONObject(0).getInt("height"))
        assertTrue(formats.getJSONObject(0).getString("directUrl").endsWith("video.mp4"))
        assertTrue(formats.getJSONObject(1).getBoolean("automatic"))
        assertEquals("audio", formats.getJSONObject(2).getString("kind"))
        node.put("video_url", "https://evil.example/video.mp4")
        try { engine().parseInstagramEmbed(URI("https://www.instagram.com/p/$shortcode/"), html(node)); fail("Reject a non-platform original") }
        catch (_: IllegalArgumentException) { }
        node.remove("video_url")
        try { engine().parseInstagramEmbed(URI("https://www.instagram.com/p/$shortcode/"), html(node)); fail("A thumbnail is not a video") }
        catch (_: org.json.JSONException) { }
        node.put("__typename", "GraphSidecar").put("is_video", false).put("edge_sidecar_to_children", JSONObject().put("edges", JSONArray().put(JSONObject().put("node", JSONObject().put("__typename", "Unsupported")))))
        try { engine().parseInstagramEmbed(URI("https://www.instagram.com/p/$shortcode/"), html(node)); fail("Never return an incomplete carousel") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun canceledPreviewDoesNotStartNetworking() {
        val engine = engine(); val id = "preview-${UUID.randomUUID()}"
        engine.reserve(id); engine.cancel(id)
        assertNull(engine.preview("https://www.youtube.com/watch?v=5Mu9i_B8bjA", id))
    }

    @Test fun instagramStoriesReachTheExtractorAndSavedCookies() {
        val work = File(context.cacheDir, "story-cookie-test/${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getNoBackupFilesDir(): File = work
        }
        val vault = CookieVault(isolated)
        val engine = Engine(context, vault)
        val url = "https://m.instagram.com/stories/xgnjzxhtzfhzh/4002710559245555959?utm_source=ig_story_item_share&stkn=test"
        var temporary: File? = null
        try {
            val cookie = File(work, "fixture.txt").apply {
                writeText("# Netscape HTTP Cookie File\n#HttpOnly_www.instagram.com\tFALSE\t/\tTRUE\t0\tsessionid\tfetchy-test-not-a-real-session\n")
            }
            vault.import("instagram", android.net.Uri.fromFile(cookie))
            for (path in listOf("/stories/user.name/", "/stories/user.name/123/", "/stories/highlights/123/"))
                assertEquals("Instagram", mediaPlatform(URI("https://www.instagram.com$path")))
            for (path in listOf("/stories/", "/stories/highlights/", "/stories/user/not-an-id/")) {
                try { mediaPlatform(URI("https://www.instagram.com$path")); fail("Reject malformed story URL") }
                catch (_: IllegalArgumentException) { }
            }
            val requestMethod = Engine::class.java.getDeclaredMethod("request", String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            val request = requestMethod.invoke(engine, url, true) as com.yausername.youtubedl_android.YoutubeDLRequest
            assertTrue(request.buildCommand().contains("https://www.instagram.com/stories/xgnjzxhtzfhzh/4002710559245555959/?utm_source=ig_story_item_share&stkn=test"))
            temporary = Engine::class.java.getDeclaredMethod("cookieFile", String::class.java).apply { isAccessible = true }.invoke(engine, url) as File
            assertTrue(temporary!!.readText().contains("\tsessionid\tfetchy-test-not-a-real-session"))
            assertEquals("ready", engine.info().getString("status"))
            val versionRequest = com.yausername.youtubedl_android.YoutubeDLRequest(emptyList<String>()).addOption("--version")
            val active = Class.forName("com.luverse.fetchy.Engine\$Active").getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val execute = Engine::class.java.declaredMethods.single { it.name == "execute" }.apply { isAccessible = true }
            execute.invoke(engine, versionRequest, active, "story-test-${UUID.randomUUID()}", url, true, 20000L, null)
            assertTrue("Saved cookie file must reach yt-dlp", versionRequest.hasOption("--cookies"))
            assertFalse("Temporary cookie file must be removed after execution", File(versionRequest.getOption("--cookies")!!).exists())
            val extractors = (requestMethod.invoke(engine, null, false) as com.yausername.youtubedl_android.YoutubeDLRequest).addOption("--list-extractors")
            val response = execute.invoke(engine, extractors, active, "story-list-${UUID.randomUUID()}", url, false, 20000L, null) as com.yausername.youtubedl_android.YoutubeDLResponse
            instrumentation.sendStatus(0, Bundle().apply { putString("storyExtractors", response.out.lineSequence().filter { it.contains("instagram", true) }.joinToString("\n")) })
            assertTrue(response.out.contains("instagram:story"))
            assertNull(engine.preview(url)) // No post embed or public login-page preview.
        } finally { temporary?.delete(); work.deleteRecursively() }
    }

    @Test fun liveStoryWithoutLoginReportsAccessInsteadOfUnsupportedUrl() {
        org.junit.Assume.assumeTrue("Do not use a user's saved login for this public probe",
            CookieVault(context).list().none { it.optString("platform") == "instagram" })
        val url = "https://www.instagram.com/stories/xgnjzxhtzfhzh/4002710559245555959?utm_source=ig_story_item_share&stkn=MTlrb3N2Zm5pdmNtbg=="
        try { engine().analyze(url); fail("Public probe expects an Instagram access restriction") }
        catch (error: java.io.IOException) {
            assertFalse(error.message.orEmpty().contains("Unsupported"))
            assertTrue(error.message.orEmpty().contains("로그인"))
            instrumentation.sendStatus(0, Bundle().apply { putString("storyResult", error.message) })
        }
    }

    @Test fun storyPhotosAreOriginalsAndNeverVideoPosters() {
        val engine = engine()
        val uri = URI("https://www.instagram.com/stories/fixture/4002710559245555959/")
        val source = JSONObject().put("id", "fixture-photo").put("fetchy_story_image", true).put("extractor_key", "InstagramStory")
            .put("formats", JSONArray().put(JSONObject().put("format_id", "fetchy-story-image").put("ext", "jpg")
                .put("vcodec", "none").put("acodec", "none").put("width", 1080).put("height", 1920)
                .put("url", "https://instagram.fixture.fna.fbcdn.net/original.jpg")))
        val asset = engine.parseExtractedMedia(uri, "Instagram", source).getJSONArray("assets").getJSONObject(0)
        try {
            val formats = asset.getJSONArray("formats")
            assertEquals(1, formats.length())
            assertEquals("image", formats.getJSONObject(0).getString("kind"))
            assertEquals(1920, formats.getJSONObject(0).getInt("height"))
            assertTrue(formats.getJSONObject(0).getString("directUrl").endsWith("original.jpg"))
            source.getJSONArray("formats").getJSONObject(0).put("url", "https://evil.example/original.jpg")
            try { engine.parseExtractedMedia(uri, "Instagram", source); fail("Reject an external image host") }
            catch (_: IllegalArgumentException) { }
            source.put("fetchy_story_image", false)
            try { engine.parseExtractedMedia(uri, "Instagram", source); fail("A poster without an explicit photo marker cannot download") }
            catch (_: java.io.IOException) { }
        } finally {
            File(context.noBackupFilesDir, "resolved-media/${asset.optString("resolvedInfo")}.json").delete()
        }
    }

    @Test fun bundledStoryCheckOnAndroidPython() {
        val script = File("/data/local/tmp/fetchy-story-check.py")
        org.junit.Assume.assumeTrue("Push scripts/check_instagram_stories.py for this opt-in fixture", script.isFile)
        assertEquals("ready", engine().info().getString("status"))
        val root = File(context.noBackupFilesDir, "youtubedl-android")
        val builder = ProcessBuilder(File(context.applicationInfo.nativeLibraryDir, "libpython.so").path, script.path,
            File(root, "yt-dlp/fetchy-modules").path,
            File(context.noBackupFilesDir, "fetchy-extractors/instagram/yt_dlp_plugins/extractor/fetchy_story.py").path).redirectErrorStream(true)
        builder.environment()["PYTHONHOME"] = File(root, "packages/python/usr").path
        builder.environment()["LD_LIBRARY_PATH"] = File(root, "packages/python/usr/lib").path
        builder.environment()["TMPDIR"] = context.cacheDir.path
        val process = builder.start()
        try {
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.exitValue())
            assertTrue(output.contains("both media retained"))
            instrumentation.sendStatus(0, Bundle().apply { putString("storyFixture", output.take(2000)) })
        } finally { process.destroy() }
    }

    @Test fun clearPerformanceCaches() {
        assertTrue(Store(context).list("jobs").all { it.optString("state") in FetchyRuntime.terminal })
        engine().clearAnalysisCache()
        for (fixture in listOf("preview-test", "preview-resume", "analysis-test")) File(context.cacheDir, fixture).deleteRecursively()
    }

    @Test fun bundledModulesRetainBytecodeAcrossEngineInstances() {
        val start = SystemClock.elapsedRealtime()
        assertEquals("ready", engine().info().getString("status"))
        val firstMs = SystemClock.elapsedRealtime() - start
        val warmStart = SystemClock.elapsedRealtime()
        assertEquals("ready", engine().info().getString("status"))
        val warmMs = SystemClock.elapsedRealtime() - warmStart
        val modules = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/fetchy-modules")
        val bytecode = modules.walkTopDown().count { it.extension == "pyc" }
        assertTrue("Bundled Python imports must retain bytecode", bytecode > 0)
        assertTrue("Warm engine startup must fit the 10-second budget", warmMs < 10000)
        instrumentation.sendStatus(0, Bundle().apply { putString("startupResult", JSONObject()
            .put("firstMs", firstMs).put("warmMs", warmMs).put("bytecodeFiles", bytecode).toString()) })
    }

    @Test fun livePublicPreviewBudget() {
        val url = InstrumentationRegistry.getArguments().getString("mediaUrl") ?: "https://vimeo.com/76979871"
        val engine = engine(); engine.clearAnalysisCache()
        val start = SystemClock.elapsedRealtime()
        val preview = engine.preview(url) ?: throw AssertionError("Public preview unavailable")
        val ms = SystemClock.elapsedRealtime() - start
        assertTrue(preview.optString("title").isNotBlank())
        assertTrue(preview.optString("thumbnail").startsWith("https://"))
        assertTrue("Preview exceeds 10-second budget", ms < 10000)
        instrumentation.sendStatus(0, Bundle().apply { putString("previewResult", JSONObject().put("platform", preview.getString("platform")).put("previewMs", ms).toString()) })
    }

    @Test fun pauseResolvedStreamsAndResumeWithNewEngine() {
        val url = "https://www.w3schools.com/html/mov_bbb.mp4"
        val first = engine()
        val selected = JSONObject().put("id", "fixture-pair").put("kind", "video").put("ext", "mkv").put("needsMerge", true)
            .put("directSources", JSONArray()
                .put(JSONObject().put("directUrl", url).put("ext", "mp4").put("kind", "video"))
                .put(JSONObject().put("directUrl", "https://www.w3schools.com/html/horse.mp3").put("ext", "mp3").put("kind", "audio")))
        val asset = JSONObject().put("id", "fixture-asset").put("formats", JSONArray().put(selected))
        val id = UUID.randomUUID().toString()
        val work = File(context.cacheDir, "preview-resume/$id").apply { mkdirs() }
        val job = JSONObject().put("id", id).put("sourceUrl", url).put("asset", asset).put("selection", selected)
            .put("assetId", asset.getString("id")).put("formatId", selected.getString("id"))
            .put("settings", JSONObject(Store(context).settings().toString()).put("resume", true))
        var paused = false
        try {
            try {
                first.download(job, work) { _, _, bytes, _ -> if (bytes >= 65536 && !paused) { paused = true; first.cancel(id) } }
                fail("Pause must stop before merging")
            } catch (_: java.io.IOException) { assertTrue(paused) }
            assertTrue(work.walkTopDown().any { it.name.endsWith(".part") && it.length() > 0 })
            val output = engine().download(job, work) { _, _, _, _ -> }
            assertTrue(output.length() > 1000)
            assertTrue(selected.getJSONObject("mediaMetadata").getInt("height") > 0)
        } finally { first.cancel(id); work.deleteRecursively() }
    }

    /** Opt-in public fixtures. No user records/settings/cookies are changed, output stays temporary. */
    @Test fun livePreviewAndExactDownload() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("mediaUrl") ?: "https://www.youtube.com/watch?v=5Mu9i_B8bjA&list=RD5Mu9i_B8bjA&start_radio=1"
        val engine = engine(); engine.clearAnalysisCache()
        val start = SystemClock.elapsedRealtime()
        val preview = engine.preview(url) ?: throw AssertionError("Expected real public preview metadata")
        val previewMs = SystemClock.elapsedRealtime() - start
        assertTrue(preview.optString("title").isNotBlank())
        assertTrue(preview.optString("thumbnail").startsWith("https://"))
        assertFalse(preview.has("assets")) // Metadata cannot be enqueued as pretend downloadable media.
        assertTrue("Preview budget is 10 seconds", previewMs < 10000)
        val analysisStart = SystemClock.elapsedRealtime()
        val analysis = engine.analyze(url, fullFormats = args.getString("fullFormats") == "true")
        val analysisMs = SystemClock.elapsedRealtime() - analysisStart
        val warmStart = SystemClock.elapsedRealtime()
        assertNotNull(engine().preview(url))
        val warmMs = SystemClock.elapsedRealtime() - warmStart
        assertTrue(warmMs < 1000)
        val asset = analysis.getJSONArray("assets").getJSONObject(0)
        val formats = asset.getJSONArray("formats")
        val mediaKind = args.getString("mediaKind") ?: "video"
        val selection = (0 until formats.length()).map { formats.getJSONObject(it) }.filter { it.getString("kind") == mediaKind }
            .sortedWith(compareBy<JSONObject> { it.optInt("height").takeIf { h -> h > 0 } ?: 99999 }
                .thenBy { if (it.has("directUrl") || it.has("directSources")) 0 else 1 }).first()
        if (args.getString("forceExtractorDownload") == "true") {
            selection.remove("directUrl"); selection.remove("directSources")
        }
        if (args.getString("expireResolved") == "true") {
            val cachedFile = File(context.noBackupFilesDir, "resolved-media/${asset.getString("resolvedInfo")}.json")
            val cached = JSONObject(cachedFile.readText())
            val fs = cached.getJSONObject("info").getJSONArray("formats")
            val selectedIds = selection.getString("selector").split('+')
            for (i in 0 until fs.length()) if (fs.getJSONObject(i).optString("format_id") in selectedIds)
                fs.getJSONObject(i).put("url", "https://www.youtube.com/not-found-fetchy-test.mp4")
            cachedFile.writeText(cached.toString())
        }
        val id = UUID.randomUUID().toString()
        val work = File(context.cacheDir, "preview-test/$id").apply { mkdirs() }
        var firstByteMs = -1L
        val downloadStart = SystemClock.elapsedRealtime()
        try {
            val job = JSONObject().put("id", id).put("sourceUrl", url).put("asset", asset).put("selection", selection)
                .put("assetId", asset.getString("id")).put("formatId", selection.getString("id")).put("settings", Store(context).settings())
            val file = engine.download(job, work) { _, _, bytes, _ -> if (bytes > 0 && firstByteMs < 0) firstByteMs = SystemClock.elapsedRealtime() - downloadStart }
            assertTrue(file.length() > 1000)
            val outputExt = if (mediaKind == "video") Store(context).settings().optString("videoFormat", "mp4") else selection.getString("ext")
            assertEquals(outputExt, file.extension)
            assertTrue(selection.has("mediaMetadata"))
            if (mediaKind == "video" && selection.optInt("height") > 0)
                assertEquals(selection.getInt("height"), selection.getJSONObject("mediaMetadata").getInt("height"))
            assertFalse(File(work, "resolved-info.json").exists())
            instrumentation.sendStatus(0, Bundle().apply { putString("speedResult", JSONObject().put("platform", analysis.getString("platform"))
                .put("previewMs", previewMs).put("analysisMs", analysisMs).put("warmPreviewMs", warmMs)
                .put("downloadFirstByteMs", firstByteMs).put("downloadMs", SystemClock.elapsedRealtime() - downloadStart)
                .put("resolvedInfo", asset.optString("resolvedInfo").isNotEmpty()).put("nativeHttp", selection.has("directUrl") || selection.has("directSources"))
                .put("provider", analysis.optString("analysisProvider", "yt-dlp"))
                .put("formats", formats.length()).put("bytes", file.length()).put("ext", file.extension).toString()) })
        } finally { engine.cancel(id); work.deleteRecursively() }
    }
}
