package com.luverse.fetchy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StorageNamesTest {
    @Test fun preservesValidUnicodeAndExtensions() {
        listOf("영상 제목.mp4", "YouTube", ".hidden", "clip (2).m4a").forEach {
            assertEquals(it, StorageNames.validate(it))
        }
    }
    @Test fun rejectsTraversalSeparatorsControlsAndAmbiguousNames() {
        listOf("", " ", ".", "..", "../clip", "a/b", "a\\b", " clip", "clip ",
            "a\u0000b", "a\nb", "a\u007fb", "x".repeat(241)).forEach {
            assertThrows(IllegalArgumentException::class.java) { StorageNames.validate(it) }
        }
    }
    @org.junit.Test fun generatedUnicodeTitlesFitFilesystemByteLimits() {
        val title = StorageNames.title("한😀".repeat(100))
        org.junit.Assert.assertTrue(title.toByteArray(Charsets.UTF_8).size <= 160)
        org.junit.Assert.assertFalse(title.last().isHighSurrogate())
        org.junit.Assert.assertEquals("media", StorageNames.title("   "))
        try { StorageNames.validate("한".repeat(100)); org.junit.Assert.fail("UTF-8 byte limit must apply") }
        catch (_: IllegalArgumentException) { }
    }
}
