package com.luverse.fetchy

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.android.FlutterActivityLaunchConfigs.BackgroundMode
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.*
import org.json.JSONObject
import java.util.UUID

open class MainActivity : FlutterActivity() {
    private lateinit var runtime: FetchyRuntime
    private var events: EventChannel.EventSink? = null
    private var pendingPicker: MethodChannel.Result? = null
    private var cookiePlatform: String? = null
    private var pickerMigrate = false
    private var analysisId: String? = null
    private var previewId: String? = null
    private val listener: (Map<String, Any?>) -> Unit = { events?.success(it) }
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        runtime = FetchyRuntime.get(this)
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, "com.luverse.fetchy/events").setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, sink: EventChannel.EventSink) { events = sink; runtime.addListener(listener) }
            override fun onCancel(arguments: Any?) { runtime.removeListener(listener); events = null }
        })
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "com.luverse.fetchy/native").setMethodCallHandler(::handle)
    }
    override fun onResume() {
        super.onResume()
        if (::runtime.isInitialized) runtime.io.execute {
            runtime.emit()
            if (runtime.hasPending()) runOnUiThread {
                try { FetchyRuntime.schedule(this) }
                catch (error: Exception) { runtime.io.execute { runtime.schedulingFailed(error.message ?: "백그라운드 시작 실패") } }
            }
        }
    }
    override fun onDestroy() {
        if (::runtime.isInitialized) {
            analysisId?.let { runtime.engine.cancel(it) }
            previewId?.let { runtime.engine.cancel(it) }
            runtime.removeListener(listener)
        }
        super.onDestroy()
    }
    private fun handle(call: MethodCall, result: MethodChannel.Result) {
        val args = jsonObject(call.arguments)
        if (runtime.storage.migration() != null && call.method in setOf("enqueue", "folderCreate", "folderMove", "folderRename", "folderDelete", "fileMove", "fileRename", "fileDelete")) {
            val failure = FailureKind.BUSY; result.error(failure.code, failure.message, mapOf("help" to failure.help)); return
        }
        when (call.method) {
            "cancelAnalysis" -> {
                analysisId?.let { runtime.engine.cancel(it) }
                previewId?.let { runtime.engine.cancel(it) }
                result.success(null); return
            }
            "openUrl" -> {
                val target = Uri.parse(args.optString("url"))
                if (target.scheme !in listOf("https", "http") || target.host.isNullOrBlank()) { result.error("url", "올바른 링크가 아닙니다.", null); return }
                try { startActivity(Intent(Intent.ACTION_VIEW, target)); result.success(null) }
                catch (_: android.content.ActivityNotFoundException) { result.error("open", "링크를 열 앱이 없습니다.", null) }
                return
            }
            "initialShare" -> { result.success(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()); return }
            "finishShare" -> { result.success(null); finish(); return }
            "chooseRoot" -> {
                if (pendingPicker != null) { result.error("busy", "다른 선택 화면이 열려 있습니다.", null); return }
                pendingPicker = result
                pickerMigrate = args.optBoolean("migrate")
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                if (Build.VERSION.SDK_INT >= 26) picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"))
                startActivityForResult(picker, 301); return
            }
            "cookiesImport" -> {
                if (pendingPicker != null) { result.error("busy", "다른 선택 화면이 열려 있습니다.", null); return }
                pendingPicker = result; cookiePlatform = args.getString("platform")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 302); return
            }
            "cookiesLogin" -> {
                startActivity(Intent(this, WebLoginActivity::class.java).putExtra("platform", args.getString("platform")))
                result.success(null); return
            }
        }
        val requestId = if (call.method in listOf("analyze", "preview")) "${call.method}-${UUID.randomUUID()}" else null
        if (requestId != null) {
            if (call.method == "preview") {
                previewId?.let { runtime.engine.cancel(it) }; previewId = requestId
            } else {
                analysisId?.let { runtime.engine.cancel(it) }; analysisId = requestId
            }
            runtime.engine.reserve(requestId)
        }
        (when (call.method) { "analyze" -> runtime.analyses; "preview" -> runtime.previews; "cookiesCheck" -> runtime.authChecks; else -> runtime.io }).execute {
            try {
                val value: Any? = when (call.method) {
                    "snapshot" -> runtime.snapshot()
                    "licenses" -> assets.list("licenses").orEmpty().sorted().joinToString("\n\n") { name ->
                        "$name\n" + assets.open("licenses/$name").bufferedReader().use { it.readText() }
                    }
                    "preview" -> runtime.engine.preview(args.getString("url"), requestId!!)?.toMap()
                    "analyze" -> runtime.engine.analyze(args.getString("url"), requestId!!, args.optBoolean("fullFormats")).also { analysis ->
                        val source = analysis.getString("sourceUrl")
                        val recent = JSONObject().put("id", source).put("sourceUrl", source).put("title", analysis.optString("title"))
                            .put("platform", analysis.optString("platform")).put("analyzedAt", System.currentTimeMillis())
                            .put("thumbnail", analysis.optJSONArray("assets")?.optJSONObject(0)?.optString("thumbnail") ?: "")
                        runtime.store.put("recentLinks", recent)
                        runtime.store.list("recentLinks").sortedByDescending { it.optLong("analyzedAt") }.drop(100).forEach { runtime.store.delete("recentLinks", it.getString("id")) }
                    }.toMap()
                    "enqueue" -> { runtime.enqueue(args); null }
                    "ensureRoot" -> { runtime.storage.ensureRoot(); null }
                    "migrationRetry" -> { runtime.retryMigration(); null }
                    "command" -> { runtime.command(args.getString("id"), args.getString("action")); null }
                    "folderCreate" -> runtime.storage.createFolder(args.getString("name"), parentId = args.optString("parentId").takeIf { it.isNotBlank() }, color = args.optLong("color", 0xff0a6bffL)).toMap()
                    "folderMove" -> { runtime.storage.moveFolder(args.getString("id"), args.optString("parentId").takeIf { it.isNotBlank() }); null }
                    "favorite" -> { runtime.storage.favorite(args.getString("kind"), args.getString("id"), args.getBoolean("value")); null }
                    "fileFolder" -> { runtime.storage.openFileFolder(args.getString("id")); null }
                    "fileInfo" -> MediaDetails(this, runtime.store).read(args.getString("id")).toMap()
                    "folderRename" -> { runtime.storage.renameFolder(args.getString("id"), args.getString("name")); null }
                    "folderDelete" -> { runtime.storage.deleteFolder(args.getString("id")); null }
                    "folderOpen" -> { runtime.storage.openFolder(args.getString("id")); null }
                    "fileOpen" -> { runtime.storage.openFile(args.getString("id")); null }
                    "fileShare" -> { runtime.storage.openFile(args.getString("id"), true); null }
                    "filePlay" -> { val id = args.getString("id"); check(runtime.store.get("files", id) != null); runOnUiThread { startActivity(Intent(this, PlaybackActivity::class.java).putExtra("id", id)) }; null }
                    "cookiesCheck" -> runtime.loginChecks.check(args.getString("platform")).toMap()
                    "diagnosticsShare" -> { Diagnostics(this, runtime.store).share(runtime.engine.info().toMap(), args.optString("id").takeIf { it.isNotBlank() }); null }
                    "fileDelete" -> { runtime.storage.deleteFile(args.getString("id")); null }
                    "fileRename" -> { runtime.storage.renameFile(args.getString("id"), args.getString("name")); null }
                    "fileMove" -> { runtime.storage.moveFile(args.getString("id"), args.optString("folderId").takeIf { it.isNotBlank() }); null }
                    "settingsSet" -> { validateSetting(args.getString("key"), args.get("value")); runtime.store.setting(args.getString("key"), args.get("value")); null }
                    "cookiesDelete" -> { runtime.cookies.delete(args.getString("platform")); null }
                    "cleanup" -> { runtime.cleanup(); null }
                    else -> { runOnUiThread { result.notImplemented() }; return@execute }
                }
                if (call.method != "preview") runtime.emit()
                runOnUiThread {
                    try {
                        if (call.method in setOf("enqueue", "command", "settingsSet", "migrationRetry") && runtime.hasPending()) {
                            FetchyRuntime.schedule(this)
                            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 303)
                        }
                        result.success(value)
                    } catch (error: Exception) {
                        runtime.io.execute { runtime.schedulingFailed(error.message ?: "백그라운드 시작 실패") }
                        // The operation is committed; returning an error would invite duplicate enqueues.
                        result.success(value)
                    }
                }
            } catch (error: Exception) { val failure = FailureKind.from(error); runOnUiThread { result.error(failure.code, failure.message, mapOf("help" to failure.help, "retryable" to failure.retryable)) } }
            finally {
                if (requestId != null) {
                    runtime.engine.discardReservation(requestId)
                    runOnUiThread {
                        if (analysisId == requestId) analysisId = null
                        if (previewId == requestId) previewId = null
                    }
                }
            }
        }
    }
    private fun validateSetting(key: String, value: Any) {
        when (key) {
            "concurrency" -> require((value as Number).toInt() in 1..4)
            "retries" -> require((value as Number).toInt() in 0..5)
            "quality" -> require((value as Number).toInt() in listOf(0, 360, 480, 720, 1080, 1440, 2160))
            "theme" -> require(value in listOf("system", "light", "dark"))
            "audioFormat" -> require(value in listOf("m4a", "mp3", "opus"))
            "videoFormat" -> require(value in listOf("mp4", "mkv", "webm"))
            "nameRule" -> require(value in listOf("title_id", "title", "date_title"))
            "duplicates" -> require(value in listOf("rename", "skip", "replace"))
            "fileDate" -> require(value in listOf("download", "source"))
            "onboarded", "platformFolders", "wifiOnly", "mobileData", "resume", "notifications", "audioMetadata", "audioCover" -> require(value is Boolean)
            else -> error("설정 항목이 올바르지 않습니다.")
        }
    }
    @Deprecated("Android activity result bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in listOf(301, 302)) return
        val result = pendingPicker ?: return
        pendingPicker = null
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) { result.success(null); return }
        val platform = cookiePlatform
        val migrate = pickerMigrate
        runtime.io.execute {
            try {
                if (requestCode == 301) {
                    if (migrate || runtime.storage.migration() != null) runtime.storage.prepareMigration(uri) else runtime.storage.chooseRoot(uri)
                } else runtime.cookies.import(platform ?: error("플랫폼이 없습니다."), uri)
                runtime.emit(); runOnUiThread {
                    if (runtime.hasPending()) runCatching { FetchyRuntime.schedule(this) }
                    result.success(null)
                }
            } catch (error: Exception) { val failure = FailureKind.from(error); runOnUiThread { result.error(failure.code, failure.message, mapOf("help" to failure.help)) } }
        }
    }
}
class ShareActivity : MainActivity() {
    override fun getInitialRoute() = "/share"
    override fun getBackgroundMode() = BackgroundMode.transparent
}
