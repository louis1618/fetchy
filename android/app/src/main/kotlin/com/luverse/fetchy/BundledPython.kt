package com.luverse.fetchy

import android.content.Context
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipFile

/** A zipapp cannot retain CPython bytecode. Install the same signed bundle as modules,
 * so subsequent processes reuse private __pycache__ files instead of recompiling imports. */
internal object BundledPython {
    /** The upstream zipapp has a shebang; Android ZipFile requires a ZIP header at byte zero.
     * Its directory offsets already refer to the ZIP payload, so remove only that prefix. */
    fun copyZipPayload(input: InputStream, output: OutputStream) {
        val stream = DataInputStream(input)
        val header = ByteArray(4)
        stream.readFully(header)
        if (header[0] == '#'.code.toByte() && header[1] == '!'.code.toByte()) {
            var length = 4
            var last = header[3].toInt()
            while (last != '\n'.code) {
                last = stream.read()
                require(last >= 0 && ++length <= 256) { "Invalid bundled zipapp prefix" }
            }
            stream.readFully(header)
        }
        require(header.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) { "Invalid bundled ZIP header" }
        output.write(header)
        stream.copyTo(output)
    }

    fun install(context: Context, target: File, bundleHash: String) {
        val parent = target.parentFile!!
        val source = File(parent, "fetchy-modules")
        val marker = File(parent, "fetchy-modules.sha256")
        val identity = "$bundleHash:modules-v3"
        if (target.isFile && File(source, "yt_dlp/__init__.py").isFile && marker.isFile && marker.readText() == identity) return
        val archive = File(parent, "fetchy-bundle.zip")
        val staging = File(parent, "fetchy-modules.installing")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Cannot prepare bundled Python modules" }
        try {
            context.assets.open("yt-dlp").use { input -> archive.outputStream().use { copyZipPayload(input, it) } }
            val prefix = staging.canonicalPath + File.separator
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                var bytes = 0L
                var count = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val file = File(staging, entry.name).canonicalFile
                    require(file.path.startsWith(prefix) && ++count <= 2048) { "Invalid bundled module path" }
                    if (entry.isDirectory) { check(file.isDirectory || file.mkdirs()); continue }
                    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                    zip.getInputStream(entry).use { input -> file.outputStream().use { out ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            bytes += n; require(bytes <= 33554432) { "Bundled modules exceed size limit" }
                            out.write(buffer, 0, n)
                        }
                    } }
                }
            }
            require(File(staging, "yt_dlp/__init__.py").isFile) { "Bundled yt-dlp module is missing" }
            check(source.deleteRecursively() && staging.renameTo(source)) { "Cannot install bundled modules" }
            val launcher = File(parent, "fetchy-launcher.tmp")
            // Android's JSON quoting can escape '/', which is not a Python string escape.
            val pythonPath = source.absolutePath.replace("\\", "\\\\").replace("'", "\\'")
            launcher.writeText("import sys\nsys.dont_write_bytecode = False\nsys.path.insert(0, '$pythonPath')\nimport yt_dlp\nyt_dlp.main()\n")
            check(launcher.renameTo(target)) { "Cannot install bundled launcher" }
            marker.writeText(identity)
        } finally { archive.delete(); staging.deleteRecursively() }
    }
}
