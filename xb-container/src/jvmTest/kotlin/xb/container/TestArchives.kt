package xb.container

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * 測試用的最小 ZIP 產生器：全部 STORED，可以造出重複名稱與走位名稱
 * （`ZipOutputStream` 不允許重複成員，測不到那條）。
 */
internal object TestArchives {
    fun sha(b: ByteArray): String = XbxProject.sha256(b)

    fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        for ((name, data) in entries) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().also { it.update(data) }.value
            val offset = out.size()
            out.u32(0x04034b50L)
            out.u16(20); out.u16(0); out.u16(0); out.u16(0); out.u16(0)
            out.u32(crc); out.u32(data.size.toLong()); out.u32(data.size.toLong())
            out.u16(nameBytes.size); out.u16(0)
            out.write(nameBytes)
            out.write(data)

            central.u32(0x02014b50L)
            central.u16(20); central.u16(20); central.u16(0); central.u16(0); central.u16(0); central.u16(0)
            central.u32(crc); central.u32(data.size.toLong()); central.u32(data.size.toLong())
            central.u16(nameBytes.size); central.u16(0); central.u16(0); central.u16(0); central.u16(0)
            central.u32(0); central.u32(offset.toLong())
            central.write(nameBytes)
        }
        val cdOffset = out.size()
        val cd = central.toByteArray()
        out.write(cd)
        out.u32(0x06054b50L)
        out.u16(0); out.u16(0); out.u16(entries.size); out.u16(entries.size)
        out.u32(cd.size.toLong()); out.u32(cdOffset.toLong()); out.u16(0)
        return out.toByteArray()
    }

    fun xbx(
        files: List<Pair<String, ByteArray>>,
        listed: Map<String, String> = files.toMap().mapValues { sha(it.value) },
        format: String = XbxProject.FORMAT,
        tune: String = "tune.amx",
        extra: List<Pair<String, ByteArray>> = emptyList(),
    ): ByteArray {
        val manifest = linkedMapOf<String, Any?>("format" to format, "tune" to tune, "files" to listed)
        return zip(
            listOf("mimetype" to XbxProject.MIME.toByteArray()) +
                ("manifest.json" to (Json.write(manifest) + "\n").toByteArray()) +
                files + extra,
        )
    }

    fun xbl(
        members: List<Pair<String, ByteArray>> = defaultRideMembers(),
        listed: Map<String, String> = members.toMap().mapValues { sha(it.value) },
        format: String = XblRide.FORMAT,
        extra: List<Pair<String, ByteArray>> = emptyList(),
        vehicle: Map<String, Any?> = linkedMapOf("platform" to "Mini X", "map_type" to "AMX"),
    ): ByteArray {
        val manifest = linkedMapOf<String, Any?>("format" to format, "vehicle" to vehicle, "files" to listed)
        return zip(
            listOf("mimetype" to XblRide.MIME.toByteArray()) +
                ("manifest.json" to (Json.write(manifest) + "\n").toByteArray()) +
                members + extra,
        )
    }

    fun defaultRideMembers(): List<Pair<String, ByteArray>> = listOf(
        "meta.json" to "{\"started_wall_ms\":1}\n".toByteArray(),
        "frames.rxraw" to "RXRAW".toByteArray(),
        "decoded.loga" to "<aRacer MX APP Log File>\nTime,RPM\n0,1500\n".toByteArray(),
        "events.jsonl" to "{\"kind\":\"connect\"}\n".toByteArray(),
        "writes.jsonl" to "{\"t_ms\":1.0,\"result\":\"ack\"}\n".toByteArray(),
    )
}

private fun ByteArrayOutputStream.u16(v: Int) {
    write(v and 0xFF)
    write((v ushr 8) and 0xFF)
}

private fun ByteArrayOutputStream.u32(v: Long) {
    write((v and 0xFF).toInt())
    write(((v ushr 8) and 0xFF).toInt())
    write(((v ushr 16) and 0xFF).toInt())
    write(((v ushr 24) and 0xFF).toInt())
}
