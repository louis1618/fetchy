package com.luverse.fetchy

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DocumentsPathTest {
    @Test fun onlyFetchyDescendantsAreResolved() {
        val root = Files.createTempDirectory("fetchy-path-test").toFile()
        try {
            assertEquals(root.canonicalFile, FetchyDocumentsProvider.resolve(root, "fetchy"))
            assertEquals(File(root, "Instagram/여행/clip.mp4"), FetchyDocumentsProvider.resolve(root, "fetchy/Instagram/여행/clip.mp4"))
            for (value in listOf("elsewhere", "fetchy/", "fetchy/../secret", "fetchy/a/../../secret", "fetchy//secret", "fetchy/.", "fetchy/a\\b"))
                assertThrows(IllegalArgumentException::class.java) { FetchyDocumentsProvider.resolve(root, value) }
            val outside = File(root.parentFile, "outside-${root.name}").apply { mkdir() }
            try {
                Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
                assertThrows(IllegalArgumentException::class.java) { FetchyDocumentsProvider.resolve(root, "fetchy/escape/secret") }
            } finally { outside.delete() }
        } finally { root.deleteRecursively() }
    }
}
