package xb.container

import xb.crypto.XbCrypto
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import javax.xml.XMLConstants
import java.net.HttpURLConnection
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 官方原廠校正檔：aRacer 伺服器上的 QB 清單（STX「快速燒錄」用的那份）。
 * 轉換一定要先從這裡拿到目標平台、目標車種組合（Table_ID）的最新原廠檔當基準，拿不到就不轉。
 * 目標車種不限定跟原檔同一台：很多車種共用同一顆引擎，使用者可以選清單裡任何一個；跟原檔不同時要使用者確認。
 * 路徑照 STX（M6／SX／MX2／SX2 的 QBList／QBResource）。清單每次都重抓；原廠檔照清單的 md5 核對，
 * 對得上的才用，下載過而且 md5 相同就用快取。清單與原廠檔都是加密容器，要使用者的金鑰。
 */
object OfficialStock {
    private const val SERVER = "https://file.aracercustomer.com/"
    internal const val MAX_DOWNLOAD = 16L shl 20

    enum class Source(val display: String, val mapType: String, val prefix: String, val folder: String, val list: String) {
        MX("Mini X", "AMX", "MX", "MX_QB/", "MX_QB_List.dat"),
        SX("Super X", "ASX", "SX", "SuperX_QB_202210/", "SX_QB_List.dat"),
        MX2("Mini XX", "AMX2", "MX2", "XX_Platform/MiniXX_QB/", "MX2_QB_List.dat"),
        SX2("Super XX", "ASX2", "SX2", "XX_Platform/SuperXX_QB/", "SX2_QB_List.dat");

        companion object {
            fun ofMapType(mapType: String): Source? = entries.firstOrNull { it.mapType == mapType.trim() }
        }
    }

    /** 清單裡的一個車種組合。 */
    class Entry(val vehicle: String, val style: String, val styleEn: String, val file: String, val md5: String, val firmware: String?) {
        val cCode: Int? get() = Regex("_v\\d+c(\\d+)").find(file)?.groupValues?.get(1)?.toIntOrNull()
        val vCode: Int? get() = Regex("_v(\\d+)c\\d+").find(file)?.groupValues?.get(1)?.toIntOrNull()
        /** 檔名裡的 Table_ID（`SX2_05_18_10_01_v2c72.asx2` → `05_18_10_01`）。 */
        val tableId: String? get() = Regex("^[A-Z0-9]+?_(\\d+_\\d+_\\d+_\\d+)_v").find(file)?.groupValues?.get(1)
    }

    class QbList(val source: Source, val version: String, val entries: List<Entry>) {
        /** 清單裡的車種組合，同一個原廠檔只列一次；[query] 比對車款、組合說明、Table_ID（不分大小寫）。 */
        fun vehicles(query: String? = null): List<Entry> = entries.distinctBy { it.file }.filter { e ->
            query.isNullOrBlank() || listOf(e.vehicle, e.style, e.styleEn, e.file).any { it.contains(query.trim(), ignoreCase = true) }
        }

        /** 同一個 Table_ID 的原廠檔；同名檔在不同車款下重複列的只算一份，C 版本不同就取最新的。 */
        fun find(tableId: String): Entry? =
            entries.filter { it.file.startsWith("${source.prefix}_${tableId.trim()}_v") }
                .distinctBy { it.file }
                .maxByOrNull { it.cCode ?: -1 }
    }

    class Baseline(val list: QbList, val entry: Entry, val file: File, val opened: OpenedTune, val fromCache: Boolean)

    class NoBaselineException(message: String) : Exception(message)

    fun cacheDir(): File = File(XbPaths.configDir(), "official-stock")

    private fun get(url: String): ByteArray {
        val uri = URI(url)
        require(uri.scheme == "https") { "Official downloads require HTTPS" }
        val c = uri.toURL().openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        try {
            if (c.responseCode != 200) throw NoBaselineException("Official server returned ${c.responseCode}: $url")
            if (c.contentLengthLong > MAX_DOWNLOAD) throw NoBaselineException("Official download is too large")
            return c.inputStream.use { readDownload(it) }
        } finally {
            c.disconnect()
        }
    }

    internal fun readDownload(input: InputStream, maxBytes: Long = MAX_DOWNLOAD): ByteArray {
        val data = try { SafeFiles.boundedRead(input, maxBytes) }
            catch (e: SafeFiles.SizeLimitException) { throw NoBaselineException("Official download exceeds its size limit") }
        val head = data.decodeToString(0, minOf(data.size, 64)).trimStart().lowercase()
        if (head.startsWith("<!doctype html") || head.startsWith("<html")) throw NoBaselineException("Official server returned a web page, not a file")
        return data
    }

    internal fun cacheFile(dir: File, name: String): File = try { SafeFiles.resolveBasename(dir.toPath(), name).toFile() }
        catch (e: IllegalArgumentException) { throw NoBaselineException("Unsafe stock filename") }

    private fun md5(b: ByteArray) = XbCrypto.hex(XbCrypto.md5(b), upper = true)

    fun fetchList(source: Source, container: TuneContainer): QbList {
        val raw = try { get(SERVER + source.folder + source.list) }
            catch (e: NoBaselineException) { throw e }
            catch (e: Exception) { throw NoBaselineException("Cannot reach the official server (${source.display} list): ${e.message}") }
        val xml = try { container.decrypt(raw) } catch (e: Exception) { throw NoBaselineException("Cannot decrypt the ${source.display} list; wrong keys?") }
        return parseList(source, xml)
    }

    internal fun parseList(source: Source, xml: ByteArray): QbList {
        if (xml.size > MAX_DOWNLOAD) throw NoBaselineException("Official list is too large")
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", 64)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val doc = try {
            factory.newDocumentBuilder().apply {
                setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
                    override fun error(e: org.xml.sax.SAXParseException) { throw e }
                    override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
                })
            }.parse(ByteArrayInputStream(xml))
        }
            catch (e: Exception) { throw NoBaselineException("Invalid official list XML") }
        fun Element.text(tag: String) = getElementsByTagName(tag).item(0)?.textContent?.trim()
        val entries = ArrayList<Entry>()
        val models = doc.getElementsByTagName("Model")
        for (i in 0 until models.length) {
            val m = models.item(i) as Element
            val types = m.getElementsByTagName("type")
            for (j in 0 until types.length) {
                val t = types.item(j) as Element
                val file = t.text("amx") ?: continue
                val md5 = t.text("amx_md5") ?: continue
                cacheFile(File("."), file)
                if (!Regex("[A-Fa-f0-9]{32}").matches(md5)) throw NoBaselineException("Invalid stock checksum in official list")
                entries += Entry(m.getAttribute("name"), t.text("style_zh") ?: "", t.text("style_en") ?: t.text("style_zh") ?: "", file, md5.uppercase(), t.text("sox"))
            }
        }
        return QbList(source, doc.documentElement.getAttribute("ver"), entries)
    }

    /**
     * 目標平台 [mapType]、車種 [tableId] 的官方最新原廠檔，下載（或取快取）、核對 md5、開檔。
     * 拿不到就丟 [NoBaselineException]，原因寫在訊息裡。
     */
    fun baseline(mapType: String, tableId: String, pack: DefinitionPack, container: TuneContainer?): Baseline {
        val source = Source.ofMapType(mapType)
            ?: throw NoBaselineException("No official stock source for $mapType yet (only Mini X, Super X, Mini XX and Super XX)")
        container ?: throw KeysRequiredException()
        val list = fetchList(source, container)
        val entry = list.find(tableId)
            ?: throw NoBaselineException("The official ${source.display} list (ver ${list.version}) has no vehicle with Table_ID $tableId")
        val root = cacheDir().toPath().toAbsolutePath().normalize()
        Files.createDirectories(root)
        if (Files.isSymbolicLink(root)) throw NoBaselineException("Stock cache is a symbolic link")
        val dir = SafeFiles.resolveBasename(root, source.prefix)
        Files.createDirectories(dir)
        if (Files.isSymbolicLink(dir)) throw NoBaselineException("Stock cache is a symbolic link")
        val cached = cacheFile(dir.toFile(), entry.file)
        var opened: OpenedTune? = null
        if (Files.isRegularFile(cached.toPath(), NOFOLLOW_LINKS)) {
            opened = runCatching { validateStock(cached, source, entry, tableId, pack, container) }.getOrNull()
        }
        val fromCache = opened != null
        if (!fromCache) {
            val c = entry.cCode
            val urls = listOfNotNull(
                SERVER + source.folder + "FileResource/" + entry.file,
                c?.let { SERVER + source.folder + "${source.mapType}_C$it/" + entry.file },
            )
            var data: ByteArray? = null
            val errors = ArrayList<String>()
            for (u in urls) {
                data = try { get(u) } catch (e: Exception) { errors += e.message ?: "Download failed"; null }
                if (data != null) break
            }
            val downloaded = data ?: throw NoBaselineException("Cannot download the official stock tune ${entry.file}: ${errors.joinToString("; ")}")
            SafeFiles.atomicWrite(cached.toPath(), validate = { tmp ->
                opened = validateStock(tmp.toFile(), source, entry, tableId, pack, container)
            }) { it.write(downloaded) }
        }
        val checked = opened ?: throw NoBaselineException("Stock validation failed")
        val result = OpenedTune(checked.tune, cached, checked.encrypted, checked.platform, checked.labels, checked.project)
        return Baseline(list, entry, cached, result, fromCache)
    }

    internal fun validateStock(file: File, source: Source, entry: Entry, tableId: String, pack: DefinitionPack, container: TuneContainer): OpenedTune {
        val data = file.inputStream().use { SafeFiles.boundedRead(it, MAX_DOWNLOAD) }
        if (md5(data) != entry.md5) throw NoBaselineException("The official stock tune ${entry.file} does not match the md5 in the list; not used")
        val opened = try { TuneIo.open(file, pack, container) }
            catch (e: Exception) { throw NoBaselineException("Cannot open the official stock tune ${entry.file}: ${e.message}") }
        val tune = opened.tune.file
        if (tune.mapType.trim() != source.mapType || tune.vCode != entry.vCode || tune.cCode != entry.cCode)
            throw NoBaselineException("The official stock tune ${entry.file} has an unexpected platform or version")
        if (entry.tableId != tableId.trim() || tune.tableId.trim() != tableId.trim())
            throw NoBaselineException("The official stock tune ${entry.file} has an unexpected Table_ID")
        return opened
    }
}
