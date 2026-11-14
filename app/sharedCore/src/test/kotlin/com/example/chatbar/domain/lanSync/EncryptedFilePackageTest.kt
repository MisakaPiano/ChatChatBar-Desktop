package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Container/AEAD proof only; inline objects are not a fabricated root/native admission fixture. */
class EncryptedFilePackageTest {
    private class Pattern(private val length: Long) : InputStream() {
        private var position = 0L
        override fun read(): Int = if (position == length) -1 else value(position++)
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            if (count == 0) return 0
            if (position == length) return -1
            val size = minOf(count.toLong(), length - position).toInt()
            repeat(size) { bytes[offset + it] = value(position++).toByte() }; return size
        }
        private fun value(index: Long) = ((index * 17 + index / 251) % 251).toInt()
    }

    internal class Fixture(val root: Path, resourceLength: Long = 1027) {
        val work = Files.createDirectory(root.resolve("work"))
        val input = Files.createDirectory(root.resolve("input"))
        val store = FileCiphertextStore(root.resolve("source"))
        val cipher = SyncStreamingCipher.create()
        val checkpoint = ref("checkpoint")
        val version = ref("one original version")
        val trust: SyncGroupTrust
        val resource: LanSyncGroupTargetObject
        val plan: LanSyncGroupTargetPlan
        init {
            val items = mutableListOf<LanSyncGroupTargetObject>()
            val management = SyncManagementSession.create()
            try {
                trust = SyncGroupTrust(cipher.group, ref("original genesis"), management.ownerCertificate)
                val vault = management.seal(trust)
                val bytes = vault.bytes(); val descriptor = descriptor(bytes)
                val operation = SyncStoreId.random()
                store.put(CiphertextPutRequest(vault.key, operation, descriptor), bytes.inputStream())
                items += LanSyncGroupTargetObject(LanSyncGroupTargetObjectRole.VAULT, "vault", vault.key, descriptor, operation)
            } finally { management.close() }
            fun encrypted(role: LanSyncGroupTargetObjectRole, slot: String, source: InputStream): LanSyncGroupTargetObject {
                val kind = if (role == LanSyncGroupTargetObjectRole.RESOURCE) CiphertextObjectKind.BLOB else CiphertextObjectKind.COMMIT
                val key = CiphertextObjectKey(kind, SyncStoreId.random()); val operation = SyncStoreId.random()
                return source.use { plain -> cipher.encrypt(plain, SyncEncryptionContext(cipher.group, cipher.epoch, key), work).use { raw ->
                    Files.newInputStream(raw.path).use { store.put(CiphertextPutRequest(key, operation, raw.descriptor), it) }
                    LanSyncGroupTargetObject(role, if (role == LanSyncGroupTargetObjectRole.RESOURCE) key.id.value else slot,
                        key, raw.descriptor, operation)
                } }
            }
            items += encrypted(LanSyncGroupTargetObjectRole.LEDGER, "ledger", "$MARKER synthetic control".byteInputStream())
            items += encrypted(LanSyncGroupTargetObjectRole.VERSION, version.id.value, "$MARKER synthetic version".byteInputStream())
            resource = encrypted(LanSyncGroupTargetObjectRole.RESOURCE, SyncStoreId.random().value, Pattern(resourceLength))
            items += resource
            plan = LanSyncGroupTargetPlan(cipher.group, checkpoint, cipher.epoch, cipher.keyCommitment(),
                SyncDeliveryTarget(SyncDeliveryKind.LOCAL_REPLICA, SyncStoreId.random()), setOf(version), items)
        }
        fun prepare(source: CiphertextStore = store) = LanSyncGroupEncryptedFilePackage.prepare(plan, source, trust, cipher, work)
        /** Small malformed-frame fixtures only. The large-object case uses actual file streams. */
        fun smallBytes(): ByteArray = prepare().use { prepared ->
            ByteArrayOutputStream().also { prepared.writeTo(it) }.toByteArray()
        }
    }

    private fun privateRoot(work: (Path) -> Unit) {
        val root = Files.createTempDirectory("group-file-inline-").toRealPath()
        try { work(root) } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun actualMultiSegmentCiphertextRoundTripsThroughBorrowedFileStreamsAndAReadOnlyQuarantine() = privateRoot { root ->
        val f = Fixture(root, 3L * 1024 * 1024 + 29)
        val packageFile = root.resolve("portable.synthetic")
        f.prepare().use { prepared ->
            var outputClosed = false
            Files.newOutputStream(packageFile).use { file ->
                val borrowed = object : OutputStream() {
                    override fun write(value: Int) = file.write(value)
                    override fun write(bytes: ByteArray, offset: Int, length: Int) = file.write(bytes, offset, length)
                    override fun close() { outputClosed = true }
                }
                assertEquals(prepared.descriptor, prepared.writeTo(borrowed)); assertFalse(outputClosed)
            }
            Files.newInputStream(packageFile).use { LanSyncGroupEncryptedFilePackage.verifyReadback(it, prepared.descriptor) }
            var inputClosed = false; var maximumRead = 0
            Files.newInputStream(packageFile).use { file ->
                val borrowed = object : InputStream() {
                    override fun read() = file.read()
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        maximumRead = maxOf(maximumRead, length); return file.read(bytes, offset, length)
                    }
                    override fun close() { inputClosed = true }
                }
                LanSyncGroupEncryptedFilePackage.stage(borrowed, f.input).use { staged ->
                    assertFalse(inputClosed); assertTrue(maximumRead <= 64 * 1024)
                    assertEquals(prepared.descriptor, staged.descriptor)
                    assertTrue(staged.quarantine.capabilities.untrustedCandidateVariants)
                    assertEquals(f.plan, staged.verifyInventory(f.cipher, f.trust, f.checkpoint).plan)
                    for (item in f.plan.objects) {
                        val digest = MessageDigest.getInstance("SHA-256"); var length = 0L
                        val sink = object : OutputStream() {
                            override fun write(value: Int) { digest.update(value.toByte()); length++ }
                            override fun write(bytes: ByteArray, offset: Int, count: Int) { digest.update(bytes, offset, count); length += count }
                        }
                        assertEquals(CiphertextObjectMetadata(item.key, item.descriptor), staged.quarantine.readTo(item.key, sink, item.descriptor))
                        assertEquals(item.descriptor.length, length); assertEquals(item.descriptor.sha256.hex, hex(digest.digest()))
                    }
                    var cursor: CommitCursor? = null; val commits = mutableSetOf<CiphertextObjectMetadata>()
                    do { val page = staged.quarantine.listCommits(cursor, 1); commits += page.entries; cursor = page.nextCursor } while (cursor != null)
                    assertEquals(f.plan.objects.filter { it.key.kind == CiphertextObjectKind.COMMIT }
                        .map { CiphertextObjectMetadata(it.key, it.descriptor) }.toSet(), commits)
                    assertEquals(CiphertextStoreFailure.UNSUPPORTED, assertFailsWith<CiphertextStoreException> {
                        staged.quarantine.put(CiphertextPutRequest(f.resource.key, SyncStoreId.random(), f.resource.descriptor), Pattern(1))
                    }.failure)
                }
                assertFalse(inputClosed)
            }
        }
        assertEquals(0, Files.list(f.input).use { it.count().toInt() })
        assertEquals(0, Files.list(f.work).use { it.count().toInt() })
    }

    @Test fun aPackageUsesRandomizedEncryptedInventoryAndContainsNoInlineBusinessPlaintext() = privateRoot { root ->
        val f = Fixture(root)
        val first = f.smallBytes(); val second = f.smallBytes()
        assertFalse(first.contentEquals(second))
        assertFalse(String(first, Charsets.ISO_8859_1).contains(MARKER))
        assertFalse(String(second, Charsets.ISO_8859_1).contains(MARKER))
        LanSyncGroupEncryptedFilePackage.stage(first.inputStream(), f.input).use { stage ->
            assertEquals(f.plan, stage.verifyInventory(f.cipher, f.trust, f.checkpoint).plan)
        }
    }

    @Test fun ordinaryMalformedFramesNeverPublishAUsableStageAndLeaveNoPartialDirectory() = privateRoot { root ->
        val f = Fixture(root); val good = f.smallBytes(); val layout = layout(good)
        val mutations = listOf(
            good.copyOf().also { it[MAGIC.size] = 2 },
            good.copyOf().also { ByteBuffer.wrap(it, MAGIC.size + 1, 4).putInt(Int.MAX_VALUE) },
            good.copyOf().also { it[layout.items.first().tag] = 99 },
            good.copyOf().also { it[layout.items.first().role] = 99 },
            good.copyOf().also { ByteBuffer.wrap(it, layout.items.last().lengthOffset, 8).putLong(-1) },
            good.copyOf().also { ByteBuffer.wrap(it, layout.items.last().lengthOffset, 8).putLong(Long.MAX_VALUE) },
            good.copyOf().also {
                good.copyInto(it, layout.items.last().keyOffset, layout.items.first().keyOffset, layout.items.first().keyOffset + 32)
                good.copyInto(it, layout.items.last().role + 3, layout.items.first().keyOffset, layout.items.first().keyOffset + 32)
            },
            good.copyOf().also { it[layout.items.last().payload + layout.items.last().length.toInt() - 1] = (it[layout.items.last().payload + layout.items.last().length.toInt() - 1].toInt() xor 1).toByte() },
            good.copyOf(layout.sealTag), good.copyOf(good.size - 1), good + byteArrayOf(0),
            good.copyOf().also { ByteBuffer.wrap(it, layout.footer + 1, 8).putLong(-1) },
            headerChanged(good) { it.copy(objectCount = Long.MAX_VALUE) },
            headerChanged(good) { it.copy(ciphertextBytes = Long.MAX_VALUE) },
            headerChanged(good) { it.copy(epoch = SyncStoreId.random()) },
        )
        for (bad in mutations) {
            assertFails { LanSyncGroupEncryptedFilePackage.stage(bad.inputStream(), f.input).close() }
            assertEquals(0, Files.list(f.input).use { it.count().toInt() })
        }
    }

    @Test fun recomputingPublicHashesCannotRepairATamperedEncryptedInventory() = privateRoot { root ->
        val f = Fixture(root); val bad = f.smallBytes(); val layout = layout(bad)
        val last = layout.sealPayload + layout.sealLength.toInt() - 1
        bad[last] = (bad[last].toInt() xor 1).toByte()
        ascii(hash(bad.copyOfRange(layout.sealPayload, layout.sealPayload + layout.sealLength.toInt())))
            .copyInto(bad, layout.sealHash)
        repairFooter(bad, layout.footer)
        LanSyncGroupEncryptedFilePackage.stage(bad.inputStream(), f.input).use { staged ->
            // All unkeyed framing and object hashes pass. The actual AEAD final segment still fails.
            assertFails { staged.verifyInventory(f.cipher, f.trust, f.checkpoint) }
            assertTrue(staged.quarantine.capabilities.untrustedCandidateVariants)
        }
    }

    @Test fun anAuthenticInventoryCannotBeReusedAfterReorderingWholeCiphertextFrames() = privateRoot { root ->
        val f = Fixture(root); val good = f.smallBytes(); val layout = layout(good)
        val reordered = ByteArrayOutputStream().also { out ->
            out.write(good, 0, layout.items.first().tag)
            for (item in layout.items.reversed()) out.write(good, item.tag, item.payload + item.length.toInt() - item.tag)
            out.write(good, layout.sealTag, good.size - layout.sealTag)
        }.toByteArray()
        repairFooter(reordered, layout.footer)
        LanSyncGroupEncryptedFilePackage.stage(reordered.inputStream(), f.input).use { staged ->
            assertFails { staged.verifyInventory(f.cipher, f.trust, f.checkpoint) }
        }
    }

    @Test fun inventoryRequiresTheIndependentOriginalTrustCheckpointAndExactEpochKey() = privateRoot { root ->
        val f = Fixture(root)
        LanSyncGroupEncryptedFilePackage.stage(f.smallBytes().inputStream(), f.input).use { staged ->
            assertFails { staged.verifyInventory(SyncStreamingCipher.create(f.cipher.group, f.cipher.epoch), f.trust, f.checkpoint) }
            assertFails { staged.verifyInventory(f.cipher, f.trust.copy(group = SyncStoreId.random()), f.checkpoint) }
            assertFails { staged.verifyInventory(f.cipher, f.trust.copy(genesis = ref("wrong genesis")), f.checkpoint) }
            assertFails { staged.verifyInventory(f.cipher, f.trust.copy(ownerCertificate = "different-root"), f.checkpoint) }
            assertFails { staged.verifyInventory(f.cipher, f.trust, ref("different checkpoint")) }
            assertEquals(f.plan, staged.verifyInventory(f.cipher, f.trust, f.checkpoint).plan)
        }
    }

    @Test fun exactSourceMetadataAndBytesAreRequiredAndTheSameKeyCannotBeSubstituted() = privateRoot { root ->
        val f = Fixture(root)
        val source = object : CiphertextStore by f.store {
            override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
                val actual = f.store.readTo(key, destination, expected) ?: return null
                return if (key == f.resource.key) actual.copy(descriptor = actual.descriptor.copy(length = actual.descriptor.length + 1)) else actual
            }
        }
        assertFails { f.prepare(source).close() }
        assertEquals(0, Files.list(f.work).use { it.count().toInt() })
        f.prepare().close()
    }

    @Test fun aDescriptorMismatchOrDamagedPrivateObjectCannotEscapeTheQuarantine() = privateRoot { root ->
        val f = Fixture(root)
        LanSyncGroupEncryptedFilePackage.stage(f.smallBytes().inputStream(), f.input).use { staged ->
            val count = AtomicInteger()
            val output = object : OutputStream() {
                override fun write(value: Int) { count.incrementAndGet() }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { count.addAndGet(length) }
            }
            assertNull(staged.quarantine.readTo(f.resource.key, output, f.resource.descriptor.copy(length = f.resource.descriptor.length + 1)))
            assertEquals(0, count.get())
            staged.verifyInventory(f.cipher, f.trust, f.checkpoint)
            val file = Files.walk(f.input).use { paths -> paths.filter {
                it.fileName.toString() == "${f.resource.key.id.value}.obj"
            }.findFirst().orElseThrow() }
            FileChannel.open(file, READ, WRITE).use { channel ->
                val byte = ByteBuffer.allocate(1); channel.read(byte, channel.size() - 1)
                byte.flip(); val changed = (byte.get().toInt() xor 1).toByte()
                channel.write(ByteBuffer.wrap(byteArrayOf(changed)), channel.size() - 1); channel.force(true)
            }
            assertFails { staged.verifyInventory(f.cipher, f.trust, f.checkpoint) }
            assertFails { staged.quarantine.readTo(f.resource.key, output, f.resource.descriptor) }
            assertEquals(0, count.get())
        }
    }

    @Test fun aReplacedPrivateStageCannotBeReadOrDeletedAsTheOriginalPreparation() = privateRoot { root ->
        val f = Fixture(root)
        val staged = LanSyncGroupEncryptedFilePackage.stage(f.smallBytes().inputStream(), f.input)
        val original = Files.list(f.input).use { it.findFirst().orElseThrow() }
        val retained = f.input.resolve("retained-original-synthetic")
        Files.move(original, retained)
        Files.createDirectory(original)
        val evidence = "synthetic replacement must remain".toByteArray(Charsets.UTF_8)
        val marker = Files.write(original.resolve("replacement-evidence"), evidence)
        assertFails { staged.quarantine.metadata(f.resource.key) }
        assertFails { staged.close() }
        assertContentEquals(evidence, Files.readAllBytes(marker))
        assertTrue(Files.isDirectory(retained))
    }

    @Test fun sinkAndGuardFailuresKeepTheirOriginalExceptionAndNeverRetryOrCloseBorrowedStreams() = privateRoot { root ->
        val f = Fixture(root); val failure = IOException("synthetic sink refusal"); val calls = AtomicInteger()
        f.prepare().use { prepared ->
            var closed = false
            val output = object : OutputStream() {
                override fun write(value: Int) { calls.incrementAndGet(); throw failure }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { calls.incrementAndGet(); throw failure }
                override fun close() { closed = true }
            }
            assertSame(failure, assertFails { prepared.writeTo(output) }); assertEquals(1, calls.get()); assertFalse(closed)
            val good = ByteArrayOutputStream().also { prepared.writeTo(it) }.toByteArray()
            var reads = 0; val stopped = IllegalStateException("synthetic cancelled owner")
            val input = object : ByteArrayInputStream(good) { override fun close() { closed = true } }
            assertSame(stopped, assertFails { LanSyncGroupEncryptedFilePackage.stage(input, f.input, checkActive = {
                if (++reads == 20) throw stopped
            }).close() })
            assertFalse(closed); assertEquals(0, Files.list(f.input).use { it.count().toInt() })
            LanSyncGroupEncryptedFilePackage.verifyReadback(good.inputStream(), prepared.descriptor)
            assertFails { LanSyncGroupEncryptedFilePackage.verifyReadback(good.copyOf(good.size - 1).inputStream(), prepared.descriptor) }
            assertFails { LanSyncGroupEncryptedFilePackage.verifyReadback((good + byteArrayOf(1)).inputStream(), prepared.descriptor) }
        }
    }

    private data class ItemOffset(val tag: Int, val role: Int, val keyOffset: Int, val lengthOffset: Int,
        val payload: Int, val length: Long)
    private data class Layout(val headerSize: Int, val headerEnd: Int, val items: List<ItemOffset>,
        val sealTag: Int, val sealHash: Int, val sealPayload: Int, val sealLength: Long, val footer: Int)
    private fun layout(bytes: ByteArray): Layout {
        val raw = ByteArrayInputStream(bytes); val data = DataInputStream(raw)
        fun position() = bytes.size - raw.available()
        data.skipBytes(MAGIC.size); assertEquals(1, data.readUnsignedByte())
        val headerSize = data.readInt(); val headerBytes = ByteArray(headerSize).also(data::readFully)
        val header = lanSyncJson.decodeFromString(LanSyncGroupEncryptedFilePackage.UnverifiedHeader.serializer(), lanSyncUtf8(headerBytes))
        val headerEnd = position(); val items = mutableListOf<ItemOffset>()
        repeat(header.objectCount.toInt()) {
            val tag = position(); assertEquals(1, data.readUnsignedByte()); val role = position(); data.readUnsignedByte()
            val slot = data.readUnsignedShort(); data.skipBytes(slot); data.readUnsignedByte()
            val key = position(); data.skipBytes(32); val lengthOffset = position(); val length = data.readLong()
            data.skipBytes(64 + 32); val payload = position(); data.skipBytes(length.toInt())
            items += ItemOffset(tag, role, key, lengthOffset, payload, length)
        }
        val sealTag = position(); assertEquals(2, data.readUnsignedByte()); val length = data.readLong()
        val sealHash = position(); data.skipBytes(64); val payload = position(); data.skipBytes(length.toInt())
        return Layout(headerSize, headerEnd, items, sealTag, sealHash, payload, length, position())
    }
    private fun headerChanged(bytes: ByteArray,
        change: (LanSyncGroupEncryptedFilePackage.UnverifiedHeader) -> LanSyncGroupEncryptedFilePackage.UnverifiedHeader): ByteArray {
        val layout = layout(bytes)
        val original = bytes.copyOfRange(MAGIC.size + 1 + 4, layout.headerEnd)
        val header = lanSyncJson.decodeFromString(LanSyncGroupEncryptedFilePackage.UnverifiedHeader.serializer(), lanSyncUtf8(original))
        val changed = lanSyncJson.encodeToString(LanSyncGroupEncryptedFilePackage.UnverifiedHeader.serializer(), change(header)).toByteArray()
        return ByteArrayOutputStream().also { raw ->
            val out = DataOutputStream(raw); out.write(MAGIC); out.writeByte(1); out.writeInt(changed.size); out.write(changed)
            out.write(bytes, layout.headerEnd, bytes.size - layout.headerEnd)
        }.toByteArray()
    }
    private fun repairFooter(bytes: ByteArray, offset: Int) { ascii(hash(bytes.copyOfRange(0, offset))).copyInto(bytes, offset + 1 + 8 + 8) }
    private companion object {
        const val MARKER = "inline-private-business-text-must-stay-encrypted"
        val MAGIC = "CCB-GROUP-ENCRYPTED-FILE\n".toByteArray(Charsets.US_ASCII)
        fun ref(value: String) = SyncVersionRef(SyncStoreId.random(), hash(value.toByteArray()))
        fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        fun descriptor(bytes: ByteArray) = CiphertextDescriptor(bytes.size.toLong(), CiphertextSha256.parse(hash(bytes)))
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        fun ascii(value: String) = value.toByteArray(Charsets.US_ASCII)
    }
}
