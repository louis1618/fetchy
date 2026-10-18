package com.luverse.fetchy

import android.app.*
import android.app.job.*
import android.content.*
import android.net.*
import android.os.*
import android.util.Log
import org.json.*
import java.io.File
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

internal fun downloadNetworkAllowed(internet: Boolean, wifi: Boolean, cellular: Boolean, wifiOnly: Boolean, mobileData: Boolean): Boolean =
    downloadNetworkWaitReason(internet, wifi, cellular, wifiOnly, mobileData) == null

internal fun downloadNetworkWaitReason(internet: Boolean, wifi: Boolean, cellular: Boolean, wifiOnly: Boolean, mobileData: Boolean): String? = when {
    !internet -> "인터넷 연결을 기다리는 중"
    wifiOnly && !wifi -> "Wi-Fi 연결을 기다리는 중"
    !mobileData && cellular -> "모바일 데이터 사용이 꺼져 있어요 · Wi-Fi에 연결하세요"
    else -> null
}

internal fun notificationBuilder(context: Context, channel: String): Notification.Builder =
    if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channel) else Notification.Builder(context)

internal fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { key -> jsonValue(opt(key)) }
internal fun jsonValue(value: Any?): Any? = when (value) {
    is JSONObject -> value.toMap()
    is JSONArray -> (0 until value.length()).map { jsonValue(value.opt(it)) }
    JSONObject.NULL -> null
    else -> value
}
internal fun jsonObject(value: Any?): JSONObject = when (value) {
    is Map<*, *> -> JSONObject(value)
    is JSONObject -> value
    else -> JSONObject()
}

/** Native state is the single source of truth, including while Flutter is absent. */
class FetchyRuntime private constructor(val context: Context) {
    val store = Store(context)
    val cookies = CookieVault(context)
    val storage = Storage(context, store)
    val engine = Engine(context, cookies)
    internal val loginChecks = LoginChecks(cookies, store, engine)
    internal val authChecks = Executors.newSingleThreadExecutor()
    private val workers = Executors.newFixedThreadPool(4)
    val io = Executors.newSingleThreadExecutor()
    val analyses = Executors.newSingleThreadExecutor()
    val previews = Executors.newFixedThreadPool(2)
    private val clock = Executors.newSingleThreadScheduledExecutor()
    private val active = ConcurrentHashMap<String, Boolean>()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Map<String, Any?>) -> Unit>()
    @Volatile private var pumping = false
    private val migrating = AtomicBoolean(false)
    @Volatile private var migrationThread: Thread? = null
    private var executionOwner: Any? = null
    private val emitScheduled = AtomicBoolean(false)
    private val recovered = CountDownLatch(1)
    @Volatile private var engineStatus: Map<String, Any?> = mapOf("status" to "initializing", "engine" to "local")

    init {
        if (!store.settings().has("onboarded")) store.setting("onboarded", store.settings().optString("rootUri").isNotEmpty() || store.list("jobs").isNotEmpty())
        if (Build.VERSION.SDK_INT >= 26) {
            notificationManager().createNotificationChannel(NotificationChannel("downloads", "다운로드 진행", NotificationManager.IMPORTANCE_LOW))
            notificationManager().createNotificationChannel(NotificationChannel("results", "완료 및 실패", NotificationManager.IMPORTANCE_DEFAULT))
        }
        io.execute {
            // Plaintext cookie exports never survive a process restart.
            File(context.noBackupFilesDir, "fetchy-cookie-temp").deleteRecursively()
            store.list("jobs").forEach {
                if (it.optString("state") in setOf("analyzing", "running", "downloading", "merging", "converting", "processing", "verifying", "ready", "saving")) {
                    it.put("state", "queued").put("error", "중단된 작업 복구 · 이어받기 대기")
                    store.put("jobs", it)
                }
            }
            try { storage.ensureRoot(); storage.recover() } catch (_: Exception) { /* Storage failures are surfaced on the next file operation. */ }
            finally { recovered.countDown() }
            emit()
        }
        workers.execute {
            engineStatus = runCatching { engine.info().toMap() }.getOrElse { mapOf("status" to "unavailable", "error" to "기기 내 엔진 초기화 실패") }
            emit()
        }
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { workers.execute { tick() } }
            override fun onLost(network: Network) { workers.execute { tick() } }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { workers.execute { tick() } }
        })
        clock.scheduleWithFixedDelay({ if (pumping) tickSafely(); if (pumping) emit() }, 1, 1, TimeUnit.SECONDS)
    }

    fun addListener(listener: (Map<String, Any?>) -> Unit) { listeners.add(listener); io.execute { emit() } }
    fun removeListener(listener: (Map<String, Any?>) -> Unit) { listeners.remove(listener) }
    fun snapshot(): Map<String, Any?> {
        val settings = store.settings()
        val savedCookies = cookies.list()
        val networkWait = networkWaitReason(settings)
        return mapOf(
            "jobs" to store.list("jobs").sortedByDescending { it.optLong("createdAt") }.map { job ->
                job.toMap() + if (job.optString("state") == "queued") {
                    val retrySeconds = ((job.optLong("retryAt") - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
                    mapOf("queueReason" to when {
                        networkWait != null -> networkWait
                        retrySeconds > 0 -> "자동 재시도까지 ${retrySeconds}초"
                        active.size >= settings.optInt("concurrency", 2).coerceIn(1, 4) -> "다른 다운로드 완료를 기다리는 중"
                        else -> "다운로드 시작 대기"
                    })
                } else emptyMap()
            },
            "folders" to store.list("folders").map { it.toMap() },
            "files" to store.list("files").map { it.toMap() },
            "settings" to settings.toMap(),
            "storageMigration" to (storage.migration()?.let { op -> JSONObject().apply { for (key in listOf("state", "destinationName", "done", "total", "missing", "error", "errorHelp")) if (op.has(key)) put(key, op.get(key)) }.toMap() } ?: emptyMap<String, Any?>()),
            "cookies" to savedCookies.map { it.toMap() },
            "loginStates" to loginChecks.states(savedCookies).map { it.toMap() },
            "cookiePlatforms" to CookieVault.supportedPlatforms(),
            "recentLinks" to store.list("recentLinks").sortedByDescending { it.optLong("analyzedAt") }.map { it.toMap() },
            "engine" to engineStatus
        )
    }
    fun emit() {
        if (!emitScheduled.compareAndSet(false, true)) return
        // Coalesce progress without dropping the final state when pumping stops.
        clock.schedule({
            emitScheduled.set(false)
            val value = snapshot()
            main.post { listeners.forEach { it(value) } }
        }, 350, TimeUnit.MILLISECONDS)
    }

    @Synchronized fun enqueue(args: JSONObject) {
        check(storage.migration() == null) { "Storage migration is pending" }
        storage.ensureRoot()
        val videoFormat = args.optString("videoFormat", store.settings().optString("videoFormat", "mp4"))
        require(videoFormat in listOf("mp4", "mkv", "webm")) { "지원하지 않는 영상 저장 형식입니다." }
        val analysis = args.getJSONObject("analysis")
        val assets = analysis.getJSONArray("assets")
        val selections = args.getJSONArray("selections")
        require(selections.length() in 1..100) { "한 번에 1~100개 항목을 선택해주세요." }
        val planned = mutableListOf<JSONObject>()
        for (i in 0 until selections.length()) {
            val selection = selections.getJSONObject(i)
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.first { it.getString("id") == selection.getString("assetId") }
            val formats = asset.getJSONArray("formats")
            val format = (0 until formats.length()).map { formats.getJSONObject(it) }.first { it.getString("id") == selection.getString("formatId") }
            val id = UUID.randomUUID().toString()
            val folder = storage.folderFor(analysis.getString("platform"), args.optString("folderId").takeIf { it.isNotBlank() })
            val job = JSONObject().put("id", id).put("sourceUrl", analysis.getString("sourceUrl"))
                .put("platform", analysis.getString("platform")).put("title", asset.optString("title", analysis.optString("title", "media")))
                .put("assetId", asset.getString("id")).put("asset", asset).put("selection", format)
                .put("formatId", format.getString("id")).put("kind", format.getString("kind")).put("ext", format.getString("ext"))
                .put("settings", store.settings().put("videoFormat", videoFormat)).put("folderId", folder?.optString("id") ?: "")
                .put("rootUri", store.settings().optString("rootUri"))
                .put("state", "queued").put("progress", 0).put("bytes", 0L).put("totalBytes", format.optLong("sizeBytes", 0L))
                .put("createdAt", System.currentTimeMillis()).put("attempts", 0)
            args.optJSONObject("subtitle")?.let { option ->
                require(format.optString("kind") != "image" && option.optString("format") in setOf("srt", "vtt") && option.opt("automatic") is Boolean && option.optString("language").matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Invalid subtitle selection" }
                val captions = asset.optJSONArray("subtitles") ?: JSONArray()
                require((0 until captions.length()).map { captions.getJSONObject(it) }.any {
                    it.optString("language") == option.optString("language") && it.optBoolean("automatic") == option.optBoolean("automatic")
                }) { "Selected subtitle language unavailable" }
                job.put("subtitle", JSONObject(option.toString()))
            }
            args.optJSONObject("audioTags")?.let { tags ->
                require(tags.keys().asSequence().all { it in setOf("title", "artist", "album") } && tags.keys().asSequence().all { key ->
                    tags.opt(key) is String && tags.getString(key).length <= 240 && tags.getString(key).none { it.code < 32 }
                }) { "Invalid audio tags" }
                if (format.optString("kind") == "audio") job.put("audioTags", JSONObject(tags.toString()))
            }
            planned.add(job)
        }
        store.putMany("jobs", planned)
        emit()
    }

    @Synchronized fun command(id: String, action: String) {
        val job = store.get("jobs", id) ?: error("작업이 없습니다.")
        val state = job.optString("state")
        when (action) {
            "pause" -> { require(state !in terminal); job.put("state", "paused"); store.put("jobs", job); engine.cancel(id) }
            "cancel" -> { require(state != "completed"); job.put("state", "canceled"); store.put("jobs", job); engine.cancel(id); if (!active.containsKey(id)) workDir(id).deleteRecursively() }
            "resume", "retry" -> {
                require(state in setOf("paused", "failed", "canceled", "queued"))
                require(!active.containsKey(id)) { "중단 처리 중입니다. 잠시 후 다시 시도해주세요." }
                job.put("state", "queued").put("error", "").put("errorCode", "").put("errorHelp", "").put("attempts", 0).put("retryAt", 0L)
                store.put("jobs", job)
            }
            "deleteRecord" -> { require(state in terminal || state == "paused"); require(!active.containsKey(id)); store.delete("jobs", id); val work = workDir(id); if (!hasPendingPublication(work)) work.deleteRecursively() }
            else -> error("지원하지 않는 명령입니다.")
        }
        emit()
    }

    @Synchronized fun isExecuting(): Boolean = pumping
    @Synchronized fun start(owner: Any = this): Boolean {
        if (executionOwner != null && executionOwner !== owner) return false
        executionOwner = owner
        pumping = true
        Log.i("FetchyQueue", "runner_start owner=${owner.javaClass.simpleName}")
        workers.execute { tickSafely() }
        return true
    }
    @Synchronized fun stop(reason: String = "시스템 중지 · 이어받기 대기", owner: Any? = null) {
        if (owner != null && executionOwner !== owner) return
        pumping = false
        executionOwner = null
        migrationThread?.interrupt()
        synchronized(this) {
            active.keys.forEach { id ->
                val job = store.get("jobs", id) ?: return@forEach
                if (job.optString("state") !in terminal && job.optString("state") != "paused") {
                    job.put("state", "queued").put("error", reason); store.put("jobs", job)
                }
                engine.cancel(id)
            }
        }
        emit()
    }
    @Synchronized fun finishIfIdle(owner: Any? = null): Boolean {
        if (owner != null && executionOwner !== owner) return true
        if (hasPending()) return false
        pumping = false
        executionOwner = null
        return true
    }
    @Synchronized fun schedulingFailed(message: String) {
        Log.e("FetchyQueue", "schedule_failed ${message.replace(Regex("https?://\\S+"), "[URL]").take(250)}")
        store.list("jobs").filter { it.optString("state") == "queued" }.forEach {
            it.put("error", "대기열에 저장됨 · $message · 앱에서 재개해주세요"); store.put("jobs", it)
        }
        emit()
    }
    fun hasPending(): Boolean = active.isNotEmpty() || store.list("jobs").any { it.optString("state") == "queued" } || storage.migration()?.optString("state") in setOf("queued", "running")
    internal fun retryMigration() {
        val op = storage.migration() ?: error("No storage migration")
        op.put("state", "queued").remove("error"); store.put("folderOps", op); emit()
    }
    private fun runMigration() {
        if (storage.migration()?.optString("state") !in setOf("queued", "running") || !migrating.compareAndSet(false, true)) return
        io.execute {
            synchronized(this) {
                if (!pumping) { migrating.set(false); return@execute }
                migrationThread = Thread.currentThread()
            }
            val wake = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Fetchy:migration")
            try {
                wake.acquire(6 * 60 * 60 * 1000L)
                storage.migrate { emit() }
            } catch (error: Exception) {
                storage.migration()?.let { op ->
                    val failure = FailureKind.from(error)
                    op.put("state", if (failure == FailureKind.CANCELED || Thread.currentThread().isInterrupted) "queued" else "failed")
                        .put("error", failure.message).put("errorHelp", failure.help); store.put("folderOps", op)
                }
            } finally {
                if (wake.isHeld) wake.release(); Thread.interrupted(); migrationThread = null; migrating.set(false); emit()
            }
        }
    }
    fun notification(): Notification {
        val count = active.size
        val migration = storage.migration()
        return notificationBuilder(context, "downloads").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (migration != null) "Fetchy · 저장 위치 이전" else "Fetchy · 다운로드 ${count}개 진행 중")
            .setContentText(if (migration != null) "${migration.optInt("done")} / ${migration.optInt("total")}개 처리" else if (count == 0) "네트워크 또는 재시도 대기 중" else "알림을 눌러 진행 상황을 확인하세요")
            .setContentIntent(openIntent()).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "앱 열기", openIntent()).build()).build()
    }
    private fun openIntent(): PendingIntent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notificationManager() = context.getSystemService(NotificationManager::class.java)
    private fun workDir(id: String) = File(context.filesDir, "work/$id").apply { mkdirs() }
    private fun networkWaitReason(settings: JSONObject): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val cap = cm.getNetworkCapabilities(cm.activeNetwork)
        return downloadNetworkWaitReason(
            cap?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            cap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            cap?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            settings.optBoolean("wifiOnly"), settings.optBoolean("mobileData", true)
        )
    }
    private fun tickSafely() {
        try { tick() }
        catch (error: Exception) { schedulingFailed("실행기 오류: ${error.javaClass.simpleName}") }
    }
    @Synchronized private fun tick() {
        if (!pumping || recovered.count > 0) return
        runMigration()
        if (storage.migration() != null) return
        val settings = store.settings()
        if (networkWaitReason(settings) != null) {
            active.keys.forEach { id ->
                val job = store.get("jobs", id) ?: return@forEach
                if (job.optString("state") in setOf("analyzing", "running", "downloading")) {
                    job.put("state", "queued").put("error", "허용된 네트워크 대기"); store.put("jobs", job); engine.cancel(id)
                }
            }
            return
        }
        val limit = settings.optInt("concurrency", 2).coerceIn(1, 4)
        store.list("jobs").sortedBy { it.optLong("createdAt") }.filter {
            it.optString("state") == "queued" && it.optLong("retryAt") <= System.currentTimeMillis() && !active.containsKey(it.getString("id"))
        }.take((limit - active.size).coerceAtLeast(0)).forEach { job ->
            val id = job.getString("id")
            try {
                engine.reserve(id)
                active[id] = true
                job.put("state", "running").put("error", "").put("errorCode", "").put("errorHelp", ""); store.put("jobs", job)
                workers.execute { runJob(id) }
            } catch (error: Exception) {
                active.remove(id); engine.discardReservation(id)
                job.put("state", "failed").put("error", "다운로드 실행 실패: ${error.message?.take(160) ?: error.javaClass.simpleName}")
                store.put("jobs", job); resultNotification(job); emit()
            }
        }
    }
    private fun runJob(id: String) {
        val work = workDir(id)
        val wakeLock = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Fetchy:download").apply { setReferenceCounted(false) }
        try {
            val original = store.get("jobs", id) ?: return
            if (original.optString("state") in setOf("paused", "canceled", "queued")) return
            // Bounded lease prevents a leaked lock while transfers/FFmpeg run with the screen off.
            wakeLock.acquire(6 * 60 * 60 * 1000L)
            var progressAt = 0L
            var progressStage = ""
            val output = engine.download(original, work) { stage, progress, bytes, total ->
                val now = SystemClock.elapsedRealtime()
                // HTTP emits for every 64 KiB. Persist at UI cadence instead of committing
                // a Room transaction per network chunk. Stage transitions remain immediate.
                if (stage == progressStage && now - progressAt < 250) return@download
                progressAt = now; progressStage = stage
                synchronized(this) {
                    val job = store.get("jobs", id) ?: return@synchronized
                    if (job.optString("state") in setOf("paused", "canceled", "queued")) return@synchronized
                    job.put("state", stage).put("progress", progress).put("bytes", bytes).put("totalBytes", total)
                    store.put("jobs", job)
                }
                emit()
            }
            synchronized(this) {
                val job = store.get("jobs", id) ?: return
                if (job.optString("state") in setOf("paused", "canceled", "queued")) return
                original.optJSONObject("selection")?.optJSONObject("mediaMetadata")?.let { job.put("mediaMetadata", it) }
                original.optJSONArray("warnings")?.let { job.put("warnings", it) }
                val settings = job.getJSONObject("settings")
                val sourceDate = original.getJSONObject("asset").optLong("sourceTimestamp").takeIf { it > 0 }
                if (job.optLong("mediaDate") <= 0) job.put("mediaDate", selectedMediaDate(settings.optString("fileDate", "download"), sourceDate, System.currentTimeMillis()))
                if (settings.optString("fileDate") == "source" && sourceDate == null) {
                    val warnings = job.optJSONArray("warnings") ?: JSONArray().also { job.put("warnings", it) }
                    warnings.put("게시 시각을 확인할 수 없어 현재 시각으로 저장했습니다.")
                }
                job.put("state", "saving"); store.put("jobs", job)
                val title = StorageNames.title(job.optString("title", "media").replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), "_"))
                val stableId = job.getString("assetId").replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
                val base = when (settings.optString("nameRule", "title_id")) {
                    "title" -> title
                    "date_title" -> "${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(java.util.Date(job.getLong("mediaDate")))}_$title"
                    else -> "${title}_$stableId"
                }
                val file = storage.publish(id, output, "$base.${output.extension}", engine.mime(output, job.getString("kind")), job.optString("folderId").takeIf { it.isNotBlank() })
                if (file.optBoolean("dateApplied", true).not() && settings.optString("fileDate") == "source") {
                    val warnings = job.optJSONArray("warnings") ?: JSONArray().also { job.put("warnings", it) }
                    warnings.put("저장소에서 파일 날짜를 변경하지 못했습니다. 앱에는 선택한 날짜를 보관합니다.")
                }
                val subtitles = original.optJSONArray("sidecars") ?: JSONArray()
                for (i in 0 until subtitles.length()) {
                    val extra = subtitles.getJSONObject(i); val source = File(extra.getString("path")).canonicalFile
                    require(source.parentFile == work.canonicalFile && source.isFile) { "Invalid subtitle output path" }
                    val language = extra.getString("language"); val ext = extra.getString("ext")
                    try {
                    storage.publish("$id-sub-$language", source, "${StorageNames.title(file.getString("name").substringBeforeLast('.'))}.$language.$ext",
                        if (ext == "srt") "application/x-subrip" else "text/vtt", file.optString("folderId").takeIf { !file.isNull("folderId") && it.isNotBlank() }, file.getString("rootUri"),
                        JSONObject().put("subtitleFor", id).put("language", language).put("automatic", extra.getBoolean("automatic")).put("mediaDate", file.optLong("mediaDate")))
                    } catch (error: Exception) {
                        val warnings = job.optJSONArray("warnings") ?: JSONArray().also { job.put("warnings", it) }
                        warnings.put("자막 파일 저장 실패 · ${FailureKind.from(error).message}")
                    }
                }
                job.optJSONArray("warnings")?.let { file.put("warnings", it); store.put("files", file) }
                job.put("state", "completed").put("progress", 100).put("fileName", file.getString("name")).put("contentUri", file.getString("uri"))
                    .put("error", "").put("errorCode", "").put("errorHelp", "").put("bytes", output.length()).put("completedAt", System.currentTimeMillis())
                store.put("jobs", job)
                if (!hasPendingPublication(work)) work.deleteRecursively()
                resultNotification(job)
            }
        } catch (error: Exception) {
            synchronized(this) {
                val job = store.get("jobs", id) ?: return
                if (job.optString("state") !in setOf("paused", "canceled", "queued")) {
                    val attempts = job.optInt("attempts") + 1
                    val failure = FailureKind.from(error)
                    val message = failure.message
                    job.put("errorCode", failure.code).put("errorHelp", failure.help)
                    val retryable = failure.retryable
                    if (retryable && attempts <= store.settings().optInt("retries", 3)) {
                        job.put("state", "queued").put("retryAt", System.currentTimeMillis() + 5000L * (1L shl attempts.coerceAtMost(6)))
                    } else { job.put("state", "failed"); resultNotification(job.put("error", message)) }
                    job.put("attempts", attempts).put("error", message); store.put("jobs", job)
                }
            }
        } finally {
            if (store.get("jobs", id)?.optString("state") == "canceled") work.deleteRecursively()
            if (wakeLock.isHeld) wakeLock.release()
            engine.discardReservation(id)
            active.remove(id); emit()
        }
    }
    private fun resultNotification(job: JSONObject) {
        if (!store.settings().optBoolean("notifications", true)) return
        val n = notificationBuilder(context, "results").setSmallIcon(R.drawable.ic_notification).setContentTitle(if (job.optString("state") == "completed") "다운로드 완료" else "다운로드 실패")
            .setContentText(job.optString("title")).setContentIntent(openIntent()).setAutoCancel(true).build()
        runCatching { notificationManager().notify(job.getString("id").hashCode(), n) }
    }
    fun cleanup() {
        engine.clearAnalysisCache()
        val live = store.list("jobs").filter { it.optString("state") !in terminal }.map { it.getString("id") }.toSet()
        File(context.filesDir, "work").listFiles()?.filter { it.name !in live && !hasPendingPublication(it) }?.forEach { it.deleteRecursively() }
        File(context.cacheDir, "previews").deleteRecursively()
        // Cookie exports belong to in-flight analyses too; each engine request removes its own export.
    }
    private fun hasPendingPublication(work: File): Boolean = store.list("folderOps").any {
        it.optString("type") == "publish" && File(it.optString("source")).canonicalPath.startsWith(work.canonicalPath + File.separator)
    }
    companion object {
        val terminal = setOf("completed", "failed", "canceled")
        @Volatile private var instance: FetchyRuntime? = null
        fun get(context: Context): FetchyRuntime = instance ?: synchronized(this) { instance ?: FetchyRuntime(context.applicationContext).also { instance = it } }
        fun schedule(context: Context) {
            val runtime = get(context)
            if (!runtime.hasPending()) return
            if (runtime.isExecuting()) { runtime.emit(); return }
            var uidtScheduled = false
            val failures = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 34) {
                val settings = runtime.store.settings()
                // Match the user's network policy, including VPN-backed default networks.
                val request = NetworkRequest.Builder().clearCapabilities().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).apply {
                    if (settings.optBoolean("wifiOnly")) addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    if (!settings.optBoolean("wifiOnly") && !settings.optBoolean("mobileData", true)) {
                        addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    }
                }.build()
                val job = JobInfo.Builder(100, ComponentName(context, TransferJobService::class.java)).setUserInitiated(true).setRequiredNetwork(request).build()
                try {
                    val scheduler = context.getSystemService(JobScheduler::class.java)
                    val previous = scheduler.getPendingJob(100)
                    uidtScheduled = previous?.requiredNetwork == request ||
                        scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
                } catch (error: Exception) { failures.add("UIDT: ${error.javaClass.simpleName}") }
            }
            // Like Morit's transfer runner, a visible user action also starts an immediate service.
            // The runtime owner prevents the service and UIDT job from executing the queue twice.
            if (Build.VERSION.SDK_INT < 34 || runtime.networkWaitReason(runtime.store.settings()) == null) {
                val intent = Intent(context, TransferService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
                    Log.i("FetchyQueue", "scheduled immediate_service uidt=$uidtScheduled")
                    return
                } catch (error: Exception) { failures.add("서비스: ${error.javaClass.simpleName}") }
            }
            if (uidtScheduled) { Log.i("FetchyQueue", "scheduled uidt"); return }
            error("Android 다운로드 실행을 시작할 수 없습니다 (${failures.joinToString()}). 앱을 열어 다시 시작해주세요.")
        }
    }
}

class TransferService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var runtime: FetchyRuntime
    private val update = object : Runnable {
        override fun run() {
            if (runtime.finishIfIdle(this@TransferService)) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
            getSystemService(NotificationManager::class.java).notify(100, runtime.notification())
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate() { super.onCreate(); runtime = FetchyRuntime.get(this) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(100, runtime.notification())
        if (!runtime.start(this)) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId); return START_NOT_STICKY }
        handler.removeCallbacks(update); handler.post(update); return START_STICKY
    }
    override fun onDestroy() { handler.removeCallbacks(update); runtime.stop(owner = this); super.onDestroy() }
    override fun onBind(intent: Intent?) = null
    override fun onTimeout(startId: Int, fgsType: Int) { runtime.stop("백그라운드 실행 제한 · 앱에서 재개해주세요", this); stopSelfResult(startId) }
}

class TransferJobService : JobService() {
    private val handler = Handler(Looper.getMainLooper())
    private var update: Runnable? = null
    private var execution: JobParameters? = null
    override fun onStartJob(params: JobParameters): Boolean {
        val runtime = FetchyRuntime.get(this)
        if (!runtime.start(params)) return false
        if (Build.VERSION.SDK_INT >= 34) setNotification(params, 102, runtime.notification(), JOB_END_NOTIFICATION_POLICY_REMOVE)
        update?.let { handler.removeCallbacks(it) }
        execution = params
        update = object : Runnable {
            override fun run() {
                if (execution !== params) return
                if (runtime.finishIfIdle(params)) { execution = null; jobFinished(params, false); update = null; return }
                getSystemService(NotificationManager::class.java).notify(102, runtime.notification())
                handler.postDelayed(this, 1000)
            }
        }.also { handler.post(it) }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        if (execution === params) { update?.let { handler.removeCallbacks(it) }; update = null; execution = null }
        Log.w("FetchyQueue", "uidt_stopped reason=${if (Build.VERSION.SDK_INT >= 31) params.stopReason else -1}")
        FetchyRuntime.get(this).stop("Android가 다운로드를 중지함 · 앱에서 재개해주세요", params)
        return true
    }
}
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Modern Android requires user interaction before starting long transfer work.
        val pending = goAsync()
        val runtime = FetchyRuntime.get(context)
        runtime.io.execute {
            try {
                if (runtime.hasPending()) {
                    val n = notificationBuilder(context, "results").setSmallIcon(R.drawable.ic_notification).setContentTitle("Fetchy 다운로드 복구")
                        .setContentText("앱을 열어 중단된 다운로드를 재개하세요")
                        .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).setAutoCancel(true).build()
                    context.getSystemService(NotificationManager::class.java).notify(101, n)
                }
            } finally { pending.finish() }
        }
    }
}
