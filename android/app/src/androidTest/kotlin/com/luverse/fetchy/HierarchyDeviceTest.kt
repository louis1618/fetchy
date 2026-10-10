package com.luverse.fetchy

import android.net.Uri
import android.provider.DocumentsContract as DC
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HierarchyDeviceTest {
    @Test fun xExtractorAliasUsesTheSamePlatformFolder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Store(context); val storage = Storage(context, store)
        val settings = store.settings().toString()
        // The dedicated installation has approved its platform folders already.
        assertTrue(store.settings().optString("rootUri").isNotBlank())
        assertTrue(store.list("folders").any { it.optString("platform") == "X" })
        val before = store.list("folders").map { it.getString("id") }.toSet()
        assertEquals(storage.folderFor("X")?.getString("id"), storage.folderFor("X (Twitter)")?.getString("id"))
        assertEquals(before, store.list("folders").map { it.getString("id") }.toSet())
        assertEquals(settings, store.settings().toString())
    }

    @Test fun legacyFlatRecordsMigrateWithoutChangingFilesOrIds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Store(context); val storage = Storage(context, store)
        val tag = UUID.randomUUID().toString().take(8)
        val folder = storage.createFolder("legacy-$tag")
        val folderId = folder.getString("id"); val fileId = "legacy-file-$tag"
        val source = File(context.cacheDir, "$fileId.txt").apply { writeText("legacy-$tag") }
        try {
            val file = storage.publish(fileId, source, "legacy.txt", "text/plain", folderId)
            storage.favorite("files", fileId, true)
            folder.remove("parentId"); folder.remove("color"); folder.remove("favorite")
            store.put("folders", folder)
            repeat(2) { Storage(context, Store(context)).recover() }
            val migrated = store.get("folders", folderId)!!
            assertEquals(folder.getString("uri"), migrated.getString("uri"))
            assertEquals(folder.getString("rootUri"), migrated.getString("rootUri"))
            assertTrue(migrated.has("parentId") && migrated.isNull("parentId"))
            assertEquals(0xff0a6bffL, migrated.getLong("color"))
            assertFalse(migrated.getBoolean("favorite"))
            assertEquals(file.getString("uri"), store.get("files", fileId)!!.getString("uri"))
            assertTrue(store.get("files", fileId)!!.getBoolean("favorite"))
            assertEquals("legacy-$tag", context.contentResolver.openInputStream(Uri.parse(file.getString("uri")))!!.bufferedReader().use { it.readText() })
        } finally {
            runCatching { storage.deleteFile(fileId) }
            runCatching { storage.deleteFolder(folderId) }
            source.delete()
        }
    }

    @Test fun pendingMoveJournalRebindsNestedRecordsAfterRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Store(context); val storage = Storage(context, store)
        val tag = UUID.randomUUID().toString().take(8)
        val a = storage.createFolder("move-a-$tag"); val b = storage.createFolder("move-b-$tag")
        val child = storage.createFolder("child", parentId = a.getString("id"))
        val grandchild = storage.createFolder("grandchild", parentId = child.getString("id"))
        val fileId = "move-journal-$tag"
        val source = File(context.cacheDir, "$fileId.txt").apply { writeText("journal-$tag") }
        val opId = "move:${child.getString("uri")}"
        try {
            storage.publish(fileId, source, "journal.txt", "text/plain", grandchild.getString("id"))
            storage.favorite("files", fileId, true)
            // Persist the same intent as Storage.transfer, then restart before any provider mutation.
            store.put("folderOps", JSONObject().put("id", opId).put("type", "move")
                .put("source", child.getString("uri")).put("sourceParent", a.getString("uri"))
                .put("parent", b.getString("uri")).put("mime", DC.Document.MIME_TYPE_DIR)
                .put("name", child.getString("name")).put("tempName", ".fetchy-$tag")
                .put("rootUri", b.getString("rootUri")).put("folderId", b.getString("id"))
                .put("record", child))
            Storage(context, Store(context)).recover()
            assertNull(store.get("folderOps", opId))
            assertEquals(b.getString("id"), store.get("folders", child.getString("id"))!!.getString("parentId"))
            assertEquals(child.getString("id"), store.get("folders", grandchild.getString("id"))!!.getString("parentId"))
            assertTrue(store.get("files", fileId)!!.getBoolean("favorite"))
            assertEquals("journal-$tag", context.contentResolver.openInputStream(Uri.parse(store.get("files", fileId)!!.getString("uri")))!!.bufferedReader().use { it.readText() })
        } finally {
            store.delete("folderOps", opId)
            runCatching { storage.deleteFile(fileId) }
            listOf(grandchild, child, a, b).forEach { runCatching { storage.deleteFolder(it.getString("id")) } }
            source.delete()
        }
    }

    @Test fun nestedFolderMovesRenameAndDeletePreserveFilesAndRecovery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Store(context); val storage = Storage(context, store)
        assertTrue("SAF setup required", store.settings().optString("rootUri").isNotEmpty())
        val tag = UUID.randomUUID().toString().take(8)
        val a = storage.createFolder("tree-a-$tag"); val b = storage.createFolder("tree-b-$tag")
        val child = storage.createFolder("child", parentId = a.getString("id"), color = 0xffec49b4L)
        val grandchild = storage.createFolder("grandchild", parentId = child.getString("id"))
        val source = File(context.cacheDir, "tree-$tag.txt").apply { writeText("preserved-$tag") }
        val fileId = "hierarchy-$tag"; val pausedId = "hierarchy-paused-$tag"
        try {
            val record = storage.publish(fileId, source, "file.txt", "text/plain", grandchild.getString("id"))
            storage.favorite("files", fileId, true); storage.favorite("folders", child.getString("id"), true)
            storage.renameFolder(a.getString("id"), "tree-renamed-$tag")
            fun contents() = context.contentResolver.openInputStream(Uri.parse(store.get("files", fileId)!!.getString("uri")))!!.bufferedReader().use { it.readText() }
            assertEquals("preserved-$tag", contents())
            storage.moveFolder(child.getString("id"), b.getString("id"))
            assertEquals(b.getString("id"), store.get("folders", child.getString("id"))!!.getString("parentId"))
            assertEquals("preserved-$tag", contents())
            assertTrue(store.get("files", fileId)!!.getBoolean("favorite"))
            try { storage.moveFolder(b.getString("id"), grandchild.getString("id")); fail("Cycle must fail") } catch (_: IllegalArgumentException) {}
            store.put("jobs", JSONObject().put("id", pausedId).put("state", "queued").put("folderId", grandchild.getString("id")))
            try { storage.renameFolder(b.getString("id"), "blocked"); fail("Descendant job must block mutation") } catch (_: IllegalStateException) {}
            store.delete("jobs", pausedId)
            // Simulate a crash after persisting a deletion journal, before mutating SAF.
            val latest = store.get("folders", child.getString("id"))!!
            latest.put("deleting", true); store.put("folders", latest)
            Storage(context, Store(context)).recover()
            assertNull(store.get("folders", child.getString("id")))
            assertEquals(b.getString("id"), store.get("folders", grandchild.getString("id"))!!.getString("parentId"))
            assertEquals("preserved-$tag", contents())
            assertTrue(store.get("files", fileId)!!.getBoolean("favorite"))
            storage.deleteFolder(grandchild.getString("id"))
            assertEquals(b.getString("id"), store.get("files", fileId)!!.getString("folderId"))
            assertEquals("preserved-$tag", contents())
            storage.moveFile(fileId, a.getString("id")); assertTrue(store.get("files", fileId)!!.getBoolean("favorite"))
            storage.renameFile(fileId, "renamed.txt"); assertEquals("preserved-$tag", contents())
        } finally {
            store.delete("jobs", pausedId)
            runCatching { storage.deleteFile(fileId) }
            listOf(grandchild, child, a, b).forEach { runCatching { storage.deleteFolder(it.getString("id")) } }
            source.delete()
        }
    }
}
