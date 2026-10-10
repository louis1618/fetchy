package com.luverse.fetchy

import java.net.URI
import java.net.URLDecoder

private data class MediaPlatform(val label: String, val hosts: List<String>, val paths: List<String>, val short: List<String> = emptyList())

// Single-post routes only. Multiple assets in one post remain selectable.
private val mediaPlatforms = listOf(
    MediaPlatform("YouTube", listOf("youtube.com", "youtu.be"), listOf("^/watch/?$", "^/(shorts|live|embed|v)/[^/]+/?$"), listOf("youtu.be")),
    MediaPlatform("Instagram", listOf("instagram.com"), listOf("^/(?:[^/]+/)?(p|reel|reels|tv)/[^/]+/?$", "^/share/(p|reel)/[^/]+/?$")),
    MediaPlatform("Facebook", listOf("facebook.com", "fb.watch"), listOf("^/(watch|reel|share|videos?)(/|$)", "^/[^/]+/(videos|posts)/", "^/(video/video|video|photo|story|permalink)\\.php$", "^/plugins/video\\.php$"), listOf("fb.watch")),
    MediaPlatform("Threads", listOf("threads.com", "threads.net"), listOf("^/@[^/]+/post/[^/]+/?$", "^/t/[^/]+/?$")),
    MediaPlatform("TikTok", listOf("tiktok.com", "tiktokv.com"), listOf("^/@[^/]+/(video|photo)/\\d+/?$", "^/(share/video|embed)/\\d+/?$", "^/t/[^/]+/?$"), listOf("vm.tiktok.com", "vt.tiktok.com")),
    MediaPlatform("X (Twitter)", listOf("x.com", "twitter.com", "t.co"), listOf("^/(?:[^/]+|i/web)/status(?:es)?/\\d+(?:/(?:video|photo)/\\d+)?/?$", "^/statuses/\\d+/?$", "^/i/(spaces|broadcasts|events)/[A-Za-z0-9]+/?$", "^/i/(?:cards/tfw/v1|videos(?:/tweet)?)/\\d+/?$"), listOf("t.co")),
    MediaPlatform("Bluesky", listOf("bsky.app", "main.bsky.dev"), listOf("^/profile/[^/]+/post/[^/]+/?$")),
    MediaPlatform("Reddit", listOf("reddit.com", "redditmedia.com", "redd.it"), listOf("^/((r|user)/[^/]+/)?comments/[^/]+(/|$)", "^/gallery/[A-Za-z0-9]+/?$", "^/r/[^/]+/s/[^/]+/?$"), listOf("redd.it")),
    MediaPlatform("Pinterest", listOf("pinterest.com", "pinterest.co.kr", "pinterest.jp", "pinterest.co.uk", "pinterest.fr", "pinterest.de", "pin.it"), listOf("^/pin/[^/]+/?$"), listOf("pin.it")),
    MediaPlatform("Snapchat", listOf("snapchat.com"), listOf("^/(spotlight|t|p)/[^/]+(/|$)")),
    MediaPlatform("LinkedIn", listOf("linkedin.com", "lnkd.in"), listOf("^/(posts|feed/update)/.+"), listOf("lnkd.in")),
    MediaPlatform("Tumblr", listOf("tumblr.com"), listOf("^/(post/)?\\d+(/|$)", "^/[^/]+/\\d+(/|$)")),
    MediaPlatform("Twitch", listOf("twitch.tv"), listOf("^/videos/\\d+/?$", "^/[^/]+/clip/[^/]+/?$", "^/[^/]+/?$", "^/[^/]+/v(?:ideo)?/\\d+/?$", "^/[^/]+/schedule$", "^/$")),
    MediaPlatform("Vimeo", listOf("vimeo.com"), listOf("^/(.*?/)?\\d+(?:/[a-f0-9]+)?/?$")),
    MediaPlatform("Dailymotion", listOf("dailymotion.com", "dai.ly"), listOf("^/(?:(?:embed|crawler|swf)/)?video/[^/]+/?$", "^/player(?:/[^/]+)?\\.html$"), listOf("dai.ly")),
    MediaPlatform("Discord", listOf("discord.com", "discord.gg", "discordapp.com", "discordapp.net"), listOf("^/channels/\\d+/\\d+/\\d+/?$", "^/attachments/.+"), listOf("discord.gg")),
    MediaPlatform("Telegram", listOf("t.me", "telegram.me"), listOf("^/(s/)?[^/]+/\\d+/?$")),
    MediaPlatform("KakaoStory", listOf("story.kakao.com"), listOf("^/.+")),
    MediaPlatform("Naver Cafe", listOf("cafe.naver.com"), listOf("^/.+")),
    MediaPlatform("Naver Blog", listOf("blog.naver.com"), listOf("^/.+")),
    MediaPlatform("Naver Band", listOf("band.us"), listOf("^/band/\\d+/post/\\d+/?$")),
    MediaPlatform("LINE", listOf("line.me"), listOf("^/.+")),
    MediaPlatform("Medium", listOf("medium.com"), listOf("^/.+")),
    MediaPlatform("Substack", listOf("substack.com"), listOf("^/p/[^/]+/?$")),
    MediaPlatform("WordPress", listOf("wordpress.com", "wp.me"), listOf("^/.+"), listOf("wp.me")),
    MediaPlatform("Behance", listOf("behance.net"), listOf("^/gallery/\\d+/.+")),
    MediaPlatform("Flickr", listOf("flickr.com", "flic.kr"), listOf("^/photos/[^/]+/\\d+(/|$)"), listOf("flic.kr")),
    MediaPlatform("Imgur", listOf("imgur.com"), listOf("^/(gallery|a)/[^/]+/?$", "^/[^/]+/?$")),
    MediaPlatform("SoundCloud", listOf("soundcloud.com", "snd.sc"), listOf("^/[^/]+/[^/]+(?:/s-[^/]+)?/?$", "^/tracks/\\d+/?$", "^/player/?$"), listOf("on.soundcloud.com", "snd.sc")),
    MediaPlatform("Naver TV", listOf("tv.naver.com", "tvcast.naver.com"), listOf("^/(v|embed|l)/\\d+/?$")),
    MediaPlatform("CHZZK", listOf("chzzk.naver.com"), listOf("^/(video/\\d+|live/[a-f0-9]+)/?$")),
    MediaPlatform("SOOP", listOf("sooplive.com", "afreecatv.com"), listOf("^/(?:PLAYER/STATION|player)/\\d+(?:/catchstory)?/?$", "^/[^/]+(?:/\\d+)?/?$")),
    MediaPlatform("KakaoTV", listOf("tv.kakao.com", "play-tv.kakao.com"), listOf("^/(?:channel/\\d+|embed/player)/cliplink/[^/]+/?$")),
    MediaPlatform("Bilibili", listOf("bilibili.com", "b23.tv", "bilibili.tv", "biliintl.com"), listOf("^/video/[A-Za-z0-9]+/?$", "^/(?:bangumi|cheese)/play/ep\\d+/?$", "^/audio/au\\d+/?$", "^/opus/\\d+/?$", "^/(?:[a-z]{2}/)?play/\\d+/\\d+/?$", "^/(?:blanc/)?\\d+/?$", "^/player\\.html$"), listOf("b23.tv")),
    MediaPlatform("Douyin", listOf("douyin.com"), listOf("^/video/\\d+/?$"), listOf("v.douyin.com")),
    MediaPlatform("Weibo", listOf("weibo.com", "weibo.cn"), listOf("^/(?:\\d+|status|detail)/[A-Za-z0-9]+/?$", "^/tv/show/[^/]+/?$", "^/show/?$")),
    MediaPlatform("VK", listOf("vk.com", "vk.ru", "vkvideo.ru"), listOf("^/(?:video|clip|wall)-?\\d+_\\d+/?$", "^/video_ext\\.php$")),
    MediaPlatform("OK.ru", listOf("ok.ru", "odnoklassniki.ru"), listOf("^/(video(?:embed)?|live)/[0-9-]+/?$", "^/web-api/video/moviePlayer/[0-9-]+/?$")),
    MediaPlatform("Rumble", listOf("rumble.com"), listOf("^/v(?!ideos)[\\w.-]+/?$", "^/embed/[a-z0-9.]+/?$")),
    MediaPlatform("Kick", listOf("kick.com"), listOf("^/[A-Za-z0-9_-]+/videos/[a-f0-9-]+/?$", "^/[A-Za-z0-9_-]+/clips/clip_[A-Za-z0-9_-]+/?$", "^/[A-Za-z0-9_-]+/?$")),
    MediaPlatform("Bandcamp", listOf("bandcamp.com"), listOf("^/track/[^/]+/?$", "^/radio/?$")),
    MediaPlatform("Mixcloud", listOf("mixcloud.com"), listOf("^/[^/]+/[^/]+/?$")),
    MediaPlatform("Streamable", listOf("streamable.com"), listOf("^/(?:e/)?[A-Za-z0-9]+/?$")),
)

internal fun mediaPlatformLabels() = mediaPlatforms.map { it.label }

internal fun mediaPlatform(u: URI): String {
    val host = u.host.orEmpty().lowercase()
    val p = mediaPlatforms.firstOrNull { it.hosts.any { domain -> host == domain || host.endsWith(".$domain") } } ?: return "Web page"
    val path = u.path.orEmpty()
    fun parameter(name: String) = u.rawQuery.orEmpty().split('&').any {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") == name && it.substringAfter('=', "").isNotBlank()
    }
    require(path.substringAfterLast('.', "").lowercase() in MEDIA_EXTENSIONS || isInstagramStory(u) ||
        (p.short.any { host == it } && path.length > 1) ||
        p.paths.any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(path) }) { "Unsupported ${p.label} post URL" }
    if (p.label == "YouTube" && path.trimEnd('/') == "/watch") require(parameter("v")) { "YouTube video ID is missing" }
    if (p.label == "Twitch" && (path.isEmpty() || path == "/")) require(host == "player.twitch.tv" && (parameter("video") || parameter("channel"))) { "Twitch video ID is missing" }
    if (p.label == "SOOP" && Regex("^/[^/]+(?:/\\d+)?/?$").matches(path) && !path.startsWith("/player/"))
        require(host in setOf("play.sooplive.com", "play.afreecatv.com")) { "Use an individual SOOP video URL" }
    if (p.label == "Bilibili" && Regex("^/(?:blanc/)?\\d+/?$").matches(path)) require(host == "live.bilibili.com") { "Use an individual Bilibili video URL" }
    if (p.label == "Bandcamp" && path.trimEnd('/') == "/radio") require(parameter("show")) { "Bandcamp episode ID is missing" }
    if (p.label == "SoundCloud") require(!Regex("^/[^/]+/(sets|tracks|albums|reposts|likes|spotlight|comments|stream|uploads|favorites|listens|playlists)/?$").matches(path)) { "Use an individual SoundCloud track URL" }
    if (p.label == "Mixcloud") require(!Regex("^/[^/]+/(stream|uploads|favorites|listens|playlists)/?$").matches(path)) { "Use an individual Mixcloud episode URL" }
    return p.label
}

internal val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "heic")
internal val MEDIA_EXTENSIONS = IMAGE_EXTENSIONS + setOf("mp4", "webm", "mkv", "mov", "mp3", "m4a", "opus", "ogg", "wav", "flac", "aac", "ts", "avi")
