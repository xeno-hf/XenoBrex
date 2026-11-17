package xb.app

import java.io.File
import java.util.Properties

/** 使用者設定，存成一個小的 properties 檔。 */
class Settings(private val file: File) {
    private val props = Properties()

    init {
        if (file.exists()) runCatching { file.inputStream().use(props::load) }
    }

    var definitionsDir: String
        get() = props.getProperty("definitionsDir") ?: defaultDefinitionsDir().path
        set(v) { props.setProperty("definitionsDir", v); save() }

    var lastDir: String?
        get() = props.getProperty("lastDir")
        set(v) { if (v == null) props.remove("lastDir") else props.setProperty("lastDir", v); save() }

    var showAll: Boolean
        get() = props.getProperty("showAll") == "true"
        set(v) { props.setProperty("showAll", v.toString()); save() }

    /** 左邊清單用「功能」（照 XTune 調校選單）還是「全部表」；預設功能。 */
    var features: Boolean
        get() = props.getProperty("features") != "false"
        set(v) { props.setProperty("features", v.toString()); save() }

    /** 配色：EMERALD、RUBY、TOPAZ、SYSTEM。 */
    var theme: String
        get() = props.getProperty("theme") ?: "EMERALD"
        set(v) { props.setProperty("theme", v); save() }

    /** 即時調校連的 ECU 位址（ReX 的預設同樣是 192.168.88.88）。 */
    var ecuHost: String
        get() = props.getProperty("ecuHost") ?: "192.168.88.88"
        set(v) { props.setProperty("ecuHost", v); save() }

    /** ECU 密碼（數字）；空的＝出廠的 888888。存在這台電腦的設定檔裡，沒有加密。 */
    var ecuPassword: String
        get() = props.getProperty("ecuPassword") ?: ""
        set(v) { if (v.isEmpty()) props.remove("ecuPassword") else props.setProperty("ecuPassword", v); save() }

    /** 連線時 ECU 原值備份的資料夾；null＝預設（設定資料夾/ecu-backups/）。 */
    var ecuBackupDir: String?
        get() = props.getProperty("ecuBackupDir")
        set(v) { if (v == null) props.remove("ecuBackupDir") else props.setProperty("ecuBackupDir", v); save() }

    /** 介面語言：[Lang.SYSTEM] 或語言 tag。 */
    var language: String
        get() = props.getProperty("language") ?: Lang.SYSTEM
        set(v) { props.setProperty("language", v); save() }

    /** 油圖準星（同 XTune 的 scope 開關，預設開）。 */
    var scopeOn: Boolean
        get() = props.getProperty("scopeOn") != "false"
        set(v) { props.setProperty("scopeOn", v.toString()); save() }

    /** Cell Track（XTune 預設關）。 */
    var cellTrack: Boolean
        get() = props.getProperty("cellTrack") == "true"
        set(v) { props.setProperty("cellTrack", v.toString()); save() }

    /** 準星四角顯示即時值（XTune，預設關）。 */
    var scopeShowVars: Boolean
        get() = props.getProperty("scopeShowVars") == "true"
        set(v) { props.setProperty("scopeShowVars", v.toString()); save() }

    /** 四角的 4 個即時變數 absid（XTune，預設 26,45,34,55＝RPM、進氣壓力、TPS%、AFR）。 */
    var scopeVars: List<Int>
        get() = (props.getProperty("scopeVars") ?: DEFAULT_SCOPE_VARS).split(',').mapNotNull { it.trim().toIntOrNull() }
            .takeIf { it.size == 4 } ?: DEFAULT_SCOPE_VARS.split(',').map { it.toInt() }
        set(v) { props.setProperty("scopeVars", v.joinToString(",")); save() }

    /** 手機同步（ReX 連進來）的伺服器開著（預設關）。 */
    var syncOn: Boolean
        get() = props.getProperty("syncOn") == "true"
        set(v) { props.setProperty("syncOn", v.toString()); save() }

    var syncPort: Int
        get() = props.getProperty("syncPort")?.toIntOrNull()?.takeIf { it in 1024..65535 } ?: 6680
        set(v) { props.setProperty("syncPort", v.toString()); save() }

    /** 手機同步過來的錄製放哪；null＝預設（[xb.container.XbPaths.ridesDir]）。 */
    var ridesDir: String?
        get() = props.getProperty("ridesDir")
        set(v) { if (v == null) props.remove("ridesDir") else props.setProperty("ridesDir", v); save() }

    /** 使用者自己調的介面縮放，疊在系統縮放上面。1.0＝不調。 */
    var zoom: Float
        get() = props.getProperty("zoom")?.toFloatOrNull()?.coerceIn(ZOOM_MIN, ZOOM_MAX) ?: 1f
        set(v) { props.setProperty("zoom", v.toString()); save() }

    /** 這張表的步進值（XTune<表名>）；沒設過是 null，用 [xb.tune.CalStep.default]。 */
    fun calStep(key: String): Double? = props.getProperty(key)?.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
    fun setCalStep(key: String, v: Double) { props.setProperty(key, v.toString()); save() }

    private fun save() {
        runCatching {
            val path = file.toPath()
            // 不跟符號連結：設定檔指到別處時不覆寫
            if (java.nio.file.Files.isSymbolicLink(path)) return@runCatching
            path.toAbsolutePath().parent?.let { xb.container.SafeFiles.ensurePrivateDirectory(it) }
            xb.container.SafeFiles.atomicWrite(path, secret = true) { props.store(it, "XenoBrex") }
        }
    }

    companion object {
        const val ZOOM_MIN = 0.7f
        const val ZOOM_MAX = 2.5f
        const val DEFAULT_SCOPE_VARS = "26,45,34,55"

        fun configDir(): File = xb.container.KeyStore.configDir()

        fun defaultDefinitionsDir(): File = xb.container.XbPaths.defaultDefinitionsDir()

        fun default() = Settings(xb.container.XbPaths.settingsFile())
    }
}
