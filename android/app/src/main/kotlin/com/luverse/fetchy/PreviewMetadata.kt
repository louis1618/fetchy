package com.luverse.fetchy

import android.text.Html
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

internal fun isInstagramStory(uri: URI): Boolean {
    val host = uri.host.orEmpty().lowercase()
    if (host != "instagram.com" && !host.endsWith(".instagram.com")) return false
    val match = Regex("^/stories/([A-Za-z0-9._]+)(?:/(\\d+))?/?$").matchEntire(uri.path.orEmpty()) ?: return false
    return match.groupValues[1] != "highlights" || match.groupValues[2].isNotEmpty()
}

/** Only known post URL aliases are collapsed. Arbitrary media query strings stay intact. */
internal fun canonicalMediaUrl(raw: String): String {
    val u = URI(raw.trim())
    require(u.scheme?.lowercase() in setOf("http", "https") && !u.host.isNullOrBlank() && u.rawUserInfo == null && u.port in listOf(-1, 80, 443)) {
        "Only HTTP(S) URLs without credentials are supported"
    }
    val host = u.host.orEmpty().lowercase()
    val query = u.rawQuery.orEmpty().split('&').associate {
        val p = it.split('=', limit = 2)
        URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p.getOrElse(1) { "" }, "UTF-8")
    }
    if (host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")) {
        val id = if (host == "youtu.be") u.path.trim('/') else if (u.path.trimEnd('/') == "/watch") query["v"]
            else Regex("^/(?:shorts|live|embed|v)/([^/]+)/?$").find(u.path)?.groupValues?.get(1)
        if (id != null && id.matches(Regex("[A-Za-z0-9_-]{11}"))) return "https://www.youtube.com/watch?v=$id"
    }
    if (host == "instagram.com" || host.endsWith(".instagram.com")) {
        if (isInstagramStory(u)) return "https://www.instagram.com${u.path.trimEnd('/')}/${u.rawQuery?.let { "?$it" }.orEmpty()}"
        Regex("^/(?:[^/]+/)?(?:p|reels?|tv)/([A-Za-z0-9_-]+)/?$").find(u.path)?.let {
            return "https://www.instagram.com/p/${it.groupValues[1]}/"
        }
    }
    if (host in setOf("x.com", "twitter.com", "www.x.com", "www.twitter.com", "mobile.twitter.com")) {
        Regex("/status(?:es)?/(\\d+)((?:/(?:video|photo)/\\d+)?)/?$").find(u.path)?.let {
            return "https://x.com/i/status/${it.groupValues[1]}${it.groupValues[2]}"
        }
    }
    if (host in setOf("tiktok.com", "www.tiktok.com", "m.tiktok.com", "tiktokv.com", "www.tiktokv.com")) {
        if (Regex("^/(?:@[^/]+/(?:video|photo)|share/video|embed)/\\d+/?$").matches(u.path))
            return "https://www.tiktok.com${u.path}${u.rawQuery?.let { "?$it" }.orEmpty()}"
    }
    if ((host == "reddit.com" || host.endsWith(".reddit.com")) && Regex("^/gallery/[A-Za-z0-9]+/?$").matches(u.path))
        return "https://www.reddit.com/comments/${u.path.trim('/').substringAfter('/')}/${u.rawQuery?.let { "?$it" }.orEmpty()}"
    if (host in setOf("vod.afreecatv.com", "play.afreecatv.com"))
        return "https://${host.replace("afreecatv.com", "sooplive.com")}${u.rawPath}${u.rawQuery?.let { "?$it" }.orEmpty()}"
    return raw.trim().substringBefore('#')
}

/** Only formats explicitly marked by our original-media extractors reach this check. */
internal fun originalPhotoUrl(platform: String, raw: String): String {
    val u = URI(raw)
    require(u.scheme == "https" && !u.host.isNullOrBlank() && u.rawUserInfo == null && u.port in listOf(-1, 443)) { "Invalid original image URL" }
    val domains = when (platform) {
        "Instagram" -> listOf("cdninstagram.com", "fbcdn.net")
        "TikTok" -> listOf("tiktokcdn.com", "tiktokcdn-us.com", "tiktokcdn-eu.com", "ibytedtos.com", "byteoversea.com", "muscdn.com", "tiktok.com")
        "Pinterest" -> listOf("pinimg.com")
        "Reddit" -> listOf("i.redd.it", "preview.redd.it")
        "Bluesky" -> {
            require(u.path == "/xrpc/com.atproto.sync.getBlob" && !u.rawQuery.isNullOrBlank()) { "Bluesky image is not an original blob" }
            emptyList()
        }
        else -> error("Unsupported original image platform")
    }
    require(domains.isEmpty() || domains.any { u.host == it || u.host.endsWith(".$it") }) { "Invalid original image host" }
    return raw
}

internal object PreviewMetadata {
    // Attribute order, single quotes and escaped entities occur in real platform pages.
    private val attributes = Regex("([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
    fun fromHtml(html: String): JSONObject? {
        val meta = mutableMapOf<String, String>()
        for (tag in Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)) {
            val values = attributes.findAll(tag.value).associate { m ->
                m.groupValues[1].lowercase() to decode(m.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty().trim())
            }
            val key = (values["property"] ?: values["name"]).orEmpty().lowercase()
            values["content"]?.takeIf { it.isNotBlank() }?.let { meta.putIfAbsent(key, it) }
        }
        val title = meta["og:title"] ?: meta["twitter:title"]
            ?: Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let(::decode)
        val image = safeImage(meta["og:image"] ?: meta["twitter:image"])
        if (title.isNullOrBlank() && image.isEmpty()) return null
        return JSONObject().put("title", title.orEmpty().take(500)).put("thumbnail", image)
    }
    fun safeImage(raw: String?): String = runCatching {
        val u = URI(raw.orEmpty())
        require(u.scheme == "https" && !u.host.isNullOrBlank() && u.rawUserInfo == null && u.port in listOf(-1, 443))
        u.toString()
    }.getOrDefault("")
    private fun decode(value: String) = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim()
}
