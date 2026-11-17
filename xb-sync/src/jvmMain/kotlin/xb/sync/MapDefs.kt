package xb.sync

import xb.container.DefinitionPack
import xb.container.Json
import xb.tune.Platform
import xb.tune.Scope
import xb.tune.TableDef
import xb.tune.TableKind

/**
 * 油圖定義包（給 ReX 的油圖頁，只看不改）：XB 從使用者自己的定義檔挑出「有準星的表」，
 * 寫成 ReX 不用金鑰、不用解定義檔就能畫的 JSON。表的內容不在包裡，ReX 連上 ECU 自己讀（物件 0x3000＋表號）。
 * ```
 * format rx-mapdefs-1
 * platform 定義檔名（S3、X2…），map_type、c、model：做這份包時用的型號（c、model 可能是 null＝沒篩）
 * tables[] id name title{語言:短名} group cols rows（ECU 上存的格數）bytes gain offset min max dec unit
 *           scope[x 變數 absid, y 變數 absid]（0＝那個方向沒有）
 *           x / y：{name, values[]}＝固定刻度；{name, table}＝動態軸，刻度是那張表第 0 列、取到不再遞增為止
 * axes[] 動態軸用到的表：id cols bytes gain offset min max dec
 * ```
 * gain／offset 是單精度（同 STX），ReX 讀進來要先轉回 Float 再換算，結果才會跟 XB 一樣。
 */
object MapDefs {
    const val FORMAT = "rx-mapdefs-1"
    val LANGS = listOf("en-US", "zh-TW", "zh-CN", "ja-JP")

    class NoDefinitions(message: String) : Exception(message)

    /** [mapType]：調校檔型號（AMX、AMX2…）或定義檔名（S3、X2…）。[c]、[model] 是 null 就不照版本／機型篩。 */
    fun build(pack: DefinitionPack, mapType: String, c: Int?, model: Int?, app: String): String {
        val platform = Platform.ofMapType(mapType.uppercase()) ?: Platform.entries.firstOrNull { it.definition.equals(mapType, true) }
            ?: throw NoDefinitions("Unknown ECU type $mapType")
        val defs = pack.definitions(platform.definition) ?: throw NoDefinitions("No ${platform.definition} definitions on this computer")
        val scales = pack.scales ?: throw NoDefinitions("No ScaleData on this computer")
        val labels = pack.labels(platform.labelSet)
        val tables = ArrayList<Map<String, Any?>>()
        val axisTables = LinkedHashMap<Int, TableDef>()
        for (def in defs.tables.values.sortedBy { it.absId }) {
            if (!wanted(def, c, model)) continue
            val (sx, sy) = Scope.vars(def) ?: continue
            fun axis(key: String): Map<String, Any?>? {
                if (key == "0") return null
                if (key.contains("Dyna-")) {
                    val parts = key.trim().split('-')
                    val id = parts.getOrNull(1)?.toIntOrNull() ?: return null
                    val ref = defs.tables[id]?.takeIf { it.cellBytes in 1..4 } ?: return null
                    axisTables[id] = ref
                    return mapOf("name" to (parts.getOrNull(2) ?: ""), "table" to id)
                }
                val values = scales.find(key) ?: return null
                return mapOf("name" to key.substring(0, key.lastIndexOf('-')), "values" to values.map { it.toDouble() })
            }
            val x = axis(def.scaleX)
            val y = axis(def.scaleY)
            // 有準星的方向一定要有軸，不然畫不出來
            if ((sx != null && x == null) || (sy != null && y == null)) continue
            tables += linkedMapOf(
                "id" to def.absId, "name" to def.name,
                "title" to LANGS.mapNotNull { l -> labels.shortName(def.absId, l)?.let { l to it } }.toMap(),
                "group" to def.group,
                "cols" to def.storedColumns, "rows" to def.storedRows, "bytes" to def.cellBytes,
                "gain" to def.gain, "offset" to def.offset, "min" to def.min, "max" to def.max, "dec" to def.decimals, "unit" to def.unit,
                "scope" to listOf(sx ?: 0, sy ?: 0),
                "x" to x, "y" to y,
            )
        }
        if (tables.isEmpty()) throw NoDefinitions("No map tables for $mapType C$c")
        val axes = axisTables.values.map { a ->
            linkedMapOf("id" to a.absId, "cols" to a.storedColumns, "bytes" to a.cellBytes,
                "gain" to a.gain, "offset" to a.offset, "min" to a.min, "max" to a.max, "dec" to a.decimals)
        }
        return Json.write(linkedMapOf(
            "format" to FORMAT, "app" to app, "serial" to defs.serial,
            "platform" to platform.definition, "map_type" to mapType.uppercase(), "c" to c, "model" to model,
            "tables" to tables, "axes" to axes,
        ))
    }

    /** ECU 本體上、有物件可讀的表；有給版本／機型就只留這個版本啟用、這個機型看得到的。 */
    private fun wanted(def: TableDef, c: Int?, model: Int?): Boolean {
        if (def.isEeprom || def.absId !in 0 until 0x1000 || def.cellBytes !in 1..4 || def.kind == TableKind.D1) return false
        if (c != null) {
            val v = def.versions[c] ?: return false
            if (!v.enabled) return false
            if (model != null && v.levels[model] == null) return false
        }
        return true
    }

    /** 表 1001 的車種代碼（05_18_10_01）的機型：第 3 段的低 7 位（同 [xb.tune.TuneFile.modelId]）。 */
    fun modelOf(tableId: String?): Int? = tableId?.split('_')?.getOrNull(2)?.trim()?.toIntOrNull()?.and(0x7F)

}
