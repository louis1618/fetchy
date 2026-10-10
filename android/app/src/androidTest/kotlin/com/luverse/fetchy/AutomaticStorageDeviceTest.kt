package com.luverse.fetchy

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract as DC
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AutomaticStorageDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val store get() = Store(context)

    private fun automatic(block: (Storage) -> Unit) {
        assumeTrue("Do not change storage while user downloads run", store.list("jobs").all { it.optString("state") in FetchyRuntime.terminal })
        val saved = store.settings()
        val previousFolders = store.list("folders").map { it.getString("id") }.toSet()
        val storage = Storage(context, store)
        try {
            store.setting("rootUri", ""); store.setting("rootName", "")
            storage.ensureRoot()
            assertEquals(FetchyDocumentsProvider.treeUri().toString(), store.settings().getString("rootUri"))
            block(storage)
        } finally {
            // Preserve all pre-existing roots, files, grants and settings.
            try { store.list("folders").filter { it.getString("id") !in previousFolders }.reversed().forEach { storage.deleteFolder(it.getString("id")) } }
            finally { store.put("settings", saved.put("id", "settings")) }
        }
        assertEquals(saved.toString(), store.settings().toString())
        assertEquals(previousFolders, store.list("folders").map { it.getString("id") }.toSet())
    }

    @Test fun automaticRootPreservesChosenRootAndRealHierarchy() = automatic { storage ->
        val initial = store.list("folders").map { it.getString("id") }.toSet()
        repeat(2) { Storage(context, Store(context)).ensureRoot() }
        assertEquals(initial, store.list("folders").map { it.getString("id") }.toSet())
        val root = FetchyDocumentsProvider.rootDirectory(context)
        assertTrue(root.isDirectory)
        assertTrue(root.path.contains("/Android/media/com.luverse.fetchy/Fetchy"))
        val tag = UUID.randomUUID().toString()
        val a = storage.createFolder("auto-a-$tag")
        val b = storage.createFolder("auto-b-$tag")
        val child = storage.createFolder("child", parentId = a.getString("id"))
        val fileId = "auto-file-$tag"
        val source = File(context.cacheDir, "$fileId.txt").apply { writeText("preserved-$tag") }
        fun contents() = context.contentResolver.openInputStream(Uri.parse(store.get("files", fileId)!!.getString("uri")))!!.bufferedReader().use { it.readText() }
        try {
            storage.publish(fileId, source, "file.txt", "text/plain", child.getString("id"))
            storage.renameFolder(a.getString("id"), "auto-renamed-$tag")
            assertEquals("preserved-$tag", contents())
            storage.moveFolder(child.getString("id"), b.getString("id"))
            assertEquals("preserved-$tag", contents())
            storage.renameFile(fileId, "renamed.txt")
            storage.moveFile(fileId, a.getString("id"))
            storage.deleteFolder(a.getString("id"))
            assertTrue(store.get("files", fileId)!!.isNull("folderId"))
            assertEquals("preserved-$tag", contents())
            Storage(context, Store(context)).recover()
            assertEquals("preserved-$tag", contents())
            // Switching back to an existing approved root does not change this file's URI/root.
            val originalRoot = store.get("files", fileId)!!.getString("rootUri")
            storage.chooseRoot(FetchyDocumentsProvider.treeUri())
            assertEquals(originalRoot, store.get("files", fileId)!!.getString("rootUri"))
        } finally {
            storage.deleteFile(fileId)
            listOf(child, a, b).forEach { if (store.get("folders", it.getString("id")) != null) storage.deleteFolder(it.getString("id")) }
            source.delete()
        }
    }

    private fun externallyDeletedFixture(removeParent: Boolean, recovery: Boolean) {
        val storage = Storage(context, store)
        val tag = UUID.randomUUID().toString()
        val folder = storage.createFolder("deleted-fixture-$tag")
        val id = "deleted-file-$tag"
        val source = File(context.cacheDir, "$id.txt").apply { writeText(tag) }
        try {
            val record = storage.publish(id, source, "gone.txt", "text/plain", folder.getString("id"))
            assertTrue(DC.deleteDocument(context.contentResolver, Uri.parse(record.getString("uri"))))
            if (removeParent) assertTrue(DC.deleteDocument(context.contentResolver, Uri.parse(folder.getString("uri"))))
            if (recovery) {
                store.put("folderOps", JSONObject().put("id", "delete-file:$id").put("type", "deleteFile").put("record", record))
                Storage(context, Store(context)).recover()
            } else storage.deleteFile(id)
            assertNull(store.get("files", id))
            assertNull(store.get("folderOps", "delete-file:$id"))
        } finally {
            if (store.get("files", id) != null) storage.deleteFile(id)
            if (removeParent) store.delete("folders", folder.getString("id"))
            else if (store.get("folders", folder.getString("id")) != null) storage.deleteFolder(folder.getString("id"))
            source.delete()
        }
    }
    @Test fun deletedSafFileCanBeRemovedFromLibrary() = externallyDeletedFixture(false, false)
    @Test fun deletedSafParentStillAllowsRemovingFileRecord() = externallyDeletedFixture(true, false)
    @Test fun pendingDeletionJournalRecoversAfterFileWasDeleted() = externallyDeletedFixture(false, true)
    @Test fun deletedAutomaticFileCanBeRemovedFromLibrary() = automatic { externallyDeletedFixture(false, false) }

    @Test fun actualMediaDownloadPublishesWithoutStorageApproval() = automatic {
        PlatformDeviceTest().individualLinkDownloadsAndSaves()
    }

    @Test fun automaticBackgroundQueueSavesAfterActivityCloses() = automatic {
        QueueDeviceTest().concurrentVideoAndAudioFinishWithActivityClosed()
    }

    @Test fun cleanOnlyInterruptedStorageFixtures() {
        val storage = Storage(context, store)
        for (record in store.list("files").filter { it.getString("id").matches(Regex("deleted-file-[0-9a-f-]{36}")) }) {
            require(record.getString("name") == "gone.txt" && record.getString("mime") == "text/plain")
            val parent = store.get("folders", record.optString("folderId"))
            if (parent == null) store.put("files", record.put("folderId", JSONObject.NULL))
            else require(parent.getString("name") == record.getString("id").replace("deleted-file-", "deleted-fixture-"))
            storage.deleteFile(record.getString("id"))
        }
        store.list("folders").filter { it.getString("name").matches(Regex("deleted-fixture-[0-9a-f-]{36}")) }.forEach {
            storage.deleteFolder(it.getString("id"))
        }
    }

    @Test fun inaccessibleProviderKeepsRecordAndDeletionJournal() {
        val storage = Storage(context, store)
        val id = "unavailable-${UUID.randomUUID()}"
        val source = File(context.cacheDir, "$id.txt").apply { writeText(id) }
        val record = storage.publish(id, source, "$id.txt", "text/plain", null)
        try {
            store.put("files", JSONObject(record.toString()).put("rootUri", "content://com.luverse.fetchy.unavailable/tree/root")
                .put("uri", "content://com.luverse.fetchy.unavailable/tree/root/document/missing"))
            try { storage.deleteFile(id); fail("Unavailable provider is not evidence of deletion") } catch (_: IllegalStateException) { }
            assertNotNull(store.get("files", id)); assertNotNull(store.get("folderOps", "delete-file:$id"))
            assertEquals(id, context.contentResolver.openInputStream(Uri.parse(record.getString("uri")))!!.bufferedReader().use { it.readText() })
        } finally {
            store.put("files", record); store.delete("folderOps", "delete-file:$id")
            storage.deleteFile(id); source.delete()
        }
    }

    @Test fun automaticImageIsScannedAndOpensInSystemApps() = automatic { storage ->
        val id = "auto-image-${UUID.randomUUID()}"
        val source = File(context.cacheDir, "$id.jpg")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        try {
            val record = storage.publish(id, source, "$id.jpg", "image/jpeg", null)
            val deadline = System.currentTimeMillis() + 10000
            while (store.get("files", id)!!.optString("mediaUri").isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100)
            val media = store.get("files", id)!!.optString("mediaUri")
            assertTrue("Automatic file must be indexed by MediaStore", media.startsWith("content://media/"))
            context.contentResolver.openInputStream(Uri.parse(media))!!.use { assertEquals(source.length(), it.readBytes().size.toLong()) }
            fun outsideFetchy() {
                val end = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < end) {
                    val app = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
                    if (app != null && app != context.packageName && !app.contains("launcher")) return
                    Thread.sleep(100)
                }
                fail("System application did not open")
            }
            storage.openFile(id); outsideFetchy()
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            storage.openFile(id, true); outsideFetchy()
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            storage.openFileFolder(id); outsideFetchy()
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            instrumentation.sendStatus(0, Bundle().apply { putString("automaticStorage", JSONObject().put("uri", record.getString("uri")).put("galleryIndexed", true).put("openShareFolder", true).toString()) })
        } finally { storage.deleteFile(id); source.delete() }
    }
}
