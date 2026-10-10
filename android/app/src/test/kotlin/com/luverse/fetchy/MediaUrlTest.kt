package com.luverse.fetchy

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class MediaUrlTest {
    @Test fun youtubeWatchWithRadioRemainsOneVideo() {
        assertFalse(analyzeAsPlaylist(URI("https://www.youtube.com/watch?v=5Mu9i_B8bjA&list=RD5Mu9i_B8bjA&start_radio=1")))
        for (url in listOf("https://youtu.be/5Mu9i_B8bjA?list=x", "https://youtube.com/shorts/one", "https://youtube.com/live/one", "https://youtube.com/embed/one")) assertFalse(analyzeAsPlaylist(URI(url)))
    }
    @Test fun explicitPlaylistsAndInstagramKeepMultipleAssets() {
        assertTrue(analyzeAsPlaylist(URI("https://youtube.com/playlist?list=PLexample")))
        assertTrue(analyzeAsPlaylist(URI("https://instagram.com/reel/DaupWjVARQ_/")))
        assertTrue(analyzeAsPlaylist(URI("https://example.com/watch?v=one")))
    }
    @Test fun oneInstagramStoryDoesNotSelectTheWholeAccount() {
        assertFalse(analyzeAsPlaylist(URI("https://www.instagram.com/stories/xgnjzxhtzfhzh/4002710559245555959?utm_source=ig_story_item_share&stkn=test")))
        assertFalse(analyzeAsPlaylist(URI("https://m.instagram.com/stories/user.name/123/")))
        assertTrue(analyzeAsPlaylist(URI("https://instagram.com/stories/user.name/")))
        assertTrue(analyzeAsPlaylist(URI("https://instagram.com/stories/highlights/123/")))
    }

    @Test fun individualMediaRoutesAndNewPlatformsAreRecognized() {
        val examples = mapOf(
            "Instagram" to "https://www.instagram.com/share/reel/BAexample/",
            "Facebook" to "https://www.facebook.com/video.php?v=123",
            "TikTok" to "https://www.tiktok.com/@user/photo/7508442021644782866",
            "X (Twitter)" to "https://x.com/i/spaces/1RDxlLnkPlVJL",
            "Reddit" to "https://www.reddit.com/gallery/abc123",
            "Twitch" to "https://clips.twitch.tv/IncredulousDeliciousSlothSquadGoals",
            "Vimeo" to "https://vimeo.com/123456789/abcdef1234",
            "Dailymotion" to "https://www.dailymotion.com/embed/video/x123abc",
            "SoundCloud" to "https://soundcloud.com/user/track/s-token",
            "Naver TV" to "https://tv.naver.com/v/12345",
            "CHZZK" to "https://chzzk.naver.com/video/12345",
            "SOOP" to "https://vod.sooplive.com/player/12345",
            "KakaoTV" to "https://tv.kakao.com/channel/123/cliplink/456",
            "Bilibili" to "https://www.bilibili.com/video/BV1example/",
            "Douyin" to "https://www.douyin.com/video/12345",
            "Weibo" to "https://m.weibo.cn/status/12345",
            "VK" to "https://vk.com/video-123_456",
            "OK.ru" to "https://ok.ru/video/12345",
            "Rumble" to "https://rumble.com/vvexample.html",
            "Kick" to "https://kick.com/user/clips/clip_123",
            "Bandcamp" to "https://artist.bandcamp.com/track/song",
            "Mixcloud" to "https://www.mixcloud.com/user/episode/",
            "Streamable" to "https://streamable.com/abc123",
        )
        assertEquals(43, mediaPlatformLabels().size)
        for ((label, url) in examples) assertEquals(url, label, mediaPlatform(URI(url)))
        assertEquals("Twitch", mediaPlatform(URI("https://player.twitch.tv/?video=123&parent=example.org")))
        assertEquals("KakaoTV", mediaPlatform(URI("https://play-tv.kakao.com/embed/player/cliplink/123@my")))
        assertEquals("Web page", mediaPlatform(URI("https://example.com/post/123")))
        for (url in listOf("https://youtube.com/playlist?list=PLexample", "https://instagram.com/user/", "https://tiktok.com/@user", "https://x.com/user", "https://reddit.com/r/test/", "https://pinterest.com/user/board/", "https://soundcloud.com/user/sets", "https://mixcloud.com/user/uploads/", "https://bandcamp.com/album/example", "https://rumble.com/videos")) {
            try { mediaPlatform(URI(url)); fail("Collection route must not be added: $url") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun originalPhotoMarkersDoNotAllowOtherPlatformsOrUnsafeHosts() {
        assertEquals("https://i.pinimg.com/originals/a.jpg", originalPhotoUrl("Pinterest", "https://i.pinimg.com/originals/a.jpg"))
        assertEquals("https://i.redd.it/a.png", originalPhotoUrl("Reddit", "https://i.redd.it/a.png"))
        for ((platform, url) in listOf("Pinterest" to "https://pinimg.com.evil.example/a.jpg", "YouTube" to "https://i.pinimg.com/a.jpg", "Reddit" to "http://i.redd.it/a.jpg", "Bluesky" to "https://cdn.bsky.app/thumbnail.jpg")) {
            try { originalPhotoUrl(platform, url); fail("Reject untrusted original: $url") }
            catch (_: IllegalArgumentException) { }
            catch (_: IllegalStateException) { }
        }
    }

    @Test fun cookiesAreSelectedBySourcePlatformWithoutCrossSiteLeakage() {
        assertEquals(21, CookieVault.supportedPlatforms().size)
        for ((url, id) in mapOf("https://youtu.be/id" to "youtube", "https://www.instagram.com/p/id" to "instagram", "https://www.threads.com/@user/post/id" to "threads", "https://clips.twitch.tv/id" to "twitch", "https://chzzk.naver.com/video/123" to "naver", "https://vod.sooplive.com/player/123" to "soop", "https://pin.it/id" to "pinterest", "https://x.com/i/status/123" to "x"))
            assertEquals(id, CookieVault.platformForUrl(url))
        for (url in listOf("https://instagram.com.evil.example/p/id", "https://video.twimg.com/video.mp4", "https://accounts.google.com/", "https://i.pinimg.com/a.jpg")) assertNull(CookieVault.platformForUrl(url))
    }
}
