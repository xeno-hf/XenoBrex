package rx.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rx.defs.ChannelDef
import rx.defs.ChannelRegistry
import rx.defs.Loga
import rx.protocol.ChannelTable
import java.nio.file.Files

/** Synthetic public fixtures: no owner rides or vendor definition files required. */
class RecordingRoundTripTest {
    @Test fun markAndClockAnchorUseTheInjectedSessionClock() {
        val def = ChannelDef("RPM", 26, 2, 1.0, 0.0, 20000.0, 0.0, "Para", "rpm", "Stage_1", 0, emptyMap())
        val layout = DecodedLayout(ChannelRegistry(null, listOf(def)), ChannelTable(listOf(ChannelTable.Entry(26, 2))))
        val dir = Files.createTempDirectory("rex-clock").toFile()
        var now = 845_510_000_000_000L // Android uptime domain, not process-relative monotonicNanos
        try {
            val rec = Recorder(kotlinx.io.files.Path(dir.path), layout, emptyMap(),
                wallClock = { 1_792_565_930_541L }, nanoTime = { now }, clockDomain = "test-uptime")
            rec.onRound(now, longArrayOf(1200))
            now += 2_000_000_000L
            rec.mark("gap check")
            rec.onRound(now, longArrayOf(1300))
            rec.close()
            val events = dir.resolve("events.jsonl").readText()
            assertTrue(events.contains("\"t_ns\":$now"), events)
            val meta = dir.resolve("meta.json").readText()
            assertTrue(meta.contains("\"domain\":\"test-uptime\""), meta)
            assertTrue(meta.contains("\"started_monotonic_ns\":845510000000000"), meta)
            assertEquals(listOf(0.0, 2000.0), Loga.parse(dir.resolve("decoded.loga").readBytes()).column("Time").toList())
        } finally { dir.deleteRecursively() }
    }

    @Test fun recordsAndReplaysWithAppendedPowerAndGps() {
        val def = ChannelDef("Vehicle_Speed", 51, 2, 10.0, 0.0, 300.0, 0.0, "Para", "km/h", "Stage_1", 1, emptyMap())
        val registry = ChannelRegistry(null, listOf(def))
        val layout = DecodedLayout(registry, ChannelTable(listOf(ChannelTable.Entry(51, 2))))
        val dir = Files.createTempDirectory("rex-roundtrip").toFile()
        try {
            Recorder(kotlinx.io.files.Path(dir.path), layout, emptyMap(), wallClock = { 0L }, extras = listOf(
                Recorder.ExtraColumn(-9002, "Phone_GPS", "Phone_GPS_Speed", 1) { 42.5 },
                Recorder.ExtraColumn(-9100, "ReX", "MGU_Power", 0) { 700.0 },
                Recorder.ExtraColumn(-9101, "ReX", "BMS_Power", 0) { -600.0 },
            )).apply {
                onRound(1_000_000L, longArrayOf(425))
                onRound(101_000_000L, longArrayOf(500))
                onRound(201_000_000L, longArrayOf(600))
                close()
            }
            val loga = Loga.parse(dir.resolve("decoded.loga").readBytes())
            assertEquals(listOf(-1, 51, -9002, -9100, -9101, Recorder.MARK_VAR_ID), loga.varIds)
            assertEquals(0, loga.rejectedRows)
            assertEquals(3, loga.rows.size)
            assertEquals(700.0, loga.column("MGU_Power")[0])
            assertEquals(-600.0, loga.column("BMS_Power")[0])
            val replay = LogaValueSource(loga, registry)
            assertEquals(425L, replay.raw(51, 2, 0))
            assertEquals(500L, replay.raw(51, 2, 100))
            // ReX 自己的欄位都接在最後；沒打標記時 Mark 欄全是 0
            assertEquals(listOf("BMS_Power", "Mark"), loga.names.takeLast(2))
            assertEquals(listOf(0.0, 0.0, 0.0), loga.column("Mark").toList())
        } finally { dir.deleteRecursively() }
    }

    /** 標記每筆錄製從 1 編號；loga 的 Mark 欄在標記之後的那一列往上跳一階；清單從 events.jsonl 讀得回來。 */
    @Test fun marksAreNumberedPerRecordingAndShowUpInLogaAndEvents() {
        val def = ChannelDef("RPM", 26, 2, 1.0, 0.0, 20000.0, 0.0, "Para", "rpm", "Stage_1", 0, emptyMap())
        val layout = DecodedLayout(ChannelRegistry(null, listOf(def)), ChannelTable(listOf(ChannelTable.Entry(26, 2))))
        val root = Files.createTempDirectory("rex-marks").toFile()
        var now = 5_000_000_000L
        try {
            fun record(name: String, body: (Recorder) -> Unit) = root.resolve(name).also { d ->
                Recorder(kotlinx.io.files.Path(d.path), layout, emptyMap(), wallClock = { 0L }, nanoTime = { now }).also(body).close()
            }
            val seen = ArrayList<Recorder.Mark>()
            val first = record("a") { rec ->
                assertEquals(0.0, rec.mark { "early $it" }!!.tMs) // 還沒有任何一幀：時間軸還沒開始
                rec.onRound(now, longArrayOf(1000))
                now += 222_000_000_000L // 3 分 42 秒
                seen += rec.mark { "標記 $it" }!!
                rec.onRound(now, longArrayOf(1100))
                rec.onRound(now + 40_000_000L, longArrayOf(1200))
                now += 3_600_000_000_000L
                seen += rec.mark("free text")!!
                rec.onRound(now, longArrayOf(1300))
                assertEquals(listOf(1, 2, 3), rec.marks.map { it.n })
            }
            assertEquals(listOf(2, 3), seen.map { it.n })
            assertEquals(listOf(222_000.0, 3_822_000.0), seen.map { it.tMs })
            assertEquals(listOf("3:42", "1:03:42"), seen.map { it.clock })

            val loga = Loga.parse(first.resolve("decoded.loga").readBytes())
            assertEquals(0, loga.rejectedRows)
            assertEquals(listOf(1.0, 2.0, 2.0, 3.0), loga.column("Mark").toList())
            assertEquals(listOf(0.0, 222_000.0, 222_040.0, 3_822_000.0), loga.column("Time").toList())

            val events = first.resolve("events.jsonl").readText()
            assertTrue("\"mark\":2,\"t_ms\":222000.0,\"text\":\"Mark: 標記 2\"" in events, events)
            val parsed = Recorder.parseMarks(events)
            assertEquals(listOf(1 to 0.0, 2 to 222_000.0, 3 to 3_822_000.0), parsed.map { it.n to it.tMs })

            // 下一筆錄製重新從 1 開始
            record("b") { rec -> rec.onRound(now, longArrayOf(1)); assertEquals(1, rec.mark("again")!!.n) }
            val closed = Recorder(kotlinx.io.files.Path(root.resolve("c").path), layout, emptyMap(), wallClock = { 0L }).also { it.close() }
            assertEquals(null, closed.mark("late"))
        } finally { root.deleteRecursively() }
    }

    /** 舊版的標記事件沒有編號和 t_ms：照順序編號，時間用第一筆事件當起點；其他事件和寫到一半的行不算。 */
    @Test fun parsesMarksWrittenByEarlierVersions() {
        val events = """
            {"t_ns":1000000000,"wall":1,"text":"Recording started: 75 ch, 243 columns"}
            {"t_ns":1500000000,"wall":2,"text":"Read 0x342B/0: 1 B (expedited)"}
            {"t_ns":61000000000,"wall":3,"text":"Mark: 標記 7"}
            {"t_ns":62000000000,"wall":4,"text":"Reply mentions \"mark\":9 and Mark: but is not one"}
            {"t_ns":131000000000,"wall":5,"text":"Mark: 標記 8"}
            {"t_ns":140000000000,"wall":6,"te
        """.trimIndent()
        val marks = Recorder.parseMarks(events)
        assertEquals(listOf(1 to 60_000.0, 2 to 130_000.0), marks.map { it.n to it.tMs })
        assertEquals(listOf("1:00", "2:10"), marks.map { it.clock })
        assertEquals(emptyList(), Recorder.parseMarks(""))
        // 斷在標記文字中間（已經有 "text":"Mark: " 但沒有結尾）的行也不算
        val cut = events.lineSequence().take(5).joinToString("\n") + "\n{\"t_ns\":150000000000,\"wall\":7,\"mark\":3,\"t_ms\":149000.0,\"text\":\"Mark: 標"
        assertEquals(listOf(1 to 60_000.0, 2 to 130_000.0), Recorder.parseMarks(cut).map { it.n to it.tMs })
        // 收尾的大括號其實在沒結束的字串裡
        assertEquals(emptyList(), Recorder.parseMarks("{\"t_ns\":1,\"text\":\"Mark: }"))
        assertEquals(emptyList(), Recorder.parseMarks("{\"t_ns\":1,\"text\":\"Mark: a\\\"}"))
        assertEquals(1, Recorder.parseMarks("{\"t_ns\":1,\"text\":\"Mark: a\\\\\"}").size)
        assertEquals(emptyList(), Recorder.parseMarks("{\"t_ns\":1,\"text\":\"Mark: a\"}}"))
        // 語法壞掉的行：括號不對、少逗號、少值、數字寫成字串、後面多東西
        for (bad in listOf(
            "{\"t_ns\":1,\"text\":\"Mark: bad\"]",
            "{\"t_ns\":1 \"text\":\"Mark: a\"}",
            "{\"t_ns\":,\"text\":\"Mark: a\"}",
            "{\"t_ns\":\"1\",\"text\":\"Mark: a\"}",
            "{\"t_ns\":1,\"text\":\"Mark: a\"} x",
            "{\"t_ns\":1,\"text\":\"Mark: a\",}",
            "{\"t_ns\":1,\"text\":\"Mark: \\u+041\"}",
            "{\"t_ns\":1,\"text\":\"Mark: \\u-041\"}",
            "{\"t_ns\":1,\"text\":\"Mark: \\u12g4\"}",
            "\u000B{\"t_ns\":1,\"text\":\"Mark: a\"}",
            "{\"t_ns\":1,\"text\":\"Mark: a\"}\u00A0",
        )) assertEquals(emptyList(), Recorder.parseMarks(bad), bad)
        assertEquals(1, Recorder.parseMarks("{\"t_ns\":1,\"text\":\"Mark: a\"}\r").size, "CRLF line ending")
        assertEquals(1, Recorder.parseMarks("{\"t_ns\":1,\"x\":[1,{\"y\":null}],\"text\":\"Mark: \\u6a19\"}").size)
    }
}
