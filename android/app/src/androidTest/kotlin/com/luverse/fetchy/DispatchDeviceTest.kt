package com.luverse.fetchy

import android.content.Intent
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

internal fun testActivityIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
        if (InstrumentationRegistry.getArguments().getString("softwareRendering") == "true") {
            // Keep repeated native queue tests off the emulator's unstable GLES renderer.
            putExtra("enable-impeller", false)
            putExtra("enable-software-rendering", true)
        }
    }

@RunWith(AndroidJUnit4::class)
class DispatchDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun exercise(url: String, disableJobService: Boolean = false, reschedule: Boolean = false) {
        val runtime = FetchyRuntime.get(context)
        assumeTrue(runtime.store.settings().optString("rootUri").isNotBlank())
        assumeTrue(runtime.store.list("jobs").all { it.optString("state") in FetchyRuntime.terminal })
        val before = runtime.store.list("jobs").map { it.getString("id") }.toSet()
        val analysis = runtime.engine.analyze(url)
        val assets = analysis.getJSONArray("assets")
        val allAssets = InstrumentationRegistry.getArguments().getString("allAssets") == "true"
        val selections = JSONArray()
        val kind = InstrumentationRegistry.getArguments().getString("mediaKind") ?: "video"
        val maxHeight = InstrumentationRegistry.getArguments().getString("maxHeight")?.toIntOrNull() ?: 480
        for (i in 0 until if (allAssets) assets.length() else 1) {
            val asset = assets.getJSONObject(i)
            val formats = asset.getJSONArray("formats")
            val choices = (0 until formats.length()).map { formats.getJSONObject(it) }.filter { it.getString("kind") == kind }
            val selected = choices.filter { it.optInt("height") in 1..maxHeight }.maxByOrNull { it.optInt("height") } ?: choices.first()
            selections.put(JSONObject().put("assetId", asset.getString("id")).put("formatId", selected.getString("id")))
        }
        val activity = instrumentation.startActivitySync(testActivityIntent(context))
        val component = ComponentName(context, TransferJobService::class.java)
        val originalComponent = context.packageManager.getComponentEnabledSetting(component)
        try {
            if (disableJobService) context.packageManager.setComponentEnabledSetting(component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            runtime.enqueue(JSONObject().put("analysis", analysis).put("videoFormat", "mp4")
                .put("selections", selections))
            val jobIds = runtime.store.list("jobs").filter { it.getString("id") !in before }.map { it.getString("id") }
            assertEquals(selections.length(), jobIds.size)
            val jobId = jobIds.first()
            if (InstrumentationRegistry.getArguments().getString("retryFailed") == "true") {
                val failed = runtime.store.get("jobs", jobId)!!.put("state", "failed")
                    .put("error", "Entry at offset zero has invalid LFH signature 752f2123")
                runtime.store.put("jobs", failed)
                runtime.command(jobId, "retry")
                val retried = runtime.store.get("jobs", jobId)!!
                assertEquals(failed.getString("assetId"), retried.getString("assetId"))
                assertEquals(failed.getString("formatId"), retried.getString("formatId"))
            }
            instrumentation.runOnMainSync { FetchyRuntime.schedule(activity) }
            if (disableJobService) {
                val startDeadline = System.currentTimeMillis() + 10000
                while (System.currentTimeMillis() < startDeadline && runtime.store.get("jobs", jobId)?.optString("state") == "queued") Thread.sleep(100)
                assertNotEquals("An unavailable UIDT service must not leave the queue stuck", "queued", runtime.store.get("jobs", jobId)?.optString("state"))
            }
            if (reschedule) repeat(10) {
                instrumentation.runOnMainSync { FetchyRuntime.schedule(activity) }
                Thread.sleep(100)
            }
            instrumentation.runOnMainSync { activity.finish() }
            val timeout = System.currentTimeMillis() + 180000
            while (System.currentTimeMillis() < timeout && jobIds.any { runtime.store.get("jobs", it)?.optString("state") !in FetchyRuntime.terminal }) Thread.sleep(200)
            var bytes = 0L
            for (id in jobIds) {
                val job = runtime.store.get("jobs", id)!!
                assertEquals(job.optString("error"), "completed", job.getString("state"))
                val saved = runtime.store.get("files", id)!!
                assertTrue(saved.getString("mime").startsWith("$kind/"))
                assertTrue(saved.getLong("size") > 1000)
                bytes += saved.getLong("size")
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("dispatchResult", JSONObject()
                .put("platform", analysis.getString("platform")).put("assets", assets.length()).put("jobs", jobIds.size)
                .put("state", "completed").put("bytes", bytes).put("kind", kind).toString()) })
        } finally {
            if (disableJobService) context.packageManager.setComponentEnabledSetting(component,
                originalComponent, PackageManager.DONT_KILL_APP)
            instrumentation.runOnMainSync { if (!activity.isFinishing) activity.finish() }
            runtime.store.list("jobs").filter { it.getString("id") !in before && it.optString("sourceUrl") == analysis.getString("sourceUrl") }.forEach { job ->
                val id = job.getString("id")
                if (job.optString("state") !in FetchyRuntime.terminal) runtime.command(id, "cancel")
                runtime.store.get("files", id)?.let { runtime.storage.deleteFile(id) }
                runtime.store.delete("jobs", id)
            }
        }
    }

    @Test fun unavailableUidtStillStartsVisibleDownload() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        exercise("https://www.w3schools.com/html/mov_bbb.mp4", disableJobService = true)
    }

    @Test fun repeatedSchedulingDoesNotRestartTheRunner() {
        exercise("https://www.w3schools.com/html/mov_bbb.mp4", reschedule = true)
    }

    @Test fun aStaleOwnerCannotStopTheNewRunner() {
        val runtime = FetchyRuntime.get(context)
        assumeTrue(!runtime.hasPending())
        // A preceding completed transfer keeps its service owner until the next idle callback.
        val idleDeadline = System.currentTimeMillis() + 5000
        while (runtime.isExecuting() && System.currentTimeMillis() < idleDeadline) Thread.sleep(100)
        assertFalse("Previous service must release its idle runner", runtime.isExecuting())
        val previous = Any(); val current = Any()
        assertTrue(runtime.start(previous))
        assertTrue(runtime.finishIfIdle(previous))
        assertTrue(runtime.start(current))
        try {
            runtime.stop(owner = previous)
            assertTrue(runtime.isExecuting())
            assertFalse(runtime.start(previous))
        } finally { runtime.stop(owner = current) }
    }

    @Test fun suppliedSocialLinkFinishesThroughTheBackgroundQueue() {
        val url = InstrumentationRegistry.getArguments().getString("mediaUrl")
        assumeTrue(url != null)
        exercise(url!!)
    }
}
