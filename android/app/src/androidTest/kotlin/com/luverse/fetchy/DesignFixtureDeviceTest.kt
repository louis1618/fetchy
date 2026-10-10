package com.luverse.fetchy

import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

/** Explicit screenshot fixtures only; never run by production or shipped in app assets. */
@RunWith(AndroidJUnit4::class)
class DesignFixtureDeviceTest {
    @Test fun seed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Store(context); val storage = Storage(context, store)
        if (store.get("designState", "saved") == null) store.put("designState", JSONObject().put("id", "saved").put("theme", store.settings().optString("theme", "system")).put("onboarded", store.settings().optBoolean("onboarded", true)))
        assertTrue(store.settings().optString("rootUri").isNotEmpty())
        val file = File(context.cacheDir, "design-source.jpg")
        val source = "https://www.w3schools.com/w3css/img_lights.jpg"
        java.net.URL(source).openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        for ((index, platform) in listOf("youtube", "instagram", "tiktok", "x").withIndex()) {
            val id = "design-fixture-$index"
            val folder = storage.folderFor(platform)!!
            store.put("jobs", JSONObject().put("id", id).put("state", "completed").put("title", "디자인 검증 이미지 ${index+1}").put("platform", platform).put("sourceUrl", source).put("createdAt", System.currentTimeMillis()).put("rootUri", store.settings().getString("rootUri")))
            storage.publish(id, file, "design-fixture-$index.jpg", "image/jpeg", folder.getString("id"))
            store.put("recentLinks", JSONObject().put("id", "design-recent-$index").put("title", "디자인 검증 이미지 ${index+1}").put("platform", platform).put("sourceUrl", source).put("thumbnail", source).put("analyzedAt", System.currentTimeMillis()-index*60000))
        }
        file.delete(); store.setting("onboarded", true); store.setting("theme", "light")
    }
    @Test fun previewCacheRegeneratesAndKeepsFavorites() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Store(context); val storage = Storage(context, store); val id = "design-fixture-0"
        val details = MediaDetails(context, store)
        val first = details.read(id)
        assertTrue(first.optInt("width") > 0 && first.optInt("height") > 0)
        val preview = File(first.getString("previewPath")); assertTrue(preview.isFile)
        storage.favorite("files", id, true)
        FetchyRuntime.get(context).cleanup(); assertFalse(preview.exists())
        assertTrue(File(details.read(id).getString("previewPath")).isFile)
        assertTrue(store.get("files", id)!!.getBoolean("favorite"))
        storage.favorite("files", id, false)
    }
    @Test fun welcome() { Store(InstrumentationRegistry.getInstrumentation().targetContext).setting("onboarded", false) }
    @Test fun light() { val store = Store(InstrumentationRegistry.getInstrumentation().targetContext); store.setting("onboarded", true); store.setting("theme", "light") }
    @Test fun dark() { Store(InstrumentationRegistry.getInstrumentation().targetContext).setting("theme", "dark") }
    @Test fun cleanup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Store(context); val storage = Storage(context, store)
        store.list("files").filter { it.getString("id").startsWith("design-fixture-") }.forEach { storage.deleteFile(it.getString("id")) }
        store.list("jobs").filter { it.getString("id").startsWith("design-fixture-") }.forEach { store.delete("jobs",it.getString("id")) }
        store.get("designState", "saved")?.let { store.setting("theme", it.getString("theme")); store.setting("onboarded", it.getBoolean("onboarded")); store.delete("designState", "saved") }
        store.list("recentLinks").filter { it.getString("id").startsWith("design-recent-") }.forEach { store.delete("recentLinks",it.getString("id")) }
    }
}
