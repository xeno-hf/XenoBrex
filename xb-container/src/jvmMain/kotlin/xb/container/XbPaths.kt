package xb.container

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

/** 桌面版與 xbrex 共用的位置：設定、定義檔、紀錄、ECU 備份。 */
object XbPaths {
    private fun os() = System.getProperty("os.name").lowercase()
    private fun home() = File(System.getProperty("user.home"))

    fun configDir(): File = KeyStore.configDir()
    fun settingsFile(): File = File(configDir(), "settings.properties")
    fun logFile(): File = File(configDir(), "logs/xenobrex.log")
    fun backupDir(): File = File(configDir(), "ecu-backups")
    fun syncDir(): File = File(configDir(), "sync")

    /** 手機同步過來的東西（錄製 .xbl、即時資料的副本）。環境變數 `XB_RIDES` 優先，其次桌面版設定的 `ridesDir`。 */
    fun ridesDir(): File {
        System.getenv("XB_RIDES")?.let { return File(it) }
        val p = Properties()
        runCatching { settingsFile().inputStream().use(p::load) }
        p.getProperty("ridesDir")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        return when {
            os().contains("win") -> File(System.getenv("APPDATA") ?: home().path, "XenoBrex/rides")
            os().contains("mac") -> File(home(), "Library/Application Support/XenoBrex/rides")
            else -> File(System.getenv("XDG_DATA_HOME") ?: File(home(), ".local/share").path, "xenobrex/rides")
        }
    }

    /** 沒設定時的定義檔資料夾。環境變數 `XB_DEFINITIONS` 優先。 */
    fun defaultDefinitionsDir(): File {
        System.getenv("XB_DEFINITIONS")?.let { return File(it) }
        return when {
            os().contains("win") -> File(System.getenv("APPDATA") ?: home().path, "XenoBrex/definitions")
            os().contains("mac") -> File(home(), "Library/Application Support/XenoBrex/definitions")
            else -> File(System.getenv("XDG_DATA_HOME") ?: File(home(), ".local/share").path, "xenobrex/definitions")
        }
    }

    /** 實際用的定義檔資料夾：`XB_DEFINITIONS` → 桌面版設定裡選的 → 預設。 */
    fun definitionsDir(): File {
        System.getenv("XB_DEFINITIONS")?.let { return File(it) }
        val p = Properties()
        runCatching { settingsFile().inputStream().use(p::load) }
        return p.getProperty("definitionsDir")?.let(::File) ?: defaultDefinitionsDir()
    }
}

/**
 * 紀錄檔（設定資料夾/logs/xenobrex.log）：連線、ECU 回應、錯誤。給使用者或 Agent 除錯用（`xbrex log`）。
 * 不寫金鑰、密碼與調校內容。超過 1 MB 就換成 .1。
 */
object AppLog {
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private const val MAX = 1L shl 20

    @Synchronized
    fun write(source: String, line: String) {
        runCatching {
            val f = XbPaths.logFile()
            f.parentFile.mkdirs()
            if (f.length() > MAX) f.renameTo(File(f.path + ".1").also { it.delete() })
            f.appendText("${LocalDateTime.now().format(TIME)} [$source] ${line.replace('\n', ' ')}\n")
        }
    }
}
