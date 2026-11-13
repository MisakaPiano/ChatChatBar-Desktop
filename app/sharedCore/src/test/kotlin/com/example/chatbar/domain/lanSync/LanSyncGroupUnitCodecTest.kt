package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.domain.syncStore.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.test.*

/** Actual shared Native records/Workspace over synthetic roots, not native app/device acceptance. */
class LanSyncGroupUnitCodecTest {
    private fun withRoot(work: (Path) -> Unit) {
        val root = Files.createTempDirectory("group-native-unit-").toRealPath()
        try { work(root) } finally { check(root.toFile().deleteRecursively()) }
    }
    private class Fixture(val root: Path) {
        val data = Files.createDirectory(root.resolve("data")); val state = Files.createDirectory(root.resolve("state"))
        val units = Files.createDirectory(root.resolve("units")); val crypto = Files.createDirectory(root.resolve("crypto"))
        val queue = Files.createDirectory(root.resolve("queue")); val cipher = SyncStreamingCipher.create()
        val workspace = LanSyncWorkspace(data, state, { Path.of(it) })
        val codec = LanSyncGroupUnitCodec(units); val blobs = SyncGroupBlobStore(cipher.group, crypto)
        val outbox = SyncEncryptedOutbox(queue, cipher); val backend = FileCiphertextStore(root.resolve("backend"))
        fun card(image: Path? = null) {
            val path = data.resolve("entities/character_cards/card.json"); Files.createDirectories(path.parent)
            LanSyncJsonFiles.write(path, CharacterCard.serializer(), CharacterCard("card", "合成原角色", avatar = image?.toString(), createdAt = 1, updatedAt = 1))
        }
        fun transfer(source: Path, identity: SyncPlaintextIdentity): SyncAuthenticatedPlaintext {
            val operation = SyncStoreId.random(); val key = CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random())
            val binding = blobs.enqueue(outbox, operation, key, cipher, source, identity)
            outbox.upload(operation, backend); return blobs.read(backend, cipher, binding, identity)
        }
        fun identity(path: Path) = SyncPlaintextIdentity(Files.size(path), LanSyncResourceStore.hashFile(path, Files.size(path)))
    }

    @Test fun actualCardUnitAndDeclaredImageDecryptValidateApplyAndReopenWithExactBusinessAndResourceHashes() = withRoot { root ->
        val f = Fixture(root); val image = f.data.resolve("images/original.png"); Files.createDirectories(image.parent)
        val bytes = LanSyncPreviewSample.image(); Files.write(image, bytes); f.card(image)
        f.workspace.capture("source").use { capture -> f.codec.prepare(capture, "character_cards/card").use { prepared ->
            assertEquals(capture.snapshot.hashes().getValue("character_cards/card"), prepared.decoded.valueHash)
            assertEquals(setOf(SyncPlaintextIdentity(bytes.size.toLong(), lanSyncHash(bytes))), prepared.decoded.dependencies)
            val change = SyncUnitChange(prepared.decoded.valueHash, prepared.decoded.plaintext, emptySet())
            f.transfer(prepared.path, prepared.decoded.plaintext).use { plaintext ->
                val decoded = f.codec.read(plaintext, "character_cards/card", change)
                val resources = decoded.dependencies.associateWith { dependency ->
                    f.transfer(requireNotNull(capture.blobs[dependency.sha256]), dependency)
                }
                try {
                    val target = Files.createDirectory(root.resolve("target")); val targetState = Files.createDirectory(root.resolve("target-state"))
                    val targetWorkspace = LanSyncWorkspace(target, targetState, { Path.of(it) })
                    val snapshot = LanSyncSnapshot(deviceId = "target", records = decoded.payload.records,
                        resources = decoded.payload.resources, coveredTypes = decoded.payload.coveredTypes)
                    targetWorkspace.capture("target").use { before -> targetWorkspace.apply(LanSyncPlan(UUID.randomUUID().toString(), snapshot, emptyMap()),
                        before.snapshot.hashes(), "target", resources.entries.associate { (identity, body) -> identity.sha256 to body.path }) }
                    val reopened = LanSyncWorkspace(target, targetState, { Path.of(it) })
                    reopened.capture("target").use { after ->
                        assertEquals(capture.snapshot.hashes(), after.snapshot.hashes())
                        assertEquals(capture.snapshot.resources, after.snapshot.resources)
                        assertContentEquals(bytes, Files.readAllBytes(after.blobs.values.single()))
                    }
                } finally { resources.values.forEach { it.close() } }
            }
        } }
        assertTrue(Files.newDirectoryStream(f.units).use { !it.iterator().hasNext() })
        assertTrue(Files.newDirectoryStream(f.crypto).use { !it.iterator().hasNext() })
    }

    @Test fun aMissingDeclaredResourceAndForeignLocalModelFieldsRefuseBeforeNativePublication() = withRoot { root ->
        val f = Fixture(root); val image = f.data.resolve("images/original.png"); Files.createDirectories(image.parent)
        Files.write(image, LanSyncPreviewSample.image()); f.card(image)
        val session = f.data.resolve("entities/chat_sessions/session.json"); Files.createDirectories(session.parent)
        LanSyncJsonFiles.write(session, ChatSession.serializer(), ChatSession("session", "card", "合成会话", modelId = "device-only-model", createdAt = 1, updatedAt = 1))
        f.workspace.capture("source").use { capture ->
            for (unit in listOf("character_cards/card", "conversation/session")) f.codec.prepare(capture, unit).use { prepared ->
                val original = prepared.decoded.payload
                val invalid = if (unit.startsWith("character_cards")) original.copy(resources = emptyMap()) else {
                    assertNull(original.records.single().body["modelId"])
                    original.copy(records = original.records.map { it.copy(body = JsonObject(it.body + ("modelId" to JsonPrimitive("foreign-model")))) })
                }
                val file = root.resolve("invalid-unit.json"); LanSyncJsonFiles.write(file, LanSyncGroupUnitPayload.serializer(), invalid)
                val identity = f.identity(file)
                f.transfer(file, identity).use { plaintext ->
                    val failure = assertFailsWith<IllegalArgumentException> { f.codec.read(plaintext, unit,
                        SyncUnitChange(prepared.decoded.valueHash, identity, emptySet())) }
                    assertEquals(if (unit.startsWith("character_cards")) "SYNC_GROUP_UNIT_RESOURCES" else "SYNC_GROUP_UNIT_DEVICE_FIELDS", failure.message)
                }
                Files.delete(file)
            }
        }
        assertEquals("device-only-model", lanSyncJson.decodeFromString(ChatSession.serializer(), Files.readString(session)).modelId)
    }

    @Test fun wrongUnitOrValueHashAndMetadataWorkingBudgetNeverGainUnitAuthority() = withRoot { root ->
        val f = Fixture(root); f.card()
        f.workspace.capture("source").use { capture -> f.codec.prepare(capture, "character_cards/card").use { prepared ->
            f.transfer(prepared.path, prepared.decoded.plaintext).use { plaintext ->
                val change = SyncUnitChange(prepared.decoded.valueHash, prepared.decoded.plaintext, emptySet())
                assertEquals("SYNC_GROUP_UNIT_BINDING", assertFailsWith<IllegalArgumentException> { f.codec.read(plaintext, "character_cards/other", change) }.message)
                assertEquals("SYNC_GROUP_UNIT_BINDING", assertFailsWith<IllegalArgumentException> { f.codec.read(plaintext, "character_cards/card", change.copy(valueHash = "0".repeat(64))) }.message)
            }
        }
            val bounded = LanSyncGroupUnitCodec(f.units, metadataBudget = LanSyncMetadataBudget(maximumDecodedCost = 1))
            assertEquals("SYNC_METADATA_WORKING_BUDGET", assertFailsWith<IllegalArgumentException> { bounded.prepare(capture, "character_cards/card") }.message)
        }
        assertTrue(Files.newDirectoryStream(f.units).use { !it.iterator().hasNext() })
    }

    @Test fun aUnitCannotReplaceTheNativeWireReferenceContractWithAnArbitraryResourceAlias() = withRoot { root ->
        val f = Fixture(root); val image = f.data.resolve("images/original.png"); Files.createDirectories(image.parent)
        Files.write(image, LanSyncPreviewSample.image()); f.card(image)
        f.workspace.capture("source").use { capture -> f.codec.prepare(capture, "character_cards/card").use { prepared ->
            val payload = prepared.decoded.payload; val hash = payload.resources.keys.single()
            val invalid = payload.copy(records = payload.records.map { it.copy(body = JsonObject(it.body +
                ("avatar" to JsonPrimitive("ccb-lan-v1/images/not-a-native-identity/$hash/png")))) })
            val file = root.resolve("invalid-reference.json"); LanSyncJsonFiles.write(file, LanSyncGroupUnitPayload.serializer(), invalid)
            val identity = f.identity(file)
            f.transfer(file, identity).use { plaintext ->
                assertEquals("SYNC_EXTERNAL_WIRE_REFERENCE", assertFailsWith<IllegalArgumentException> {
                    f.codec.read(plaintext, "character_cards/card", SyncUnitChange(prepared.decoded.valueHash, identity, emptySet()))
                }.message)
            }
        } }
    }
}
