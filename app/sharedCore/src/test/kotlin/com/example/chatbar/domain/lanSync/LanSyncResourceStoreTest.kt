package com.example.chatbar.domain.lanSync

import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.Paths
import java.util.UUID
import org.junit.Assume.assumeNoException
import kotlin.test.*

class LanSyncResourceStoreTest {
    private val budget = LanSyncResourceBudget(minimumFreeBytes = 0)

    @Test fun interruptedUnreceiptedBytesAndReceiptTailAreTruncatedBeforeARealReopenedResume() {
        val root = Files.createTempDirectory("lan-chunk-resume-").toRealPath()
        val bytes = ByteArray(1003) { ((it * 71) % 251).toByte() }; val hash = lanSyncHash(bytes)
        try {
            val store = LanSyncResourceStore(root, budget, chunkBytes = 256)
            store.open(hash, bytes.size.toLong()).use { partial ->
                partial.append(0, bytes.copyOfRange(0, 256), lanSyncHash(bytes.copyOfRange(0, 256)))
                assertEquals(256L, partial.offset)
            }
            val directory = root.resolve("partial-$hash")
            Files.write(directory.resolve("data.part"), bytes.copyOfRange(256, 317), StandardOpenOption.APPEND)
            Files.write(directory.resolve("verified.chunks"), "256 61".toByteArray(), StandardOpenOption.APPEND)
            val reopened = LanSyncResourceStore(root, budget, chunkBytes = 256)
            reopened.open(hash, bytes.size.toLong()).use { partial ->
                assertEquals(256L, partial.offset)
                assertEquals(256L, Files.size(directory.resolve("data.part")))
                while (partial.offset < bytes.size) {
                    val start = partial.offset.toInt(); val chunk = bytes.copyOfRange(start, minOf(start + 256, bytes.size))
                    partial.append(partial.offset, chunk, lanSyncHash(chunk))
                }
                assertContentEquals(bytes, Files.readAllBytes(partial.finish()))
            }
            assertFalse(Files.exists(directory))
            assertContentEquals(bytes, Files.readAllBytes(requireNotNull(reopened.completed(hash, bytes.size.toLong()))))
            reopened.open(hash, bytes.size.toLong()).use { assertEquals(bytes.size.toLong(), it.offset) }
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun aDataDurabilityFailureDoesNotPublishOrGrantAuthorityToTheUnreceiptedChunk() {
        val root = Files.createTempDirectory("lan-chunk-fault-").toRealPath()
        val bytes = byteArrayOf(5, 4, 3, 2); val hash = lanSyncHash(bytes)
        try {
            val failed = LanSyncResourceStore(root, budget, chunkBytes = 4) { phase, _ ->
                if (phase == "CHUNK_DATA_DURABLE") throw IOException("synthetic failure before verified receipt")
            }
            failed.open(hash, 4).use { partial -> assertFailsWith<IOException> { partial.append(0, bytes, hash) } }
            assertNull(failed.completed(hash, 4))
            val reopened = LanSyncResourceStore(root, budget, chunkBytes = 4)
            reopened.open(hash, 4).use { partial ->
                assertEquals(0L, partial.offset)
                partial.append(0, bytes, hash)
                assertContentEquals(bytes, Files.readAllBytes(partial.finish()))
            }
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun corruptedVerifiedPrefixAndCorruptedCompletedObjectRefuseRatherThanBecomingMissingSuccess() {
        val root = Files.createTempDirectory("lan-chunk-corrupt-").toRealPath()
        val bytes = byteArrayOf(1, 2, 3, 4); val hash = lanSyncHash(bytes)
        try {
            val store = LanSyncResourceStore(root, budget, chunkBytes = 2)
            store.open(hash, 4).use { it.append(0, bytes.copyOfRange(0, 2), lanSyncHash(bytes.copyOfRange(0, 2))) }
            val file = root.resolve("partial-$hash/data.part")
            Files.write(file, byteArrayOf(9, 2))
            assertEquals("SYNC_RESUME_DAMAGED", assertFailsWith<IllegalArgumentException> { store.open(hash, 4) }.message)
            assertFalse(Files.exists(root.resolve(hash)))
            val other = byteArrayOf(9, 8); val otherHash = lanSyncHash(other)
            store.open(otherHash, 2).use { it.append(0, other, otherHash); it.finish() }
            Files.write(root.resolve(otherHash), byteArrayOf(8, 9))
            assertEquals("SYNC_CACHED_RESOURCE_DAMAGED", assertFailsWith<IllegalArgumentException> { store.completed(otherHash, 2) }.message)
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun defaultBudgetHasNoBusinessSizeCeilingButChecksOverflowExplicitQuotaAndAvailableDisk() {
        val defaults = LanSyncResourceBudget()
        val large = 300L * 1024 * 1024
        assertEquals(large * 4, defaults.total(listOf(large, large, large, large)))
        assertFailsWith<IllegalArgumentException> { defaults.total(listOf(Long.MAX_VALUE, 1)) }
        assertFailsWith<IllegalArgumentException> { LanSyncResourceBudget(maximumResourceBytes = 16).requireResource(17) }
        val root = Files.createTempDirectory("lan-budget-").toRealPath()
        try {
            assertEquals("SYNC_DISK_SPACE", assertFailsWith<IllegalArgumentException> {
                LanSyncResourceBudget(minimumFreeBytes = Long.MAX_VALUE).requireSpace(root, 0)
            }.message)
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun sameThreadCannotOpenTwoIndependentOffsetsForTheSameResource() {
        val root = Files.createTempDirectory("lan-chunk-owner-").toRealPath()
        val bytes = byteArrayOf(1, 2, 3, 4); val hash = lanSyncHash(bytes)
        try {
            val store = LanSyncResourceStore(root, budget, chunkBytes = 2)
            store.open(hash, 4).use { first ->
                assertEquals("SYNC_RESOURCE_ALREADY_OPEN", assertFailsWith<IllegalStateException> { store.open(hash, 4) }.message)
                first.append(0, bytes.copyOfRange(0, 2), lanSyncHash(bytes.copyOfRange(0, 2)))
                first.append(2, bytes.copyOfRange(2, 4), lanSyncHash(bytes.copyOfRange(2, 4)))
                assertContentEquals(bytes, Files.readAllBytes(first.finish()))
            }
            store.open(hash, 4).use { assertEquals(4L, it.offset) }
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun unsafeStoreRootRefusesBeforeCreatingARelativeOrLinkedExternalDirectory() {
        val relative = Paths.get("lan-relative-${UUID.randomUUID()}")
        assertFalse(Files.exists(relative))
        assertFailsWith<IllegalArgumentException> { LanSyncResourceStore(relative, budget) }
        assertFalse(Files.exists(relative))
        val parent = Files.createTempDirectory("lan-store-link-").toRealPath()
        val outside = Files.createTempDirectory("lan-store-outside-").toRealPath()
        try {
            val link = parent.resolve("link")
            try { Files.createSymbolicLink(link, outside) }
            catch (failure: Exception) { assumeNoException("This probe requires symbolic-link support", failure) }
            assertFailsWith<IllegalArgumentException> { LanSyncResourceStore(link.resolve("must-not-be-created"), budget) }
            assertFalse(Files.exists(outside.resolve("must-not-be-created")))
            Files.deleteIfExists(link)
        } finally { check(parent.toFile().deleteRecursively()); check(outside.toFile().deleteRecursively()) }
    }
}
