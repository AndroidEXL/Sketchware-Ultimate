package com.example.ubuntuterminal.setup

import android.system.Os
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.exists

/**
 * Minimal, dependency-free streaming `.tar.gz` extractor.
 *
 * It handles every feature found in the official Ubuntu "ubuntu-base" tarballs:
 *  - ustar & GNU tar formats (including the `prefix` field)
 *  - PAX extended headers (`x` records with path / linkpath / size overrides)
 *  - GNU long name (`L`) and long link (`K`) records
 *  - regular files, directories, symlinks and hardlinks
 *  - executable permission bits
 *  - protection against path traversal (`..` entries)
 */
object TarGzExtractor {

    private const val BLOCK = 512

    class TarException(message: String) : IOException(message)

    /** Extracts the `.tar.gz` [archive] into [outDir]. [onProgress] receives a 0..100 value. */
    fun extract(archive: File, outDir: File, onProgress: (Int) -> Unit = {}) {
        val totalBytes = archive.length().coerceAtLeast(1L)
        FileInputStream(archive).use { fis ->
            val counting = CountingInputStream(fis)
            GZIPInputStream(counting, 1 shl 16).use { gz ->
                extractTar(gz, outDir) {
                    onProgress(((counting.count * 100) / totalBytes).toInt().coerceIn(0, 100))
                }
            }
        }
    }

    /** Counts the (compressed) bytes already consumed – used for the progress bar. */
    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count += n
            return n
        }

        override fun read(): Int {
            val n = super.read()
            if (n >= 0) count++
            return n
        }
    }

    private fun extractTar(input: InputStream, outDir: File, onProgress: () -> Unit) {
        val header = ByteArray(BLOCK)
        val scratch = ByteArray(BLOCK)
        val root: Path = outDir.toPath()

        var paxPath: String? = null
        var paxLink: String? = null
        var paxSize: Long? = null
        var gnuLongName: String? = null
        var gnuLongLink: String? = null

        while (true) {
            if (!readHeader(input, header)) throw TarException("Unexpected end of archive")
            if (isAllZero(header)) break // two consecutive zero blocks = end of archive
            onProgress()

            var name = cstring(header, 0, 100)
            val mode = octal(header, 100, 8)
            var size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            var linkName = cstring(header, 157, 100)
            val isUstar = cstring(header, 257, 6).startsWith("ustar")
            val prefix = if (isUstar) cstring(header, 345, 155) else ""

            // ---- meta records (describe the NEXT entry, carry no file data) ----
            when (type) {
                'x' -> {
                    val map = parsePax(readData(input, size, scratch))
                    paxPath = map["path"]
                    paxLink = map["linkpath"]
                    paxSize = map["size"]?.toLongOrNull()
                    continue
                }
                'g' -> {
                    skipData(input, size, scratch); continue // global PAX header – ignored
                }
                'L' -> {
                    gnuLongName = readData(input, size, scratch).toString(Charsets.UTF_8).trimEnd('\u0000', '\n'); continue
                }
                'K' -> {
                    gnuLongLink = readData(input, size, scratch).toString(Charsets.UTF_8).trimEnd('\u0000', '\n'); continue
                }
            }

            // ---- apply meta overrides ----
            paxPath?.let { name = it }
            paxLink?.let { linkName = it }
            paxSize?.let { size = it }
            gnuLongName?.let { name = it; gnuLongName = null }
            gnuLongLink?.let { linkName = it; gnuLongLink = null }
            paxPath = null; paxLink = null; paxSize = null

            if (prefix.isNotEmpty()) name = "$prefix/$name"
            val entryName = name.removePrefix("./").trimStart('/')
            if (entryName.isEmpty()) {
                if (size > 0) skipData(input, size, scratch)
                continue
            }

            // ---- path traversal protection (normalize WITHOUT following symlinks) ----
            val targetPath = root.resolve(entryName).normalize()
            if (targetPath != root && !targetPath.startsWith(root)) {
                throw TarException("Refusing unsafe path in archive: $entryName")
            }
            val target = targetPath.toFile()

            when (type) {
                '5', '/' -> { // directory
                    target.mkdirs()
                    applyMode(target, mode or 0x1C0L /* ensure rwx for owner */)
                }

                '2' -> { // symlink
                    target.parentFile?.mkdirs()
                    deleteIfExists(target)
                    try {
                        Os.symlink(linkName, target.absolutePath)
                    } catch (e: Exception) {
                        throw TarException("Failed to create symlink $entryName -> $linkName: ${e.message}")
                    }
                }

                '1' -> { // hardlink (its target always appears earlier in the stream)
                    val source = root.resolve(linkName.removePrefix("./").trimStart('/')).toFile()
                    target.parentFile?.mkdirs()
                    deleteIfExists(target)
                    try {
                        Os.link(source.absolutePath, target.absolutePath)
                    } catch (e: Exception) {
                        if (!source.exists()) throw TarException("Hardlink target missing: $linkName")
                        // Some devices (vendor SELinux) forbid creating hardlinks in app
                        // storage. Fall back to a RELATIVE symlink first: it preserves the
                        // shared-content semantics and costs nothing – Ubuntu 26.04 ships
                        // 114 hardlinks to the 10.5 MB `coreutils` multicall binary, which
                        // would waste ~1.2 GB if materialised as copies.
                        val rel = target.parentFile.toPath().relativize(source.toPath()).toString()
                        try {
                            Os.symlink(rel, target.absolutePath)
                        } catch (_: Exception) {
                            source.copyTo(target, overwrite = true)
                            applyMode(target, mode) // copyTo creates the file 0644 – restore tar mode
                        }
                    }
                }

                '0', '\u0000', '7' -> { // regular file
                    target.parentFile?.mkdirs()
                    deleteIfExists(target)
                    target.outputStream().buffered(1 shl 16).use { out ->
                        var remaining = size
                        while (remaining > 0) {
                            val n = input.read(scratch, 0, remaining.toInt().coerceAtMost(BLOCK))
                            if (n == -1) throw TarException("Unexpected EOF while reading $entryName")
                            out.write(scratch, 0, n)
                            remaining -= n
                        }
                    }
                    applyMode(target, mode)
                }

                else -> skipData(input, size, scratch) // device nodes, fifos, sparse markers…
            }

            if (size > 0) { // align stream to the next 512-byte boundary
                val pad = (BLOCK - (size % BLOCK).toInt()) % BLOCK
                if (pad > 0) skipExactly(input, pad, scratch)
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Reads exactly one 512-byte header. Returns false on a *clean* EOF at a block boundary. */
    private fun readHeader(input: InputStream, header: ByteArray): Boolean {
        var off = 0
        while (off < header.size) {
            val n = input.read(header, off, header.size - off)
            if (n == -1) {
                if (off == 0) return false
                throw TarException("Corrupted archive (partial header)")
            }
            off += n
        }
        return true
    }

    private fun readData(input: InputStream, size: Long, scratch: ByteArray): ByteArray {
        if (size < 0 || size > (64L shl 20)) throw TarException("Bad meta-record size: $size")
        val data = ByteArray(size.toInt())
        var off = 0
        while (off < data.size) {
            val n = input.read(data, off, data.size - off)
            if (n == -1) throw TarException("Unexpected EOF in meta record")
            off += n
        }
        val pad = (BLOCK - (size % BLOCK).toInt()) % BLOCK
        if (pad > 0) skipExactly(input, pad, scratch)
        return data
    }

    private fun skipData(input: InputStream, size: Long, scratch: ByteArray) {
        var remaining = size
        while (remaining > 0) {
            val n = input.read(scratch, 0, remaining.toInt().coerceAtMost(BLOCK))
            if (n == -1) throw TarException("Unexpected EOF while skipping entry data")
            remaining -= n
        }
        val pad = (BLOCK - (size % BLOCK).toInt()) % BLOCK
        if (pad > 0) skipExactly(input, pad, scratch)
    }

    private fun skipExactly(input: InputStream, count: Int, scratch: ByteArray) {
        var remaining = count
        while (remaining > 0) {
            val n = input.read(scratch, 0, remaining)
            if (n == -1) throw TarException("Unexpected EOF while skipping padding")
            remaining -= n
        }
    }

    private fun isAllZero(block: ByteArray): Boolean {
        for (b in block) if (b != 0.toByte()) return false
        return true
    }

    /** Interprets a NUL-terminated string field of a tar header. */
    private fun cstring(block: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val max = offset + length
        while (end < max && block[end] != 0.toByte()) end++
        return String(block, offset, end - offset, Charsets.UTF_8)
    }

    /**
     * Parses a tar numeric field. Tar stores these as OCTAL ASCII strings
     * ("00000005004" == 0o5004 == 2564); GNU also allows base-256 (binary)
     * encoding for large values, signalled by the high bit of the first byte.
     */
    private fun octal(block: ByteArray, offset: Int, length: Int): Long {
        if (block[offset].toInt() and 0x80 != 0) { // GNU base-256 (binary) encoding
            var value = (block[offset].toInt() and 0x7F).toLong()
            for (i in 1 until length) value = (value shl 8) or (block[offset + i].toLong() and 0xFF)
            return value
        }
        val s = cstring(block, offset, length).trim(' ', '\u0000', '\n')
        if (s.isEmpty()) return 0L
        // radix 8 – tar numeric fields are octal, NOT decimal
        return s.toLongOrNull(8) ?: throw TarException("Bad numeric field: '$s'")
    }

    /**
     * Parses a PAX extended header: a sequence of `<length> key=value\n` records.
     * IMPORTANT: record lengths are counted in BYTES, so parsing has to happen
     * on the raw bytes (values may contain multi-byte UTF-8 sequences).
     */
    private fun parsePax(bytes: ByteArray): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val space = ' '.code.toByte()
        val newline = '\n'.code.toByte()
        val equals = '='.code.toByte()
        var pos = 0
        while (pos < bytes.size) {
            // find the space that terminates the decimal length field
            var lenEnd = pos
            while (lenEnd < bytes.size && bytes[lenEnd] != space) lenEnd++
            if (lenEnd == bytes.size || lenEnd == pos) break
            val recordLength = String(bytes, pos, lenEnd - pos, Charsets.ISO_8859_1).toIntOrNull() ?: break
            if (recordLength <= 0 || pos + recordLength > bytes.size) break

            var bodyEnd = pos + recordLength
            if (bytes[bodyEnd - 1] == newline) bodyEnd--

            val bodyStart = lenEnd + 1
            if (bodyStart < bodyEnd) {
                val body = bytes.copyOfRange(bodyStart, bodyEnd)
                val eq = body.indexOf(equals)
                if (eq > 0) {
                    map[String(body, 0, eq, Charsets.UTF_8)] =
                        String(body, eq + 1, body.size - eq - 1, Charsets.UTF_8)
                }
            }
            pos += recordLength
        }
        return map
    }

    private fun deleteIfExists(file: File) {
        if (file.isDirectory) file.deleteRecursively()
        else if (file.exists()) file.delete()
    }

    /** Applies tar permission bits (mode is an octal value like 0o644 = 420). */
    private fun applyMode(target: File, mode: Long) {
        val perms = (mode and 0x1FF).toInt()
        if (perms != 0) {
            try {
                Os.chmod(target.absolutePath, perms)
                return
            } catch (_: Exception) {
                // fall through to the java.io fallback
            }
        }
        // fallback: at least keep it accessible for the app itself
        target.setReadable(true, false)
        target.setWritable(true, true)
        if (perms and 0x49 != 0) target.setExecutable(true, false)
    }
}
