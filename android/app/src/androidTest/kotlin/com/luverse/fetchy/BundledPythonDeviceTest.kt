package com.luverse.fetchy

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class BundledPythonDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun upstreamZipappBecomesAPlatformReadableZip() {
        val upstream = context.assets.open("yt-dlp").use { it.readBytes() }
        val signature = ByteBuffer.wrap(upstream).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(0x752f2123, signature)
        val file = File(context.cacheDir, "bundle-check-${UUID.randomUUID()}.zip")
        try {
            upstream.inputStream().use { input -> file.outputStream().use { BundledPython.copyZipPayload(input, it) } }
            val normalized = file.readBytes()
            assertEquals(0x04034b50, ByteBuffer.wrap(normalized).order(ByteOrder.LITTLE_ENDIAN).int)
            assertArrayEquals(upstream.copyOfRange(23, upstream.size), normalized)
            ZipFile(file).use { zip ->
                assertTrue(zip.getInputStream(zip.getEntry("yt_dlp/__init__.py")).use { it.readBytes().isNotEmpty() })
                instrumentation.sendStatus(0, Bundle().apply { putString("bundleResult", JSONObject()
                    .put("upstreamSignature", Integer.toHexString(signature)).put("prefixRemoved", 23)
                    .put("normalizedSignature", "4034b50").put("entries", zip.size()).toString()) })
            }
        } finally { file.delete() }
    }

    @Test fun failedAndOlderInstallationsAreRepairedWithoutRemovingBytecodeOnReuse() {
        val directory = File(context.cacheDir, "bundle-install-${UUID.randomUUID()}").apply { mkdirs() }
        val target = File(directory, "yt-dlp")
        val source = File(directory, "fetchy-modules")
        val marker = File(directory, "fetchy-modules.sha256")
        val hash = context.assets.open("yt-dlp").use { input ->
            MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { "%02x".format(it) }
        }
        try {
            // Interrupted setup must not make an incomplete module tree look installed.
            File(directory, "fetchy-modules.installing").mkdirs()
            source.mkdirs(); target.writeText("old launcher")
            marker.writeText("$hash:modules-v2")
            BundledPython.install(context, target, hash)
            assertTrue(File(source, "yt_dlp/__init__.py").length() > 0)
            assertEquals("$hash:modules-v3", marker.readText())
            assertTrue(target.readText().contains("import yt_dlp"))
            assertFalse(File(directory, "fetchy-bundle.zip").exists())
            assertFalse(File(directory, "fetchy-modules.installing").exists())
            val bytecode = File(source, "test-bytecode.pyc").apply { writeText("keep") }
            BundledPython.install(context, target, hash)
            assertEquals("keep", bytecode.readText())
        } finally { directory.deleteRecursively() }
    }
}
