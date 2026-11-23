package xb.container

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 官方原廠檔清單的挑選規則（不連網，清單內容是自己編的）。 */
class OfficialStockListTest {
    private fun e(vehicle: String, file: String) = OfficialStock.Entry(vehicle, "", "", file, "00", null)

    private val list = OfficialStock.QbList(
        OfficialStock.Source.SX2, "1",
        listOf(
            e("A", "SX2_05_18_10_01_v2c72.asx2"),
            e("A", "SX2_05_18_10_01_v2c76.asx2"),
            e("B", "SX2_05_18_10_01_v2c76.asx2"), // 同一個檔在別的車款下重複列
            e("A", "SX2_05_18_10_01_v2c9.asx2"),  // C 碼要當數字比，9 不能贏過 76
            e("C", "SX2_05_18_10_02_v2c80.asx2"),
        ),
    )

    @Test fun sameTableIdPicksNewestC() {
        assertEquals("SX2_05_18_10_01_v2c76.asx2", list.find("05_18_10_01")?.file)
        assertEquals("SX2_05_18_10_01_v2c76.asx2", list.find(" 05_18_10_01 ")?.file)
        assertEquals(76, list.find("05_18_10_01")?.cCode)
        assertEquals("05_18_10_01", list.find("05_18_10_01")?.tableId)
    }

    @Test fun otherTableIdsDoNotMix() {
        assertEquals("SX2_05_18_10_02_v2c80.asx2", list.find("05_18_10_02")?.file)
        assertNull(list.find("05_18_10_03"))
        assertNull(list.find("05_18_10"))
    }

    @Test fun duplicateFilesListedOnce() {
        assertEquals(4, list.vehicles().size)
        assertEquals(listOf("SX2_05_18_10_02_v2c80.asx2"), list.vehicles("c").filter { it.vehicle == "C" }.map { it.file })
    }
}
