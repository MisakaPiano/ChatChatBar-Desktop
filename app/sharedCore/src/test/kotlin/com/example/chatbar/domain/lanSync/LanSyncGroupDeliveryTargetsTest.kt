package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.*

/** Offline file stores simulate independent routes; no cloud account or native discovery is implied. */
class LanSyncGroupDeliveryTargetsTest {
    private fun withRoot(work: (Path) -> Unit) {
        val root = Files.createTempDirectory("synthetic-group-delivery-targets-").toRealPath()
        try { work(root) } finally { check(root.toFile().deleteRecursively()) }
    }
    @Test fun localAndNearbyCompletionNeverSkipCloudAndALostCloudResponseRetainsIndependentReceipts() = withRoot { root ->
        val f = LanSyncGroupPublicationQueueTest.Fixture(Files.createDirectory(root.resolve("fixture")))
        val prepared = f.prepare(); val publisher = f.publisher()
        val plan = publisher.prepare(prepared.metadata, listOf(prepared.unit), prepared.operations, emptyList())
        val local = SyncDeliveryTarget(SyncDeliveryKind.LOCAL_REPLICA, SyncStoreId.random())
        val nearby = SyncDeliveryTarget(SyncDeliveryKind.NEARBY_DEVICE, local.id)
        val cloud = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, SyncStoreId.random())
        val nearStore = FileCiphertextStore(root.resolve("nearby")); val cloudStore = FileCiphertextStore(root.resolve("simulated-cloud"))
        val own = publisher.upload(plan.metadata.operation, f.backend, local)
        assertEquals(local, own.target); assertNull(publisher.receipt(plan.metadata.operation, nearby)); assertNull(publisher.receipt(plan.metadata.operation, cloud))
        check(f.data.toFile().deleteRecursively())
        val near = f.publisher().upload(plan.metadata.operation, nearStore, nearby)
        assertEquals(nearby, near.target); assertEquals(own, f.publisher().receipt(plan.metadata.operation, local))
        assertNull(cloudStore.metadata(plan.metadata.key)); assertNull(f.publisher().receipt(plan.metadata.operation, cloud))
        var interrupt = true
        val lostReply = object : CiphertextStore by cloudStore {
            override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult {
                val result = cloudStore.put(request, source)
                if (interrupt && request.key == plan.metadata.key) { interrupt = false; throw IOException("SYNTHETIC_CLOUD_REPLY_LOSS") }
                return result
            }
        }
        assertFailsWith<IOException> { f.publisher().upload(plan.metadata.operation, lostReply, cloud) }
        assertNull(f.publisher().receipt(plan.metadata.operation, cloud))
        assertEquals(near, f.publisher().receipt(plan.metadata.operation, nearby))
        val completed = f.publisher().upload(plan.metadata.operation, cloudStore, cloud)
        assertEquals(cloud, completed.target)
        assertTrue(plan.blobs.values.all { cloudStore.metadata(it.key)?.descriptor == it.descriptor })
        assertEquals(plan.metadata.descriptor, cloudStore.metadata(plan.metadata.key)?.descriptor)
        val unavailable = object : CiphertextStore by cloudStore {
            override fun metadata(key: CiphertextObjectKey): CiphertextObjectMetadata? = error("SYNTHETIC_COMPLETED_RECEIPT_IS_HISTORICAL")
        }
        assertEquals(completed, f.publisher().upload(plan.metadata.operation, unavailable, cloud))
        assertNull(f.publisher().receipt(plan.metadata.operation)) // legacy completion is a separate route too
        val path = f.publications.resolve("publication-receipt-${plan.metadata.operation.value}-${cloud.kind.name.lowercase(Locale.ROOT)}-${cloud.id.value}.json")
        LanSyncJsonFiles.write(path, LanSyncGroupPublishReceipt.serializer(), near)
        assertEquals("SYNC_GROUP_PUBLICATION_RECEIPT_CHANGED", assertFailsWith<IllegalArgumentException> { f.publisher().receipt(plan.metadata.operation, cloud) }.message)
    }
    @Test fun rootKeyCapsulesAndLedgerAreAlsoDeliveredIndependentlyToEveryRoute() = withRoot { root ->
        val f = LanSyncGroupPublicationQueueTest.Fixture(Files.createDirectory(root.resolve("fixture")))
        val (metadata, operations) = f.prepareLedger(); val publisher = f.rootPublisher()
        val plan = publisher.prepare(metadata, emptyList(), emptyMap(), emptyList(), operations)
        val nearby = SyncDeliveryTarget(SyncDeliveryKind.NEARBY_DEVICE, SyncStoreId.random())
        val cloud = SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, SyncStoreId.random())
        val cloudStore = FileCiphertextStore(root.resolve("simulated-cloud"))
        publisher.upload(plan.metadata.operation, f.backend, nearby)
        assertNull(publisher.receipt(plan.metadata.operation, cloud)); assertNull(cloudStore.metadata(plan.metadata.key))
        val receipt = f.rootPublisher().upload(plan.metadata.operation, cloudStore, cloud)
        assertEquals(cloud, receipt.target); assertEquals(2L, receipt.keyDeliveryCount)
        assertTrue(plan.keyDeliveries.values.all { cloudStore.metadata(it.key)?.descriptor == it.descriptor })
        assertEquals(plan.metadata.descriptor, cloudStore.metadata(plan.metadata.key)?.descriptor)
        assertEquals(nearby, f.rootPublisher().receipt(plan.metadata.operation, nearby)?.target)
    }
    @Test fun theNativeSourceWriterPublishesTheSameSignedFactToEachExplicitRoute() = withRoot { root ->
        val node = LanSyncGroupSourceWriterTest.Node(root); node.localCard("a", "独立目的地合成源")
        val operation = SyncStoreId.random(); val source = node.writer().commit(node.review(setOf("character_cards/a")).authorize(), operation)
        val local = SyncDeliveryTarget(SyncDeliveryKind.LOCAL_REPLICA, SyncStoreId.random())
        val nearby = SyncDeliveryTarget(SyncDeliveryKind.NEARBY_DEVICE, SyncStoreId.random())
        val nearStore = FileCiphertextStore(root.resolve("nearby"))
        assertEquals(local, node.writer().upload(operation, node.f.source.backend, local).target)
        assertEquals(local, node.writer().receipt(operation, local)?.target); assertNull(node.writer().receipt(operation, nearby))
        assertNull(nearStore.metadata(source.publication.metadata.key))
        assertEquals(nearby, node.writer().upload(operation, nearStore, nearby).target)
        assertEquals(nearby, node.writer().receipt(operation, nearby)?.target)
        assertEquals(source.metadata.metadata, nearStore.metadata(source.publication.metadata.key))
        assertEquals(1L, node.journal().state().committedSequence)
        assertEquals(source.entry, node.writer().resume(operation).entry)
    }
}
