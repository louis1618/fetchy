package com.luverse.fetchy

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Checks jobs created by actual Flutter taps, without reading unrelated media into logs. */
@RunWith(AndroidJUnit4::class)
class UiQueueValidationDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val source get() = InstrumentationRegistry.getArguments().getString("mediaUrl")
        ?: "https://www.w3schools.com/html/mov_bbb.mp4"
    private val fixture get() = File(context.filesDir, "queue-ui-validation.json")

    @Test fun prepare() {
        val store = Store(context)
        assumeTrue(store.settings().optString("rootUri").isNotEmpty())
        assertFalse("Clean the previous fixture first", fixture.exists())
        assumeTrue(store.list("jobs").all { it.optString("state") in FetchyRuntime.terminal })
        fixture.writeText(JSONObject()
            .put("before", JSONArray(store.list("jobs").map { it.getString("id") }))
            .put("recent", store.get("recentLinks", source) ?: JSONObject.NULL).toString())
    }

    @Test fun verifyCurrentTabCompletedExactlyOneJob() {
        assertTrue(fixture.exists())
        val store = Store(context)
        val ids = JSONObject(fixture.readText()).getJSONArray("before")
        val before = (0 until ids.length()).map { ids.getString(it) }.toSet()
        val jobs = store.list("jobs").filter { it.getString("id") !in before && it.optString("sourceUrl") == source }
        assertEquals("One save must enqueue only the visible media tab", 1, jobs.size)
        val job = jobs.single()
        val kind = InstrumentationRegistry.getArguments().getString("expectedKind")
        require(kind in listOf("video", "audio"))
        assertEquals(kind, job.getString("kind"))
        assertEquals(job.optString("error"), "completed", job.getString("state"))
        val saved = store.get("files", job.getString("id"))!!
        assertTrue(saved.getLong("size") > 1000)
        assertTrue(saved.getString("mime").startsWith("$kind/"))
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("uiQueueResult", JSONObject().put("jobCount", jobs.size)
                .put("kind", kind).put("state", job.getString("state"))
                .put("ext", saved.getString("name").substringAfterLast('.')).put("mime", saved.getString("mime"))
                .put("bytes", saved.getLong("size")).toString())
        })
    }

    @Test fun cleanup() {
        if (!fixture.exists()) return
        val store = Store(context)
        val baseline = JSONObject(fixture.readText())
        val ids = baseline.getJSONArray("before")
        val before = (0 until ids.length()).map { ids.getString(it) }.toSet()
        val jobs = store.list("jobs").filter { it.getString("id") !in before && it.optString("sourceUrl") == source }
        assertTrue("Stop this fixture's downloads before cleanup", jobs.all { it.optString("state") in FetchyRuntime.terminal })
        jobs.forEach { job ->
            val id = job.getString("id")
            store.get("files", id)?.let { Storage(context, store).deleteFile(id) }
            store.delete("jobs", id)
            File(context.filesDir, "work/$id").deleteRecursively()
        }
        baseline.optJSONObject("recent")?.let { store.put("recentLinks", it) }
            ?: store.delete("recentLinks", source)
        fixture.delete()
    }
}
