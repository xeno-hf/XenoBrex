package xb.container

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class XblRideTest {
    private fun save(bytes: ByteArray): File {
        val dir = kotlin.io.path.createTempDirectory("xbl").toFile()
        val f = File(dir, "ride.xbl")
        f.writeBytes(bytes)
        return f
    }

    @Test
    fun opensAndVerifiesEveryMember() {
        val members = TestArchives.defaultRideMembers()
        val f = save(TestArchives.xbl(members))
        try {
            val r = XblRide.open(f)
            assertEquals((members.map { it.first } + listOf("mimetype", "manifest.json")).toSet(), r.members.toSet())
            assertEquals("Mini X", r.vehicle["platform"])
            for ((name, bytes) in members) assertContentEquals(bytes, r.bytes(name), name)
            assertContentEquals(XblRide.MIME.toByteArray(), r.bytes("mimetype"))
            val manifest = ZipFile(f).use { z -> z.getInputStream(z.getEntry("manifest.json")).use { it.readBytes() } }
            assertContentEquals(manifest, r.bytes("manifest.json"))
            assertEquals(1, r.writes().size)
        } finally { f.parentFile.deleteRecursively() }
    }

    @Test
    fun checksumAndPresenceProblemsAreRefused() {
        val members = TestArchives.defaultRideMembers()
        val listed = members.toMap().mapValues { TestArchives.sha(it.value) }
        val badHash = listed + ("decoded.loga" to TestArchives.sha("other".toByteArray()))
        val f1 = save(TestArchives.xbl(members, badHash))
        try {
            assertTrue(assertFailsWith<XbxException> { XblRide.open(f1) }.message!!.contains("checksum"))
        } finally { f1.parentFile.deleteRecursively() }

        val withoutLoga = members.filter { it.first != "decoded.loga" }
        val f2 = save(TestArchives.xbl(withoutLoga, listed))
        try {
            assertTrue(assertFailsWith<XbxException> { XblRide.open(f2) }.message!!.contains("missing"))
        } finally { f2.parentFile.deleteRecursively() }

        val extra = members + ("extra.bin" to byteArrayOf(1))
        val f3 = save(TestArchives.xbl(extra, listed))
        try {
            assertTrue(assertFailsWith<XbxException> { XblRide.open(f3) }.message!!.contains("not listed"))
        } finally { f3.parentFile.deleteRecursively() }
    }

    @Test
    fun formatIdentifiersMustBeExactlySupported() {
        for (bad in listOf("xbl-0", "xbl-01", "xbl-1.0", "xbl-2", "xbx-1", "foo-1", "XBL-1", "")) {
            val f = save(TestArchives.xbl(format = bad))
            try {
                assertFailsWith<XbxException>(bad) { XblRide.open(f) }
            } finally { f.parentFile.deleteRecursively() }
        }
        val f = save(TestArchives.xbl(format = "xbl-2"))
        try {
            assertTrue(assertFailsWith<XbxException> { XblRide.open(f) }.message!!.contains("newer"))
        } finally { f.parentFile.deleteRecursively() }
    }

    @Test
    fun coreMembersAreRequired() {
        val members = TestArchives.defaultRideMembers()
        for (core in listOf("frames.rxraw", "decoded.loga")) {
            val without = members.filter { it.first != core }
            val f = save(TestArchives.xbl(without))
            try {
                val e = assertFailsWith<XbxException>(core) { XblRide.open(f) }
                assertTrue(e.message!!.contains(core), e.message)
            } finally { f.parentFile.deleteRecursively() }
        }
    }

    @Test
    fun unsafeAndDuplicateNamesAreRefused() {
        val members = TestArchives.defaultRideMembers()
        for (name in listOf("../evil", "a\\evil", "/evil", "C:/evil", "a//b", "CON", "aux.txt", "conin$", "a".repeat(256))) {
            val f = save(TestArchives.xbl(extra = listOf(name to byteArrayOf(1))))
            try {
                assertFailsWith<XbxException>(name) { XblRide.open(f) }
            } finally { f.parentFile.deleteRecursively() }
        }
        // 同名檔案／目錄也算重複，不能靠 ZIP 的目錄項繞過。
        val f = save(TestArchives.xbl(extra = listOf("decoded.loga/" to ByteArray(0))))
        try {
            assertFailsWith<XbxException> { XblRide.open(f) }
        } finally { f.parentFile.deleteRecursively() }
    }

    @Test
    fun bytesReverifiesAgainstTheManifestAfterTheFileChanges() {
        val members = TestArchives.defaultRideMembers()
        val f = save(TestArchives.xbl(members))
        try {
            val r = XblRide.open(f)
            assertNotNull(r.bytes("decoded.loga"))
            val changed = members.map { if (it.first == "decoded.loga") it.first to "different".toByteArray() else it }
            f.writeBytes(TestArchives.xbl(changed))
            assertTrue(assertFailsWith<XbxException> { r.bytes("decoded.loga") }.message!!.contains("checksum"))
            assertTrue(assertFailsWith<XbxException> { r.bytes("manifest.json") }.message!!.contains("checksum"))
        } finally { f.parentFile.deleteRecursively() }
    }
}
