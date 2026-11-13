package com.example.chatbar.domain.lanSync

import java.nio.file.Files
import java.nio.file.Path

/** Storage policy, separate from bounded transport frames and native image decode budgets. */
data class LanSyncResourceBudget(
    val maximumResourceBytes: Long? = null,
    val maximumTotalBytes: Long? = null,
    val minimumFreeBytes: Long = 32L * 1024 * 1024,
) {
    init {
        require(maximumResourceBytes == null || maximumResourceBytes > 0)
        require(maximumTotalBytes == null || maximumTotalBytes > 0)
        require(minimumFreeBytes >= 0)
    }

    fun requireResource(length: Long) {
        require(length > 0 && (maximumResourceBytes == null || length <= maximumResourceBytes)) { "SYNC_RESOURCE_BUDGET" }
    }

    fun total(lengths: Collection<Long>): Long {
        var total = 0L
        for (length in lengths) {
            requireResource(length)
            total = lanSyncAddLength(total, length)
        }
        require(maximumTotalBytes == null || total <= maximumTotalBytes) { "SYNC_TOTAL_BUDGET" }
        return total
    }

    fun requireSpace(existingRoot: Path, additionalBytes: Long, copies: Int = 1) {
        require(additionalBytes >= 0 && copies > 0)
        val multiplied = try { Math.multiplyExact(additionalBytes, copies.toLong()) }
            catch (failure: ArithmeticException) { throw IllegalArgumentException("SYNC_LENGTH_OVERFLOW", failure) }
        val required = lanSyncAddLength(multiplied, minimumFreeBytes)
        require(Files.getFileStore(existingRoot).usableSpace >= required) { "SYNC_DISK_SPACE" }
    }
}

internal fun lanSyncAddLength(first: Long, second: Long): Long {
    require(first >= 0 && second >= 0) { "SYNC_RESOURCE_LENGTH" }
    return try { Math.addExact(first, second) }
    catch (failure: ArithmeticException) { throw IllegalArgumentException("SYNC_LENGTH_OVERFLOW", failure) }
}
