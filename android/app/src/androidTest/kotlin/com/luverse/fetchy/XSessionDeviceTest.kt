package com.luverse.fetchy

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class XSessionDeviceTest {
    @Test fun legacyMergeSelectionStillDownloadsTheSameAsset() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val url = InstrumentationRegistry.getArguments().getString("mediaUrl") ?: error("Provide mediaUrl")
        val engine = Engine(context, CookieVault(context)); val analysis = engine.analyze(url)
        val asset = analysis.getJSONArray("assets").getJSONObject(0); val formats = asset.getJSONArray("formats")
        val rows = (0 until formats.length()).map { formats.getJSONObject(it) }
        val video = rows.filter { it.optString("kind") == "video" && !it.optBoolean("needsMerge") }.maxBy { it.optInt("height") }
        val audio = rows.first { it.optString("kind") == "audio" && !it.optBoolean("extractAudio") }
        val selection = JSONObject(video.toString()).put("selector", video.getString("selector") + "+" + audio.getString("selector")).put("ext", "mkv").put("needsMerge", true)
        val id = UUID.randomUUID().toString(); val work = File(context.cacheDir, "legacy-merge-$id").apply { mkdirs() }
        val job = JSONObject().put("id", id).put("sourceUrl", url).put("platform", analysis.getString("platform")).put("asset", asset).put("selection", selection)
            .put("assetId", asset.getString("id")).put("formatId", selection.getString("id")).put("kind", "video").put("settings", Store(context).settings().put("videoFormat", "mp4"))
        try {
            val file = engine.download(job, work) { _, _, _, _ -> }
            assertEquals("mp4", file.extension); assertTrue(file.length() > 1000)
            assertTrue(selection.getJSONObject("mediaMetadata").getInt("height") > 0)
            instrumentation.sendStatus(0, Bundle().apply { putLong("legacyMergeBytes", file.length()) })
        } finally { engine.cancel(id); work.deleteRecursively() }
    }
    @Test fun savedSessionReachesXApiWithoutExportingSecrets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = CookieVault(instrumentation.targetContext).temporary("x", "scope-${UUID.randomUUID()}")
        assertNotNull("Save the X login in the app before this opt-in check", file)
        try {
            val rows = file!!.readLines().filter { it.startsWith("#HttpOnly_") || !it.startsWith('#') }.map { it.removePrefix("#HttpOnly_").split('\t') }.filter { it.size == 7 }
            val root = rows.any { it[0].removePrefix(".") == "x.com" && it[5] == "auth_token" }
            val api = rows.any { it[5] == "auth_token" && (it[0] == "api.x.com" || (it[0] == ".x.com" && it[1] == "TRUE")) }
            instrumentation.sendStatus(0, Bundle().apply { putBoolean("rootSession", root); putBoolean("apiSession", api) })
            assertTrue("The saved X session must be available to the first-party API", api)
        } finally { file?.delete() }
    }
}
