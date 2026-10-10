package com.luverse.fetchy

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DownloadFailureTest {
    @Test fun instagramPkMatchesTheBundledShortcodeIdentifiers() {
        assertEquals("B", instagramExtractorId("1")); assertEquals("BA", instagramExtractorId("64"))
        assertEquals("B_", instagramExtractorId("127_456")); assertEquals("", instagramExtractorId("invalid"))
        var numeric = java.math.BigInteger.ZERO; val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        for (char in "DaupWjVARQ_") numeric = numeric * java.math.BigInteger.valueOf(64) + java.math.BigInteger.valueOf(alphabet.indexOf(char).toLong())
        assertEquals("DaupWjVARQ_", instagramExtractorId(numeric.toString()))
    }
    @Test fun explainsAccessStorageAndNetworkWithoutLeakingDetails() {
        val cases = mapOf(
            "HTTP Error 429" to FailureKind.LIMIT, "network timeout" to FailureKind.NETWORK,
            "Sign in to confirm your age" to FailureKind.AGE, "Join this channel to access members-only" to FailureKind.MEMBERSHIP,
            "login required" to FailureKind.AUTH, "requested format not available" to FailureKind.FORMAT,
            "HTTP Error 404" to FailureKind.MISSING, "DRM encrypted media" to FailureKind.DRM,
            "No space left on device" to FailureKind.SPACE, "Storage migration is pending" to FailureKind.BUSY,
            "Name already exists" to FailureKind.CONFLICT, "ffprobe validation failed" to FailureKind.ENGINE)
        for ((message, expected) in cases) {
            val failure = FailureKind.from(IOException("$message https://example.com/private?token=SECRET Cookie: session=SECRET"))
            assertEquals(message, expected, failure)
            assertFalse(failure.message.contains("SECRET")); assertFalse(failure.help.contains("SECRET")); assertFalse(failure.message.contains("https://"))
        }
        assertEquals(FailureKind.STORAGE, FailureKind.from(SecurityException("sensitive path")))
        assertEquals(FailureKind.CANCELED, FailureKind.from(IOException("Download canceled")))
    }
    @Test fun automaticQualityUsesTheBundledPortraitAwareSorter() {
        assertEquals("res", automaticVideoSort(0))
        for (height in listOf(360, 480, 720, 1080, 1440, 2160)) {
            assertEquals("res:$height", automaticVideoSort(height)); assertEquals("bestvideo+bestaudio/best", automaticVideoSelector(height))
        }
        assertThrows(IllegalArgumentException::class.java) { automaticVideoSort(-1) }
        assertThrows(IllegalArgumentException::class.java) { automaticVideoSort(999999) }
    }
}
