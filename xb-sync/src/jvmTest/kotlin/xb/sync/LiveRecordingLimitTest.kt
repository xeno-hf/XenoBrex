package xb.sync

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import rx.defs.ChannelDef
import rx.defs.ChannelRegistry
import rx.protocol.ChannelTable
import rx.session.DecodedLayout
import rx.session.Recorder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 即時副本的位元組上限：每次寫入前先扣，最後的 meta 也一樣。 */
class LiveRecordingLimitTest {
    private fun layout() = DecodedLayout(
        ChannelRegistry(null, listOf(ChannelDef("RPM", 26, 2, 1.0, 0.0, 20000.0, 0.0, "Para", "rpm", "Stage_1", 0, emptyMap()))),
        ChannelTable(listOf(ChannelTable.Entry(26, 2))),
    )

    @Test
    fun aCappedSinkRefusesTheWriteThatWouldCrossTheLimit() {
        val budget = RecordingBudget(10)
        val out = Buffer()
        val sink: RawSink = CappedSink(out, budget)
        val first = Buffer().also { it.write(ByteArray(6), 0, 6) }
        sink.write(first, 6)
        assertEquals(6L, out.size)
        assertFailsWith<RecordingLimitException> { sink.write(Buffer().also { it.write(ByteArray(6), 0, 6) }, 6) }
        assertEquals(6L, out.size, "超過的部分一個位元組都不寫")
        assertEquals(6L, budget.bytes)
    }

    @Test
    fun rapidRecorderWritesStopAtTheBudget() {
        val dir = Files.createTempDirectory("xb-live-limit").toFile()
        try {
            val budget = RecordingBudget(32L shl 10)
            val rec = Recorder(kotlinx.io.files.Path(dir.path), layout(), emptyMap(), wallClock = { 0L }, openSink = { p ->
                CappedSink(kotlinx.io.files.SystemFileSystem.sink(p), budget)
            })
            assertFailsWith<RecordingLimitException> {
                for (i in 0 until 100_000) rec.onRound(i * 1_000_000L, longArrayOf(i.toLong()))
            }
            assertTrue(budget.bytes <= 32L shl 10, "budget: ${budget.bytes}")
            val onDisk = Files.walk(dir.toPath()).use { s -> s.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }
            assertTrue(onDisk <= 32L shl 10, "on disk: $onDisk")
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun finalMetadataAlsoGoesThroughTheCappedSink() {
        val dir = Files.createTempDirectory("xb-live-meta").toFile()
        try {
            val opened = ArrayList<String>()
            val budget = RecordingBudget(1L shl 20)
            val rec = Recorder(kotlinx.io.files.Path(dir.path), layout(), emptyMap(), wallClock = { 0L }, openSink = { p ->
                opened += p.name
                CappedSink(kotlinx.io.files.SystemFileSystem.sink(p), budget)
            })
            rec.onRound(1_000_000L, longArrayOf(1200))
            rec.close()
            // 開始與收檔的 meta 都先寫暫存檔，成功才換掉 meta.json
            assertEquals(2, opened.count { it == ".meta.json.tmp" }, "開始與收檔的 meta 都走同一條：$opened")
            assertTrue("sha256" in java.io.File(dir, "meta.json").readText())
        } finally { dir.deleteRecursively() }
    }

    /** 平常的資料上限先扣掉收檔保留量；收檔開始後才准用保留量。 */
    @Test
    fun finalizeReserveStopsDataBeforeTheHardLimit() {
        val budget = RecordingBudget(100, 40)
        budget.take(60)
        assertFailsWith<RecordingLimitException> { budget.take(1) }
        assertEquals(60L, budget.bytes)
        budget.beginFinalize()
        budget.take(40)
        assertEquals(100L, budget.bytes)
        assertFailsWith<RecordingLimitException> { budget.take(1) }
    }
}
