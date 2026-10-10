package com.luverse.fetchy

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.json.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecoveryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureId = "fetchy-process-recovery-fixture"
    @Test fun seedInterruptedDownload() {
        val store = Store(context)
        assumeTrue(store.settings().optString("rootUri").isNotEmpty())
        store.get("files", fixtureId)?.let { Storage(context, store).deleteFile(fixtureId) }
        store.delete("jobs", fixtureId)
        File(context.filesDir, "work/$fixtureId").deleteRecursively()
        val engine = Engine(context, CookieVault(context))
        val a = engine.analyze("https://www.w3schools.com/html/mov_bbb.mp4")
        val asset = a.getJSONArray("assets").getJSONObject(0)
        val format = asset.getJSONArray("formats").getJSONObject(0)
        val job = JSONObject().put("id", fixtureId).put("title", "Fetchy background verification").put("sourceUrl", a.getString("sourceUrl"))
            .put("platform", a.getString("platform")).put("asset", asset).put("selection", format).put("assetId", asset.getString("id"))
            .put("formatId", format.getString("id")).put("kind", format.getString("kind")).put("ext", format.getString("ext"))
            .put("settings", store.settings()).put("rootUri", store.settings().getString("rootUri")).put("folderId", "")
            .put("state", "running").put("createdAt", System.currentTimeMillis()).put("progress", 10)
        val work = File(context.filesDir, "work/$fixtureId").apply { mkdirs() }
        var canceled = false
        try { engine.download(job, work) { _, _, bytes, _ -> if (bytes > 65536 && !canceled) { canceled = true; engine.cancel(fixtureId) } }; fail("Expected interrupt") }
        catch (_: java.io.IOException) { assertTrue(canceled) }
        store.put("jobs", job)
        assertTrue(work.listFiles()!!.any { it.name.endsWith(".part") && it.length() > 0 })
    }
    @Test fun restoreAndFinishInBackground() {
        val store = Store(context)
        assumeTrue(store.get("jobs", fixtureId) != null)
        val runtime = FetchyRuntime.get(context)
        val timeout = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < timeout && store.get("jobs", fixtureId)?.optString("state") == "running") Thread.sleep(100)
        // A sticky service may already have resumed this job before the assertion reads it.
        assertTrue(store.get("jobs", fixtureId)!!.getString("state") in listOf("queued", "running", "merging", "converting", "saving", "completed"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync { FetchyRuntime.schedule(activity); activity.finish() }
        val screenOff = InstrumentationRegistry.getArguments().getString("screenOff") == "true"
        try {
            if (screenOff) {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP")).use { it.readBytes() }
                Thread.sleep(300)
                assertFalse(context.getSystemService(android.os.PowerManager::class.java).isInteractive)
            }
            val end = System.currentTimeMillis() + 60000
            while (System.currentTimeMillis() < end && store.get("jobs", fixtureId)?.optString("state") !in listOf("completed", "failed")) Thread.sleep(200)
            if (screenOff) assertFalse(context.getSystemService(android.os.PowerManager::class.java).isInteractive)
        } finally {
            if (screenOff) android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP")).use { it.readBytes() }
        }
        val finished = store.get("jobs", fixtureId)!!
        assertEquals(finished.optString("error"), "completed", finished.getString("state"))
        val saved = store.get("files", fixtureId)!!
        assertTrue(saved.optLong("size") > 700000)
        val firstByte = context.contentResolver.openInputStream(android.net.Uri.parse(saved.getString("uri")))!!.use { it.read() }
        assertTrue(firstByte >= 0)
        assertFalse(File(context.filesDir, "work/$fixtureId").exists())
    }
    @Test fun cleanFixture() {
        val store = Store(context)
        store.get("files", fixtureId)?.let { Storage(context, store).deleteFile(fixtureId) }
        store.delete("jobs", fixtureId)
        File(context.filesDir, "work/$fixtureId").deleteRecursively()
    }
}
