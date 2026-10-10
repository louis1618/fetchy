package com.luverse.fetchy

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class Diagnostics(private val context: Context, private val store: Store) {
    fun report(engine: Map<String, Any?>, jobId: String? = null): JSONObject {
        val settings = store.settings()
        val selected = store.list("jobs").filter { jobId == null || it.optString("id") == jobId }.takeLast(20)
        return JSONObject().put("app", "Fetchy").put("version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            .put("android", Build.VERSION.SDK_INT).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("abi", JSONArray(Build.SUPPORTED_ABIS.toList())).put("createdAt", System.currentTimeMillis())
            .put("engine", JSONObject().apply { for (key in listOf("status", "ytDlp", "python", "wrapper", "quickjsVersion")) if (engine[key] != null) put(key, engine[key]) })
            .put("settings", JSONObject().apply { for (key in listOf("quality", "videoFormat", "audioFormat", "wifiOnly", "mobileData", "concurrency", "retries", "resume")) put(key, settings.opt(key)) })
            .put("jobs", JSONArray(selected.map { job ->
                val kind = if (job.optString("error").isBlank() && job.optString("state") != "failed") null
                    else if (job.has("errorCode")) FailureKind.byCode(job.optString("errorCode")) else FailureKind.from(java.io.IOException(job.optString("error")))
                JSONObject().put("state", job.optString("state")).put("kind", job.optString("kind"))
                    .put("attempts", job.optInt("attempts")).put("errorCode", kind?.code ?: "").put("error", kind?.message ?: "")
            }))
    }
    fun share(engine: Map<String, Any?>, jobId: String?) {
        val directory = File(context.cacheDir, "diagnostics").apply { check(isDirectory || mkdirs()) }
        directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86400000 }?.forEach { it.delete() }
        val file = File.createTempFile("fetchy-diagnostic-", ".json", directory)
        file.writeText(report(engine, jobId).toString(2))
        val uri = FileProvider.getUriForFile(context, "com.luverse.fetchy.share", file)
        val intent = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
            .apply { clipData = ClipData.newRawUri("Fetchy 진단", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        context.startActivity(Intent.createChooser(intent, "문제 진단 공유").apply {
            clipData = intent.clipData; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}
