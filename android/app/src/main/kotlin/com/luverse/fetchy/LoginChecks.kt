package com.luverse.fetchy

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/** Status evidence only; response bodies and account identifiers are never persisted. */
internal class LoginChecks(private val vault: CookieVault, private val store: Store, private val engine: Engine) {
    fun states(savedCookies: List<JSONObject> = vault.list()): List<JSONObject> {
        val saved = savedCookies.associateBy { it.getString("platform") }
        val revision = vault.revision()
        return CookieVault.supportedPlatforms().map { platform ->
            val cached = store.get("loginChecks", platform)
            when {
                saved[platform] == null -> status(platform, "none")
                saved[platform]?.optInt("activeCount") == 0 -> status(platform, "expired")
                cached?.optString("revision") == revision && System.currentTimeMillis() - cached.optLong("checkedAt") < 300000 -> cached
                else -> status(platform, "unchecked")
            }
        }
    }
    fun check(platform: String): JSONObject {
        require(platform in CookieVault.supportedPlatforms())
        val revision = vault.revision()
        val exists = vault.list().any { it.getString("platform") == platform }
        val file = vault.temporary(platform, UUID.randomUUID().toString())
        val result = try {
            if (file == null) status(platform, if (exists) "expired" else "none") else {
                val rows = file.readLines().filter { it.isNotBlank() && (!it.startsWith('#') || it.startsWith("#HttpOnly_")) }
                    .map { it.removePrefix("#HttpOnly_").split('\t') }
                val url = when (platform) {
                    "youtube" -> "https://www.youtube.com/feed/history"
                    "instagram" -> "https://www.instagram.com/api/v1/accounts/current_user/?edit=true"
                    "x" -> "https://x.com/i/api/1.1/account/verify_credentials.json?skip_status=true"
                    "tiktok" -> "https://www.tiktok.com/passport/web/account/info/"
                    "reddit" -> "https://www.reddit.com/api/v1/me.json"
                    "twitch" -> "https://id.twitch.tv/oauth2/validate"
                    "bilibili" -> "https://api.bilibili.com/x/web-interface/nav"
                    "weibo" -> "https://weibo.com/ajax/config"
                    else -> CookieVault.loginUrl(platform)
                }
                val uri = engine.checkedUrl(url)
                require(uri.scheme == "https" && CookieVault.allowed(platform, uri.host.lowercase()))
                val scoped = rows.filter { row ->
                    val host = row[0].removePrefix(".")
                    (uri.host == host || (row[1] == "TRUE" && uri.host.endsWith(".$host"))) && uri.path.startsWith(row[2])
                }.associate { it[5] to it[6] }
                val connection = uri.toURL().openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false; connection.connectTimeout = 5000; connection.readTimeout = 5000
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
                    connection.setRequestProperty("Cookie", scoped.entries.joinToString("; ") { "${it.key}=${it.value}" })
                    if (platform == "instagram") {
                        connection.setRequestProperty("X-IG-App-ID", "936619743392459")
                        scoped["csrftoken"]?.let { connection.setRequestProperty("X-CSRFToken", it) }
                    }
                    if (platform == "x") {
                        // Public web client constant from the bundled yt-dlp Twitter extractor.
                        connection.setRequestProperty("Authorization", "Bearer AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA")
                        scoped["ct0"]?.let { connection.setRequestProperty("X-CSRF-Token", it) }
                    }
                    if (platform == "twitch") {
                        val token = rows.firstOrNull { it[5] == "auth-token" && CookieVault.allowed(platform, it[0].removePrefix(".")) }?.get(6)
                        if (token == null) return save(status(platform, "unknown"), revision)
                        connection.setRequestProperty("Authorization", "OAuth $token")
                    }
                    val code = connection.responseCode
                    val body = if (code == 200) connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16384); val deadline = System.nanoTime() + 10000000000L
                        while (true) { require(System.nanoTime() < deadline); val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= 1048576); output.write(buffer, 0, n) }
                        output.toString("UTF-8")
                    } else ""
                    status(platform, classify(platform, code, body, connection.getHeaderField("Location").orEmpty()))
                } finally { connection.disconnect() }
            }
        } catch (_: Exception) { status(platform, "unknown") }
        finally { file?.delete() }
        return save(result, revision)
    }
    private fun save(result: JSONObject, revision: String): JSONObject {
        result.put("revision", revision); store.put("loginChecks", result); return result
    }
    private fun status(platform: String, state: String) = JSONObject().put("id", platform).put("platform", platform)
        .put("state", state).put("checkedAt", System.currentTimeMillis()).put("message", when (state) {
            "valid" -> "로그인 확인됨"
            "invalid" -> "로그아웃 상태 · 쿠키를 다시 저장하세요"
            "expired" -> "쿠키 만료 · 다시 로그인하세요"
            "none" -> "저장된 쿠키 없음"
            "unchecked" -> "쿠키 저장됨 · 로그인 확인 필요"
            else -> "확인 불가 · 원본 서비스의 추가 인증 또는 응답 제한"
        })

    companion object {
        internal fun classify(platform: String, code: Int, body: String, redirect: String = ""): String {
            if (code == 401) return "invalid"
            if (code in setOf(301, 302, 303, 307, 308) && Regex("(?i)(/login|/signin|accounts.google.com)").containsMatchIn(redirect)) return "invalid"
            if (code != 200) return "unknown"
            val json = runCatching { JSONObject(body) }.getOrNull()
            fun positive(value: String?) = value?.toLongOrNull()?.let { it > 0 } == true
            return when (platform) {
                "youtube" -> when { Regex("\"LOGGED_IN\"\\s*:\\s*true").containsMatchIn(body) -> "valid"; Regex("\"LOGGED_IN\"\\s*:\\s*false").containsMatchIn(body) -> "invalid"; else -> "unknown" }
                "instagram" -> when { positive(json?.optJSONObject("user")?.optString("pk")) -> "valid"; json?.optBoolean("require_login") == true || json?.optString("message") == "login_required" -> "invalid"; else -> "unknown" }
                "x" -> if (positive(json?.optString("id_str"))) "valid" else "unknown"
                "tiktok" -> if (positive(json?.optJSONObject("data")?.optString("user_id"))) "valid" else "unknown"
                "reddit" -> if (json?.optString("name")?.isNotBlank() == true && json.optString("id").isNotBlank()) "valid" else "unknown"
                "twitch" -> if (positive(json?.optString("user_id"))) "valid" else "unknown"
                "bilibili" -> if (json?.optInt("code", -1) == 0 && json.optJSONObject("data")?.has("isLogin") == true) if (json.getJSONObject("data").getBoolean("isLogin")) "valid" else "invalid" else "unknown"
                "weibo" -> if (json?.optJSONObject("data")?.has("login") == true) if (json.getJSONObject("data").getBoolean("login")) "valid" else "invalid" else "unknown"
                else -> when { Regex("\"(?:isLoggedIn|isAuthenticated)\"\\s*:\\s*true").containsMatchIn(body) -> "valid"; Regex("\"(?:isLoggedIn|isAuthenticated)\"\\s*:\\s*false").containsMatchIn(body) -> "invalid"; else -> "unknown" }
            }
        }
    }
}
