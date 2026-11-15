package com.example.chatbar.domain.syncStore

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class SyncCiphertextCandidatesTest {
    @Test fun sameKeyDifferentCiphertextDescriptorsSurviveReopenWithoutPoisoningEachOtherOrLegacy() = withRoot { root ->
        val key = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random())
        val first = ByteArray(8193) { ((it * 17 + 3) and 255).toByte() }
        val second = ByteArray(8193) { ((it * 31 + 9) and 255).toByte() }
        val old = FileCiphertextStore(root.resolve("legacy"))
        val good = FileCiphertextStore(root.resolve("peer"))
        val firstMetadata = put(old, key, first)
        val secondMetadata = put(good, key, second)
        val directory = root.resolve("candidates")
        val candidates = SyncCiphertextCandidates(directory, old)
        // The original may be hash-consistent yet fail a later AEAD/owner check. Keep that evidence.
        candidates.retain(firstMetadata, old)
        candidates.retain(secondMetadata, good)
        val beforeRetry = candidates.entries().toSet()
        candidates.retain(secondMetadata, good)
        assertEquals(setOf(firstMetadata, secondMetadata), beforeRetry)
        assertEquals(beforeRetry, candidates.entries().toSet())
        assertEquals(0L, Files.list(directory.resolve("preparations")).use { it.count() })

        val reopened = SyncCiphertextCandidates(directory, old)
        assertEquals(setOf(firstMetadata, secondMetadata), reopened.entries().toSet())
        assertContentEquals(first, read(reopened.exact(firstMetadata), firstMetadata))
        assertContentEquals(second, read(reopened.exact(secondMetadata), secondMetadata))
        assertEquals(firstMetadata, old.metadata(key))
        assertEquals(listOf(firstMetadata), old.listCommits().entries)
        assertContentEquals(first, read(old, firstMetadata))
    }

    @Test fun retainedDiscoveryDescriptorCanDifferFromTheSourcesDefaultSameKeyMetadata() = withRoot { root ->
        val key = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random())
        val old = FileCiphertextStore(root.resolve("first-source"))
        val requested = FileCiphertextStore(root.resolve("requested-source"))
        val firstMetadata = put(old, key, byteArrayOf(10, 11, 12))
        val requestedBytes = byteArrayOf(30, 31, 32, 33)
        val requestedMetadata = put(requested, key, requestedBytes)
        val source = object : CiphertextStore by old {
            override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? =
                if (expected == requestedMetadata.descriptor) requested.readTo(key, destination, expected)
                else old.readTo(key, destination, expected)
        }
        assertEquals(firstMetadata, source.metadata(key))
        val candidates = SyncCiphertextCandidates(root.resolve("candidates"), old)
        candidates.retain(requestedMetadata, source)
        assertEquals(setOf(firstMetadata, requestedMetadata), candidates.entries().toSet())
        assertContentEquals(requestedBytes, read(candidates.exact(requestedMetadata), requestedMetadata))
        assertContentEquals(byteArrayOf(10, 11, 12), read(candidates.exact(firstMetadata), firstMetadata))
    }

    @Test fun exactLegacyLensNeverSubstitutesADifferentDescriptorAndHasNoWriteAuthority() = withRoot { root ->
        val key = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random())
        val bytes = byteArrayOf(3, 4, 5, 6)
        val legacy = FileCiphertextStore(root.resolve("legacy"))
        val metadata = put(legacy, key, bytes)
        val repository = SyncCiphertextCandidates(root.resolve("candidates"), legacy)
        val lens = repository.exact(metadata)
        assertContentEquals(bytes, read(lens, metadata))
        assertNull(lens.metadata(CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random())))
        val other = metadata.copy(descriptor = metadata.descriptor.copy(length = bytes.size.toLong() + 1))
        val absent = repository.exact(other)
        assertNull(absent.metadata(key))
        assertNull(absent.readTo(key, ByteArrayOutputStream(), other.descriptor))
        val output = ByteArrayOutputStream()
        assertFailsWith<IllegalArgumentException> { lens.readTo(key, output, other.descriptor) }
        assertEquals(0, output.size())
        assertEquals(CiphertextStoreFailure.UNSUPPORTED, assertFailsWith<CiphertextStoreException> {
            lens.put(CiphertextPutRequest(key, SyncStoreId.random(), metadata.descriptor), bytes.inputStream())
        }.failure)
        assertEquals(listOf(metadata), legacy.listCommits().entries)
        assertContentEquals(bytes, read(legacy, metadata))
    }

    @Test fun incompleteCorruptOrSubstitutedSourceCannotPublishAnEnumeratedCandidate() = withRoot { root ->
        val bytes = ByteArray(4097) { (it % 251).toByte() }
        val original = FileCiphertextStore(root.resolve("remote"))
        val metadata = put(original, CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()), bytes)
        val variants = listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(0x55),
            bytes.copyOf().also { it[23] = (it[23].toInt() xor 1).toByte() })
        variants.forEachIndexed { index, malformed ->
            val source = object : CiphertextStore by original {
                override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
                    destination.write(malformed)
                    return metadata
                }
            }
            val candidates = SyncCiphertextCandidates(root.resolve("candidates-$index"))
            assertEquals("SYNC_CANDIDATE_SOURCE_CHANGED", assertFailsWith<IllegalArgumentException> {
                candidates.retain(metadata, source)
            }.message)
            assertTrue(candidates.entries().isEmpty())
        }
        val substituted = object : CiphertextStore by original {
            override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
                original.readTo(key, destination, expected)
                return metadata.copy(key = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()))
            }
        }
        val candidates = SyncCiphertextCandidates(root.resolve("substituted"))
        assertFailsWith<IllegalArgumentException> { candidates.retain(metadata, substituted) }
        assertTrue(candidates.entries().isEmpty())
        assertContentEquals(bytes, read(original, metadata))
    }

    @Test fun unexpectedFilesAndRenamedCandidateDirectoriesRefuseInsteadOfCreatingAuthority() = withRoot { root ->
        val source = FileCiphertextStore(root.resolve("source"))
        val metadata = put(source, CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()), byteArrayOf(7, 8, 9))
        val candidateRoot = root.resolve("candidates")
        val repository = SyncCiphertextCandidates(candidateRoot)
        repository.retain(metadata, source)
        val directory = Files.newDirectoryStream(candidateRoot).use { paths ->
            paths.single { it.fileName.toString() != "preparations" }
        }
        val replacement = if (directory.fileName.toString() == "a".repeat(64)) "b".repeat(64) else "a".repeat(64)
        Files.move(directory, candidateRoot.resolve(replacement))
        assertEquals("SYNC_CANDIDATE_DAMAGED", assertFailsWith<IllegalArgumentException> { repository.entries() }.message)

        val extraRoot = root.resolve("extra")
        val withUnexpected = SyncCiphertextCandidates(extraRoot)
        Files.write(extraRoot.resolve("unexpected"), byteArrayOf(1))
        assertEquals("SYNC_CANDIDATE_UNEXPECTED_PATH", assertFailsWith<IllegalArgumentException> { withUnexpected.entries() }.message)
    }

    private fun put(store: CiphertextStore, key: CiphertextObjectKey, bytes: ByteArray): CiphertextObjectMetadata {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val request = CiphertextPutRequest(key, SyncStoreId.random(), CiphertextDescriptor(bytes.size.toLong(), CiphertextSha256.parse(hash)))
        return store.put(request, bytes.inputStream()).metadata
    }
    private fun read(store: CiphertextStore, metadata: CiphertextObjectMetadata): ByteArray = ByteArrayOutputStream().also {
        assertEquals(metadata, store.readTo(metadata.key, it, metadata.descriptor))
    }.toByteArray()
    private fun withRoot(work: (Path) -> Unit) {
        val root = Files.createTempDirectory("ccb-ciphertext-candidates-").toRealPath()
        try { work(root) } finally { root.toFile().deleteRecursively() }
    }
}
