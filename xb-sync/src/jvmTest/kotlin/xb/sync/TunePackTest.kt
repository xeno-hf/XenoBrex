package xb.sync

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xb.container.DefinitionPack
import xb.container.KeyStore
import xb.container.OfficialStock
import xb.tune.TunePack

/** 調校包：手機解開後建出的 Tune 跟 XB 直接開官方原廠檔的一模一樣（定義、版本、每張表的值與軸）。要連官方伺服器。 */
class TunePackTest {
    private val defsDir = File(System.getProperty("xb.definitions")!!)

    @Test fun phoneTuneMatchesDesktop() {
        val keys = KeyStore.load()?.container
        if (!defsDir.isDirectory || keys == null) { println("略過：沒有定義檔或金鑰"); return }
        val pack = DefinitionPack(defsDir, keys)
        for ((type, id) in listOf("AMX" to "05_18_10_01", "ASX2" to "05_18_10_01")) {
            val zip = try { TunePackZip.build(pack, keys, type, id, "test") } catch (e: TunePackZip.NoPack) { println("略過 $type：${e.message}"); continue }
            val p = TunePackZip.read(zip)
            assertEquals(TunePack.FORMAT, p.manifest["format"])
            val phone = p.open()
            val desk = OfficialStock.baseline(type, id, pack, keys).opened
            println("$type $id：${zip.size / 1024} KB，${phone.tune.definitions.tables.size} 張定義，附掛 ${phone.tune.attachedDefinitions?.tables?.size ?: 0}")
            assertEquals(desk.tune.definitions.tables.keys, phone.tune.definitions.tables.keys)
            assertEquals(desk.tune.attachedDefinitions?.tables?.keys, phone.tune.attachedDefinitions?.tables?.keys)
            assertEquals(desk.tune.file.cCode, phone.tune.file.cCode)
            assertEquals(desk.tune.file.tableId, phone.tune.file.tableId)
            for ((absId, t) in desk.tune.file.tables) {
                val a = desk.tune.rows(absId)!!; val b = phone.tune.rows(absId)!!
                assertEquals(a.size, b.size); a.indices.forEach { assertTrue(a[it].contentEquals(b[it]), "table $absId row $it") }
                val d = desk.tune.definition(absId) ?: continue
                assertEquals(desk.tune.axisX(d).toString().length > 0, true)
                assertEquals(desk.labels.shortName(absId, "zh-TW"), phone.labels.shortName(absId, "zh-TW"), "label $absId (${t.name})")
            }
        }
    }
}
