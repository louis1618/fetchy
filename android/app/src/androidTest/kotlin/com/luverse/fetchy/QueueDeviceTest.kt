package com.luverse.fetchy

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.json.*

@RunWith(AndroidJUnit4::class)
class QueueDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun enqueuePair(runtime: FetchyRuntime): List<String> {
        assumeTrue(runtime.store.settings().optString("rootUri").isNotEmpty())
        val before = runtime.store.list("jobs").map { it.getString("id") }.toSet()
        val analysis = runtime.engine.analyze("https://www.w3schools.com/html/mov_bbb.mp4")
        val asset = analysis.getJSONArray("assets").getJSONObject(0)
        val formats = asset.getJSONArray("formats")
        val audio = (0 until formats.length()).map { formats.getJSONObject(it) }.first { it.optString("kind") == "audio" && it.optString("ext") == "m4a" }
        val selections = JSONArray().put(JSONObject().put("assetId", asset.getString("id")).put("formatId", formats.getJSONObject(0).getString("id")))
            .put(JSONObject().put("assetId", asset.getString("id")).put("formatId", audio.getString("id")))
        runtime.enqueue(JSONObject().put("analysis", analysis).put("selections", selections).put("videoFormat", "mp4"))
        return runtime.store.list("jobs").map { it.getString("id") }.filter { it !in before }.also { assertEquals(2, it.size) }
    }
    @Test fun queueControlsPersistAndRemoveCanceledWork() {
        val runtime = FetchyRuntime.get(context)
        runtime.stop()
        val ids = enqueuePair(runtime)
        for (id in ids) {
            runtime.command(id, "pause"); assertEquals("paused", runtime.store.get("jobs", id)!!.getString("state"))
            runtime.command(id, "resume"); assertEquals("queued", runtime.store.get("jobs", id)!!.getString("state"))
            runtime.command(id, "cancel"); assertEquals("canceled", runtime.store.get("jobs", id)!!.getString("state"))
            runtime.command(id, "retry"); assertEquals("queued", runtime.store.get("jobs", id)!!.getString("state"))
            runtime.command(id, "cancel"); runtime.command(id, "deleteRecord"); assertNull(runtime.store.get("jobs", id))
        }
    }
    @Test fun concurrentVideoAndAudioFinishWithActivityClosed() {
        val runtime = FetchyRuntime.get(context)
        val originalConcurrency = runtime.store.settings().getInt("concurrency")
        runtime.store.setting("concurrency", 2)
        val ids = enqueuePair(runtime)
        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val activity = instrumentation.startActivitySync(testActivityIntent(context))
            instrumentation.runOnMainSync { FetchyRuntime.schedule(activity); activity.finish() }
            val end = System.currentTimeMillis() + 60000
            while (System.currentTimeMillis() < end && ids.any { runtime.store.get("jobs", it)?.optString("state") !in listOf("completed", "failed") }) Thread.sleep(200)
            ids.forEach {
                val job = runtime.store.get("jobs", it)!!
                assertEquals(job.optString("error"), "completed", job.getString("state"))
                assertTrue(runtime.store.get("files", it)!!.getLong("size") > 1000)
            }
        } finally {
            runtime.store.setting("concurrency", originalConcurrency)
            ids.forEach { id ->
                runtime.store.get("files", id)?.let { runtime.storage.deleteFile(id) }
                if (runtime.store.get("jobs", id)?.optString("state") !in FetchyRuntime.terminal) runCatching { runtime.command(id, "cancel") }
                runtime.store.delete("jobs", id)
            }
        }
    }
}
