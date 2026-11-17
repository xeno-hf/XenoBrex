package xb.container

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal data class ArchiveLimits(
    val compressed: Long,
    val entry: Long,
    val total: Long,
    val entries: Int,
    val depth: Int = 2,
)

internal object ArchiveSafety {
    val PROJECT = ArchiveLimits(128L shl 20, 64L shl 20, 256L shl 20, 4096)
    val RIDE = ArchiveLimits(1L shl 30, 1L shl 30, 2L shl 30, 4096)
    val PACKAGE = ArchiveLimits(1L shl 30, 256L shl 20, 2L shl 30, 30_000, 2)

    fun validateMemberName(name: String) {
        val parts = name.removeSuffix("/").split('/')
        if (name.isEmpty() || name.length > 512 || '\\' in name || parts.any { part ->
            part.isEmpty() || part == "." || part == ".." || ':' in part || part.last() == '.' || part.last() == ' ' ||
                part.any { it.code < 32 || it in "<>\"|?*" } ||
                Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?").matches(part)
        }) throw IOException("Unsafe archive member name")
    }
}

/** 計算實際解壓位元組；不採信 ZIP 宣告的大小。 */
internal class ArchiveBudget(val limits: ArchiveLimits) {
    private var count = 0
    private var expanded = 0L

    fun entry(name: String) {
        ArchiveSafety.validateMemberName(name)
        if (++count > limits.entries) throw IOException("Too many archive members (limit ${limits.entries})")
    }

    fun read(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        consume(input, out)
        return out.toByteArray()
    }

    fun consume(input: InputStream, output: OutputStream = OutputStream.nullOutputStream()): Long {
        val allowed = minOf(limits.entry, limits.total - expanded)
        val count = SafeFiles.copyBounded(input, output, allowed)
        expanded += count
        return count
    }
}
