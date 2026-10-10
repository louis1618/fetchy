package com.luverse.fetchy

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.*
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EngineDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun engine() = Engine(context, CookieVault(context))
    private fun job(analysis: JSONObject, formatIndex: Int = 0): JSONObject {
        val asset = analysis.getJSONArray("assets").getJSONObject(0)
        val format = asset.getJSONArray("formats").getJSONObject(formatIndex)
        return JSONObject().put("id", UUID.randomUUID().toString()).put("sourceUrl", analysis.getString("sourceUrl"))
            .put("platform", analysis.getString("platform")).put("asset", asset).put("selection", format)
            .put("assetId", asset.getString("id")).put("formatId", format.getString("id"))
            .put("kind", format.getString("kind")).put("ext", format.getString("ext"))
            .put("settings", Store(context).settings())
    }
    @Test fun bundledEngineInitializes() {
        val info = engine().info()
        assertEquals(info.toString(), "ready", info.optString("status"))
        assertEquals("2026.08.19", info.getString("ytDlp"))
        assertTrue(info.getBoolean("quickjs")); assertTrue(info.getBoolean("ffmpeg"))
    }
    @Test fun directMediaDownloadAndPublish() {
        val engine = engine()
        val urls = listOf("https://www.w3schools.com/html/mov_bbb.mp4", "https://www.w3schools.com/html/horse.mp3", "https://www.w3schools.com/w3css/img_lights.jpg", "https://www.gstatic.com/webp/gallery/1.webp")
        for (url in urls) {
            val analysis = engine.analyze(url)
            val job = job(analysis)
            val work = File(context.cacheDir, "engine-test/${job.getString("id")}").apply { mkdirs() }
            val file = engine.download(job, work) { _, _, _, _ -> }
            assertTrue(file.length() > 100)
            assertFalse(file.inputStream().bufferedReader().use { it.readText().take(30) }.startsWith("<!DOCTYPE"))
            val store = Store(context)
            if (store.settings().optString("rootUri").isNotEmpty()) {
                store.put("jobs", job.put("rootUri", store.settings().getString("rootUri")).put("state", "saving"))
                val storage = Storage(context, store)
                val folder = storage.folderFor("other")
                val saved = storage.publish(job.getString("id"), file, "Fetchy-test.${file.extension}", engine.mime(file, job.getString("kind")), folder?.getString("id"))
                val savedSize = context.contentResolver.openAssetFileDescriptor(android.net.Uri.parse(saved.getString("uri")), "r")!!.use { it.length }
                assertEquals(file.length(), savedSize)
                storage.deleteFile(saved.getString("id")); store.delete("jobs", job.getString("id"))
            }
            work.deleteRecursively()
        }
    }
    @Test fun youtubeAnalysisAndSmallVideo() {
        val engine = engine()
        val analysis = engine.analyze("https://www.youtube.com/watch?v=jNQXAC9IVRw")
        val asset = analysis.getJSONArray("assets").getJSONObject(0)
        val formats = asset.getJSONArray("formats")
        val index = (0 until formats.length()).filter { formats.getJSONObject(it).getString("kind") == "video" }
            .minBy { formats.getJSONObject(it).optInt("height", 99999).takeIf { n -> n > 0 } ?: 99999 }
        val job = job(analysis, index)
        val work = File(context.cacheDir, "engine-youtube/${job.getString("id")}").apply { mkdirs() }
        try { val output = engine.download(job, work) { _, _, _, _ -> }; assertTrue(output.length() > 1000) }
        finally { work.deleteRecursively() }
    }
    @Test fun pausePreservesPartialAndResumesAfterEngineRestart() {
        val first = engine()
        val analysis = first.analyze("https://www.w3schools.com/html/mov_bbb.mp4")
        val job = job(analysis)
        val work = File(context.filesDir, "test-resume/${job.getString("id")}").apply { mkdirs() }
        var canceled = false
        try {
            try {
                first.download(job, work) { _, _, bytes, _ ->
                    if (bytes > 65536 && !canceled) { canceled = true; first.cancel(job.getString("id")) }
                }
                fail("Cancellation must interrupt the download")
            } catch (_: java.io.IOException) { assertTrue(canceled) }
            assertTrue(work.listFiles()!!.any { it.name.endsWith(".part") && it.length() > 0 })
            val store = Store(context)
            store.put("jobs", job.put("state", "paused"))
            assertEquals("paused", Store(context).get("jobs", job.getString("id"))!!.getString("state"))
            val result = engine().download(job, work) { _, _, _, _ -> }
            assertTrue(result.length() > 700000)
            store.delete("jobs", job.getString("id"))
        } finally { work.deleteRecursively() }
    }
    @Test fun reservedCancellationStopsBeforeAnyNetwork() {
        val engine = engine()
        val analysis = engine.analyze("https://www.w3schools.com/html/mov_bbb.mp4")
        val job = job(analysis)
        val work = File(context.cacheDir, "reserved-cancel-test").apply { mkdirs() }
        try {
            engine.reserve(job.getString("id")); engine.cancel(job.getString("id"))
            try { engine.download(job, work) { _, _, _, _ -> }; fail("Reserved cancellation must stop") }
            catch (e: java.io.IOException) { assertTrue(e.message.orEmpty().contains("canceled")) }
            assertTrue(work.listFiles().isNullOrEmpty())
        } finally { work.deleteRecursively() }
    }
    @Test fun directVideoExtractsSelectedAudio() {
        val engine = engine()
        val analysis = engine.analyze("https://www.w3schools.com/html/mov_bbb.mp4")
        val fs = analysis.getJSONArray("assets").getJSONObject(0).getJSONArray("formats")
        for (ext in listOf("m4a", "mp3", "opus")) {
            val index = (0 until fs.length()).first { fs.getJSONObject(it).getString("kind") == "audio" && fs.getJSONObject(it).getString("ext") == ext }
            val job = job(analysis, index)
            val work = File(context.cacheDir, "extract-audio/${job.getString("id")}").apply { mkdirs() }
            try { val output = engine.download(job, work) { _, _, _, _ -> }; assertEquals(ext, output.extension); assertTrue(output.length() > 1000) }
            finally { work.deleteRecursively() }
        }
    }
    @Test fun cookieEncryptionAndDomainFiltering() {
        val vault = CookieVault(context)
        val input = File(context.cacheDir, "test-cookies.txt")
        input.writeText("# Netscape HTTP Cookie File\n.instagram.com\tTRUE\t/\tTRUE\t0\tfetchy_test\tfixture_only\n.evilinstagram.com\tTRUE\t/\tTRUE\t0\tbad\tnever_export\n")
        try {
            vault.import("instagram", android.net.Uri.fromFile(input))
            val exported = vault.temporary("instagram", "test-export")!!
            val text = exported.readText(); assertTrue(text.contains("fixture_only")); assertFalse(text.contains("never_export"))
            exported.delete()
            val persisted = File(context.noBackupFilesDir, "fetchy-cookies").walkTopDown().filter { it.isFile }.toList()
            persisted.forEach { assertFalse(it.readBytes().toString(Charsets.ISO_8859_1).contains("fixture_only")) }
        } finally { input.delete(); vault.delete("instagram") }
    }
    @Test fun platformFolderMappingSurvivesRenameAndDeletionRetargetsRetries() {
        val store = Store(context)
        org.junit.Assume.assumeTrue(store.settings().optString("rootUri").isNotEmpty())
        val storage = Storage(context, store)
        val root = store.settings().getString("rootUri")
        val folder = storage.folderFor("youtube")!!
        val folderId = folder.getString("id")
        val fixtureIds = listOf("paused", "failed", "canceled").map { "platform-delete-fixture-$it" }
        try {
            storage.renameFolder(folderId, "YouTube-rename-test")
            assertEquals(folderId, storage.folderFor("youtube")!!.getString("id"))
            for (state in listOf("paused", "failed", "canceled")) {
                store.put("jobs", JSONObject().put("id", "platform-delete-fixture-$state").put("state", state).put("folderId", folderId).put("rootUri", root))
            }
            storage.deleteFolder(folderId)
            assertNull(storage.folderFor("youtube"))
            fixtureIds.forEach { assertEquals("", store.get("jobs", it)!!.getString("folderId")); assertEquals(root, store.get("jobs", it)!!.getString("rootUri")) }
            storage.chooseRoot(android.net.Uri.parse(root))
            assertNull("Deleted mapping must not automatically recreate", storage.folderFor("youtube"))
        } finally {
            fixtureIds.forEach { store.delete("jobs", it) }
            store.setting("disabled:$root:YouTube", false)
            storage.folderFor("youtube")?.let { if (it.getString("name") != "YouTube") storage.renameFolder(it.getString("id"), "YouTube") }
        }
    }
    @Test fun folderCrudPreservesFiles() {
        val store = Store(context)
        org.junit.Assume.assumeTrue("Select a SAF root in the app before running this case", store.settings().optString("rootUri").isNotEmpty())
        val storage = Storage(context, store)
        val f = storage.createFolder("Fetchy-test-${System.currentTimeMillis()}")
        val src = File(context.cacheDir, "folder-test.txt").apply { writeText("Fetchy durable folder fixture") }
        val id = UUID.randomUUID().toString()
        try {
            val saved = storage.publish(id, src, "fixture.txt", "text/plain", f.getString("id"))
            val nestedName = "untracked-${System.currentTimeMillis()}"
            val nested = android.provider.DocumentsContract.createDocument(context.contentResolver, android.net.Uri.parse(f.getString("uri")), android.provider.DocumentsContract.Document.MIME_TYPE_DIR, nestedName)!!
            val unknown = android.provider.DocumentsContract.createDocument(context.contentResolver, nested, "text/plain", "unknown.txt")!!
            context.contentResolver.openOutputStream(unknown)!!.bufferedWriter().use { it.write("Untracked data must survive") }
            storage.renameFolder(f.getString("id"), f.getString("name") + "-renamed")
            storage.deleteFolder(f.getString("id"))
            assertNull(store.get("folders", f.getString("id")))
            val moved = store.get("files", saved.getString("id"))!!
            assertEquals("Fetchy durable folder fixture", context.contentResolver.openInputStream(android.net.Uri.parse(moved.getString("uri")))!!.bufferedReader().use { it.readText() })
            val root = android.net.Uri.parse(store.settings().getString("rootUri"))
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(root, android.provider.DocumentsContract.getTreeDocumentId(root))
            val nestedDoc = context.contentResolver.query(children, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use { cursor ->
                var found: android.net.Uri? = null
                while (cursor.moveToNext()) if (cursor.getString(1) == nestedName) found = android.provider.DocumentsContract.buildDocumentUriUsingTree(root, cursor.getString(0))
                found
            }
            assertNotNull("Unknown nested folder must move to root", nestedDoc)
            val nestedChildren = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(root, android.provider.DocumentsContract.getDocumentId(nestedDoc!!))
            val unknownDoc = context.contentResolver.query(nestedChildren, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)!!.use { cursor -> assertTrue(cursor.moveToFirst()); android.provider.DocumentsContract.buildDocumentUriUsingTree(root, cursor.getString(0)) }
            assertEquals("Untracked data must survive", context.contentResolver.openInputStream(unknownDoc)!!.bufferedReader().use { it.readText() })
            assertTrue(android.provider.DocumentsContract.deleteDocument(context.contentResolver, nestedDoc))
            storage.renameFile(id, "fixture-renamed.txt")
            storage.deleteFile(id)
        } finally { src.delete() }
    }
}
