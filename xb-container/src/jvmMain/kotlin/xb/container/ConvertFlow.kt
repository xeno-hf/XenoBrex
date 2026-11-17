package xb.container

import xb.tune.TuneConvert

/**
 * 轉換流程：先從官方抓目標平台、同車種的最新原廠檔當基準，再轉（[TuneConvert]）。拿不到基準就不轉。
 * 原版本的原廠檔也從官方抓，用來判斷哪些設定原本沒動過；抓不到或版本不同就退回只看原檔，結果列進確認清單。
 */
object ConvertFlow {
    class Outcome(
        val result: TuneConvert.Result,
        /** 目標基準（官方最新原廠檔）。 */
        val target: OfficialStock.Baseline,
        /** 原版本的官方原廠檔；null＝沒用上，原因在 [sourceStockNote]。 */
        val sourceStock: OfficialStock.Baseline?,
        val sourceStockNote: String?,
    ) {
        /** 轉好的內容，用目標的平台與名稱，存檔用。 */
        val opened: OpenedTune get() = with(target.opened) { OpenedTune(result.tune, file, encrypted, platform, labels) }
    }

    /** [tableId]：目標車種；預設跟原檔同一台車。 */
    fun run(source: OpenedTune, targetMapType: String, pack: DefinitionPack, container: TuneContainer?,
            tableId: String = source.tune.file.tableId): Outcome {
        val target = OfficialStock.baseline(targetMapType, tableId, pack, container)
        val f = source.tune.file
        var note: String? = null
        val stock = try {
            OfficialStock.baseline(f.mapType, f.tableId, pack, container).takeIf {
                val s = it.opened.tune.file
                (s.cCode == f.cCode).also { same -> if (!same) note = "The official stock tune for the input's platform is C${s.cCode}, the input is C${f.cCode}; cannot compare" }
            }
        } catch (e: OfficialStock.NoBaselineException) { note = e.message; null }
        return Outcome(TuneConvert.convert(source.tune, target.opened.tune, stock?.opened?.tune), target, stock, note)
    }
}
