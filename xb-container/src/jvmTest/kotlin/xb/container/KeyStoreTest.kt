package xb.container

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 金鑰的貼上、存放與驗證。用的是測試自己編的金鑰，不需要任何真實素材。 */
class KeyStoreTest {
    private val k1 = "made-up key one".toByteArray()
    private val k2 = "made-up-key-2".toByteArray()
    private val xml = "<Tune><T id=\"1\">1,2,3</T></Tune>".toByteArray()

    @Test
    fun containerRoundTripAndWrongKeyIsRejected() {
        val c = TuneContainer(k1, k2)
        val packed = c.encrypt(xml)
        assertTrue(TuneContainer.looksEncrypted(packed))
        assertFalse(TuneContainer.looksEncrypted(xml))
        assertContentEquals(xml, c.decrypt(packed))
        assertTrue(c.opens(packed))
        assertFalse(TuneContainer("another key".toByteArray(), k2).opens(packed))
        assertFalse(TuneContainer(k1, "another salt".toByteArray()).opens(packed))
        // 解得開但不是 XML 的也不算
        assertFalse(c.opens(c.encrypt("plain text".toByteArray())))
    }

    @Test
    fun parseAcceptsTextHexAndKeyFile() {
        val a = KeyStore.parse("  abc+DEF#1 \n xyz-9  ")!!
        assertEquals("abc+DEF#1", a.key1.toString(Charsets.UTF_8))
        assertEquals("xyz-9", a.key2.toString(Charsets.UTF_8))
        val h = KeyStore.parse("hex:616263 HEX:78797A")!!
        assertEquals("abc", h.key1.toString(Charsets.UTF_8))
        assertEquals("xyz", h.key2.toString(Charsets.UTF_8))
        val f = KeyStore.parse("#comment\nkey1.hex=616263\nkey2.hex=78797a\n")!!
        assertEquals("abc", f.key1.toString(Charsets.UTF_8))
        assertNull(KeyStore.parse(""))
        assertNull(KeyStore.parse("only-one"))
        assertNull(KeyStore.parse("one two three"))
        assertNull(KeyStore.parse("hex:zz ok"))
        assertNull(KeyStore.parse("key1.hex=6162"))
    }

    @Test
    fun matchingFixesSwappedOrder() {
        val packed = TuneContainer(k1, k2).encrypt(xml)
        val right = KeyStore.matching(UserKeys(k1, k2), packed)
        assertNotNull(right)
        val swapped = KeyStore.matching(UserKeys(k2, k1), packed)
        assertNotNull(swapped)
        assertContentEquals(k1, swapped.key1)
        assertContentEquals(k2, swapped.key2)
        assertNull(KeyStore.matching(UserKeys(k1, "nope".toByteArray()), packed))
    }

    @Test
    fun saveLoadClear() {
        val dir = java.nio.file.Files.createTempDirectory("xb-keys").toFile()
        val file = File(dir, "keys.properties")
        try {
            assertNull(KeyStore.load(file))
            KeyStore.save(UserKeys(k1, k2), file)
            val back = KeyStore.load(file)!!
            assertContentEquals(k1, back.key1)
            assertContentEquals(k2, back.key2)
            KeyStore.clear(file)
            assertNull(KeyStore.load(file))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun encryptedFilesNeedKeys() {
        val dir = File.createTempFile("xb-pack", "").apply { delete(); mkdirs() }
        try {
            val c = TuneContainer(k1, k2)
            File(dir, "ScaleData.dat").writeBytes(c.encrypt("<Scales/>".toByteArray()))
            File(dir, "ControlFile_S3.xml").writeText("<Variables/>")
            val without = DefinitionPack(dir, null as TuneContainer?)
            assertTrue(without.complete)
            assertEquals("<Variables/>", without.text("ControlFile_S3"))
            assertTrue(runCatching { without.text("ScaleData") }.exceptionOrNull() is KeysRequiredException)
            assertTrue(c.opens(without.encryptedSample()!!))
            assertEquals("<Scales/>", DefinitionPack(dir, c).text("ScaleData"))
            val wrong = DefinitionPack(dir, TuneContainer("x".toByteArray(), k2))
            assertTrue(runCatching { wrong.text("ScaleData") }.exceptionOrNull() is TuneOpenException)
        } finally { dir.deleteRecursively() }
    }
}
