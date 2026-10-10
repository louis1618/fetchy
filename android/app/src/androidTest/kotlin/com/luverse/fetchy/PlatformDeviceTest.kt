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
class PlatformDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun originalPhotosAndMuxedAudioUseTheRealParser() {
        val engine = Engine(context, CookieVault(context))
        val resolved = mutableListOf<String>()
        try {
            for ((platform, url) in mapOf("TikTok" to "https://p16.tiktokcdn.com/original.jpg", "Pinterest" to "https://i.pinimg.com/originals/a.jpg", "Reddit" to "https://i.redd.it/a.jpg", "Bluesky" to "https://pds.bsky.social/xrpc/com.atproto.sync.getBlob?did=did:plc:fixture&cid=fixture")) {
                val root = JSONObject().put("id", "fixture-photo").put("fetchy_original_image", true)
                    .put("formats", JSONArray().put(JSONObject().put("format_id", "fetchy-image").put("url", url)
                        .put("ext", "jpg").put("vcodec", "none").put("acodec", "none").put("width", 1080).put("height", 1920)))
                val asset = engine.parseExtractedMedia(URI("https://example.com/post/fixture"), platform, root).getJSONArray("assets").getJSONObject(0)
                resolved.add(asset.optString("resolvedInfo"))
                assertEquals("image", asset.getString("kind"))
                assertEquals(1, asset.getJSONArray("formats").length())
                assertEquals(url, asset.getJSONArray("formats").getJSONObject(0).getString("directUrl"))
                root.put("fetchy_original_image", false)
                try { engine.parseExtractedMedia(URI("https://example.com/post/fixture"), platform, root); fail("A poster is not downloadable") }
                catch (_: java.io.IOException) { }
            }
            val video = JSONObject().put("id", "muxed-fixture").put("formats", JSONArray().put(JSONObject()
                .put("format_id", "original").put("ext", "mp4").put("url", "https://www.w3schools.com/html/mov_bbb.mp4")
                .put("protocol", "https").put("vcodec", "h264").put("acodec", "aac")))
            val asset = engine.parseExtractedMedia(URI("https://example.com/post/fixture"), "Facebook", video).getJSONArray("assets").getJSONObject(0)
            resolved.add(asset.optString("resolvedInfo"))
            val formats = asset.getJSONArray("formats")
            assertEquals(4, formats.length())
            assertEquals("video", formats.getJSONObject(0).getString("kind"))
            for (i in 1..3) { assertEquals("audio", formats.getJSONObject(i).getString("kind")); assertTrue(formats.getJSONObject(i).getBoolean("extractAudio")) }
            val stream = video.getJSONArray("formats").getJSONObject(0)
            stream.remove("vcodec"); stream.remove("acodec"); stream.put("height", 144).put("width", 256).put("abr", 64)
            val inferred = engine.parseExtractedMedia(URI("https://example.com/post/fixture"), "Naver TV", video).getJSONArray("assets").getJSONObject(0)
            resolved.add(inferred.optString("resolvedInfo"))
            assertEquals(4, inferred.getJSONArray("formats").length())
            assertEquals("video", inferred.getJSONArray("formats").getJSONObject(0).getString("kind"))
            stream.put("vcodec", "none").put("acodec", "none")
            try { engine.parseExtractedMedia(URI("https://example.com/post/fixture"), "Naver TV", video); fail("Explicit codec absence must never be inferred as media") }
            catch (_: java.io.IOException) { }
        } finally { resolved.filter { it.isNotBlank() }.forEach { File(context.noBackupFilesDir, "resolved-media/$it.json").delete() } }
    }

    @Test fun extendedCookiesStayEncryptedAndIsolated() {
        val work = File(context.cacheDir, "extended-cookie-test/${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getNoBackupFilesDir(): File = work
        }
        val vault = CookieVault(isolated)
        val engine = Engine(context, vault)
        val cookieFile = Engine::class.java.getDeclaredMethod("cookieFile", String::class.java).apply { isAccessible = true }
        try {
            for ((id, url) in mapOf("facebook" to "https://www.facebook.com/video.php?v=123", "reddit" to "https://www.reddit.com/comments/123", "twitch" to "https://www.twitch.tv/videos/123", "naver" to "https://chzzk.naver.com/video/123", "bilibili" to "https://www.bilibili.com/video/BVexample")) {
                val host = URI(url).host
                val source = File(work, "fixture.txt").apply { writeText("# Netscape HTTP Cookie File\n$host\tFALSE\t/\tTRUE\t0\tsession\tfixture-$id\nevil.example\tFALSE\t/\tTRUE\t0\tsession\tnever-send\n") }
                vault.import(id, android.net.Uri.fromFile(source))
                assertFalse(File(work, "fetchy-cookies/$id.enc").readBytes().toString(Charsets.ISO_8859_1).contains("fixture-$id"))
                val temporary = cookieFile.invoke(engine, url) as File
                try { val plain = temporary.readText(); assertTrue(plain.contains("fixture-$id")); assertFalse(plain.contains("never-send")) }
                finally { assertTrue(temporary.delete()) }
            }
            assertEquals(5, vault.list().size)
            assertNull(cookieFile.invoke(engine, "https://video.twimg.com/fixture.mp4"))
            assertTrue(File(work, "fetchy-cookie-temp").listFiles().orEmpty().isEmpty())
        } finally { work.deleteRecursively() }
    }

    @Test fun shippedPhotoExtensionsRunOnAndroidPython() {
        val engine = Engine(context, CookieVault(context))
        assertEquals("ready", engine.info().getString("status"))
        val script = File("/data/local/tmp/fetchy-post-photos-check.py")
        assertTrue("Push scripts/check_post_photos.py before this test", script.isFile)
        val work = File(context.cacheDir, "photo-python-check/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            for (name in listOf("fetchy_photos.py", "fetchy_instagram_story.py")) context.assets.open(name).use { input -> File(work, name).outputStream().use { output -> input.copyTo(output) } }
            val root = File(context.noBackupFilesDir, "youtubedl-android")
            val builder = ProcessBuilder(File(context.applicationInfo.nativeLibraryDir, "libpython.so").path, script.path,
                File(root, "yt-dlp/fetchy-modules").path, work.path).redirectErrorStream(true)
            builder.environment()["PYTHONHOME"] = File(root, "packages/python/usr").path
            builder.environment()["LD_LIBRARY_PATH"] = File(root, "packages/python/usr/lib").path
            builder.environment()["TMPDIR"] = context.cacheDir.path
            val process = builder.start()
            try {
                assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(output, 0, process.exitValue())
                assertTrue(output.contains("originalPhotos"))
                instrumentation.sendStatus(0, Bundle().apply { putString("photoCheck", output.takeLast(1500)) })
            } finally { process.destroy() }
        } finally { work.deleteRecursively() }
    }

    /** Opt-in actual download. Failures stay failures; no cookies or user records are removed. */
    @Test fun individualLinkDownloadsAndSaves() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("mediaUrl") ?: "https://streamable.com/dnd1"
        val kind = args.getString("mediaKind") ?: "video"
        val engine = Engine(context, CookieVault(context))
        val store = Store(context)
        val storage = Storage(context, store)
        val id = UUID.randomUUID().toString()
        val work = File(context.cacheDir, "platform-download/$id").apply { mkdirs() }
        val start = SystemClock.elapsedRealtime()
        try {
            val analysis = engine.analyze(url)
            val analyzedMs = SystemClock.elapsedRealtime() - start
            val assets = analysis.getJSONArray("assets")
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.first { item ->
                val fs = item.getJSONArray("formats"); (0 until fs.length()).any { fs.getJSONObject(it).optString("kind") == kind }
            }
            val fs = asset.getJSONArray("formats")
            val selected = (0 until fs.length()).map { fs.getJSONObject(it) }.filter { it.optString("kind") == kind }
                .minByOrNull { it.optInt("height").takeIf { h -> h > 0 } ?: 99999 }!!
            val job = JSONObject().put("id", id).put("sourceUrl", analysis.getString("sourceUrl")).put("asset", asset).put("selection", selected)
                .put("assetId", asset.getString("id")).put("formatId", selected.getString("id")).put("kind", kind)
                .put("settings", store.settings()).put("rootUri", store.settings().optString("rootUri"))
            val file = engine.download(job, work) { _, _, bytes, _ -> if (bytes > 32 * 1024 * 1024 || SystemClock.elapsedRealtime() - start > 90000) engine.cancel(id) }
            assertTrue(file.length() > 100)
            assertTrue(selected.has("mediaMetadata"))
            var saved = false
            if (store.settings().optString("rootUri").isNotEmpty()) {
                // One disposable folder tests the same SAF publisher without changing platform mappings.
                val folder = storage.createFolder("Fetchy-platform-test-$id")
                try {
                    store.put("jobs", job.put("state", "saving").put("folderId", folder.getString("id")))
                    val record = storage.publish(id, file, "fixture.${file.extension}", engine.mime(file, kind), folder.getString("id"))
                    context.contentResolver.openInputStream(android.net.Uri.parse(record.getString("uri")))!!.use { assertEquals(file.length(), it.readBytes().size.toLong()) }
                    saved = true
                    store.delete("jobs", id)
                    storage.deleteFile(id)
                } finally { store.delete("jobs", id); if (store.get("files", id) != null) storage.deleteFile(id); storage.deleteFolder(folder.getString("id")) }
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("platformResult", JSONObject().put("platform", analysis.getString("platform"))
                .put("kind", kind).put("assets", assets.length()).put("analysisMs", analyzedMs).put("totalMs", SystemClock.elapsedRealtime() - start)
                .put("bytes", file.length()).put("ext", file.extension).put("safSaved", saved).toString()) })
        } finally { engine.cancel(id); work.deleteRecursively(); store.delete("jobs", id) }
    }

    @Test fun cleanOnlyInterruptedPlatformFixtures() {
        val store = Store(context)
        val storage = Storage(context, store)
        for (folder in store.list("folders").filter { it.optString("name").matches(Regex("Fetchy-platform-test-[0-9a-f-]{36}")) }) {
            val jobId = folder.getString("name").removePrefix("Fetchy-platform-test-")
            val files = store.list("files").filter { it.optString("folderId") == folder.getString("id") }
            require(files.all { it.getString("id") == jobId && it.getString("name").startsWith("fixture.") })
            store.delete("jobs", jobId)
            files.forEach { storage.deleteFile(it.getString("id")) }
            storage.deleteFolder(folder.getString("id"))
            File(context.cacheDir, "platform-download/$jobId").deleteRecursively()
        }
    }
}
