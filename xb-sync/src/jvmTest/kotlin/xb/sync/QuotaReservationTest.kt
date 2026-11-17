package xb.sync

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class QuotaReservationTest {
    private val root = Files.createTempDirectory("xb-quota-").toFile()
    private val limits = RideLimits(phoneBytes = 100, totalBytes = 150, phoneFiles = 2, totalFiles = 3, freeBeforeUpload = 0)
    private fun phone(name: String) = File(root, name).apply { mkdirs() }

    @AfterTest fun clean() { root.deleteRecursively() }

    @Test fun concurrentReservationsCannotExceedPhoneOrGlobalLimits() {
        val quota = RideQuota({ root }, limits, freeBytes = { 10_000 })
        val first = phone("First_aaaaaaaaaaaa")
        val second = phone("Second_bbbbbbbbbbbb")
        val ticket = quota.reserve(first.name, first, 80, 1)
        assertFailsWith<IllegalStateException> { quota.reserve(first.name, first, 30, 1) }
        assertFailsWith<IllegalStateException> { quota.reserve(second.name, second, 80, 1) }
        quota.release(ticket)
        quota.release(quota.reserve(second.name, second, 80, 1))
    }

    @Test fun changingThePhoneNameCannotResetItsQuota() {
        val old = phone("Old_aaaaaaaaaaaa")
        File(old, "old.xbl").writeBytes(ByteArray(80))
        val renamed = phone("New_aaaaaaaaaaaa")
        val quota = RideQuota({ root }, limits, freeBytes = { 10_000 })
        assertFailsWith<IllegalStateException> { quota.reserve(renamed.name, renamed, 21, 1) }
    }

    @Test fun freeSpaceIncludesOutstandingReservations() {
        val large = RideLimits(phoneBytes = 1_000, totalBytes = 10_000, freeBeforeUpload = 10)
        val quota = RideQuota({ root }, large, freeBytes = { 100 })
        val first = phone("First_aaaaaaaaaaaa")
        val second = phone("Second_bbbbbbbbbbbb")
        val ticket = quota.reserve(first.name, first, 60, 1)
        assertFailsWith<IllegalStateException> { quota.reserve(second.name, second, 40, 1) }
        quota.release(ticket)
        quota.release(quota.reserve(second.name, second, 40, 1))
    }

    @Test fun impossibleSizesAreRejectedWithoutIntegerOverflow() {
        val quota = RideQuota({ root }, limits, freeBytes = { Long.MAX_VALUE })
        val dir = phone("First_aaaaaaaaaaaa")
        assertFailsWith<IllegalStateException> { quota.reserve(dir.name, dir, Long.MAX_VALUE, 1) }
    }
}
