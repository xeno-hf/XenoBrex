package xb.container

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 用真正的 XTune 安裝檔驗。安裝檔與金鑰都是使用者自己的，本機沒有就略過。 */
class XTunePackageTest {
    private val pkg = File(System.getProperty("xb.xtune") ?: "")

    @Test
    fun readsKeysAndDefinitionsFromARealPackage() {
        val mine = KeyStore.load()
        if (!pkg.exists() || mine == null) { println("  略過：本機沒有 XTune 安裝檔或金鑰"); return }
        val t = System.nanoTime()
        val r = XTunePackage.read(pkg)
        println("  ${pkg.name}：${(System.nanoTime() - t) / 1_000_000} ms，定義檔 ${r.definitions.keys.sorted()}")
        assertTrue(mine.key1.contentEquals(r.keys.key1), "keys differ")
        assertTrue(mine.key2.contentEquals(r.keys.key2), "keys differ")
        assertTrue("ControlFile_S3.dat" in r.definitions && r.definitions.keys.any { it.startsWith("ScaleData") })
        for ((name, bytes) in r.definitions) if (TuneContainer.looksEncrypted(bytes)) assertTrue(r.keys.container.opens(bytes), name)
    }

    @Test
    fun rejectsFilesThatAreNotPackages() {
        val junk = File.createTempFile("xb-junk", ".apk").apply { writeText("not a zip") }
        try {
            assertTrue(runCatching { XTunePackage.read(junk) }.exceptionOrNull() is XTuneImportException)
        } finally { junk.delete() }
    }

    // ---- 下面用測試自己做的假安裝檔，任何機器都會跑 ----

    private val k1 = "made-up first part 21".toByteArray()
    private val k2 = "made-up second 17".toByteArray()

    /** 做一個最小的 64 位元 ARM 程式庫：兩個函式各自引用一段常數，第二個另外帶一個立即數。 */
    private fun fakeLibrary(): ByteArray {
        val b = ByteBuffer.allocate(0x3000).order(ByteOrder.LITTLE_ENDIAN)
        val names = "\u0000Java_demo_App_getMxKey1\u0000Java_demo_App_getMxKey2\u0000".toByteArray(Charsets.US_ASCII)
        val f1 = 0x1000; val f2 = 0x1020; val s1 = 0x2056; val s2 = 0x2100
        val dynsym = 0x400; val dynstr = 0x500; val shoff = 0x600
        // 檔頭
        b.put(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0)); b.position(16)
        b.putShort(3); b.putShort(183); b.putInt(1); b.putLong(0); b.putLong(64); b.putLong(shoff.toLong())
        b.putInt(0); b.putShort(64); b.putShort(56); b.putShort(1); b.putShort(64); b.putShort(3); b.putShort(2)
        // 一個載入區段：整個檔案照原位置載入
        b.position(64); b.putInt(1); b.putInt(5); b.putLong(0); b.putLong(0); b.putLong(0); b.putLong(0x3000); b.putLong(0x3000); b.putLong(0x1000)
        // 符號表
        b.position(dynstr); b.put(names)
        b.position(dynsym + 24); b.putInt(1); b.put(0x12); b.put(0); b.putShort(1); b.putLong(f1.toLong()); b.putLong(12)
        b.putInt(1 + "Java_demo_App_getMxKey1".length + 1); b.put(0x12); b.put(0); b.putShort(1); b.putLong(f2.toLong()); b.putLong(12)
        b.position(shoff + 64); b.putInt(0); b.putInt(11); b.putLong(2); b.putLong(dynsym.toLong()); b.putLong(dynsym.toLong()); b.putLong(72); b.putInt(2); b.putInt(1); b.putLong(8); b.putLong(24)
        b.putInt(0); b.putInt(3); b.putLong(2); b.putLong(dynstr.toLong()); b.putLong(dynstr.toLong()); b.putLong(names.size.toLong()); b.putInt(0); b.putInt(0); b.putLong(1); b.putLong(0)
        // 函式一：ADRP x9, 頁；ADD x9, x9, #頁內位移；RET
        val page = (s1 and 0xFFF.inv()) - (f1 and 0xFFF.inv()) shr 12
        b.position(f1); b.putInt((0x90000000L or ((page and 3).toLong() shl 29) or ((page shr 2).toLong() shl 5) or 9).toInt())
        b.putInt((0x91000000L or ((s1 and 0xFFF).toLong() shl 10) or (9L shl 5) or 9).toInt()); b.putInt(0xD65F03C0.toInt())
        // 函式二：ADR x8, 位址；MOVZ w8, #最後一個位元組；RET
        val rel = s2 - f2
        b.position(f2); b.putInt((0x10000000L or ((rel and 3).toLong() shl 29) or ((rel shr 2).toLong() shl 5) or 8).toInt())
        b.putInt((0x52800000L or ((k2[16].toLong() and 0xFF) shl 5) or 8).toInt()); b.putInt(0xD65F03C0.toInt())
        // 常數：第一段是以 0 結尾的字串；第二段只放前 16 個位元組，後面接別的資料
        b.position(s1); b.put(k1); b.put(0)
        b.position(s2); b.put(k2, 0, 16); b.put("XXXX".toByteArray())
        return b.array()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, d) in entries) { z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test
    fun malformedElfTablesAreRejectedBeforeArithmetic() {
        val lib = fakeLibrary()
        fun patched(offset: Int, bytes: ByteArray) = lib.copyOf().also { bytes.copyInto(it, offset) }
        fun u64(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
        fun u16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())

        assertNull(Elf64.parse(ByteArray(32)))
        // 程式表頭：位置、項目大小、項目數都不能讓整張表跑出檔案
        assertNull(Elf64.parse(patched(32, u64(lib.size.toLong() - 8))))
        assertNull(Elf64.parse(patched(32, u64(Long.MAX_VALUE))))
        assertNull(Elf64.parse(patched(54, u16(0))))
        assertNull(Elf64.parse(patched(56, u16(0xFFFF))))
        // 區段表頭一樣
        assertNull(Elf64.parse(patched(40, u64(lib.size.toLong() - 8))))
        assertNull(Elf64.parse(patched(40, u64(Long.MAX_VALUE))))
        assertNull(Elf64.parse(patched(58, u16(1))))
        assertNull(Elf64.parse(patched(60, u16(0xFFFF))))
        // 載入區段的位移、長度、位址相加都不行
        assertNull(Elf64.parse(patched(64 + 8, u64(Long.MAX_VALUE))))
        assertNull(Elf64.parse(patched(64 + 32, u64(Long.MAX_VALUE))))
        assertNull(Elf64.parse(patched(64 + 16, u64(Long.MAX_VALUE - 8))))

        val symSh = 0x640
        val strSh = 0x680
        // 符號表在檔案外、項目大小不合理、字串表索引或位移在檔案外
        assertNull(Elf64.parse(patched(symSh + 24, u64(Long.MAX_VALUE))))
        assertNull(Elf64.parse(patched(symSh + 32, u64(Long.MAX_VALUE))))
        assertNull(Elf64.parse(patched(symSh + 56, u64(1))))
        assertNull(Elf64.parse(patched(symSh + 40, u16(0xFFFF) + u16(0xFFFF))))
        assertNull(Elf64.parse(patched(strSh + 24, u64(Long.MAX_VALUE))))
        assertNull(Elf64.parse(patched(strSh + 32, u64(Long.MAX_VALUE))))

        // 壞掉的符號名稱不會撐大配置，也不會把後面的解析弄成例外
        val parsed = Elf64.parse(patched(0x418, u16(0xFFFF) + u16(0xFFFF)))
        assertNull(parsed?.symbolEndingWith("getMxKey1"))
    }

    @Test
    fun readsKeysAndDefinitionsFromAMadeUpPackage() {
        val c = TuneContainer(k1, k2)
        val base = zip(
            "assets/ECUFile/ControlFile_S3.dat" to c.encrypt("<ECUControlFile/>".toByteArray()),
            "assets/ECUFile/ScaleData.xml" to "<TableScale/>".toByteArray(),
            "assets/ECUFile/Default_AMX_Template.dat" to c.encrypt("<AMC_File/>".toByteArray()),
            "assets/other.txt" to "x".toByteArray(),
        )
        val split = zip("lib/arm64-v8a/libnative-lib.so" to fakeLibrary())
        val dir = File.createTempFile("xb-pkg", "").apply { delete(); mkdirs() }
        try {
            // 外層包兩個安裝檔
            val xapk = File(dir, "fake.xapk").apply { writeBytes(zip("base.apk" to base, "config.arm64_v8a.apk" to split, "icon.png" to ByteArray(4))) }
            val r = XTunePackage.read(xapk)
            assertContentEquals(k1, r.keys.key1)
            assertContentEquals(k2, r.keys.key2)
            assertEquals(setOf("ControlFile_S3.dat", "ScaleData.xml"), r.definitions.keys)
            // 兩個安裝檔放在同一個資料夾裡
            val folder = File(dir, "apks").apply { mkdirs() }
            File(folder, "base.apk").writeBytes(base); File(folder, "split_config.arm64_v8a.apk").writeBytes(split)
            assertContentEquals(k1, XTunePackage.read(folder).keys.key1)
            // 只有主程式、沒有程式庫：說清楚缺什麼
            val only = File(dir, "base.apk").apply { writeBytes(base) }
            val e = runCatching { XTunePackage.read(only) }.exceptionOrNull()
            assertTrue(e is XTuneImportException && e.message!!.contains("library"))
            // 程式庫是別的處理器的版本
            val x86 = File(dir, "x86.xapk").apply { writeBytes(zip("base.apk" to base, "config.x86_64.apk" to zip("lib/x86_64/libnative-lib.so" to ByteArray(8)))) }
            val e2 = runCatching { XTunePackage.read(x86) }.exceptionOrNull()
            assertTrue(e2 is XTuneImportException && e2.message!!.contains("ARM"))
        } finally { dir.deleteRecursively() }
    }
}
