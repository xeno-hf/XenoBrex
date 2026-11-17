package xb.container

import java.io.File
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class XTuneImportException(message: String) : Exception(message)

/**
 * 從使用者自己的 XTune 安裝檔（Android 版）取出需要的東西：金鑰與定義檔。
 * XenoBrex 不帶金鑰也不帶定義檔。使用者手上若有 XTune 的安裝檔（`.xapk`／`.apks`／`.apk`，
 * 或放著這些檔案的資料夾），可以讓程式從裡面讀：
 * - 定義檔：安裝檔裡的 `assets/ECUFile/ControlFile_*`、`ScaleData`。
 * - 金鑰：安裝檔裡的程式庫本來就帶著，這裡把它找出來，再拿同一份安裝檔裡的加密檔實際解一次，解得開才算。
 * 只讀檔案內容，不執行安裝檔裡的任何程式。只認 64 位元 ARM 的程式庫（一般手機用的那一種）。
 */
object XTunePackage {
    class Result(
        val keys: UserKeys,
        /** 檔名 → 內容，照安裝檔裡的樣子（加密的仍然是加密的）。 */
        val definitions: Map<String, ByteArray>,
    )

    private const val LIB = "lib/arm64-v8a/libnative-lib.so"
    private const val ASSETS = "assets/ECUFile/"

    fun read(source: File): Result {
        val found = scan(source, ArchiveSafety.PACKAGE)
        val lib = found.lib ?: throw XTuneImportException(
            if (found.otherAbi) "The library in this package is not the 64-bit ARM build, so the keys cannot be read. Use the phone (arm64) package, or enter the keys directly."
            else "No XTune library found. Choose the complete package (.xapk / .apks); the base .apk alone is not enough.",
        )
        val encrypted = found.assets.filterValues { TuneContainer.looksEncrypted(it) }
        if (encrypted.isEmpty()) throw XTuneImportException("No encrypted definitions found, so the keys cannot be verified.")
        val keys = findKeys(lib, encrypted.values.minBy { it.size })
            ?: throw XTuneImportException("Cannot read the keys: this XTune version is not the expected one. Enter the keys directly.")
        val defs = found.assets.toMap()
        return Result(keys, defs)
    }

    /**
     * 把讀到的定義檔寫進 [dir]，回寫了幾個。先寫暫存檔再換過去：原本是符號連結的話換掉的是連結本身，
     * 不會寫穿到連結指的檔案。桌面版與 `xbrex import` 共用。
     */
    fun installDefinitions(r: Result, dir: File): Int {
        val base = dir.toPath().toAbsolutePath().normalize()
        java.nio.file.Files.createDirectories(base)
        if (java.nio.file.Files.isSymbolicLink(base)) throw XTuneImportException("Definitions directory is a symbolic link")
        val names = HashSet<String>()
        for (name in r.definitions.keys) {
            if (!isDefinitionName(name)) throw XTuneImportException("Unexpected definition filename")
            if (!names.add(name.lowercase(java.util.Locale.ROOT))) throw XTuneImportException("Duplicate definition member")
        }
        for ((name, bytes) in r.definitions) {
            val target = SafeFiles.resolveBasename(base, name)
            SafeFiles.atomicWrite(target) { it.write(bytes) }
        }
        return r.definitions.size
    }

    private class Found {
        var lib: ByteArray? = null
        var otherAbi = false
        val assets = LinkedHashMap<String, ByteArray>()
        val assetNames = HashSet<String>()
    }

    private fun isPackage(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("apk", "xapk", "apks", "apkm", "zip")

    internal fun isDefinitionName(name: String): Boolean {
        SafeFiles.requireSafeBasename(name)
        return Regex("(?:ControlFile_[A-Za-z0-9_]+|ScaleData|[A-Za-z0-9_]+_(?:TabShortName|GroupDes|ParaAndVarDes))(?:\\.(?:dat|xml|txt))?", RegexOption.IGNORE_CASE).matches(name)
    }

    private fun take(name: String, bytes: ByteArray, found: Found) {
        when {
            name == LIB -> {
                if (found.lib != null) throw XTuneImportException("Duplicate XTune library")
                found.lib = bytes
            }
            name.startsWith("lib/") && name.endsWith("/libnative-lib.so") -> found.otherAbi = true
            name.startsWith(ASSETS) -> {
                val basename = name.substring(ASSETS.length)
                if (!isDefinitionName(basename)) return
                if (!found.assetNames.add(basename.lowercase(java.util.Locale.ROOT))) throw XTuneImportException("Duplicate definition member")
                found.assets[basename] = bytes
            }
        }
    }

    internal fun validateArchive(source: File, limits: ArchiveLimits = ArchiveSafety.PACKAGE) {
        scan(source, limits)
    }

    private fun scan(source: File, limits: ArchiveLimits): Found {
        val found = Found()
        val budget = ArchiveBudget(limits)
        var compressed = 0L
        try {
            val files = if (source.isDirectory) source.listFiles().orEmpty().filter { it.isFile } else listOf(source)
            for (file in files) if (isPackage(file.name)) {
                if (file.length() > limits.compressed - compressed) throw IOException("Package input size limit exceeded")
                compressed += file.length()
                scanFile(file, found, budget)
            }
        } catch (e: XTuneImportException) { throw e }
        catch (e: Exception) { throw XTuneImportException("Cannot read this package: ${e.message}") }
        return found
    }

    /** 外層用中央目錄，兼容官方 XAPK 的 STORED data descriptor。 */
    private fun scanFile(file: File, found: Found, budget: ArchiveBudget) {
        val names = HashSet<String>()
        ZipFile(file).use { zip ->
            for (entry in zip.entries()) {
                budget.entry(entry.name)
                if (!names.add(entry.name)) throw XTuneImportException("Duplicate archive member")
                zip.getInputStream(entry).use { input -> scanEntry(entry.name, entry.isDirectory, input, found, budget, 0) }
            }
            if (names.isEmpty()) throw XTuneImportException("Not a ZIP package")
        }
    }

    private fun scanEntry(name: String, directory: Boolean, input: InputStream, found: Found, budget: ArchiveBudget, depth: Int) {
        when {
            directory -> budget.consume(input)
            name.endsWith(".apk", ignoreCase = true) -> {
                if (depth >= budget.limits.depth) throw XTuneImportException("Nested APK depth limit exceeded")
                scanArchive(ByteArrayInputStream(budget.read(input)), found, budget, depth + 1)
            }
            name == LIB -> take(name, budget.read(input), found)
            name.startsWith(ASSETS) -> {
                if (isDefinitionName(name.substring(ASSETS.length))) take(name, budget.read(input), found)
                else budget.consume(input)
            }
            else -> {
                if (name.startsWith("lib/") && name.endsWith("/libnative-lib.so")) found.otherAbi = true
                budget.consume(input)
            }
        }
    }

    /** 巢狀 APK 共用同一份總量限制，連未使用的成員也計數。 */
    private fun scanArchive(input: InputStream, found: Found, budget: ArchiveBudget, depth: Int) {
        val names = HashSet<String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                budget.entry(entry.name)
                if (!names.add(entry.name)) throw XTuneImportException("Duplicate archive member")
                scanEntry(entry.name, entry.isDirectory, zip, found, budget, depth)
            }
            if (names.isEmpty()) throw XTuneImportException("Not a ZIP package")
        }
    }

    // ---- 從程式庫找金鑰 ----

    /**
     * 兩個取金鑰的函式各自引用幾段常數資料、帶幾個立即數。把這些湊成候選，
     * 一組一組拿 [sample]（同一份安裝檔裡的加密檔）試，解得開的就是。
     */
    internal fun findKeys(lib: ByteArray, sample: ByteArray): UserKeys? {
        val elf = Elf64.parse(lib) ?: return null
        val f1 = elf.symbolEndingWith("getMxKey1") ?: return null
        val f2 = elf.symbolEndingWith("getMxKey2") ?: return null
        val r1 = Arm64.references(elf, f1)
        val r2 = Arm64.references(elf, f2)

        // 第一段：一個以 0 結尾的字串
        val firsts = r1.addresses.mapNotNull { elf.cString(it) }.filter { it.size in 4..128 }.distinctBy { it.toList() }
        // 第二段：一段固定長度的資料，可能再接一個立即數
        val seconds = ArrayList<ByteArray>()
        for (a in r2.addresses) {
            elf.cString(a)?.takeIf { it.size in 4..128 }?.let(seconds::add)
            for (len in r2.immediates.filter { it in 4..128 }) elf.bytes(a, len)?.let(seconds::add)
            val head = elf.bytes(a, 16) ?: continue
            seconds.add(head)
            for (imm in r2.immediates.filter { it in 1..255 }) seconds.add(head + imm.toByte())
        }
        val prefix = firstBlock(sample) ?: return null
        for (a in firsts) for (b in seconds.distinctBy { it.toList() }) {
            if (!plausible(a, b, prefix)) continue
            val keys = UserKeys(a, b)
            if (keys.container.opens(sample)) return keys
        }
        return null
    }

    /** 加密檔開頭的第一個區塊（16 位元組的密文）。 */
    private fun firstBlock(sample: ByteArray): ByteArray? {
        var i = 0
        if (sample.size >= 3 && sample[0] == 0xEF.toByte() && sample[1] == 0xBB.toByte() && sample[2] == 0xBF.toByte()) i = 3
        val b64 = StringBuilder()
        while (i < sample.size && b64.length < 24) {
            val c = sample[i++].toInt().toChar()
            if (!c.isWhitespace()) b64.append(c)
        }
        return runCatching { java.util.Base64.getDecoder().decode(b64.toString()).copyOf(16) }.getOrNull()
    }

    /** 快速篩：只解第一個區塊，看起來像 XML 的開頭才值得把整個檔案解開來確認。 */
    private fun plausible(key1: ByteArray, key2: ByteArray, block: ByteArray): Boolean {
        val dk = TuneContainer.pbkdf2Sha1(key1, key2, 1000, 32)
        val plain = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(dk.copyOfRange(0, 16), "AES"), IvParameterSpec(dk.copyOfRange(16, 32)))
            doFinal(block)
        }
        var i = 0
        if (plain[0] == 0xEF.toByte() && plain[1] == 0xBB.toByte() && plain[2] == 0xBF.toByte()) i = 3
        return plain[i] == '<'.code.toByte()
    }
}

/** 64 位元、小端序的 ELF：只讀找符號和依位址取資料需要的部分。 */
internal class Elf64 private constructor(private val data: ByteArray, private val loads: List<Load>, private val symbols: Map<String, Pair<Long, Long>>) {
    private class Load(val vaddr: Long, val offset: Long, val size: Long)
    class Symbol(val address: Long, val size: Long)

    fun symbolEndingWith(suffix: String): Symbol? =
        symbols.entries.firstOrNull { it.key.endsWith(suffix) }?.let { Symbol(it.value.first, it.value.second) }

    private fun offsetOf(address: Long, length: Int): Int? {
        if (address < 0 || length < 0) return null
        val l = loads.firstOrNull { address >= it.vaddr && address - it.vaddr <= it.size - length } ?: return null
        val offset = l.offset + (address - l.vaddr)
        if (!inFile(offset, length.toLong())) return null
        return offset.toInt()
    }

    /** 位移與長度都先用 Long 檢查，加法不進位、窄化前一定在檔案內。 */
    private fun inFile(offset: Long, size: Long): Boolean =
        offset >= 0 && size >= 0 && offset <= data.size.toLong() && size <= data.size.toLong() - offset

    fun bytes(address: Long, length: Int): ByteArray? = offsetOf(address, length)?.let { data.copyOfRange(it, it + length) }

    /** [address] 開始、以 0 結尾、全是可見 ASCII 字元的字串；不是的話回 null。 */
    fun cString(address: Long): ByteArray? {
        val start = offsetOf(address, 1) ?: return null
        var o = start
        while (o < data.size && data[o].toInt() != 0) {
            if (data[o] !in 0x20..0x7E) return null
            o++
        }
        return if (o < data.size && o > start) data.copyOfRange(start, o) else null
    }

    companion object {
        const val EM_AARCH64 = 183

        fun parse(d: ByteArray): Elf64? = runCatching {
            if (d.size < 64 || d[0] != 0x7F.toByte() || d[1] != 'E'.code.toByte() || d[2] != 'L'.code.toByte() || d[3] != 'F'.code.toByte()) return null
            if (d[4].toInt() != 2 || d[5].toInt() != 1) return null // 64 位元、小端序
            fun u16(o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
            fun u32(o: Int) = (u16(o).toLong() or (u16(o + 2).toLong() shl 16))
            fun u64(o: Int) = u32(o) or (u32(o + 4) shl 32)
            fun inFile(o: Long, n: Long) = o >= 0 && n >= 0 && o <= d.size.toLong() && n <= d.size.toLong() - o
            // 表頭欄位都在檔案內才讀；讀不到就當格式不對，不先截成 Int。
            fun u32o(o: Long): Long? = if (inFile(o, 4)) u32(o.toInt()) else null
            fun u64o(o: Long): Long? = if (inFile(o, 8)) u64(o.toInt()) else null
            if (u16(18) != EM_AARCH64) return null
            val phoff = u64(32); val shoff = u64(40)
            val phentsize = u16(54); val phnum = u16(56); val shentsize = u16(58); val shnum = u16(60)
            // 一個項目至少要放得下用到的欄位；長度不合理就整個不認。
            if (phnum > 0 && (phentsize < 56 || phentsize > 4096)) return null
            if (shnum > 0 && (shentsize < 64 || shentsize > 4096)) return null
            // 整張表也要在檔案內，不能只靠逐項讀到才停。
            if (phnum > 0 && !inFile(phoff, phnum.toLong() * phentsize)) return null
            if (shnum > 0 && !inFile(shoff, shnum.toLong() * shentsize)) return null
            val loads = ArrayList<Load>()
            for (i in 0 until phnum) {
                val ph = phoff + i.toLong() * phentsize
                if (u32o(ph) != 1L) continue
                val offset = u64o(ph + 8) ?: return null
                val vaddr = u64o(ph + 16) ?: return null
                val filesz = u64o(ph + 32) ?: return null
                if (!inFile(offset, filesz) || vaddr > Long.MAX_VALUE - filesz) return null
                loads += Load(vaddr, offset, filesz)
            }
            val symbols = HashMap<String, Pair<Long, Long>>()
            for (i in 0 until shnum) {
                val sh = shoff + i.toLong() * shentsize
                if (u32o(sh + 4) != 11L) continue // SHT_DYNSYM
                val symOff = u64o(sh + 24) ?: return null
                val symSize = u64o(sh + 32) ?: return null
                val rawEntry = u64o(sh + 56) ?: return null
                val strIndex = u32o(sh + 40) ?: return null
                val entSize = if (rawEntry > 0) rawEntry else 24
                if (entSize < 24 || entSize > 4096 || !inFile(symOff, symSize)) return null
                val strSh = shoff + strIndex * shentsize
                val strOff = u64o(strSh + 24) ?: return null
                val strSize = u64o(strSh + 32) ?: return null
                if (!inFile(strOff, strSize) || strSize > Int.MAX_VALUE) return null
                val count = symSize / entSize
                for (s in 0 until count) {
                    val e = symOff + s * entSize
                    if (!inFile(e, 24)) break
                    val nameAt = strOff + (u32o(e) ?: return null)
                    if (nameAt < strOff || nameAt - strOff >= strSize) continue
                    val limit = strOff + strSize
                    var end = nameAt
                    while (end < limit && d[end.toInt()].toInt() != 0) end++
                    if (end >= limit) continue // 沒有結尾的字串不算
                    val name = String(d, nameAt.toInt(), (end - nameAt).toInt(), Charsets.US_ASCII)
                    val value = u64o(e + 8) ?: return null
                    val size = u64o(e + 16) ?: return null
                    if (name.isNotEmpty() && value != 0L && size in 1..4096) symbols[name] = value to size
                }
            }
            Elf64(d, loads, symbols)
        }.getOrNull()
    }
}

/** 64 位元 ARM 的指令：只認「取常數資料的位址」和「放一個小的立即數」這幾種。 */
internal object Arm64 {
    class References(val addresses: List<Long>, val immediates: List<Int>)

    fun references(elf: Elf64, f: Elf64.Symbol): References {
        val code = elf.bytes(f.address, f.size.toInt()) ?: return References(emptyList(), emptyList())
        val reg = arrayOfNulls<Long>(32)
        val addresses = LinkedHashSet<Long>()
        val immediates = LinkedHashSet<Int>()
        fun sext(v: Long, bits: Int): Long = (v shl (64 - bits)) shr (64 - bits)
        for (i in 0 until code.size / 4) {
            val o = i * 4
            val insn = (code[o].toLong() and 0xFF) or ((code[o + 1].toLong() and 0xFF) shl 8) or ((code[o + 2].toLong() and 0xFF) shl 16) or ((code[o + 3].toLong() and 0xFF) shl 24)
            val pc = f.address + o
            val rd = (insn and 31).toInt()
            val rn = ((insn shr 5) and 31).toInt()
            when {
                (insn and 0x9F000000L) == 0x90000000L -> { // ADRP：取某一頁的位址
                    val imm = sext((((insn shr 5) and 0x7FFFF) shl 2) or ((insn shr 29) and 3), 21) shl 12
                    reg[rd] = (pc and 0xFFFL.inv()) + imm
                }
                (insn and 0x9F000000L) == 0x10000000L -> { // ADR：取附近的位址
                    val a = pc + sext((((insn shr 5) and 0x7FFFF) shl 2) or ((insn shr 29) and 3), 21)
                    reg[rd] = a; addresses.add(a)
                }
                (insn and 0xFF800000L) == 0x91000000L -> { // ADD 立即數：頁位址加上頁內的位移
                    val base = reg[rn]
                    val imm = ((insn shr 10) and 0xFFF) shl (if (((insn shr 22) and 1L) == 1L) 12 else 0)
                    reg[rd] = base?.plus(imm)
                    if (base != null) addresses.add(base + imm)
                }
                (insn and 0x3B000000L) == 0x39000000L -> { // 以暫存器加位移讀寫：位址直接寫在位移裡的情形
                    val base = reg[rn] ?: continue
                    val size = ((insn shr 30) and 3).toInt()
                    val vector = ((insn shr 26) and 1L) == 1L
                    val opc = ((insn shr 22) and 3).toInt()
                    val scale = if (vector && (opc and 2) != 0) 4 else size
                    val load = (opc and 1) != 0
                    if (load) addresses.add(base + (((insn shr 10) and 0xFFF) shl scale))
                    if (load && !vector) reg[rd] = null
                }
                (insn and 0x7F800000L) == 0x52800000L -> { // MOVZ：放一個立即數
                    if (((insn shr 21) and 3L) == 0L) immediates.add(((insn shr 5) and 0xFFFF).toInt())
                    reg[rd] = null
                }
            }
        }
        return References(addresses.toList(), immediates.toList())
    }
}
