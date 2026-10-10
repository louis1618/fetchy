package com.luverse.fetchy

import org.junit.Assert.*
import org.junit.Test

class PreviewUrlTest {
    @Test fun unsafeAliasesCannotReachAPublicPostCacheEntry() {
        for (url in listOf("https://user:pass@youtube.com/watch?v=5Mu9i_B8bjA", "https://youtube.com:444/watch?v=5Mu9i_B8bjA", "ftp://youtube.com/watch?v=5Mu9i_B8bjA")) {
            try { canonicalMediaUrl(url); fail("Unsafe source URL must be rejected") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun knownAliasesShareCacheWithoutTurningPlaylistsIntoVideos() {
        val video = "https://www.youtube.com/watch?v=5Mu9i_B8bjA"
        assertEquals(video, canonicalMediaUrl("$video&list=RD5Mu9i_B8bjA&start_radio=1"))
        assertEquals(video, canonicalMediaUrl("https://youtu.be/5Mu9i_B8bjA?si=abc"))
        assertEquals(video, canonicalMediaUrl("https://m.youtube.com/shorts/5Mu9i_B8bjA"))
        val playlist = "https://www.youtube.com/playlist?list=PL123"
        assertEquals(playlist, canonicalMediaUrl(playlist))
        assertEquals("https://www.instagram.com/p/DaupWjVARQ_/", canonicalMediaUrl("https://www.instagram.com/reel/DaupWjVARQ_/?utm_source=ig&stkn=abc"))
        assertEquals("https://x.com/i/status/123/video/2", canonicalMediaUrl("https://twitter.com/user/status/123/video/2?s=20"))
    }
    @Test fun arbitrarySignedMediaQueryIsNeverDiscarded() {
        val first = "https://cdn.example/video.mp4?token=first&quality=720"
        assertEquals(first, canonicalMediaUrl(first))
        assertNotEquals(canonicalMediaUrl(first), canonicalMediaUrl(first.replace("first", "second")))
        assertNotEquals(canonicalMediaUrl("https://x.com/i/status/123/photo/1"), canonicalMediaUrl("https://x.com/i/status/123/photo/2"))
    }
    @Test fun storyAliasesPreserveTheRequestedItemAndRejectMalformedPaths() {
        val story = "https://www.instagram.com/stories/xgnjzxhtzfhzh/4002710559245555959/"
        assertEquals("$story?utm_source=ig_story_item_share&stkn=test", canonicalMediaUrl("${story.removeSuffix("/")}?utm_source=ig_story_item_share&stkn=test"))
        assertEquals(story, canonicalMediaUrl(story.replace("www.", "m.")))
        assertTrue(isInstagramStory(java.net.URI(story)))
        assertTrue(isInstagramStory(java.net.URI("https://instagram.com/stories/highlights/123/")))
        assertTrue(isInstagramStory(java.net.URI("https://instagram.com/stories/user.name/")))
        for (path in listOf("/stories/", "/stories/highlights/", "/stories/user/not-a-number/", "/stories/user/123/extra/"))
            assertFalse(isInstagramStory(java.net.URI("https://instagram.com$path")))
        assertFalse(isInstagramStory(java.net.URI("https://instagram.com.evil.example/stories/user/123/")))
        assertEquals("$story?access_token=keep%2Bthis", canonicalMediaUrl("$story?access_token=keep%2Bthis"))
        assertNotEquals(story, canonicalMediaUrl(story.replace("4002710559245555959", "4002710559245555960")))
    }

    @Test fun newIndividualAliasesKeepAccessTokensAndAssetIds() {
        assertEquals("https://www.instagram.com/p/DaupWjVARQ_/", canonicalMediaUrl("https://www.instagram.com/user/reel/DaupWjVARQ_/?utm_source=share"))
        assertEquals("https://www.reddit.com/comments/abc123/?context=3", canonicalMediaUrl("https://www.reddit.com/gallery/abc123?context=3"))
        assertEquals("https://www.tiktok.com/@user/photo/123?token=keep%2Bthis", canonicalMediaUrl("https://m.tiktok.com/@user/photo/123?token=keep%2Bthis"))
        assertEquals("https://vod.sooplive.com/player/123?token=keep", canonicalMediaUrl("https://vod.afreecatv.com/player/123?token=keep"))
        val privateTrack = "https://soundcloud.com/user/track/s-secret?secret_token=keep"
        assertEquals(privateTrack, canonicalMediaUrl(privateTrack))
    }
}
