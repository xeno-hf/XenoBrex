package xb.container

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 調校檔（.amx／.asx…）與定義檔的容器：Base64( AES-128-CBC-PKCS7( 內容 ) )。
 * ```
 * dk = PBKDF2-HMAC-SHA1(key1, salt = key2, 1000 次, 32 位元組)
 * AES 金鑰 = dk[0..16)、IV = dk[16..32)
 * ```
 * 金鑰由使用者提供（見 [KeyStore]）；程式不帶金鑰。UMA M9 用另一把鑰，這裡沒有處理。
 */
class TuneContainer(key1: ByteArray, key2: ByteArray) {
    private val dk = pbkdf2Sha1(key1, key2, 1000, 32)

    private fun cipher(mode: Int): Cipher =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(dk.copyOfRange(0, 16), "AES"), IvParameterSpec(dk.copyOfRange(16, 32)))
        }

    /** 解開容器，回傳內容的位元組（通常是 UTF-8 的 XML）。開頭的 BOM 與 Base64 裡的空白會略過。 */
    fun decrypt(file: ByteArray): ByteArray {
        var start = 0
        if (file.size >= 3 && file[0] == 0xEF.toByte() && file[1] == 0xBB.toByte() && file[2] == 0xBF.toByte()) start = 3
        val b64 = ByteArray(file.size - start)
        var n = 0
        for (i in start until file.size) {
            val c = file[i].toInt()
            if (c != ' '.code && c != '\n'.code && c != '\r'.code && c != '\t'.code) b64[n++] = file[i]
        }
        return cipher(Cipher.DECRYPT_MODE).doFinal(Base64.getDecoder().decode(b64.copyOf(n)))
    }

    /** 這組金鑰解不解得開 [file]：解得開、而且內容是 XML 才算。用來確認使用者貼的金鑰對不對。 */
    fun opens(file: ByteArray): Boolean {
        val plain = runCatching { decrypt(file) }.getOrElse { return false }
        var i = 0
        if (plain.size >= 3 && plain[0] == 0xEF.toByte() && plain[1] == 0xBB.toByte() && plain[2] == 0xBF.toByte()) i = 3
        return i < plain.size && plain[i] == '<'.code.toByte()
    }

    /** 包成容器：單行 Base64，沒有 BOM、沒有換行。 */
    fun encrypt(content: ByteArray): ByteArray =
        Base64.getEncoder().encode(cipher(Cipher.ENCRYPT_MODE).doFinal(content))

    companion object {
        /** 內容看起來是不是加密的容器（Base64 文字），而不是已解開的 XML。 */
        fun looksEncrypted(file: ByteArray): Boolean {
            var i = 0
            if (file.size >= 3 && file[0] == 0xEF.toByte() && file[1] == 0xBB.toByte() && file[2] == 0xBF.toByte()) i = 3
            while (i < file.size && (file[i] == ' '.code.toByte() || file[i] == '\n'.code.toByte() || file[i] == '\r'.code.toByte() || file[i] == '\t'.code.toByte())) i++
            return i < file.size && file[i] != '<'.code.toByte()
        }

        fun pbkdf2Sha1(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(password, "HmacSHA1"))
            val out = ByteArray(dkLen)
            var block = 1
            var pos = 0
            while (pos < dkLen) {
                mac.update(salt)
                mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
                var u = mac.doFinal()
                val t = u.copyOf()
                repeat(iterations - 1) {
                    u = mac.doFinal(u)
                    for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                }
                val take = minOf(t.size, dkLen - pos)
                System.arraycopy(t, 0, out, pos, take)
                pos += take
                block++
            }
            return out
        }
    }
}
