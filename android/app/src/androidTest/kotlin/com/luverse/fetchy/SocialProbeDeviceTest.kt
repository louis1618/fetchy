package com.luverse.fetchy

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Reports live-site availability separately from assertions on our implementation. */
@RunWith(AndroidJUnit4::class)
class SocialProbeDeviceTest {
    @Test fun probePublicMedia() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val platform = InstrumentationRegistry.getArguments().getString("platform") ?: "x"
        val url = when(platform) {
            "instagram" -> "https://www.instagram.com/p/BQ0eAlwhDrw/"
            "tiktok" -> "https://www.tiktok.com/@leenabhushan/video/6748451240264420610"
            else -> "https://x.com/BTNBrentYarina/status/705235433198714880"
        }
        val context = instrumentation.targetContext
        val engine = Engine(context, CookieVault(context))
        var result = "unavailable"
        var reason = ""
        val id = UUID.randomUUID().toString()
        val work = File(context.cacheDir, "social-probe/$id").apply { mkdirs() }
        try {
            val analysis = engine.analyze(url)
            val asset = analysis.getJSONArray("assets").getJSONObject(0)
            val formats = asset.getJSONArray("formats")
            val list = (0 until formats.length()).map { formats.getJSONObject(it) }
            val format = list.filter { it.optString("kind") == "video" }.minByOrNull { it.optInt("height").takeIf { h -> h > 0 } ?: 9999 } ?: list.first()
            val job = JSONObject().put("id", id).put("sourceUrl", url).put("platform", platform).put("asset", asset).put("selection", format)
                .put("assetId", asset.getString("id")).put("formatId", format.getString("id")).put("kind", format.getString("kind"))
                .put("ext", format.getString("ext")).put("settings", Store(context).settings())
            val file = engine.download(job, work) { _, _, _, _ -> }
            result = "downloaded ${file.length()} bytes, ${file.extension}, assets=${analysis.getJSONArray("assets").length()}"
        } catch (e: Exception) { reason = e.message.orEmpty() }
        finally { engine.cancel(id); work.deleteRecursively() }
        instrumentation.sendStatus(0, Bundle().apply { putString("platform", platform); putString("probe_result", result); putString("probe_reason", reason) })
    }
}
