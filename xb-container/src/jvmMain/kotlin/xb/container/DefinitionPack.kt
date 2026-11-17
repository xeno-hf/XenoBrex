package xb.container

import xb.tune.Definitions
import xb.tune.Labels
import xb.tune.Platform
import xb.tune.ScaleData
import xb.tune.TuneFile
import java.io.File

/**
 * 定義檔資料夾：定義檔、刻度表、名稱與說明。
 * 檔名照 STX 放在「文件」資料夾裡的樣子：`ControlFile_S3`、`ScaleData`、`XECU_TabShortName`…
 * 副檔名 `.dat`（加密）或 `.xml`／`.txt`（已解開）都認；會往下找一層子資料夾。
 * XenoBrex 本身不帶這些檔案，由使用者指到自己的來源。
 * [container]：要讀加密的檔案時才會問它要金鑰；回 null 表示使用者還沒提供。
 */
class DefinitionPack(val dir: File, private val container: () -> TuneContainer?) {
    constructor(dir: File, container: TuneContainer?) : this(dir, { container })


    private val files: Map<String, File> by lazy {
        val out = HashMap<String, File>()
        val all = (dir.listFiles() ?: emptyArray()).flatMap { f -> if (f.isDirectory) (f.listFiles() ?: emptyArray()).toList() else listOf(f) }
        // 同名時優先用已解開的，其次是加密的
        for (f in all.filter { it.isFile }.sortedBy { if (it.extension.lowercase() == "dat") 1 else 0 }) {
            out.putIfAbsent(f.nameWithoutExtension.substringAfterLast("__"), f)
        }
        out
    }

    private val defCache = HashMap<String, Definitions?>()
    private val labelCache = HashMap<String, Labels>()

    fun has(name: String): Boolean = name in files

    /** 讀一個檔案的文字內容；加密的會先解開。找不到回 null。 */
    fun text(name: String): String? {
        val f = files[name] ?: return null
        val bytes = f.inputStream().use { SafeFiles.boundedRead(it, 16L shl 20) }
        val plain = if (!TuneContainer.looksEncrypted(bytes)) bytes else {
            val c = container() ?: throw KeysRequiredException()
            try { c.decrypt(bytes) } catch (e: Exception) { throw TuneOpenException("Cannot decrypt definitions ${f.name}; the keys may be wrong") }
        }
        return plain.toString(Charsets.UTF_8).removePrefix("\uFEFF")
    }

    /** 需要的檔案齊不齊：至少一個平台的定義檔，加上刻度表。只看檔名，不解密。 */
    val complete: Boolean get() = platforms.isNotEmpty() && has("ScaleData")

    /** 資料夾裡一個加密的檔案（挑最小的），拿來試金鑰對不對。沒有加密的檔案回 null。 */
    fun encryptedSample(): ByteArray? =
        files.values.sortedBy { it.length() }.asSequence().map { it.inputStream().use { input -> SafeFiles.boundedRead(input, 16L shl 20) } }.firstOrNull { TuneContainer.looksEncrypted(it) }

    val scales: ScaleData? by lazy { text("ScaleData")?.let(ScaleData::parse) }

    fun definitions(name: String, idOffset: Int = 0): Definitions? =
        defCache.getOrPut("$name@$idOffset") { text("ControlFile_$name")?.let { Definitions.parse(it, idOffset) } }

    fun labels(set: String, idOffset: Int = 0): Labels =
        labelCache.getOrPut("$set@$idOffset") {
            Labels.parse(text("${set}_GroupDes"), text("${set}_TabShortName"), text("${set}_ParaAndVarDes"), idOffset)
        }

    /** 這個資料夾裡有哪些平台的定義檔。 */
    val platforms: List<Platform> get() = Platform.entries.filter { has("ControlFile_${it.definition}") }

    fun attachedDefinitions(): Definitions? = definitions(Platform.ATTACHED_DEFINITION, TuneFile.SYMGU_ID_OFFSET)
    fun attachedLabels(): Labels = labels(Platform.ATTACHED_LABEL_SET, TuneFile.SYMGU_ID_OFFSET)
}
