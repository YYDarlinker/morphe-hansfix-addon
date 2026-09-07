package io.github.yydarlinker.hansfix.integration

import com.android.tools.build.apkzlib.zip.AlignmentRules
import com.android.tools.build.apkzlib.zip.ZFile
import com.android.tools.build.apkzlib.zip.ZFileOptions
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipFile

private fun signatureEntry(name: String): Boolean {
    val upper = name.uppercase(Locale.ROOT)
    if (!upper.startsWith("META-INF/")) return false
    val leaf = upper.removePrefix("META-INF/")
    return '/' !in leaf && (leaf == "MANIFEST.MF" || leaf.startsWith("SIG-") ||
        listOf(".SF", ".RSA", ".DSA", ".EC").any(leaf::endsWith))
}

/** A NEW ZIP drops the old APK signing block (v2/v3) as well as JAR signature entries (v1).
 * Reusing the signed input ZIP and merely deleting META-INF would not establish unsigned output.
 * Retains every other entry and its compression; apkzlib applies the Patcher alignment rules.
 * No ApkSigner, key store, private key, external signing tool or network is used. */
internal fun writeUnsignedApk(patched: File, output: File) {
    check(!output.exists())
    val options = ZFileOptions().setAlignmentRule(AlignmentRules.compose(
        AlignmentRules.constantForSuffix(".so", 4096), AlignmentRules.constant(4)))
    ZFile.openReadOnly(patched).use { source ->
        ZFile.openReadWrite(output, options).use { target ->
            target.mergeFrom(source) { signatureEntry(it) }
            target.realign()
        }
    }
}

/** Structural output gate, not an Android playback/installation or HansFix semantic assertion. */
internal fun verifyUnsignedApk(output: File): Int {
    check(output.isFile && output.length() > 0)
    var entries = 0
    ZipFile(output).use { zip ->
        val names = mutableSetOf<String>()
        val buffer = ByteArray(1024 * 1024)
        zip.entries().asSequence().forEach { entry ->
            check(names.add(entry.name))
            check(!signatureEntry(entry.name))
            if (!entry.isDirectory) {
                val crc = CRC32()
                var bytes = 0L
                zip.getInputStream(entry).use { stream ->
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        bytes += count; crc.update(buffer, 0, count)
                    }
                }
                check(bytes == entry.size && crc.value == entry.crc)
                if (entry.name.matches(Regex("classes(?:[2-9]|[1-9][0-9]+)?\\.dex"))) {
                    zip.getInputStream(entry).use { stream ->
                        val header = stream.readNBytes(8)
                        check(header.size == 8 && header[0] == 'd'.code.toByte() &&
                            header[1] == 'e'.code.toByte() && header[2] == 'x'.code.toByte() && header[3] == 10.toByte())
                    }
                }
            }
            entries++
        }
        check(setOf("AndroidManifest.xml", "resources.arsc", "classes.dex").all { it in names })
    }
    // Non-ZIP64 Android APK, conventional EOCD and no signing-block footer before central dir.
    RandomAccessFile(output, "r").use { apk ->
        val tailSize = minOf(apk.length(), 65557L).toInt()
        val tail = ByteArray(tailSize)
        apk.seek(apk.length() - tailSize); apk.readFully(tail)
        val eocd = (tail.size - 22 downTo 0).firstOrNull { i ->
            tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() &&
                tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte() &&
                i + 22 + ((tail[i + 20].toInt() and 255) or ((tail[i + 21].toInt() and 255) shl 8)) == tail.size
        } ?: error("INVALID_ZIP")
        var centralOffset = 0L
        repeat(4) { centralOffset = centralOffset or ((tail[eocd + 16 + it].toLong() and 255) shl (8 * it)) }
        check(centralOffset != 0xffffffffL)
        if (centralOffset >= 16) {
            apk.seek(centralOffset - 16)
            val magic = ByteArray(16); apk.readFully(magic)
            check(!magic.contentEquals("APK Sig Block 42".toByteArray(Charsets.US_ASCII)))
        }
    }
    return entries
}
