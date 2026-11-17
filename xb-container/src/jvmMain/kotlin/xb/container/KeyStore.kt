package xb.container

import java.io.File
import java.util.Properties

/** 要開加密的檔案，但使用者還沒有提供金鑰。 */
class KeysRequiredException : Exception("This file is encrypted; keys are required to open it")

/** 使用者自己提供的兩段金鑰。 */
class UserKeys(val key1: ByteArray, val key2: ByteArray) {
    val container: TuneContainer by lazy { TuneContainer(key1, key2) }
    fun swapped() = UserKeys(key2, key1)
}

/**
 * 金鑰的來源與存放。
 * XenoBrex 不帶金鑰。使用者把自己取得的金鑰貼進來，存在自己電腦的設定資料夾裡；
 * 之後開檔、存檔都用這一份。程式不會把它送到任何地方。
 */
object KeyStore {
    private fun os() = System.getProperty("os.name").lowercase()
    private fun home() = File(System.getProperty("user.home"))

    /** 設定資料夾，各系統放在慣用的位置。 */
    fun configDir(): File = when {
        os().contains("win") -> File(System.getenv("APPDATA") ?: home().path, "XenoBrex")
        os().contains("mac") -> File(home(), "Library/Application Support/XenoBrex")
        else -> File(System.getenv("XDG_CONFIG_HOME") ?: File(home(), ".config").path, "xenobrex")
    }

    /** 金鑰檔的位置。環境變數 `XB_KEYS` 可以指到別的檔案。 */
    fun defaultFile(): File = System.getenv("XB_KEYS")?.let(::File) ?: File(configDir(), "keys.properties")

    fun load(file: File = defaultFile()): UserKeys? {
        if (!file.isFile) return null
        val p = Properties()
        runCatching { file.inputStream().use(p::load) }.getOrElse { return null }
        return fromProperties(p)
    }

    fun save(keys: UserKeys, file: File = defaultFile()) {
        val p = Properties()
        p.setProperty("key1.hex", hex(keys.key1))
        p.setProperty("key2.hex", hex(keys.key2))
        SafeFiles.atomicWrite(file.toPath(), secret = true) { p.store(it, "XenoBrex keys") }
    }

    fun clear(file: File = defaultFile()) { file.delete() }

    /**
     * 把使用者貼上的文字變成金鑰。接受兩種寫法：
     * - 兩段文字，中間用空白或換行分開；每一段可以是原文，或 `hex:` 開頭的十六進位。
     * - 金鑰檔的內容（`key1.hex=…`、`key2.hex=…`）。
     * 格式不對回 null。這裡不檢查金鑰對不對，那要拿加密的檔案試（[matching]）。
     */
    fun parse(text: String): UserKeys? {
        if (text.contains("key1.hex")) {
            val p = Properties()
            runCatching { p.load(text.reader()) }.getOrElse { return null }
            return fromProperties(p)
        }
        val parts = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val a = token(parts[0]) ?: return null
        val b = token(parts[1]) ?: return null
        return UserKeys(a, b)
    }

    /**
     * 拿一個加密的檔案試金鑰：照貼上的順序，不行就前後對調再試。
     * 能解開就回傳順序正確的金鑰，否則回 null。
     */
    fun matching(keys: UserKeys, sample: ByteArray): UserKeys? = when {
        keys.container.opens(sample) -> keys
        keys.swapped().container.opens(sample) -> keys.swapped()
        else -> null
    }

    private fun fromProperties(p: Properties): UserKeys? {
        val a = unhex(p.getProperty("key1.hex") ?: return null) ?: return null
        val b = unhex(p.getProperty("key2.hex") ?: return null) ?: return null
        return if (a.isEmpty() || b.isEmpty()) null else UserKeys(a, b)
    }

    private fun token(s: String): ByteArray? {
        val bytes = if (s.startsWith("hex:", ignoreCase = true)) unhex(s.substring(4)) else s.toByteArray(Charsets.UTF_8)
        return bytes?.takeIf { it.isNotEmpty() }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): ByteArray? {
        val t = s.trim()
        if (t.length % 2 != 0 || !t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
