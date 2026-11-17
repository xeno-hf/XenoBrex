package xb.container

import xb.tune.Xml
import xb.tune.XmlException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 不使用私人素材，也不連網。 */
class FileSafetyTest {
    @Test fun stockNamesCannotEscapeCacheOrUseSpecialWindowsNames() {
        val dir = File("cache/stock").absoluteFile
        val valid = "SX2_05_18_10_01_v2c72.asx2"
        val resolved = OfficialStock.cacheFile(dir, valid).toPath()
        assertEquals(dir.toPath().normalize(), resolved.parent)
        for (name in listOf("../x", "..\\x", "/tmp/x", "C:\\x", ".hidden", "a/b", "a\\b", "CON", "nul.dat", "COM1.xml", "lpt9", "a.", "a ", "a".repeat(181))) {
            assertFailsWith<OfficialStock.NoBaselineException>(name) { OfficialStock.cacheFile(dir, name) }
        }
    }

    @Test fun stockDownloadCountsActualBytesAndStopsEarly() {
        var read = 0
        val endless = object : InputStream() {
            override fun read(): Int { read++; return 65 }
        }
        assertFailsWith<OfficialStock.NoBaselineException> { OfficialStock.readDownload(endless, 32) }
        assertTrue(read <= 33, "oversize stream must stop promptly")
        assertContentEquals(byteArrayOf(1, 2, 3), OfficialStock.readDownload(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3))
        assertFailsWith<OfficialStock.NoBaselineException> { OfficialStock.readDownload(ByteArrayInputStream("<html>error</html>".toByteArray())) }
    }

    private fun list(name: String) = """<List ver="1"><Model name="test"><type><amx>$name</amx><amx_md5>${"a".repeat(32)}</amx_md5></type></Model></List>""".toByteArray()

    @Test fun stockListRejectsUnsafeNamesAndDtds() {
        val parsed = OfficialStock.parseList(OfficialStock.Source.SX2, list("SX2_05_18_10_01_v2c72.asx2"))
        assertEquals(72, parsed.entries.single().cCode)
        assertFailsWith<OfficialStock.NoBaselineException> { OfficialStock.parseList(OfficialStock.Source.SX2, list("..\\outside")) }
        val xxe = """<!DOCTYPE List [<!ENTITY x SYSTEM "file:///not-read">]><List>&x;</List>""".toByteArray()
        assertFailsWith<OfficialStock.NoBaselineException> { OfficialStock.parseList(OfficialStock.Source.SX2, xxe) }
    }

    @Test fun stockContentMustMatchChecksumPlatformAndVersionBeforeCaching() {
        val dir = Files.createTempDirectory("xb-stock-test-").toFile()
        try {
            File(dir, "ScaleData.xml").writeText("<Scales/>")
            File(dir, "ControlFile_X2.xml").writeText("<Definitions><Parameters/></Definitions>")
            File(dir, "ControlFile_S3.xml").writeText("<Definitions><Parameters/></Definitions>")
            val pack = DefinitionPack(dir, null as TuneContainer?)
            val container = TuneContainer("test-one".toByteArray(), "test-two".toByteArray())
            val target = File(dir, "cached.asx2").apply { writeText("old cache") }
            val name = "SX2_05_18_10_01_v2c72.asx2"
            fun content(type: String, version: Int) = """<AMC_File><MAP_Type>$type</MAP_Type><V_Code>2</V_Code><C_Code>$version</C_Code><A_Code>1</A_Code><Table_ID>05_18_10_01</Table_ID><LastSaveTime/><CarNote/><IO_Config/><TableValue/></AMC_File>""".toByteArray()
            fun entry(bytes: ByteArray) = OfficialStock.Entry("test", "", "", name,
                java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02X".format(it) }, null)
            val good = container.encrypt(content("ASX2", 72))
            val e = entry(good)
            for ((bytes, manifestEntry) in listOf(
                good to OfficialStock.Entry("test", "", "", name, "0".repeat(32), null),
                container.encrypt(content("ASX", 72)).let { it to entry(it) },
                container.encrypt(content("ASX2", 73)).let { it to entry(it) },
            )) {
                assertFailsWith<OfficialStock.NoBaselineException> {
                    SafeFiles.atomicWrite(target.toPath(), validate = { tmp -> OfficialStock.validateStock(tmp.toFile(), OfficialStock.Source.SX2, manifestEntry, "05_18_10_01", pack, container) }) { it.write(bytes) }
                }
                assertEquals("old cache", target.readText())
            }
            SafeFiles.atomicWrite(target.toPath(), validate = { tmp -> OfficialStock.validateStock(tmp.toFile(), OfficialStock.Source.SX2, e, "05_18_10_01", pack, container) }) { it.write(good) }
            assertContentEquals(good, target.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun commonXmlHasDepthAndElementLimits() {
        assertEquals("x", Xml.parse("<x>".repeat(64) + "</x>".repeat(64)).name)
        assertFailsWith<XmlException> { Xml.parse("<x>".repeat(65) + "</x>".repeat(65)) }
        assertFailsWith<XmlException> { Xml.parse("<x>" + "<a/>".repeat(300_000) + "</x>") }
    }

    @Test fun keyFilesAndParentsAreOwnerOnlyAndSymlinksAreRefused() {
        val root = Files.createTempDirectory("xb-private-")
        try {
            val file = root.resolve("keys/keys.properties")
            val keys = UserKeys("test-key-one".toByteArray(), "test-key-two".toByteArray())
            KeyStore.save(keys, file.toFile())
            if (Files.getFileAttributeView(root, PosixFileAttributeView::class.java) != null) {
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file))
                assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(file.parent))
                val outside = root.resolve("untouched").also { Files.writeString(it, "keep") }
                Files.delete(file)
                Files.createSymbolicLink(file, outside)
                assertFailsWith<IOException> { KeyStore.save(keys, file.toFile()) }
                assertEquals("keep", Files.readString(outside))
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun secretsFailBeforeWritingWhenOwnerOnlyCannotBeEstablished() {
        // 舊介面做不到時（任何一步回 false）就要先失敗
        val dir = Files.createTempDirectory("xb-perm-")
        try {
            assertFailsWith<IOException> { SafeFiles.legacyOwnerOnly(dir.resolve("keys.properties").toFile(), false) { _, _ -> false } }
            assertFalse(Files.exists(dir.resolve("keys.properties")))
        } finally { dir.toFile().deleteRecursively() }

        // 沒有 POSIX 也沒有 ACL 的檔案系統：秘密檔不得先寫下去
        val zipDir = Files.createTempDirectory("xb-noacl-")
        val zip = zipDir.resolve("new.zip")
        try {
            java.nio.file.FileSystems.newFileSystem(java.net.URI.create("jar:${zip.toUri()}"), mapOf("create" to "true")).use { fs ->
                val keys = fs.getPath("/keys")
                Files.createDirectories(keys)
                val target = keys.resolve("keys.properties")
                assertFailsWith<IOException> { SafeFiles.atomicWrite(target, secret = true) { it.write("key=secret".toByteArray()) } }
                assertFalse(Files.exists(target), "秘密檔不應該先寫進去")
            }
        } finally { zipDir.toFile().deleteRecursively() }
    }

    @Test fun atomicValidationFailureLeavesTargetAndCleansTemp() {
        val dir = Files.createTempDirectory("xb-atomic-")
        try {
            val target = dir.resolve("tune.amx").also { Files.writeString(it, "old") }
            assertFailsWith<IOException> {
                SafeFiles.atomicWrite(target, validate = { throw IOException("invalid") }) { it.write("new".toByteArray()) }
            }
            assertEquals("old", Files.readString(target))
            Files.list(dir).use { assertEquals(listOf(target), it.toList()) }
        } finally { dir.toFile().deleteRecursively() }
    }

    private fun zip(vararg members: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> for ((name, bytes) in members) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
    }.toByteArray()

    private fun checkPackage(bytes: ByteArray, limits: ArchiveLimits) {
        val file = Files.createTempFile("xb-limits-", ".apk").toFile()
        try { file.writeBytes(bytes); XTunePackage.validateArchive(file, limits) }
        finally { file.delete() }
    }

    private val small = ArchiveLimits(4096, 32, 64, 3, 1)

    @Test fun apkRejectsTooManyEntriesAndActualExpandedSizes() {
        assertFailsWith<XTuneImportException> { checkPackage(zip("a" to ByteArray(0), "b" to ByteArray(0), "c" to ByteArray(0), "d" to ByteArray(0)), small) }
        assertFailsWith<XTuneImportException> { checkPackage(zip("ignored" to ByteArray(33)), small) }
        assertFailsWith<XTuneImportException> { checkPackage(zip("a" to ByteArray(30), "b" to ByteArray(30), "c" to ByteArray(30)), small) }
        assertFailsWith<XTuneImportException> { checkPackage(zip("a" to ByteArray(0)), small.copy(compressed = 8)) }
    }

    @Test fun apkRejectsDuplicatesTraversalAndNestedDepth() {
        val duplicate = zip("aa" to ByteArray(0), "bb" to ByteArray(0))
        for (i in 0 until duplicate.size - 1) if (duplicate[i] == 'b'.code.toByte() && duplicate[i + 1] == 'b'.code.toByte()) {
            duplicate[i] = 'a'.code.toByte(); duplicate[i + 1] = 'a'.code.toByte()
        }
        assertFailsWith<XTuneImportException> { checkPackage(duplicate, small) }
        for (name in listOf("../outside", "a/../../outside", "a\\outside", "/outside", "C:/outside", "NUL.dat", "assets/ECUFile/ControlFile_X/evil")) {
            assertFailsWith<XTuneImportException>(name) { checkPackage(zip(name to ByteArray(0)), small) }
        }
        val nested = zip("a.apk" to zip("b.apk" to zip("data" to ByteArray(0))))
        assertFailsWith<XTuneImportException> { checkPackage(nested, small.copy(entry = 2048, total = 4096)) }
    }

    @Test fun directoryEntriesCannotHideUnverifiedPayloads() {
        val project = TestArchives.xbx(listOf("tune.amx" to ByteArray(0)), extra = listOf("hidden/" to byteArrayOf(1)))
        assertFailsWith<XbxException> { XbxProject.read(project) }
        val file = Files.createTempFile("xb-directory-", ".xbl").toFile()
        try {
            file.writeBytes(TestArchives.xbl(extra = listOf("hidden/" to byteArrayOf(1))))
            assertFailsWith<XbxException> { XblRide.open(file) }
        } finally { file.delete() }
    }

    @Test fun nestedExportPathsCannotTraverseSymlinkParents() {
        val root = Files.createTempDirectory("xb-export-")
        val outside = Files.createTempDirectory("xb-outside-")
        try {
            val target = SafeFiles.resolveMember(root, "future/notes.txt")
            assertTrue(target.startsWith(root))
            if (Files.getFileAttributeView(root, PosixFileAttributeView::class.java) != null) {
                val link = Files.createSymbolicLink(root.resolve("future"), outside)
                // 用 root 跑時連結也歸 root，會被當成系統連結放行，這個檢查不適用
                if (uid(link) != 0) assertFailsWith<IOException> { SafeFiles.resolveMember(root, "future/notes.txt") }
            }
        } finally { root.toFile().deleteRecursively(); outside.toFile().deleteRecursively() }
    }

    private fun uid(p: java.nio.file.Path): Int? =
        runCatching { Files.getAttribute(p, "unix:uid", java.nio.file.LinkOption.NOFOLLOW_LINKS) as Int }.getOrNull()

    /** 系統本身的連結（root 擁有，例如 macOS 的 /var、合併 /usr 後的 /bin）可以經過；使用者自己的連結照擋（上一個測試）。 */
    @Test fun rootOwnedSystemLinksAreAllowed() {
        val system = listOf("/var", "/tmp", "/home", "/bin", "/lib").map { java.nio.file.Path.of(it) }
            .firstOrNull { Files.isSymbolicLink(it) && uid(it) == 0 }
        if (system == null) { println("rootOwnedSystemLinksAreAllowed: no root-owned system link on this host, skipped"); return }
        assertTrue(SafeFiles.resolveMember(system, "future/notes.txt").startsWith(system))
    }

    /** 自己建的連結就算指向 root 的資料夾也要擋：看的是連結本身的擁有者，不是它指向的地方。 */
    @Test fun userLinkToARootDirectoryIsRefused() {
        val root = Files.createTempDirectory("xb-link-")
        try {
            if (Files.getFileAttributeView(root, PosixFileAttributeView::class.java) == null) return
            val link = Files.createSymbolicLink(root.resolve("sys"), java.nio.file.Path.of("/usr"))
            if (uid(link) == 0) return
            assertFailsWith<IOException> { SafeFiles.resolveMember(root, "sys/notes.txt") }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun definitionInstallationRejectsWindowsCaseCollisions() {
        val dir = Files.createTempDirectory("xb-case-")
        try {
            val result = XTunePackage.Result(UserKeys(byteArrayOf(1), byteArrayOf(2)), linkedMapOf("ScaleData" to byteArrayOf(1), "scaledata" to byteArrayOf(2)))
            assertFailsWith<XTuneImportException> { XTunePackage.installDefinitions(result, dir.toFile()) }
            Files.list(dir).use { assertEquals(0L, it.count()) }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun installedDefinitionNamesAreStrictlyWhitelisted() {
        for (name in listOf("ControlFile_S3.dat", "ScaleData.xml", "XECU_TabShortName.dat", "XECU_GroupDes.txt")) assertTrue(XTunePackage.isDefinitionName(name))
        assertFalse(XTunePackage.isDefinitionName("arbitrary.exe"))
        for (name in listOf("..\\ControlFile_X", "ControlFile_X/evil", ".hidden", "CON")) {
            assertFailsWith<IllegalArgumentException> { XTunePackage.isDefinitionName(name) }
        }
    }
}
