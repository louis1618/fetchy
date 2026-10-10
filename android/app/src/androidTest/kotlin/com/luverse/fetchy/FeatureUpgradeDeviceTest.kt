package com.luverse.fetchy

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract as DC
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FeatureUpgradeDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val store get() = Store(context)
    private fun engine() = Engine(context, CookieVault(context)).also { assertEquals("ready", it.info().optString("status")) }
    private fun work() = File(context.cacheDir, "feature-fixture-${UUID.randomUUID()}").apply { mkdirs() }
    private fun native(engine: Engine, binary: String, args: List<String>): String {
        val result = engine.native(binary, args, Engine.Active(), 30000); assertEquals(result.second, 0, result.first); return result.second
    }

    @Before fun removeOnlyOrphanedJournalsFromThisTestFixture() {
        for (op in store.list("folderOps")) {
            if (op.optString("type") != "move") continue
            val source = Uri.parse(op.optString("source")); val target = Uri.parse(op.optString("target"))
            if (source.authority != FetchyDocumentsProvider.AUTHORITY || target.authority != source.authority) continue
            val sourceId = DC.getDocumentId(source); val targetId = DC.getDocumentId(target)
            val match = Regex("^fetchy/migration-source-([0-9a-f-]{36})(?:/.*)?$").matchEntire(sourceId) ?: continue
            val tag = match.groupValues[1]
            if (!targetId.startsWith("fetchy/migration-target-$tag/") || File(FetchyDocumentsProvider.rootDirectory(context), "migration-source-$tag").exists()) continue
            store.delete("folderOps", op.getString("id"))
        }
    }

    @Test fun loginEvidenceNeverTreatsPublicSuccessAsAuthentication() {
        val samples = mapOf("youtube" to "{\"LOGGED_IN\":true}", "instagram" to "{\"user\":{\"pk\":\"123\"}}", "x" to "{\"id_str\":\"123\"}",
            "tiktok" to "{\"data\":{\"user_id\":\"123\"}}", "reddit" to "{\"id\":\"abc\",\"name\":\"fixture\"}",
            "twitch" to "{\"user_id\":\"123\"}", "bilibili" to "{\"code\":0,\"data\":{\"isLogin\":true}}", "weibo" to "{\"data\":{\"login\":true}}")
        for ((platform, response) in samples) {
            assertEquals(platform, "valid", LoginChecks.classify(platform, 200, response))
            assertEquals(platform, "unknown", LoginChecks.classify(platform, 200, "<html>Public media</html>"))
            assertEquals(platform, "invalid", LoginChecks.classify(platform, 401, ""))
            assertEquals(platform, "unknown", LoginChecks.classify(platform, 403, ""))
        }
        assertEquals("invalid", LoginChecks.classify("youtube", 200, "{\"LOGGED_IN\":false}"))
        assertEquals("unknown", LoginChecks.classify("threads", 200, samples.getValue("instagram")))
        val root = File(context.cacheDir, "auth-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : android.content.ContextWrapper(context) { override fun getApplicationContext() = this; override fun getNoBackupFilesDir() = root }
        val vault = CookieVault(isolated); val source = File(root, "cookies.txt")
        val previous = store.get("loginChecks", "youtube")
        try {
            source.writeText("# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t1\tSID\tfixture-secret\n")
            vault.import("youtube", Uri.fromFile(source))
            val checks = LoginChecks(vault, store, engine())
            assertEquals("expired", checks.check("youtube").getString("state"))
            assertFalse(checks.states().toString().contains("fixture-secret"))
            source.writeText("# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t0\tSID\tfixture-new\n")
            vault.import("youtube", Uri.fromFile(source))
            assertEquals("unchecked", checks.states().first { it.getString("platform") == "youtube" }.getString("state"))
        } finally { if (previous == null) store.delete("loginChecks", "youtube") else store.put("loginChecks", previous); root.deleteRecursively() }
    }

    @Test fun diagnosticExportExcludesCookiesUrlsTitlesAndHeaders() {
        val id = "diagnostic-fixture-${UUID.randomUUID()}"
        try {
            store.put("jobs", JSONObject().put("id", id).put("state", "failed").put("kind", "video").put("sourceUrl", "https://private.example/SECRET")
                .put("title", "PRIVATE_TITLE").put("asset", JSONObject().put("cookies", "SECRET_COOKIE")).put("error", "login required Authorization: SECRET_HEADER"))
            val report = Diagnostics(context, store).report(mapOf("status" to "ready", "ytDlp" to "2026.08.19", "cookie" to "ENGINE_SECRET"), id)
            val text = report.toString()
            for (value in listOf("SECRET", "PRIVATE_TITLE", "https://", "sourceUrl", "asset", "Authorization")) assertFalse(value, text.contains(value))
            assertEquals("login_required", report.getJSONArray("jobs").getJSONObject(0).getString("errorCode"))
            val directory = File(context.cacheDir, "diagnostics"); val before = directory.listFiles().orEmpty().map { it.name }.toSet()
            try {
                val automation = instrumentation.uiAutomation
                Diagnostics(context, store).share(mapOf("status" to "ready"), id)
                val shared = directory.listFiles().orEmpty().single { it.name !in before }
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.luverse.fetchy.share", shared)
                val exported = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                assertEquals(shared.readText(), exported); assertFalse(exported.contains("SECRET")); assertFalse(exported.contains("PRIVATE_TITLE"))
                automation.waitForIdle(500, 10000)
                assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            } finally { directory.listFiles().orEmpty().filter { it.name !in before }.forEach { it.delete() } }
        } finally { store.delete("jobs", id) }
    }

    @Test fun ffmpegWritesTagsAndCoverForAllThreeAudioFormats() {
        val engine = engine(); val dir = work()
        try {
            val png = File(dir, "cover.png"); val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val extras = MediaExtras(engine); val cover = extras.jpegCover(png, File(dir, "cover.jpg"))
            for ((ext, codec) in listOf("mp3" to "libmp3lame", "m4a" to "aac", "opus" to "libopus")) {
                val source = File(dir, "source.$ext")
                native(engine, "libffmpeg.so", listOf("-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=2", "-c:a", codec, source.path))
                val result = extras.writeAudio(source, JSONObject().put("title", "Fetchy 테스트").put("artist", "Fixture artist").put("album", "Fixture album"), cover, Engine.Active())
                val probe = JSONObject(native(engine, "libffprobe.so", listOf("-v", "error", "-show_streams", "-show_format", "-of", "json", result.path)))
                val streams = probe.getJSONArray("streams"); val rows = (0 until streams.length()).map { streams.getJSONObject(it) }
                val tags = probe.getJSONObject("format").optJSONObject("tags") ?: rows.first { it.optString("codec_type") == "audio" }.getJSONObject("tags")
                assertEquals(ext, "Fetchy 테스트", tags.optString("title")); assertEquals(ext, "Fixture artist", tags.optString("artist")); assertEquals(ext, "Fixture album", tags.optString("album"))
                assertTrue(ext, rows.any { it.optString("codec_type") == "video" && it.optJSONObject("disposition")?.optInt("attached_pic") == 1 })
                native(engine, "libffmpeg.so", listOf("-v", "error", "-i", result.path, "-map", "0:a:0", "-f", "null", "-"))
            }
            val job = JSONObject().put("title", "Must not be added").put("asset", JSONObject().put("thumbnail", "https://raw.githubusercontent.com/mdn/learning-area/main/html/multimedia-and-embedding/video-and-audio-content/poster.png"))
                .put("settings", JSONObject().put("audioMetadata", false).put("audioCover", true))
            val coverOnly = extras.audio(File(dir, "source.mp3"), job, Engine.Active()) { _, _, _, _ -> }
            assertFalse(job.toString(), job.has("warnings"))
            val probe = JSONObject(native(engine, "libffprobe.so", listOf("-v", "error", "-show_streams", "-show_format", "-of", "json", coverOnly.path)))
            assertTrue(probe.getJSONObject("format").getJSONObject("tags").optString("title").isBlank())
            assertTrue((0 until probe.getJSONArray("streams").length()).any { probe.getJSONArray("streams").getJSONObject(it).optJSONObject("disposition")?.optInt("attached_pic") == 1 })
        } finally { dir.deleteRecursively() }
    }

    @Test fun subtitlesConvertAndRejectHtmlResponses() {
        val engine = engine(); val dir = work()
        try {
            val source = File(dir, "source.vtt").apply { writeText("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n한국어 자막\n") }
            val output = File(dir, "subtitle.srt")
            MediaExtras(engine).convertSubtitle(source, output, Engine.Active())
            assertTrue(output.readText().contains("00:00:00,000 --> 00:00:01,000")); assertTrue(output.readText().contains("한국어 자막"))
            source.writeText("<html>Sign in</html>")
            try { MediaExtras(engine).convertSubtitle(source, output, Engine.Active()); fail("HTML cannot be a caption") } catch (_: IllegalArgumentException) { }
        } finally { dir.deleteRecursively() }
    }

    @Test fun captionMetadataStaysPrivateAndSelectedSidecarSaves() {
        val engine = engine(); val storage = Storage(context, store); val dir = work(); val id = "caption-fixture-${UUID.randomUUID()}"
        try {
            val raw = JSONObject().put("id", "caption").put("title", "fixture").put("formats", JSONArray().put(JSONObject().put("format_id", "http").put("height", 144).put("vcodec", "h264").put("acodec", "aac").put("ext", "mp4")))
                .put("subtitles", JSONObject().put("en", JSONArray().put(JSONObject().put("ext", "vtt").put("name", "English").put("url", "https://raw.githubusercontent.com/mdn/learning-area/main/html/multimedia-and-embedding/tasks/audio-video/subtitles_en.vtt"))))
            val asset = engine.parseExtractedMedia(URI("https://example.com/caption"), "Web page", raw).getJSONArray("assets").getJSONObject(0)
            assertFalse(asset.toString().contains("raw.githubusercontent")); assertEquals("en", asset.getJSONArray("subtitles").getJSONObject(0).getString("language"))
            val job = JSONObject().put("sourceUrl", "https://example.com/caption").put("asset", asset).put("subtitle", JSONObject().put("language", "en").put("automatic", false).put("format", "srt"))
            MediaExtras(engine).subtitles(job, dir, Engine.Active()) { _, _, _, _ -> }
            assertFalse(job.toString(), job.has("warnings")); val sidecar = job.getJSONArray("sidecars").getJSONObject(0)
            val source = File(sidecar.getString("path")); assertTrue(source.length() > 20)
            val saved = storage.publish(id, source, "$id.srt", "application/x-subrip", null)
            assertEquals(source.readText(), context.contentResolver.openInputStream(Uri.parse(saved.getString("uri")))!!.bufferedReader().use { it.readText() })
        } finally { if (store.get("files", id) != null) storage.deleteFile(id); dir.deleteRecursively() }
    }

    @Test fun localAudioAndVideoPlayPauseSeekAndRelease() {
        instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
        instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        val engine = engine(); val storage = Storage(context, store); val dir = work()
        try {
            for (video in listOf(false, true)) {
                val id = "player-fixture-${UUID.randomUUID()}"; val source = File(dir, if (video) "clip.mp4" else "clip.m4a")
                val inputs = if (video) listOf("-f", "lavfi", "-i", "color=c=blue:s=320x180:d=8", "-f", "lavfi", "-i", "sine=frequency=440:duration=8", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac") else listOf("-f", "lavfi", "-i", "sine=frequency=440:duration=8", "-c:a", "aac")
                native(engine, "libffmpeg.so", listOf("-v", "error", "-y") + inputs + listOf(source.path))
                storage.publish(id, source, "$id.${source.extension}", if (video) "video/mp4" else "audio/mp4", null)
                val activity = instrumentation.startActivitySync(Intent(context, PlaybackActivity::class.java).putExtra("id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackActivity
                try {
                    val deadline = System.currentTimeMillis() + 10000
                    while (activity.playbackState() != "playing" && System.currentTimeMillis() < deadline) Thread.sleep(100)
                    assertEquals("playing", activity.playbackState())
                    if (video) {
                        val frameDeadline = System.currentTimeMillis() + 10000
                        while (!activity.hasRenderedVideo() && System.currentTimeMillis() < frameDeadline) Thread.sleep(50)
                        assertTrue("Video must display an actual decoded frame", activity.hasRenderedVideo())
                    }
                    instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                        try { File(context.cacheDir, "feature-playback-$video.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { screenshot.recycle() }
                    }
                    instrumentation.runOnMainSync { instrumentation.callActivityOnPause(activity) }; assertEquals("paused", activity.playbackState())
                    instrumentation.runOnMainSync { instrumentation.callActivityOnResume(activity) }; assertEquals("playing", activity.playbackState())
                    var touchX = 0f; var touchY = 0f
                    instrumentation.runOnMainSync {
                        val root = activity.window.decorView
                        fun seek(view: android.view.View): android.widget.SeekBar? = when (view) {
                            is android.widget.SeekBar -> view
                            is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { seek(view.getChildAt(it)) }
                            else -> null
                        }
                        val bar = seek(root)!!; val point = IntArray(2); bar.getLocationOnScreen(point)
                        touchX = point[0] + bar.width * .65f; touchY = point[1] + bar.height / 2f
                    }
                    val now = android.os.SystemClock.uptimeMillis()
                    for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                        val event = android.view.MotionEvent.obtain(now, now + 50, action, touchX, touchY, 0)
                        try { event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN; assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                    }
                    Thread.sleep(300)
                    instrumentation.runOnMainSync { assertTrue("Seek position ${activity.playbackPosition()}, video=$video, touch=$touchX/$touchY", activity.playbackPosition() in 4000..7000) }
                } finally { instrumentation.runOnMainSync { activity.finish() }; Thread.sleep(200); storage.deleteFile(id) }
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun storageMigrationPreservesFilesMappingsCollisionsAndResumes() {
        assumeTrue(store.list("jobs").all { it.optString("state") in FetchyRuntime.terminal })
        val storage = Storage(context, store); val savedSettings = store.settings(); val before = store.list("folders").map { it.getString("id") }.toSet()
        val root = DC.buildDocumentUriUsingTree(FetchyDocumentsProvider.treeUri(), "fetchy")
        val tag = UUID.randomUUID().toString()
        val sourceContainer = DC.createDocument(context.contentResolver, root, DC.Document.MIME_TYPE_DIR, "migration-source-$tag")!!
        val destContainer = DC.createDocument(context.contentResolver, root, DC.Document.MIME_TYPE_DIR, "migration-target-$tag")!!
        val sourceTree = DC.buildTreeDocumentUri(FetchyDocumentsProvider.AUTHORITY, DC.getDocumentId(sourceContainer))
        val destTree = DC.buildTreeDocumentUri(FetchyDocumentsProvider.AUTHORITY, DC.getDocumentId(destContainer))
        val fileId = "migration-fixture-$tag"; val missingId = "$fileId-missing"; val pausedId = "$fileId-paused"; val dir = work()
        try {
            storage.chooseRoot(sourceTree)
            val platform = storage.folderFor("YouTube")!!
            val child = storage.createFolder("child", parentId = platform.getString("id"))
            val source = File(dir, "original.txt").apply { writeText("preserved-$tag") }
            val file = storage.publish(fileId, source, "original.txt", "text/plain", child.getString("id")); storage.favorite("files", fileId, true)
            store.put("files", JSONObject(file.toString()).put("id", missingId).put("name", "missing.txt").put("uri", DC.buildDocumentUriUsingTree(sourceTree, "${DC.getDocumentId(sourceContainer)}/YouTube/child/missing.txt").toString()))
            store.put("jobs", JSONObject().put("id", pausedId).put("state", "queued").put("folderId", child.getString("id")).put("rootUri", sourceTree.toString()))
            try { storage.prepareMigration(destTree); fail("Active job must block migration") } catch (_: IllegalStateException) { }
            store.put("jobs", store.get("jobs", pausedId)!!.put("state", "paused"))
            val conflict = DC.createDocument(context.contentResolver, destContainer, DC.Document.MIME_TYPE_DIR, "YouTube")!!
            val untouched = DC.createDocument(context.contentResolver, conflict, "text/plain", "untouched.txt")!!
            context.contentResolver.openOutputStream(untouched)!!.use { it.write("unrelated".toByteArray()) }
            storage.prepareMigration(destTree)
            try { storage.migrate { if ((storage.migration()?.optInt("done") ?: 0) == 1) throw java.io.IOException("fixture interruption") }; fail("Fixture interruption") } catch (_: java.io.IOException) { }
            assertNotNull(storage.migration()); Storage(context, Store(context)).recover(); Storage(context, Store(context)).migrate()
            assertNull(storage.migration()); assertEquals(destTree.toString(), store.settings().getString("rootUri"))
            val migrated = store.get("files", fileId)!!; assertTrue(migrated.getBoolean("favorite")); assertEquals(destTree.toString(), migrated.getString("rootUri"))
            assertEquals("preserved-$tag", context.contentResolver.openInputStream(Uri.parse(migrated.getString("uri")))!!.bufferedReader().use { it.readText() })
            assertEquals(platform.getString("id"), storage.folderFor("YouTube")!!.getString("id")); assertEquals("YouTube (1)", store.get("folders", platform.getString("id"))!!.getString("name"))
            assertTrue(store.get("files", missingId)!!.optBoolean("missing")); assertEquals(sourceTree.toString(), store.get("files", missingId)!!.getString("rootUri"))
            assertEquals(destTree.toString(), store.get("jobs", pausedId)!!.getString("rootUri"))
            assertEquals("unrelated", context.contentResolver.openInputStream(untouched)!!.bufferedReader().use { it.readText() })
            val summary = store.settings().getJSONObject("lastMigration"); assertEquals("completed", summary.getString("state")); assertEquals(1, summary.getInt("missing"))
            instrumentation.sendStatus(0, Bundle().apply { putString("migrationFixture", "preserved, collision renamed, interrupted/restarted, mapping retained, missing record retained") })
        } finally {
            try {
                store.delete("jobs", pausedId); store.delete("folderOps", "storage-migration")
                store.delete("files", missingId)
                if (store.get("files", fileId) != null) storage.deleteFile(fileId)
                store.list("folders").filter { it.getString("id") !in before }.reversed().forEach { storage.deleteFolder(it.getString("id")) }
                // Containers and the unrelated fixture were created by this test only.
                val provider = FetchyDocumentsProvider.rootDirectory(context)
                for (name in listOf("migration-source-$tag", "migration-target-$tag")) File(provider, name).deleteRecursively()
            } finally { store.put("settings", savedSettings.put("id", "settings")); dir.deleteRecursively() }
        }
    }
    @Test fun pendingSidecarSurvivesCleanupAndRecoversMetadata() {
        val runtime = FetchyRuntime.get(context); val storage = Storage(context, store)
        val id = "sidecar-fixture-${UUID.randomUUID()}"
        val dir = File(context.filesDir, "work/$id").apply { mkdirs() }
        val source = File(dir, "caption.srt").apply { writeText("1\n00:00:00,000 --> 00:00:01,000\nFixture\n") }
        storage.ensureRoot(); val root = Uri.parse(store.settings().getString("rootUri")); val parent = DC.buildDocumentUriUsingTree(root, DC.getTreeDocumentId(root))
        val op = JSONObject().put("id", "publish:$id").put("type", "publish").put("fileId", id)
            .put("source", source.path).put("parent", parent.toString()).put("name", "$id.srt").put("mime", "application/x-subrip")
            .put("folderId", JSONObject.NULL).put("rootUri", root.toString()).put("replace", "").put("tempName", ".fetchy-$id")
            .put("metadata", JSONObject().put("subtitleFor", "fixture-video").put("language", "en").put("automatic", false))
        try {
            store.put("folderOps", op); store.put("jobs", JSONObject().put("id", id).put("state", "completed")); runtime.command(id, "deleteRecord"); assertTrue(source.isFile)
            runtime.cleanup(); assertTrue(source.isFile)
            storage.recover(); val record = store.get("files", id)!!
            assertEquals("fixture-video", record.getString("subtitleFor")); assertEquals("en", record.getString("language")); assertFalse(record.getBoolean("automatic"))
            assertNull(store.get("folderOps", "publish:$id")); runtime.cleanup(); assertFalse(dir.exists())
        } finally { store.delete("folderOps", "publish:$id"); if (store.get("files", id) != null) storage.deleteFile(id); dir.deleteRecursively() }
    }

    @Test fun instagramAutomaticQualityDownloadsWithoutSecondPreviewAnalysis() {
        val url = "https://www.instagram.com/reel/DaupWjVARQ_/"
        val engine = engine(); val storage = Storage(context, store); val dir = work()
        val started = android.os.SystemClock.elapsedRealtime()
        val preview = engine.preview(url); val previewMs = android.os.SystemClock.elapsedRealtime() - started
        val analysis = engine.analyze(url); val analysisMs = android.os.SystemClock.elapsedRealtime() - started - previewMs
        val asset = analysis.getJSONArray("assets").getJSONObject(0)
        val formats = asset.getJSONArray("formats")
        val automatic = (0 until formats.length()).map { formats.getJSONObject(it) }.first { it.optBoolean("automatic") }
        val folder = storage.createFolder("automatic-quality-${UUID.randomUUID()}")
        try {
            assertNotNull(preview)
            for (quality in listOf(1080, 0)) {
                val id = "automatic-fixture-${UUID.randomUUID()}"; val work = File(dir, "$quality").apply { mkdirs() }
                val job = JSONObject().put("id", id).put("sourceUrl", analysis.getString("sourceUrl")).put("asset", JSONObject(asset.toString()))
                    .put("selection", JSONObject(automatic.toString())).put("assetId", asset.getString("id")).put("formatId", automatic.getString("id"))
                    .put("kind", "video").put("platform", "Instagram").put("settings", store.settings().put("quality", quality)).put("title", analysis.getString("title"))
                val downloadAt = android.os.SystemClock.elapsedRealtime()
                try {
                    val output = engine.download(job, work) { _, _, bytes, _ -> if (bytes > 33554432 || android.os.SystemClock.elapsedRealtime() - downloadAt > 120000) engine.cancel(id) }
                    val metadata = job.getJSONObject("selection").getJSONObject("mediaMetadata"); assertTrue(metadata.getInt("height") > 0)
                    store.put("jobs", job.put("state", "saving").put("folderId", folder.getString("id")).put("mediaMetadata", metadata))
                    val record = storage.publish(id, output, "$id.${output.extension}", engine.mime(output, "video"), folder.getString("id"))
                    assertEquals(output.length(), record.getLong("size"))
                    instrumentation.sendStatus(0, Bundle().apply { putString("automaticQuality", JSONObject().put("quality", quality).put("previewMs", previewMs)
                        .put("analysisMs", analysisMs).put("downloadMs", android.os.SystemClock.elapsedRealtime() - downloadAt).put("height", metadata.getInt("height"))
                        .put("warnings", job.optJSONArray("warnings") ?: JSONArray()).toString()) })
                } finally { engine.cancel(id); store.delete("jobs", id); if (store.get("files", id) != null) storage.deleteFile(id) }
            }
        } finally { storage.deleteFolder(folder.getString("id")); dir.deleteRecursively() }
    }

    @Test fun verifiedFolderCopyResumesPartialSourceDeletion() {
        val storage = Storage(context, store); val tag = UUID.randomUUID().toString()
        val source = storage.createFolder("copy-source-$tag"); val destination = storage.createFolder("copy-destination-$tag")
        val sourceUri = Uri.parse(source.getString("uri")); val parentUri = Uri.parse(destination.getString("uri"))
        val target = DC.createDocument(context.contentResolver, parentUri, DC.Document.MIME_TYPE_DIR, source.getString("name"))!!
        val original = mutableListOf<Uri>(); val fileIds = mutableListOf<String>(); val work = work()
        val sourceRoot = Uri.parse(source.getString("rootUri")); val sourceParent = DC.buildDocumentUriUsingTree(sourceRoot, DC.getTreeDocumentId(sourceRoot))
        val opId = "move:$sourceUri"
        try {
            for (i in 1..2) {
                val id = "partial-copy-$tag-$i"; fileIds.add(id)
                val file = File(work, "$i.txt").apply { writeText("verified-$i-$tag") }
                val record = storage.publish(id, file, "$i.txt", "text/plain", source.getString("id")); original.add(Uri.parse(record.getString("uri")))
                val copy = DC.createDocument(context.contentResolver, target, "text/plain", "$i.txt")!!
                context.contentResolver.openOutputStream(copy)!!.use { it.write(file.readBytes()) }
            }
            val hash = Storage::class.java.getDeclaredMethod("fingerprintHash", Uri::class.java).apply { isAccessible = true }.invoke(storage, target) as String
            store.put("folderOps", JSONObject().put("id", opId).put("type", "move").put("source", sourceUri.toString()).put("sourceParent", sourceParent.toString())
                .put("parent", parentUri.toString()).put("mime", DC.Document.MIME_TYPE_DIR).put("name", source.getString("name")).put("rootUri", source.getString("rootUri"))
                .put("folderId", destination.getString("id")).put("record", source).put("target", target.toString()).put("verified", true).put("finalized", true)
                .put("deletingSource", true).put("targetFingerprint", hash))
            assertTrue(DC.deleteDocument(context.contentResolver, original.first()))
            Storage(context, Store(context)).recover(); assertNull(store.get("folderOps", opId))
            assertEquals(destination.getString("id"), store.get("folders", source.getString("id"))!!.getString("parentId"))
            for ((index, id) in fileIds.withIndex()) assertEquals("verified-${index + 1}-$tag", context.contentResolver.openInputStream(Uri.parse(store.get("files", id)!!.getString("uri")))!!.bufferedReader().use { it.readText() })
        } finally {
            for (id in fileIds) if (store.get("files", id) != null) storage.deleteFile(id)
            if (store.get("folders", source.getString("id")) != null) storage.deleteFolder(source.getString("id"))
            storage.deleteFolder(destination.getString("id")); work.deleteRecursively()
        }
    }

}
