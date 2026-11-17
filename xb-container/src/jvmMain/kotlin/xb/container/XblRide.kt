package xb.container

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * `.xbl`：XB／ReX 共用的騎乘紀錄（ReX 打包，規格見 README「自家檔案格式」）。這裡只讀。
 * ```
 * mimetype application/vnd.xenobrex.ride+zip（第一個成員、不壓縮）
 * manifest.json 格式版本、寫入的程式、起訖時間、車子型號、分享模式、各成員的 SHA-256
 * meta.json ReX 錄製的 meta
 * frames.rxraw 原始封包（唯一的原始資料）
 * decoded.loga 解碼後的 loga（XTune 可以直接開）
 * events.jsonl 連線事件與標記
 * writes.jsonl App 寫進 ECU 的動作（從 frames.rxraw 整理）
 * ```
 * 開檔時把每個成員的實際內容串流算一次 SHA-256，不只看 manifest 有沒有列；
 * 之後 [bytes] 再核對一次，檔案在開檔後被換掉也擋得住。
 */
class XblRide private constructor(
    val file: File,
    val manifest: Map<String, Any?>,
    val members: List<String>,
    private val hashes: Map<String, String>,
    private val manifestSha: String,
) {
    @Suppress("UNCHECKED_CAST")
    val vehicle: Map<String, Any?> get() = manifest["vehicle"] as? Map<String, Any?> ?: emptyMap()

    /** 讀一個成員的內容（核對 manifest 的 SHA-256；manifest 自己核對開檔時算的雜湊）。 */
    fun bytes(name: String): ByteArray {
        val normalized = XbxArchive.safeName(name)
        if (normalized != name) throw XbxException("Bad member name: $name")
        if (name !in members) throw XbxException("$name is not in this ride log")
        val want = when (name) {
            "manifest.json" -> manifestSha
            "mimetype" -> null
            else -> hashes[name] ?: throw XbxException("$name is not listed in the manifest")
        }
        if (file.length() > ArchiveSafety.RIDE.compressed) throw XbxException("The ride log is too large")
        try {
            ZipFile(file).use { z ->
                val e = z.getEntry(name) ?: throw XbxException("$name is not in this ride log")
                if (e.isDirectory) throw XbxException("$name is a directory")
                val budget = ArchiveBudget(ArchiveSafety.RIDE)
                XbxArchive.entry(budget, HashSet(), name)
                val b = z.getInputStream(e).use { input -> budget.read(input) }
                if (name == "mimetype") {
                    if (!b.contentEquals(MIME.toByteArray())) throw XbxException("Not an .xbl ride log")
                } else if (XbxProject.sha256(b) != want) {
                    throw XbxException("$name does not match its checksum; the ride log is damaged")
                }
                return b
            }
        } catch (e: XbxException) {
            throw e
        } catch (e: Exception) {
            throw XbxException("Cannot read $name: ${e.message}")
        }
    }

    /** writes.jsonl 每一行。 */
    fun writes(): List<Map<String, Any?>> = if ("writes.jsonl" !in members) emptyList() else
        bytes("writes.jsonl").decodeToString().lines().filter { it.isNotBlank() }.map { Json.obj(it) }

    companion object {
        const val MIME = "application/vnd.xenobrex.ride+zip"
        const val FORMAT = "xbl-1"

        fun isXbl(bytes: ByteArray): Boolean = XbxProject.mimetypeOf(bytes) == MIME

        fun open(file: File): XblRide {
            if (!file.isFile) throw XbxException("Cannot read the file")
            val length = try { file.length() } catch (e: Exception) { throw XbxException("Cannot read the file: ${e.message}") }
            XbxArchive.checkCompressed(length, ArchiveSafety.RIDE)
            val head = try { file.inputStream().use { it.readNBytes(256) } } catch (e: Exception) { throw XbxException("Cannot read the file: ${e.message}") }
            if (!isXbl(head)) throw XbxException("Not an .xbl ride log")
            try {
                ZipFile(file).use { z ->
                    val budget = ArchiveBudget(ArchiveSafety.RIDE)
                    val seen = HashSet<String>()
                    val entries = LinkedHashMap<String, ZipEntry>()
                    for (e in z.entries()) {
                        val name = XbxArchive.entry(budget, seen, e.name)
                        if (e.isDirectory) {
                            val n = z.getInputStream(e).use { budget.consume(it) }
                            if (n != 0L) throw XbxException("Directory entry has content: ${e.name}")
                            continue
                        }
                        if (name != e.name) throw XbxException("Bad member name: ${e.name}")
                        if (entries.put(name, e) != null) throw XbxException("Duplicate member: ${e.name}")
                    }
                    val manifestEntry = entries["manifest.json"] ?: throw XbxException("No manifest.json")
                    val manifestBytes = z.getInputStream(manifestEntry).use { XbxArchive.read(it, budget, XbxArchive.MANIFEST_MAX) }
                    val manifest = try { Json.obj(manifestBytes.decodeToString()) }
                        catch (e: Json.ParseException) { throw XbxException("manifest.json: ${e.message}") }
                    XbxArchive.requireFormat(manifest["format"] as? String, "xbl", "ride log")

                    val mimeEntry = entries["mimetype"] ?: throw XbxException("No mimetype")
                    val mime = z.getInputStream(mimeEntry).use { XbxArchive.read(it, budget, XbxArchive.MIMETYPE_MAX) }
                    if (!mime.contentEquals(MIME.toByteArray())) throw XbxException("Not an .xbl ride log")

                    val listed = XbxArchive.readFileList(manifest["files"], "manifest.json")
                    for (name in listed.keys) {
                        if (name !in entries) throw XbxException("$name is listed in the manifest but missing")
                    }
                    for (name in entries.keys) {
                        if (name == "mimetype" || name == "manifest.json") continue
                        if (name !in listed) throw XbxException("$name is not listed in the manifest; the ride log is damaged")
                    }
                    // XB 目前一定要有原始資料與可給 XTune 開的 loga。
                    for (name in listOf("frames.rxraw", "decoded.loga")) {
                        if (name !in entries) throw XbxException("The ride log is missing $name")
                    }

                    // 串流核對每個成員的實際內容；不把整份原始錄影讀進記憶體。
                    for ((name, want) in listed) {
                        val e = entries.getValue(name)
                        val md = MessageDigest.getInstance("SHA-256")
                        z.getInputStream(e).use { input -> budget.consume(input, XbxArchive.digestSink(md)) }
                        val got = md.digest().joinToString("") { "%02x".format(it) }
                        if (got != want) throw XbxException("$name does not match its checksum; the ride log is damaged")
                    }
                    return XblRide(file, manifest, entries.keys.toList(), listed, XbxProject.sha256(manifestBytes))
                }
            } catch (e: XbxException) {
                throw e
            } catch (e: Exception) {
                throw XbxException("The ride log is damaged: ${e.message}")
            }
        }
    }
}
