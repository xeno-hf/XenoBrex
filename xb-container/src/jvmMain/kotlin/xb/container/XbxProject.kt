package xb.container

import xb.tune.Tune
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class XbxException(message: String) : Exception(message)

/** 專案／騎乘紀錄的格式細節；壓縮檔界線交給 ArchiveSafety／SafeFiles。 */
internal object XbxArchive {
    const val MIMETYPE_MAX = 200L
    const val MANIFEST_MAX = 4L * 1024 * 1024
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    private val EXTRA_RESERVED = Regex("(?i)(CONIN\\$|CONOUT\\$)(?:\\..*)?")

    fun normalize(name: String): String = if (name.endsWith('/')) name.dropLast(1) else name

    fun checkCompressed(size: Long, limits: ArchiveLimits) {
        if (size > limits.compressed) throw XbxException("The file is too large (${size} bytes; the limit is ${limits.compressed})")
    }

    fun safeName(name: String): String {
        try { ArchiveSafety.validateMemberName(name) } catch (e: IOException) { throw XbxException(e.message ?: "Bad member name") }
        val normalized = normalize(name)
        for (part in normalized.split('/')) {
            if (part.length > 255) throw XbxException("Member name is too long: $name")
            if ('\u007f' in part) throw XbxException("Bad member name: $name")
            if (EXTRA_RESERVED.matches(part)) throw XbxException("Windows reserved name: $name")
        }
        return normalized
    }

    /** 登記成員：共用驗名、計數，這裡再加同名檔案／目錄的檢查。 */
    fun entry(budget: ArchiveBudget, seen: MutableSet<String>, name: String): String {
        val normalized = safeName(name)
        try { budget.entry(name) } catch (e: IOException) { throw XbxException(e.message ?: "Bad member name") }
        if (!seen.add(normalized.lowercase(Locale.ROOT))) throw XbxException("Duplicate member: $name")
        return normalized
    }

    /** 讀一個成員；manifest／mimetype 可以再收緊上限，位元組仍計入總預算。 */
    fun read(input: InputStream, budget: ArchiveBudget, maxBytes: Long): ByteArray {
        val cap = minOf(maxBytes, budget.limits.entry)
        if (cap >= budget.limits.entry) return budget.read(input)
        val bytes = try { SafeFiles.boundedRead(input, cap) } catch (e: IOException) { throw XbxException(e.message ?: "Cannot read a member") }
        try { budget.consume(ByteArrayInputStream(bytes)) } catch (e: IOException) { throw XbxException(e.message ?: "Cannot read a member") }
        return bytes
    }

    fun digestSink(md: MessageDigest): OutputStream = object : OutputStream() {
        override fun write(b: Int) { md.update(b.toByte()) }
        override fun write(b: ByteArray, off: Int, len: Int) { md.update(b, off, len) }
    }

    /** 讀 manifest 的 files 表：每個名字都要安全、每個值都是 64 位十六進位 SHA-256。 */
    fun readFileList(raw: Any?, what: String): Map<String, String> {
        val files = raw as? Map<*, *> ?: throw XbxException("$what has no file list")
        val listed = LinkedHashMap<String, String>()
        val seen = HashSet<String>()
        for ((k, v) in files) {
            val rawName = k as? String ?: throw XbxException("$what has a non-string file name")
            val name = safeName(rawName)
            if (name != rawName) throw XbxException("Bad member name in $what: $rawName")
            if (name == "mimetype" || name == "manifest.json") throw XbxException("$what lists $name")
            if (!seen.add(name.lowercase(Locale.ROOT))) throw XbxException("Duplicate member in $what: $name")
            val hash = v as? String ?: throw XbxException("$name has no SHA-256 in $what")
            if (!SHA256.matches(hash)) throw XbxException("$name has an invalid SHA-256 in $what")
            listed[name] = hash.lowercase(Locale.ROOT)
        }
        return listed
    }

    /** 只接受目前支援的 `前綴-1`；較大的主版號另外說「更新」，其餘一律不認。 */
    fun requireFormat(format: String?, prefix: String, what: String): String {
        if (format == null) throw XbxException("manifest.json has no format")
        if (format == "$prefix-1") return format
        val major = Regex("^" + Regex.escape(prefix) + "-(\\d+)(?:\\..*)?$").matchEntire(format)
            ?.groupValues?.get(1)?.toIntOrNull()
        if (major != null && major > 1) {
            throw XbxException("This $what is format $format, newer than this version of XenoBrex reads ($prefix-1); please update")
        }
        throw XbxException("Unsupported format: $format")
    }
}

/**
 * `.xbx`：XB 專案包。
 * ```
 * mimetype application/vnd.xenobrex.project+zip（第一個成員、不壓縮）
 * manifest.json 格式版本、寫入的程式、調校檔名、各成員的 SHA-256
 * tune.<型號> 調校檔，內容跟官方檔一樣（有金鑰就是加密的容器）；副檔名是 MAP_Type 小寫
 * vehicle.json 車輛備註
 * changelog.jsonl 修改紀錄，一個動作一行
 * ecu/index.jsonl 每次讀回 ECU 的紀錄（全部保留）
 * ecu/<指紋>.<型號> 讀回的內容；同樣內容只存一份
 * convert/<時間>.json 轉換報告
 * ```
 * 沒見過的成員原樣保留。讀檔時核對 manifest 列的 SHA-256，對不上就拒開。
 */
class XbxProject(
    var tuneName: String,
    var tuneBytes: ByteArray,
    val vehicle: MutableMap<String, Any?> = LinkedHashMap(),
    val changelog: MutableList<Map<String, Any?>> = ArrayList(),
    val ecuIndex: MutableList<Map<String, Any?>> = ArrayList(),
    /** ecu/ 底下的內容檔，鍵是成員名。 */
    val ecuFiles: MutableMap<String, ByteArray> = LinkedHashMap(),
    /** convert/ 底下的報告，鍵是成員名。 */
    val conversions: MutableMap<String, ByteArray> = LinkedHashMap(),
    /** 建立時間與寫入它的程式，讀檔時從 manifest 來。 */
    val created: String = now(),
    val createdBy: String = APP,
    /** 不認得的成員，原樣寫回。 */
    private val unknown: MutableMap<String, ByteArray> = LinkedHashMap(),
) {
    /** 加一筆修改紀錄；[entry] 不用帶時間，這裡補。 */
    fun log(entry: Map<String, Any?>) {
        changelog += linkedMapOf<String, Any?>("time" to now()) + entry
    }

    /**
     * 加一份 ECU 讀回的內容。指紋相同的內容檔只存一份，但每次都記一筆 index。
     * [bytes] 照調校檔的寫法存（跟 [tuneBytes] 一樣：有金鑰就加密）。回傳成員名。
     */
    fun addEcuBackup(bytes: ByteArray, fingerprint: String, mapType: String, reason: String, ecu: Map<String, Any?>): String {
        val name = "ecu/${fingerprint.take(16)}.${extension(mapType)}"
        if (name !in ecuFiles) ecuFiles[name] = bytes
        ecuIndex += linkedMapOf("time" to now(), "file" to name, "fingerprint" to fingerprint, "reason" to reason, "ecu" to ecu)
        return name
    }

    /** 加一份轉換報告，回傳成員名。 */
    fun addConversion(report: Map<String, Any?>): String {
        var name = "convert/${OffsetDateTime.now().format(FILE_TIME)}.json"
        var n = 2
        while (name in conversions) name = "convert/${OffsetDateTime.now().format(FILE_TIME)}_${n++}.json"
        conversions[name] = (Json.write(report) + "\n").toByteArray()
        return name
    }

    /** 換調校檔（例如轉換到別的型號），副檔名跟著 [mapType] 改。 */
    fun replaceTune(bytes: ByteArray, mapType: String) {
        tuneName = "tune.${extension(mapType)}"
        tuneBytes = bytes
    }

    fun toBytes(): ByteArray = toBytes(ArchiveSafety.PROJECT)

    /** [limits] 正常是讀檔同一組；測試會縮小。 */
    internal fun toBytes(limits: ArchiveLimits): ByteArray {
        val out = ByteArrayOutputStream()
        writeTo(out, limits)
        return out.toByteArray()
    }

    /** 寫入端先套用讀取端的上限：超量就失敗，不會留下一個自己讀不回來的專案。 */
    private fun writeTo(out: OutputStream, limits: ArchiveLimits) {
        val members = LinkedHashMap<String, ByteArray>()
        val seen = HashSet<String>()
        var expanded = MIME.toByteArray(Charsets.UTF_8).size.toLong()
        fun add(name: String, bytes: ByteArray) {
            val normalized = XbxArchive.safeName(name)
            if (normalized != name) throw XbxException("Bad member name: $name")
            if (name == "mimetype" || name == "manifest.json") throw XbxException("Reserved member name: $name")
            // 讀取端不分大小寫；寫入端不先擋，就會存出自己讀不回來的檔。
            if (!seen.add(normalized.lowercase(Locale.ROOT))) throw XbxException("Duplicate member: $name")
            if (bytes.size.toLong() > limits.entry) {
                throw XbxException("$name is too large for a project (${bytes.size} bytes; the limit is ${limits.entry})")
            }
            if (members.size + 2 >= limits.entries) throw XbxException("Too many project members (limit ${limits.entries})")
            expanded += bytes.size
            if (expanded > limits.total) throw XbxException("The project is too large (the limit is ${limits.total} bytes)")
            members[name] = bytes
        }
        add(tuneName, tuneBytes)
        add("vehicle.json", (Json.write(vehicle) + "\n").toByteArray())
        add("changelog.jsonl", lines(changelog))
        if (ecuIndex.isNotEmpty() || ecuFiles.isNotEmpty()) add("ecu/index.jsonl", lines(ecuIndex))
        for ((k, v) in ecuFiles) add(k, v)
        for ((k, v) in conversions) add(k, v)
        for ((k, v) in unknown) if (k !in members) add(k, v)

        val manifest = linkedMapOf<String, Any?>(
            "format" to FORMAT,
            "created" to created,
            "created_by" to createdBy,
            "modified" to now(),
            "modified_by" to APP,
            "tune" to tuneName,
            "files" to members.mapValues { sha256(it.value) },
        )
        val manifestBytes = (Json.write(manifest) + "\n").toByteArray(Charsets.UTF_8)
        if (manifestBytes.size > XbxArchive.MANIFEST_MAX) throw XbxException("manifest.json is too large (the limit is ${XbxArchive.MANIFEST_MAX} bytes)")
        if (expanded + manifestBytes.size > limits.total) throw XbxException("The project is too large (the limit is ${limits.total} bytes)")
        ZipOutputStream(CountingOutputStream(out, limits.compressed), Charsets.UTF_8).use { z ->
            stored(z, "mimetype", MIME.toByteArray(Charsets.UTF_8))
            z.putNextEntry(ZipEntry("manifest.json")); z.write(manifestBytes); z.closeEntry()
            for ((k, v) in members) { z.putNextEntry(ZipEntry(k)); z.write(v); z.closeEntry() }
        }
    }

    /**
     * 寫到 [target]：先在**同一個資料夾**開一個獨佔的暫存檔，寫完再原子換過去。
     * 寫到一半失敗不會毀掉原檔，也不會留下共用的暫存檔名。
     */
    fun save(target: File) = save(target, ArchiveSafety.PROJECT)

    internal fun save(target: File, limits: ArchiveLimits) {
        val abs = target.absoluteFile
        try {
            // 完成後用讀取端再驗一次；過了才換掉原檔。
            SafeFiles.atomicWrite(abs.toPath(), validate = { tmp -> XbxProject.read(tmp.toFile()) }) { writeTo(it, limits) }
        } catch (e: Exception) {
            if (e is XbxException) throw e
            throw XbxException("Cannot save the project: ${e.message}")
        }
    }

    /** 算實際寫進去的壓縮位元組；超過上限就在寫入前失敗。 */
    private class CountingOutputStream(private val out: OutputStream, private val max: Long) : OutputStream() {
        private var count = 0L

        override fun write(b: Int) { add(1); out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { add(len.toLong()); out.write(b, off, len) }
        override fun flush() = out.flush()
        override fun close() = out.close()

        private fun add(n: Long) {
            if (n > max - count) throw XbxException("The project is too large (the limit is ${max} bytes)")
            count += n
        }
    }

    companion object {
        const val MIME = "application/vnd.xenobrex.project+zip"
        const val FORMAT = "xbx-1"
        val APP: String get() = "XenoBrex " + (XbxProject::class.java.`package`?.implementationVersion ?: "dev")
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        fun now(): String = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()

        /** 調校檔的副檔名：跟官方一樣，是 MAP_Type 小寫（Mini X＝amx、X2 Mini＝amx2…）。 */
        fun extension(mapType: String): String = mapType.trim().lowercase().ifEmpty { "xml" }

        /** 檔案是不是 .xbx：看開頭的 mimetype，不看副檔名。 */
        fun isXbx(bytes: ByteArray): Boolean = mimetypeOf(bytes) == MIME

        /** ZIP 第一個成員叫 mimetype、不壓縮時的內容；不是這種檔就回 null。 */
        fun mimetypeOf(bytes: ByteArray): String? {
            if (bytes.size < 38 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte() || bytes[2].toInt() != 3 || bytes[3].toInt() != 4) return null
            fun u16(p: Int) = (bytes[p].toInt() and 255) or ((bytes[p + 1].toInt() and 255) shl 8)
            fun u32(p: Int) = u16(p).toLong() or (u16(p + 2).toLong() shl 16)
            if (u16(8) != 0 || u16(26) != 8 || bytes.decodeToString(30, 38) != "mimetype") return null
            val compressed = u32(18)
            val size = u32(22)
            if (compressed != size || compressed !in 1..200) return null
            val start = 38 + u16(28)
            if (start < 38 || start.toLong() + size > bytes.size.toLong()) return null
            return bytes.decodeToString(start, start + size.toInt())
        }

        fun read(file: File): XbxProject {
            val length = try { file.length() } catch (e: Exception) { throw XbxException("Cannot read the file: ${e.message}") }
            XbxArchive.checkCompressed(length, ArchiveSafety.PROJECT)
            return read(try { file.inputStream().use { SafeFiles.boundedRead(it, ArchiveSafety.PROJECT.compressed) } } catch (e: Exception) { throw XbxException("Cannot read the file: ${e.message}") })
        }

        fun read(bytes: ByteArray): XbxProject {
            XbxArchive.checkCompressed(bytes.size.toLong(), ArchiveSafety.PROJECT)
            if (!isXbx(bytes)) throw XbxException("Not an .xbx project")
            val members = LinkedHashMap<String, ByteArray>()
            val budget = ArchiveBudget(ArchiveSafety.PROJECT)
            val seen = HashSet<String>()
            try {
                ZipInputStream(ByteArrayInputStream(bytes), Charsets.UTF_8).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val name = XbxArchive.entry(budget, seen, e.name)
                        if (e.isDirectory) {
                            // 目錄項只能是空的；有內容就是拿來藏未核對的位元組。
                            if (budget.consume(z) != 0L) throw XbxException("Directory entry has content: ${e.name}")
                            continue
                        }
                        if (name != e.name) throw XbxException("Bad member name: ${e.name}")
                        val cap = when (name) {
                            "mimetype" -> XbxArchive.MIMETYPE_MAX
                            "manifest.json" -> XbxArchive.MANIFEST_MAX
                            else -> ArchiveSafety.PROJECT.entry
                        }
                        members[name] = XbxArchive.read(z, budget, cap)
                    }
                }
            } catch (e: XbxException) {
                throw e
            } catch (e: Exception) {
                throw XbxException("The project is damaged: ${e.message}")
            }
            val mime = members["mimetype"] ?: throw XbxException("No mimetype")
            if (mime.decodeToString() != MIME) throw XbxException("Not an .xbx project")
            val manifestBytes = members["manifest.json"] ?: throw XbxException("No manifest.json")
            val manifest = try { Json.obj(manifestBytes.decodeToString()) }
                catch (e: Json.ParseException) { throw XbxException("manifest.json: ${e.message}") }
            XbxArchive.requireFormat(manifest["format"] as? String, "xbx", "project")
            val listed = XbxArchive.readFileList(manifest["files"], "manifest.json")
            val tuneName = manifest["tune"] as? String ?: throw XbxException("manifest.json has no tune")
            val tunePath = XbxArchive.safeName(tuneName)
            if (tunePath != tuneName) throw XbxException("Bad member name in manifest.json: $tuneName")
            if (tuneName !in listed) throw XbxException("The tune $tuneName is not listed in the manifest")
            for ((name, want) in listed) {
                val b = members[name] ?: throw XbxException("$name is listed in the manifest but missing")
                if (sha256(b) != want) throw XbxException("$name does not match its checksum; the project is damaged")
            }
            for (name in members.keys) {
                if (name == "mimetype" || name == "manifest.json") continue
                if (name !in listed) throw XbxException("$name is not listed in the manifest; the project is damaged")
            }
            val tune = members[tuneName] ?: throw XbxException("The tune $tuneName is missing")
            fun jsonl(name: String) = members[name]?.decodeToString()?.lines()?.filter { it.isNotBlank() }?.mapIndexed { i, l ->
                try { Json.obj(l) } catch (e: Json.ParseException) { throw XbxException("$name line ${i + 1}: ${e.message}") }
            } ?: emptyList()
            val known = setOf("mimetype", "manifest.json", tuneName, "vehicle.json", "changelog.jsonl", "ecu/index.jsonl")
            return XbxProject(
                tuneName = tuneName,
                tuneBytes = tune,
                vehicle = members["vehicle.json"]?.let { b -> try { LinkedHashMap(Json.obj(b.decodeToString())) } catch (e: Json.ParseException) { throw XbxException("vehicle.json: ${e.message}") } } ?: LinkedHashMap(),
                changelog = jsonl("changelog.jsonl").toMutableList(),
                ecuIndex = jsonl("ecu/index.jsonl").toMutableList(),
                ecuFiles = members.filterKeys { it.startsWith("ecu/") && it !in known }.toMutableMap(),
                conversions = members.filterKeys { it.startsWith("convert/") }.toMutableMap(),
                created = manifest["created"] as? String ?: "",
                createdBy = manifest["created_by"] as? String ?: "",
                unknown = members.filterKeys { it !in known && !it.startsWith("ecu/") && !it.startsWith("convert/") }.toMutableMap(),
            )
        }

        /**
         * 調校內容的指紋：所有表依表號排序，每張表的原始值。跟存檔時間、加不加密無關，
         * 兩份讀回的內容一樣，指紋就一樣（ECU 備份去重用）。
         */
        fun fingerprint(tune: Tune): String = fingerprint(tune.file.tables.keys) { tune.rows(it) }

        fun fingerprint(file: xb.tune.TuneFile): String = fingerprint(file.tables.keys) { file.tables[it]?.rows }

        private fun fingerprint(ids: Collection<Int>, rowsOf: (Int) -> List<LongArray>?): String {
            val md = MessageDigest.getInstance("SHA-256")
            for (id in ids.sorted()) {
                val rows = rowsOf(id) ?: continue
                md.update("$id:".toByteArray())
                md.update(rows.joinToString(";") { it.joinToString(",") }.toByteArray())
                md.update('\n'.code.toByte())
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** 一張表目前的原始值副本（改之前先存，給 [changedCells] 比）。 */
        fun copyRows(tune: Tune, absId: Int): List<LongArray>? = tune.rows(absId)?.map { it.copyOf() }

        /** 改了哪些格：每格 [列, 欄, 改前, 改後]（原始值）。尺寸不同的部分當成沒有值（null）。 */
        fun changedCells(before: List<LongArray>?, after: List<LongArray>?): List<List<Any?>> = buildList {
            val rows = maxOf(before?.size ?: 0, after?.size ?: 0)
            for (r in 0 until rows) {
                val b = before?.getOrNull(r); val a = after?.getOrNull(r)
                for (c in 0 until maxOf(b?.size ?: 0, a?.size ?: 0)) {
                    val x = b?.getOrNull(c); val y = a?.getOrNull(c)
                    if (x != y) add(listOf(r, c, x, y))
                }
            }
        }

        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        private fun lines(l: List<Map<String, Any?>>) = l.joinToString("") { Json.write(it) + "\n" }.toByteArray()

        private fun stored(z: ZipOutputStream, name: String, b: ByteArray) {
            val e = ZipEntry(name)
            e.method = ZipEntry.STORED
            e.size = b.size.toLong(); e.compressedSize = b.size.toLong()
            e.crc = CRC32().also { it.update(b) }.value
            z.putNextEntry(e); z.write(b); z.closeEntry()
        }
    }
}
