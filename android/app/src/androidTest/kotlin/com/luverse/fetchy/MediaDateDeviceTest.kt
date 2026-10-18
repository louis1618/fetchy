package com.luverse.fetchy

import android.net.Uri
import android.provider.DocumentsContract as DC
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MediaDateDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun modified(uri: Uri): Long = context.contentResolver.query(uri, arrayOf(DC.Document.COLUMN_LAST_MODIFIED), null, null, null)!!.use { assertTrue(it.moveToFirst()); it.getLong(0) }

    @Test fun selectedDatesReachUserStorageDefaultStorageAndGallery() {
        val store = Store(context); val storage = Storage(context, store); val engine = Engine(context, CookieVault(context))
        assertEquals("ready", engine.info().getString("status"))
        val tag = UUID.randomUUID().toString(); val work = File(context.cacheDir, "date-$tag").apply { mkdirs() }
        val source = File(work, "fixture.mp4")
        assertEquals(0, engine.native("libffmpeg.so", listOf("-v", "error", "-y", "-f", "lavfi", "-i", "color=c=blue:s=128x128:r=1", "-t", "2", "-c:v", "libx264", "-pix_fmt", "yuv420p", source.path), Engine.Active(), 30000).first)
        val folder = storage.createFolder("date-$tag")
        val ownRoot = DC.buildTreeDocumentUri(FetchyDocumentsProvider.AUTHORITY, "fetchy")
        val ownFolder = DC.createDocument(context.contentResolver, DC.buildDocumentUriUsingTree(ownRoot, "fetchy"), DC.Document.MIME_TYPE_DIR, "date-$tag")!!
        val ownTree = DC.buildTreeDocumentUri(FetchyDocumentsProvider.AUTHORITY, DC.getDocumentId(ownFolder))
        val ids = mutableListOf<String>()
        try {
            for (own in listOf(false, true)) for (mode in listOf("source", "download")) {
                val id = "date-${UUID.randomUUID()}"; ids.add(id)
                val before = System.currentTimeMillis(); val original = 1577836800000L
                store.put("jobs", JSONObject().put("id", id).put("state", "saving").put("settings", JSONObject().put("fileDate", mode))
                    .put("asset", JSONObject().put("sourceTimestamp", original)))
                val record = storage.publish(id, source, "$id.mp4", "video/mp4", if (own) null else folder.getString("id"), if (own) ownTree.toString() else null)
                val date = record.getLong("mediaDate")
                if (mode == "source") assertEquals(original, date) else assertTrue(date in before..System.currentTimeMillis())
                assertTrue("File date setter, own=$own, mode=$mode", record.getBoolean("dateApplied"))
                assertTrue(kotlin.math.abs(modified(Uri.parse(record.getString("uri"))) - date) < 2000)
                var scanned: JSONObject? = null
                for (attempt in 0 until 150) {
                    scanned = store.get("files", id)!!
                    if (scanned.has("galleryDateApplied")) break
                    Thread.sleep(100)
                }
                assertTrue("Media scan callback must report date update capability", scanned!!.has("galleryDateApplied"))
                val galleryApplied = scanned.getBoolean("galleryDateApplied")
                if (galleryApplied) context.contentResolver.query(Uri.parse(scanned.getString("mediaUri")), arrayOf(MediaStore.Images.Media.DATE_TAKEN), null, null, null)!!.use {
                    assertTrue(it.moveToFirst()); assertTrue(kotlin.math.abs(it.getLong(0) - date) < 2000)
                }
                assertTrue("Gallery indexing must preserve the selected physical file date", kotlin.math.abs(modified(Uri.parse(record.getString("uri"))) - date) < 2000)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putString("dateResult", JSONObject().put("ownRoot", own).put("mode", mode).put("fileDateApplied", true).put("galleryDateApplied", galleryApplied).toString())
                })
            }
            val originalId = ids.first(); val originalRecord = store.get("files", originalId)!!; val uri = Uri.parse(originalRecord.getString("uri"))
            val before = modified(uri); val skipId = "date-${UUID.randomUUID()}"; ids.add(skipId)
            store.put("jobs", JSONObject().put("id", skipId).put("settings", JSONObject().put("duplicates", "skip").put("fileDate", "download")))
            val skipped = storage.publish(skipId, source, originalRecord.getString("name"), "video/mp4", folder.getString("id"))
            assertEquals(uri.toString(), skipped.getString("uri")); assertEquals(before, modified(uri))
        } finally {
            ids.forEach { if (store.get("files", it) != null) storage.deleteFile(it); store.delete("jobs", it) }
            storage.deleteFolder(folder.getString("id")); DC.deleteDocument(context.contentResolver, ownFolder); work.deleteRecursively()
        }
    }

    @Test fun publicationRecoveryKeepsTheSelectedDate() {
        val store = Store(context); val storage = Storage(context, store); val id = "date-${UUID.randomUUID()}"
        val folder = storage.createFolder(id); val source = File(context.cacheDir, "$id.txt").apply { writeText("date recovery fixture") }
        val date = 1577836800000L
        try {
            store.put("folderOps", JSONObject().put("id", "publish:$id").put("type", "publish").put("fileId", id)
                .put("source", source.path).put("parent", folder.getString("uri")).put("name", "$id.txt").put("mime", "text/plain")
                .put("folderId", folder.getString("id")).put("rootUri", folder.getString("rootUri")).put("replace", "").put("tempName", ".fetchy-$id")
                .put("metadata", JSONObject().put("mediaDate", date).put("sourceTimestamp", date).put("subtitleFor", "fixture")))
            Storage(context, Store(context)).recover()
            assertNull(store.get("folderOps", "publish:$id"))
            val saved = store.get("files", id)!!
            assertEquals(date, saved.getLong("mediaDate")); assertEquals(date, saved.getLong("sourceTimestamp"))
            assertEquals("fixture", saved.getString("subtitleFor")); assertTrue(saved.getBoolean("dateApplied"))
            assertTrue(kotlin.math.abs(modified(Uri.parse(saved.getString("uri"))) - date) < 2000)
        } finally {
            store.delete("folderOps", "publish:$id"); if (store.get("files", id) != null) storage.deleteFile(id)
            storage.deleteFolder(folder.getString("id")); source.delete()
        }
    }
}
