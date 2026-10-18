package com.luverse.fetchy

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.webkit.*
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun xApiCookieRows(rows: List<String>): List<String> {
    val parsed = rows.map { it.removePrefix("#HttpOnly_").split('\t') }
    val covered = parsed.filter { it[0] == "api.x.com" || (it[0] == ".x.com" && it[1] == "TRUE") }.map { it[5] }.toSet()
    // Older WebView exports lost domain scope; only these X login cookies need the first-party API alias.
    return rows + parsed.filter { it[0].removePrefix(".") == "x.com" && it[2] == "/" && it[5] in setOf("auth_token", "ct0") && it[5] !in covered }
        .map { "#HttpOnly_api.x.com\tFALSE\t" + it.drop(2).joinToString("\t") }
}

/** Encrypted Netscape cookie vault. Disk APIs should run off the UI thread.
 * updatedAt is Unix milliseconds. Caller deletes temporary() files in finally;
 * startup may delete stale files under noBackupFilesDir/fetchy-cookie-temp.
 * WebView cookies are process-wide: delete clears ALL WebView sessions asynchronously,
 * but leaves other encrypted vaults intact. Extend REGISTRY in source for other sites.
 */
class CookieVault(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.noBackupFilesDir, "fetchy-cookies")
    private val temps = File(app.noBackupFilesDir, "fetchy-cookie-temp")

    /** Cache identity only; never exports cookie contents. */
    fun revision(): String = synchronized(LOCK) {
        REGISTRY.keys.joinToString("|") { id ->
            val file = File(directory, "$id.enc")
            val backup = File(directory, "$id.enc.bak")
            "$id:${file.lastModified()}:${file.length()}:${backup.lastModified()}"
        }
    }

    fun import(platform: String, uri: Uri) = synchronized(LOCK) {
        val id = platformId(platform)
        require(uri.scheme in listOf("content", "file")) { "Unsupported import URI" }
        val bytes = app.contentResolver.openInputStream(uri)?.use { bounded(it, MAX) }
            ?: throw IllegalArgumentException("Cannot open cookie file")
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        save(id, parse(id, decoder.decode(ByteBuffer.wrap(bytes)).toString()))
    }

    fun delete(platform: String) = synchronized(LOCK) {
        val id = platformId(platform)
        AtomicFile(File(directory, "$id.enc")).delete()
        temps.listFiles()?.filter { it.name.startsWith("$id-") }?.forEach {
            check(it.delete() || !it.exists()) { "Cannot delete temporary cookies" }
        }
        // CookieManager cannot enumerate original cookie domain/path scopes reliably.
        Handler(Looper.getMainLooper()).post {
            CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
        }
        Unit
    }

    fun list(): List<JSONObject> = synchronized(LOCK) {
        REGISTRY.keys.mapNotNull { id ->
            if (!exists(id)) null else {
                val rows = parse(id, decrypt(id))
                JSONObject().put("platform", id).put("count", rows.size)
                    .put("activeCount", rows.count { val expiry = it.removePrefix("#HttpOnly_").split('\t')[4].toLong(); expiry == 0L || expiry > System.currentTimeMillis() / 1000 })
                    .put("updatedAt", File(directory, "$id.enc").lastModified())
            }
        }
    }

    fun hasXSession(): Boolean = synchronized(LOCK) {
        exists("x") && parse("x", decrypt("x")).any {
            val fields = it.removePrefix("#HttpOnly_").split('\t')
            fields[5] == "auth_token" && fields[6].isNotBlank() &&
                (fields[4] == "0" || fields[4].toLong() > System.currentTimeMillis() / 1000)
        }
    }

    fun temporary(platform: String, jobId: String): File? = synchronized(LOCK) {
        val id = platformId(platform)
        require(jobId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid job ID" }
        if (!exists(id)) return@synchronized null
        val rows = parse(id, decrypt(id)).filter {
            val expiry = it.removePrefix("#HttpOnly_").split('\t')[4].toLong()
            expiry == 0L || expiry > System.currentTimeMillis() / 1000
        }
        if (rows.isEmpty()) return@synchronized null
        check(temps.isDirectory || temps.mkdirs()) { "Cannot create temporary directory" }
        val file = File.createTempFile("$id-$jobId-", ".txt", temps)
        try {
            check(file.setReadable(false, false) && file.setWritable(false, false) &&
                file.setReadable(true, true) && file.setWritable(true, true)) { "Cannot restrict cookie file" }
            file.outputStream().use { it.write(serialize(if (id == "x") xApiCookieRows(rows) else rows)) }
            file
        } catch (error: Exception) { file.delete(); throw error }
    }

    /** CookieManager returns native Cookie header pairs, including HttpOnly cookies,
     * without original scope, expiry or HttpOnly metadata. Export host-only secure session
     * cookies at /, conservatively marked HttpOnly; file import preserves original metadata.
     * Call on UI thread when used with WebView.
     */
    fun saveFromWebView(platform: String) = synchronized(LOCK) {
        val id = platformId(platform)
        val rows = linkedSetOf<String>()
        var totalBytes = 0
        REGISTRY.getValue(id).urls.forEach { url ->
            val host = Uri.parse(url).host ?: error("Invalid platform URL")
            val cookies = CookieManager.getInstance().getCookie(url) ?: return@forEach
            totalBytes += cookies.toByteArray(Charsets.UTF_8).size
            require(totalBytes <= MAX) { "Cookie file exceeds 2 MiB" }
            cookies.split(';').forEach { pair ->
                val equal = pair.indexOf('=')
                if (equal > 0) rows.add("#HttpOnly_$host\tFALSE\t/\tTRUE\t0\t${pair.substring(0, equal).trim()}\t${pair.substring(equal + 1).trim()}")
            }
        }
        save(id, parse(id, String(serialize(rows.toList()), Charsets.UTF_8)))
    }

    private fun exists(id: String) = File(directory, "$id.enc").exists() || File(directory, "$id.enc.bak").exists()
    private fun bounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "Cookie file exceeds size limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun parse(id: String, text: String): List<String> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX) { "Cookie file exceeds 2 MiB" }
        val rows = linkedSetOf<String>()
        text.removePrefix("\uFEFF").lineSequence().forEach { raw ->
            if (raw.isBlank() || (raw.startsWith('#') && !raw.startsWith("#HttpOnly_"))) return@forEach
            val fields = raw.removePrefix("#HttpOnly_").split('\t')
            require(fields.size == 7) { "Invalid Netscape cookie fields" }
            val domain = fields[0].lowercase(Locale.ROOT)
            val host = domain.removePrefix(".")
            require(host.length <= 253 && host.split('.').all {
                it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
            }) { "Invalid cookie domain" }
            require(fields[1] in listOf("TRUE", "FALSE") && fields[3] in listOf("TRUE", "FALSE")) { "Invalid cookie flags" }
            require(fields[2].startsWith('/') && fields[2].none { it.code < 32 || it.code == 127 }) { "Invalid cookie path" }
            require(fields[4].matches(Regex("[0-9]+")) && fields[4].toLongOrNull() != null) { "Invalid cookie expiry" }
            require(fields[5].isNotEmpty() && fields[5].all {
                it.code in 33..126 && it !in "()<>@,;:\\\"/[]?={}"
            }) { "Invalid cookie name" }
            require(fields[6].none { it.code < 32 || it.code == 127 }) { "Invalid cookie value" }
            if (allowed(id, host)) rows.add((if (raw.startsWith("#HttpOnly_")) "#HttpOnly_" else "") +
                (listOf(domain) + fields.drop(1)).joinToString("\t"))
        }
        return rows.toList()
    }
    private fun serialize(rows: List<String>) =
        ("# Netscape HTTP Cookie File\n" + rows.joinToString("\n", postfix = "\n")).toByteArray(Charsets.UTF_8)
    private fun save(id: String, rows: List<String>) {
        require(rows.isNotEmpty()) { "No allowed cookies found; import a Netscape cookies file" }
        val plain = serialize(rows)
        require(plain.size <= MAX) { "Cookie file exceeds 2 MiB" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create vault" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
        val atomic = AtomicFile(File(directory, "$id.enc"))
        val stream = atomic.startWrite()
        try { stream.write(encrypted); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    private fun decrypt(id: String): String {
        val encrypted = AtomicFile(File(directory, "$id.enc")).openRead().use { bounded(it, MAX + 64) }
        require(encrypted.size >= 29 && encrypted[0] == 1.toByte()) { "Invalid vault format" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(1, 13)))
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(encrypted.copyOfRange(13, encrypted.size)), Charsets.UTF_8)
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    companion object {
        private val LOCK = Any()
        private const val MAX = 2 * 1024 * 1024
        private const val KEY_ALIAS = "fetchy.cookie-vault.v1"
        private data class Platform(val domains: List<String>, val urls: List<String>)
        private val REGISTRY = linkedMapOf(
            "youtube" to Platform(listOf("youtube.com", "google.com"), listOf("https://www.youtube.com/", "https://youtube.com/", "https://accounts.google.com/", "https://www.google.com/")),
            "instagram" to Platform(listOf("instagram.com"), listOf("https://www.instagram.com/", "https://instagram.com/")),
            "tiktok" to Platform(listOf("tiktok.com"), listOf("https://www.tiktok.com/", "https://tiktok.com/")),
            "x" to Platform(listOf("x.com", "twitter.com"), listOf("https://x.com/", "https://api.x.com/", "https://twitter.com/")),
            "facebook" to Platform(listOf("facebook.com"), listOf("https://www.facebook.com/", "https://m.facebook.com/", "https://facebook.com/")),
            "threads" to Platform(listOf("threads.com", "threads.net", "instagram.com"), listOf("https://www.threads.com/", "https://www.threads.net/", "https://www.instagram.com/")),
            "reddit" to Platform(listOf("reddit.com"), listOf("https://www.reddit.com/", "https://old.reddit.com/", "https://reddit.com/")),
            "pinterest" to Platform(listOf("pinterest.com", "pinterest.co.kr", "pinterest.jp", "pinterest.co.uk", "pinterest.fr", "pinterest.de"), listOf("https://www.pinterest.com/", "https://www.pinterest.co.kr/")),
            "twitch" to Platform(listOf("twitch.tv"), listOf("https://www.twitch.tv/", "https://m.twitch.tv/", "https://twitch.tv/")),
            "vimeo" to Platform(listOf("vimeo.com"), listOf("https://vimeo.com/", "https://www.vimeo.com/")),
            "soundcloud" to Platform(listOf("soundcloud.com"), listOf("https://soundcloud.com/", "https://www.soundcloud.com/")),
            "bilibili" to Platform(listOf("bilibili.com", "bilibili.tv", "biliintl.com"), listOf("https://www.bilibili.com/", "https://www.bilibili.tv/")),
            "douyin" to Platform(listOf("douyin.com"), listOf("https://www.douyin.com/")),
            "weibo" to Platform(listOf("weibo.com", "weibo.cn"), listOf("https://weibo.com/", "https://www.weibo.com/", "https://m.weibo.cn/")),
            "naver" to Platform(listOf("naver.com"), listOf("https://nid.naver.com/", "https://www.naver.com/", "https://tv.naver.com/", "https://chzzk.naver.com/")),
            "soop" to Platform(listOf("sooplive.com", "afreecatv.com"), listOf("https://www.sooplive.com/", "https://login.sooplive.com/", "https://vod.sooplive.com/", "https://play.sooplive.com/")),
            "kakao" to Platform(listOf("kakao.com"), listOf("https://tv.kakao.com/", "https://accounts.kakao.com/", "https://story.kakao.com/")),
            "vk" to Platform(listOf("vk.com", "vk.ru", "vkvideo.ru"), listOf("https://vk.com/", "https://vk.ru/", "https://vkvideo.ru/")),
            "ok" to Platform(listOf("ok.ru", "odnoklassniki.ru"), listOf("https://ok.ru/", "https://www.ok.ru/")),
            "bandcamp" to Platform(listOf("bandcamp.com"), listOf("https://bandcamp.com/")),
            "mixcloud" to Platform(listOf("mixcloud.com"), listOf("https://www.mixcloud.com/"))
        )
        fun supportedPlatforms(): List<String> = REGISTRY.keys.toList()
        internal fun platformForUrl(url: String): String? {
            val host = java.net.URI(url).host.orEmpty().lowercase(Locale.ROOT)
            // Resolve by the site's own domains, never by a redirect/CDN or another site's vault.
            return REGISTRY.entries.firstOrNull { (id, p) ->
                p.domains.any { host == it || host.endsWith(".$it") } && !(id == "youtube" && (host == "google.com" || host.endsWith(".google.com"))) &&
                    !(id == "threads" && (host == "instagram.com" || host.endsWith(".instagram.com")))
            }?.key ?: when (host) {
                "youtu.be" -> "youtube"
                "fb.watch" -> "facebook"
                "pin.it" -> "pinterest"
                "snd.sc" -> "soundcloud"
                "b23.tv" -> "bilibili"
                "t.co" -> "x"
                "redd.it" -> "reddit"
                else -> null
            }
        }
        internal fun platformId(platform: String): String {
            val id = platform.lowercase(Locale.ROOT)
            require(REGISTRY.containsKey(id)) { "Unsupported cookie platform" }
            return id
        }
        internal fun loginUrl(platform: String) = REGISTRY.getValue(platformId(platform)).urls.first()
        internal fun allowed(platform: String, host: String): Boolean =
            REGISTRY.getValue(platformId(platform)).domains.any {
                host.lowercase(Locale.ROOT) == it || host.lowercase(Locale.ROOT).endsWith(".$it")
            }
    }
}

/** Launch Intent(context, WebLoginActivity::class.java).putExtra("platform", id).
 * Manifest owner registers .WebLoginActivity exported=false. RESULT_OK means saved;
 * caller refreshes list() in onResume. No JavaScript native bridge.
 */
class WebLoginActivity : Activity() {
    private var webView: WebView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val platform = try { CookieVault.platformId(intent.getStringExtra("platform") ?: "") }
        catch (_: IllegalArgumentException) { finish(); return }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = TextView(this).apply {
            text = "If this provider blocks WebView login, import a Netscape cookies file instead."
            setPadding(16, 16, 16, 16)
        }
        layout.addView(status)
        val controls = LinearLayout(this)
        val save = Button(this).apply { text = "Save cookies" }
        controls.addView(save, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Button(this).apply { text = "Close"; setOnClickListener { finish() } }, LinearLayout.LayoutParams(0, -2, 1f))
        layout.addView(controls)
        setContentView(layout)
        try {
            val view = WebView(this)
            webView = view
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
            fun permitted(url: String): Boolean {
                val uri = Uri.parse(url)
                return uri.scheme == "https" && uri.userInfo == null &&
                    (uri.port == -1 || uri.port == 443) && uri.host?.let { CookieVault.allowed(platform, it) } == true
            }
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !permitted(request.url.toString())
                @kotlin.Deprecated("Legacy WebView callback")
                override fun shouldOverrideUrlLoading(view: WebView, url: String) = !permitted(url)
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    status.text = "Secure login failed. Import a Netscape cookies file instead."
                }
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    if (!permitted(url)) { view.stopLoading(); status.text = "Navigation blocked. Use file import if login cannot continue." }
                }
            }
            layout.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
            view.loadUrl(CookieVault.loginUrl(platform))
            save.setOnClickListener {
                save.isEnabled = false
                try {
                    CookieVault(this).saveFromWebView(platform)
                    setResult(RESULT_OK)
                    finish()
                } catch (_: Exception) {
                    status.text = "Could not save cookies. Try logging in or import a Netscape cookies file."
                    save.isEnabled = true
                }
            }
        } catch (_: Exception) {
            save.isEnabled = false
            status.text = "WebView login is unavailable. Import a Netscape cookies file instead."
        }
    }
    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            (parent as? android.view.ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
        super.onDestroy()
    }
}
