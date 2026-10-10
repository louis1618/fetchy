package com.luverse.fetchy

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BundledPythonTest {
    private fun zip() = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use {
            it.putNextEntry(ZipEntry("yt_dlp/__init__.py"))
            it.write("print('bundled')\n".toByteArray()); it.closeEntry()
        }
    }.toByteArray()
    private fun normalize(bytes: ByteArray) = ByteArrayOutputStream().also { out ->
        bytes.inputStream().use { BundledPython.copyZipPayload(it, out) }
    }.toByteArray()
    private fun signature(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int

    @Test fun zipappPrefixIsRemovedWithoutChangingDirectoryOffsets() {
        val body = zip()
        val executable = "#!/usr/bin/env python3\n".toByteArray() + body
        // The exact first-header value reported by Android's strict ZipFile implementation.
        assertEquals(0x752f2123, signature(executable))
        val normalized = normalize(executable)
        assertEquals(0x04034b50, signature(normalized))
        assertArrayEquals(body, normalized)
    }

    @Test fun ordinaryZipIsUnchanged() {
        val body = zip()
        assertArrayEquals(body, normalize(body))
    }

    @Test fun malformedOrUnboundedPrefixesAreRejected() {
        for (bytes in listOf(byteArrayOf(), "#!/usr".toByteArray(), "html response".toByteArray(),
                ("#!" + "x".repeat(256) + "\n").toByteArray() + zip(),
                "#!/usr/bin/env python3\nnot a zip".toByteArray())) {
            try { normalize(bytes); fail("Invalid archive must not be installed") }
            catch (_: java.io.EOFException) { }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun actualUpstreamBundleCanBeReadFromByteZero() {
        val upstream = File("src/main/assets/yt-dlp").readBytes()
        assertEquals(0x752f2123, signature(upstream))
        val normalized = normalize(upstream)
        assertArrayEquals(upstream.copyOfRange(23, upstream.size), normalized)
        val file = File.createTempFile("fetchy-normalized-", ".zip")
        try {
            file.writeBytes(normalized)
            ZipFile(file).use { zip ->
                assertNotNull(zip.getEntry("yt_dlp/__init__.py"))
                val entries = zip.entries(); var count = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory) zip.getInputStream(entry).use { assertEquals(entry.size, it.readBytes().size.toLong()) }
                    count++
                }
                assertTrue(count in 1..2048)
            }
        } finally { file.delete() }
    }
}
