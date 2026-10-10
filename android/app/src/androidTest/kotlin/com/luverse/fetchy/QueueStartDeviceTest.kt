package com.luverse.fetchy

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class QueueStartDeviceTest {
    @Test fun finalSnapshotIsDeliveredAfterTheQueueBecomesIdle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = FetchyRuntime.get(context)
        assumeTrue(runtime.store.list("jobs").none { it.optString("state") !in FetchyRuntime.terminal })
        val id = "snapshot-regression-${UUID.randomUUID()}"
        val queued = CountDownLatch(1); val completed = CountDownLatch(1)
        val listener: (Map<String, Any?>) -> Unit = { snapshot ->
            @Suppress("UNCHECKED_CAST")
            val job = (snapshot["jobs"] as List<Map<String, Any?>>).firstOrNull { it["id"] == id }
            if (job?.get("state") == "queued") queued.countDown()
            if (job?.get("state") == "completed") completed.countDown()
        }
        try {
            runtime.store.put("jobs", JSONObject().put("id", id).put("state", "queued"))
            runtime.addListener(listener)
            assertTrue("Initial queued snapshot missing", queued.await(5, TimeUnit.SECONDS))
            runtime.store.put("jobs", JSONObject().put("id", id).put("state", "completed"))
            runtime.emit()
            assertTrue(runtime.finishIfIdle())
            assertTrue("Final state was dropped while the runtime was idle", completed.await(3, TimeUnit.SECONDS))
        } finally {
            runtime.removeListener(listener)
            runtime.store.delete("jobs", id)
        }
    }

    @Test fun inspectQueueWithoutPrivateMediaData() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = Store(context)
        val settings = store.settings()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
        val jobs = store.list("jobs")
        val status = JSONObject()
            .put("wifiOnly", settings.optBoolean("wifiOnly"))
            .put("mobileData", settings.optBoolean("mobileData", true))
            .put("internet", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
            .put("wifi", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
            .put("cellular", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
            .put("states", JSONObject(jobs.groupingBy { it.optString("state") }.eachCount()))
            .put("queuedWithError", jobs.count { it.optString("state") == "queued" && it.optString("error").isNotBlank() })
            .put("delayedRetries", jobs.count { it.optString("state") == "queued" && it.optLong("retryAt") > System.currentTimeMillis() })
        instrumentation.sendStatus(0, Bundle().apply { putString("queueDiagnostic", status.toString()) })
    }
}
