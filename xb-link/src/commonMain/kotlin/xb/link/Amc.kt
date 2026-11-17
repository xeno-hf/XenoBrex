package xb.link

import kotlinx.coroutines.delay
import rx.protocol.CanId
import rx.protocol.IOException
import rx.protocol.Frame
import rx.protocol.hexLower
import xb.tune.TableDef
import xb.tune.Tune
import xb.tune.TuneFile

/**
 * AMC 校正寫入：把整份調校寫進 ECU 的 flash。
 * 內容格式：
 * - 依 ControlFile 的 C 版本，取出這個版本有定義、狀態為 En／Enable／Dis／Disable、且不是 EEPROM 的表，
 *   依表號由小到大排列；每張表的值以 little-endian 接起來。
 * - 請求 0x680 `30 C碼(BE16) 長度(BE16) checksum(BE16)`，checksum＝補 FF 到 1024 倍數後所有 byte 的和（低 16 位）。
 *   ECU 回 `31 00 …`。
 * - 每 1024 bytes 一塊，171 幀：`32 01 塊號 10 +4 bytes`，接著 `32 序號 +6 bytes`（序號 2–171）。
 *   ECU 每塊回 `33 塊號 狀態`，狀態 0＝收下、2＝最後一塊收下（完成）。
 */
object Amc {
    const val REQUEST = 0x30
    const val DATA = 0x32
    const val BLOCK = 1024

    /** 版本識別表。Table ID 用 [EcuSync.TABLE_ID]。 */
    private const val TABLE_V_CODE = 1002
    private const val TABLE_C_CODE = 1016
    private const val MAX_CONTENT = 0xFFFF

    private val PACKED_STATUSES = setOf("En", "Enable", "Dis", "Disable")
    private val VALID_WIDTHS = setOf(1, 2, 4)

    /**
     * 打包後的 AMC 內容。只能由 [pack] 建立；[data]／[padded] 回傳副本，避免呼叫端改到已驗證的內容。
     */
    class Packed internal constructor(content: ByteArray, val cCode: Int, val tables: Int, skippedIds: List<Int>, internal val expected: Tune) {
        private val contentBytes = content.copyOf()
        private val paddedBytes = contentBytes + ByteArray((BLOCK - contentBytes.size % BLOCK) % BLOCK) { 0xFF.toByte() }

        /** 未補齊的內容。 */
        val data: ByteArray get() = contentBytes.copyOf()

        /** 補 FF 到 1024 倍數的內容。 */
        val padded: ByteArray get() = paddedBytes.copyOf()

        val checksum: Int = paddedBytes.sumOf { it.toInt() and 0xFF } and 0xFFFF
        val blocks: Int = paddedBytes.size / BLOCK

        /** 這個 C 版本有定義、但不在內容裡的非附掛表：EEPROM 或狀態不是 En／Dis。 */
        val skipped: List<Int> = skippedIds.toList()

        internal fun contentView(): ByteArray = contentBytes
        internal fun paddedView(): ByteArray = paddedBytes
    }

    class AmcException(message: String) : Exception(message)

    /**
     * 把調校檔目前的內容（含還沒存檔的修改）打包成 AMC，並在建立 [Packed] 前驗證：
     * 檔頭 V／C／Table ID 與表 1002／1016／1001 一致、C 版本的表序完整且順序固定、
     * 沒有重複或多出來的非預期表、每張表寬度與列數正確、總長度等於 ControlFile 算出的長度。
     * [overrides]：這幾張表改用給的值（即時調校時 ECU 不收的表用 ECU 上的值）；只能指定內容裡有的表。
     */
    fun pack(tune: Tune, overrides: Map<Int, List<LongArray>> = emptyMap()): Packed {
        if (tune.file.duplicateIds.isNotEmpty()) {
            throw AmcException("Duplicate tables in the tune: ${tune.file.duplicateIds.sorted().joinToString(", ")}")
        }

        val cCode = identityValue(tune, TABLE_C_CODE, "C code")
        if (cCode !in 0L..0xFFFFL) throw AmcException("C code $cCode exceeds the 16-bit request field")
        if (cCode != tune.file.cCode.toLong()) {
            throw AmcException("The tune header says C${tune.file.cCode} but table $TABLE_C_CODE says C$cCode")
        }
        val vCode = identityValue(tune, TABLE_V_CODE, "V code")
        if (vCode !in 0L..0xFFFFL) throw AmcException("V code $vCode exceeds the ECU identity field")
        if (vCode != tune.file.vCode.toLong()) {
            throw AmcException("The tune header says V${tune.file.vCode} but table $TABLE_V_CODE says V$vCode")
        }
        val tableId = identityValue(tune, EcuSync.TABLE_ID, "Table ID")
        val headerId = tableIdParts(tune.file.tableId)
            ?: throw AmcException("The tune header has no valid Table ID (${tune.file.tableId})")
        val tableIdBytes = listOf(24, 16, 8, 0).map { ((tableId ushr it) and 0xFF).toInt() }
        if (headerId != tableIdBytes) {
            throw AmcException("The tune header says Table ID ${tune.file.tableId} but table ${EcuSync.TABLE_ID} says ${EcuSync.tableIdText(tableId)}")
        }

        val code = cCode.toInt()
        val expected = expectedLayout(tune, code)
        val expectedIds = expected.mapTo(HashSet()) { it.absId }

        val badOverrides = overrides.keys.filter { it !in expectedIds }.sorted()
        if (badOverrides.isNotEmpty()) {
            throw AmcException("Override table(s) ${badOverrides.joinToString(", ")} are not in the C$code AMC layout")
        }

        val skipped = ArrayList<Int>()
        for (id in tune.file.tables.keys.sorted()) {
            val raw = tune.file.tables.getValue(id)
            if (raw.attached) continue
            val def = tune.definitions.tables[id]
                ?: throw AmcException("Table $id (${raw.name}) is not in the definitions")
            val status = def.versions[code]?.status
                ?: throw AmcException("Table $id (${def.name}) has no C$code entry in the definitions")
            if (def.isEeprom || status !in PACKED_STATUSES) {
                if (id in expectedIds) throw AmcException("Table $id (${def.name}) is not part of the C$code AMC layout")
                skipped += id
            } else if (id !in expectedIds) {
                throw AmcException("Table $id (${def.name}) is not part of the C$code AMC layout")
            }
        }

        var total = 0L
        val packedRows = LinkedHashMap<Int, List<LongArray>>(expected.size)
        for (def in expected) {
            val raw = tune.file.tables[def.absId]
                ?: throw AmcException("The tune is missing table ${def.absId} (${def.name}) for C$code")
            if (raw.attached) throw AmcException("Table ${def.absId} (${def.name}) is an attached table and cannot be packed")
            val rows = overrides[def.absId] ?: tune.rows(def.absId) ?: raw.rows
            validateShape(def, rows)
            packedRows[def.absId] = rows
            total += def.storedRows.toLong() * def.storedColumns * def.cellBytes
        }
        if (total < 1L || total > MAX_CONTENT.toLong()) {
            throw AmcException("Bad content length $total (the ECU request field is 16 bits)")
        }

        val out = ArrayList<Byte>(total.toInt())
        for (def in expected) {
            for (row in packedRows.getValue(def.absId)) out.addAll(EcuSync.encodeRow(def, row).toList())
        }
        if (out.size.toLong() != total) {
            throw AmcException("AMC content length ${out.size} does not match the ControlFile layout ($total)")
        }
        val frozen = Tune(tune.definitions, tune.scales, TuneFile.parse(tune.toXml()), tune.attachedDefinitions)
        for ((id, rows) in overrides) {
            if (id in setOf(TABLE_V_CODE, TABLE_C_CODE, EcuSync.TABLE_ID) &&
                (rows.size != 1 || rows[0].size != 1 || rows[0][0] != identityValue(tune, id, "identity"))) {
                throw AmcException("Override table $id disagrees with the tune header")
            }
            check(frozen.loadRows(id, rows.map { it.copyOf() }))
        }
        return Packed(out.toByteArray(), code, expected.size, skipped, frozen)
    }

    /** 這個 C 版本要放進 AMC 的表，依 ControlFile 的表號排序。 */
    private fun expectedLayout(tune: Tune, code: Int): List<TableDef> {
        val out = ArrayList<TableDef>()
        for (def in tune.definitions.tables.values.sortedBy { it.absId }) {
            val status = def.versions[code]?.status ?: continue
            if (def.isEeprom || status !in PACKED_STATUSES) continue
            if (def.absId !in 0 until 0x1000) throw AmcException("Table ${def.absId} is outside the calibration object range")
            if (def.cellBytes !in VALID_WIDTHS) {
                throw AmcException("Table ${def.absId} (${def.name}) has unsupported width ${def.cellBytes} for C$code")
            }
            if (def.storedRows < 1 || def.storedColumns < 1) {
                throw AmcException("Table ${def.absId} (${def.name}) has an empty shape in the definitions")
            }
            out += def
        }
        return out
    }

    private fun identityValue(tune: Tune, id: Int, label: String): Long {
        val def = tune.definitions.tables[id]
            ?: throw AmcException("The tune has no $label table $id in the definitions")
        if (def.storedRows != 1 || def.storedColumns != 1) {
            throw AmcException("Table $id ($label) must be a single value in the definitions")
        }
        val raw = tune.file.tables[id]
            ?: throw AmcException("The tune is missing $label table $id")
        if (raw.attached) throw AmcException("Table $id ($label) is attached and cannot be packed")
        val rows = tune.rows(id) ?: raw.rows
        validateShape(def, rows)
        return rows[0][0]
    }

    private fun validateShape(def: TableDef, rows: List<LongArray>) {
        if (def.cellBytes !in VALID_WIDTHS) {
            throw AmcException("Table ${def.absId} (${def.name}) has unsupported width ${def.cellBytes}")
        }
        if (rows.size != def.storedRows) {
            throw AmcException("Table ${def.absId} (${def.name}) has ${rows.size} rows, expected ${def.storedRows}")
        }
        for ((r, row) in rows.withIndex()) {
            if (row.size != def.storedColumns) {
                throw AmcException("Table ${def.absId} (${def.name}) row $r has ${row.size} cells, expected ${def.storedColumns}")
            }
            for ((c, v) in row.withIndex()) {
                if (v < 0 || v > def.rawMax) {
                    throw AmcException("Table ${def.absId} (${def.name}) cell ($r,$c) is $v, outside 0..${def.rawMax}")
                }
            }
        }
    }

    private fun tableIdParts(text: String): List<Int>? {
        val parts = text.trim().split('_').map { it.trim().toIntOrNull() ?: return null }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return null
        return parts
    }

    fun requestFrame(p: Packed): Frame {
        val size = p.contentView().size
        val ck = p.checksum
        return Frame(CanId.SYS, byteArrayOf(REQUEST.toByte(), (p.cCode ushr 8).toByte(), p.cCode.toByte(),
            (size ushr 8).toByte(), size.toByte(), (ck ushr 8).toByte(), ck.toByte()))
    }

    fun blockFrames(p: Packed, index: Int): List<Frame> {
        if (index !in 0 until p.blocks) throw AmcException("Block index $index is outside 0..${p.blocks - 1}")
        val d = p.paddedView().copyOfRange(index * BLOCK, (index + 1) * BLOCK)
        val out = ArrayList<Frame>(171)
        out += Frame(CanId.SYS, byteArrayOf(DATA.toByte(), 1, index.toByte(), 16) + d.copyOfRange(0, 4))
        for (seq in 2..171) {
            val o = 4 + (seq - 2) * 6
            out += Frame(CanId.SYS, byteArrayOf(DATA.toByte(), seq.toByte()) + d.copyOfRange(o, o + 6))
        }
        return out
    }

    /**
     * 寫進 ECU。ECU 拒絕或逾時就丟 [AmcException]，不重試、不自動回滾。
     * 寫到一半斷掉時，ECU 上的校正可能是不完整的：要用備份再寫一次。
     * 全程獨占連線，先重新確認 ECU 身分，最後完整讀回；讀不到或不同都算失敗。
     * 最後一塊收下後 ECU 還在處理（XTune 這時等約 2 秒才斷線，讀回留到重連），
     * 所以先等 [settleMs]，讀回時每張表逾時或讀不到最多重讀 [readAttempts] 次。
     * [onProgress]（已確認的塊數、總塊數）。回傳驗證完成的讀回。
     */
    suspend fun write(link: EcuLink, p: Packed, paceMs: Long = 4, blockTimeoutMs: Long = 10_000, settleMs: Long = 2_000,
                      readAttempts: Int = 4, retryDelayMs: Long = 1_000, log: (String) -> Unit = {},
                      onProgress: (Int, Int) -> Unit = { _, _ -> }): Snapshot {
        // 參數不合理要在寫任何東西之前擋下（不能寫完整份才在讀回時發現）
        require(paceMs >= 0 && blockTimeoutMs > 0 && settleMs >= 0 && readAttempts >= 1 && retryDelayMs >= 0) { "invalid AMC write timing" }
        return writeChecked(link, p, paceMs, blockTimeoutMs, settleMs, readAttempts, retryDelayMs, log, onProgress)
    }

    private suspend fun writeChecked(link: EcuLink, p: Packed, paceMs: Long, blockTimeoutMs: Long, settleMs: Long,
                                     readAttempts: Int, retryDelayMs: Long, log: (String) -> Unit,
                                     onProgress: (Int, Int) -> Unit): Snapshot = link.amcExclusive {
        try {
            check(link.wholeImageEnabled) { "whole-image writes require a complete verified backup" }
            val reason = EcuSync.compatibility(EcuSync.identity(link), link.platform, p.expected)
            if (reason != null) throw AmcException(reason)
            for (index in link.refusedCalibrationObjects) {
                val id = index - EcuSync.TABLE_BASE
                val def = p.expected.definition(id) ?: throw AmcException("Refused table $id is missing")
                val back = EcuSync.readTable(link, def) as? TableRead.Ok ?: throw AmcException("Refused table $id could not be read")
                val rows = p.expected.rows(id) ?: throw AmcException("Refused table $id is missing")
                if (rows.size != back.rows.size || rows.indices.any { !rows[it].contentEquals(back.rows[it]) }) {
                    throw AmcException("Refused table $id must preserve verified ECU values")
                }
            }
            if (p.blocks > 255) throw AmcException("Content too large (${p.blocks} blocks)")
            val r0 = link.sysExchange(listOf(requestFrame(p)), 0, blockTimeoutMs) { it.dlc >= 1 && it.u8(0) == 0x31 }
                ?: throw AmcException("No response from the ECU to the write request")
            log("AMC request: ${p.data.size} B, C${p.cCode}, ${p.blocks} blocks; ECU ${r0.data.hexLower(" ")}")
            if (r0.dlc < 2 || r0.u8(1) != 0) throw AmcException("The ECU rejected the write (${r0.data.hexLower(" ")})")
            onProgress(0, p.blocks)
            for (i in 0 until p.blocks) {
                val r = link.sysExchange(blockFrames(p, i), paceMs, blockTimeoutMs) { it.dlc >= 1 && it.u8(0) == 0x33 }
                    ?: throw AmcException("No response for block ${i + 1}/${p.blocks} (the calibration on the ECU may be incomplete; write again from the backup)")
                val last = i == p.blocks - 1
                val ok = r.dlc >= 3 && r.u8(1) == i && r.u8(2) == (if (last) 2 else 0)
                if (!ok) throw AmcException("Block ${i + 1}/${p.blocks} rejected (${r.data.hexLower(" ")}; the calibration on the ECU may be incomplete; write again from the backup)")
                onProgress(i + 1, p.blocks)
            }
            log("AMC write accepted: ${p.blocks} blocks; verifying after $settleMs ms")
            delay(settleMs)
            val after = try {
                EcuSync.readAll(link, p.expected, readAttempts, retryDelayMs, log)
            } catch (e: IOException) {
                throw AmcException("The ECU accepted all ${p.blocks} blocks, but the read-back failed: ${e.message}")
            }
            val differences = EcuSync.differences(after, p.expected)
            if (!after.complete || differences.isNotEmpty()) {
                after.failed.forEach { log("Unreadable after write: ${it.def.name} (table ${it.def.absId}): ${it.reason}") }
                differences.forEach { log("Differs after write: ${it.def.name} (table ${it.def.absId})") }
                val names = (after.failed.map { it.def.name } + differences.map { it.def.name }).take(5).joinToString()
                throw AmcException("Full verification failed: ${after.failed.size} unreadable tables, ${differences.size} differing tables ($names)")
            }
            log("AMC write verified: ${after.ok.size} tables, ${p.blocks} blocks")
            after
        } catch (e: Exception) {
            link.disableCalibration()
            throw e
        }
    }
}
