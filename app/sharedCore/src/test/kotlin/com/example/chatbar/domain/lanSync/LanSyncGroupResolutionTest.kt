package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.syncStore.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** Actual existing AEAD/native fixtures; candidate planning is not native apply/signing/UI proof. */
class LanSyncGroupResolutionTest {
    private val local = LanSyncGroupResolutionSource.Local
    private fun version(value: SyncCausalVersion) = LanSyncGroupResolutionSource.Version(value.reference())
    private fun keep(source: LanSyncGroupResolutionSource, copies: Map<String, LanSyncGroupResolutionSource> = emptyMap()) =
        LanSyncGroupResolutionChoice.Keep(source, copies)
    private class Inputs(val f: LanSyncGroupNativePlannerTest.Fixture, val applied: Map<String, LanSyncGroupAppliedUnit>) : AutoCloseable {
        val view = f.view()
        val evidence = LanSyncGroupRecordEvidence(requireNotNull(view.history), requireNotNull(view.ledgerCheckpoint))
        val service = LanSyncGroupDependencies(f.source.cipher.group, f.source.backend, f.custody(), f.source.work,
            f.workspace.resourceStore, { _, _, _ -> })
        val dependencies = linkedMapOf<LanSyncGroupMembershipRequest, LanSyncGroupDependencyResult>()
        val capture = f.workspace.capture(f.source.owner.fingerprint)
        val native: LanSyncGroupNativePlan
        init {
            view.authenticatedVersions.forEach { metadata ->
                val fact = requireNotNull(metadata.envelope.version)
                fact.changes.filterValues { it.content != null }.keys.forEach { unit ->
                    service.membership(view, metadata, unit).membership?.let(evidence::offer)
                    dependencies[LanSyncGroupMembershipRequest(fact.reference(), unit)] = service.inspect(view, metadata, unit)
                }
            }
            native = LanSyncGroupNativePlanner.prepare(capture, view, applied, dependencies, evidence)
        }
        fun prepare(choices: Map<String, LanSyncGroupResolutionChoice>, operation: String = UUID.randomUUID().toString(),
            bodies: Map<LanSyncGroupMembershipRequest, LanSyncGroupDependencyResult> = dependencies,
            manifests: LanSyncGroupRecordEvidence = evidence) =
            LanSyncGroupResolution.prepare(native, capture, view, bodies, manifests, choices, operation)
        override fun close() = capture.close()
    }
    private fun withFixture(work: (LanSyncGroupNativePlannerTest.Fixture) -> Unit) {
        val root = Files.createTempDirectory("synthetic-group-resolution-").toRealPath()
        try { work(LanSyncGroupNativePlannerTest.Fixture(root)) } finally { check(root.toFile().deleteRecursively()) }
    }
    private fun localCard(f: LanSyncGroupNativePlannerTest.Fixture, id: String, text: String) {
        val path = f.target.resolve("entities/character_cards/$id.json"); Files.createDirectories(path.parent)
        LanSyncJsonFiles.write(path, CharacterCard.serializer(), CharacterCard(id, text, createdAt = 1, updatedAt = 1))
    }

    @Test fun choosingOneCompleteVersionResolvesAllConcurrentFrontiersInANewCausalRevision() = withFixture { f ->
        val unit = "character_cards/a"
        f.card("a", "base"); val base = f.publish(setOf(unit)); val applied = f.apply(f.plan(emptyMap()))
        f.card("a", "first branch"); val first = f.publish(setOf(unit), parents = setOf(base.reference()))
        f.card("a", "second branch"); val second = f.publish(setOf(unit), author = 1, parents = setOf(base.reference()))
        localCard(f, "a", "unpublished local third value")
        Inputs(f, applied).use { input ->
            val preview = input.prepare(mapOf(unit to keep(version(second))))
            assertEquals("second branch", preview.target.records.single().text("name"))
            assertEquals(setOf(unit), preview.resolvedUnits)
            assertEquals(setOf(first.reference(), second.reference()), preview.previous.getValue(unit))
            assertEquals(applied, preview.appliedBefore)
            assertEquals(setOf(base.reference(), first.reference(), second.reference()), preview.units.getValue(unit).versions.keys)
            preview.acknowledge(preview.binding, preview.requiredBindings, preview.requiredRestorations)
            // Exercise the real causal kernel separately from membership/AEAD admission.
            val history = SyncCausalHistory(f.source.cipher.group, SyncVersionAdmission {})
            requireNotNull(input.view.history).admittedVersions().forEach { assertEquals(SyncVersionState.ADMITTED, history.offer(it)) }
            val actor = LanSyncTlsIdentity.create()
            val change = SyncUnitChange(preview.target.hashes().getValue(unit), SyncPlaintextIdentity(1, "a".repeat(64)), preview.previous.getValue(unit))
            val merged = SyncCausalVersion(f.source.cipher.group, SyncStoreId.random(), preview.checkpoint, actor.fingerprint, 1, null,
                preview.heads, mapOf(unit to change), "unsigned").let { it.copy(signature = Base64.getEncoder().encodeToString(actor.signSyncGroupPayload(it.signingBytes()))) }
            assertEquals(SyncVersionState.ADMITTED, history.offer(merged))
            assertEquals(setOf(merged.reference()), history.frontier(unit).map { it.version }.toSet())
            // An unseen old branch is concurrent, never made newer by an mtime or a pair receipt.
            val oldActor = LanSyncTlsIdentity.create()
            val stale = merged.copy(id = SyncStoreId.random(), author = oldActor.fingerprint, parents = setOf(base.reference()),
                changes = mapOf(unit to change.copy(previous = setOf(base.reference()))), signature = "unsigned").let {
                it.copy(signature = Base64.getEncoder().encodeToString(oldActor.signSyncGroupPayload(it.signingBytes())))
            }
            assertEquals(SyncVersionState.ADMITTED, history.offer(stale))
            assertEquals(setOf(merged.reference(), stale.reference()), history.frontier(unit).map { it.version }.toSet())
        }
    }

    @Test fun keepingTheSameLocalHashStillResolvesWhileLaterNeverAdvancesAnotherUnit() = withFixture { f ->
        val a = "character_cards/a"; val b = "character_cards/b"
        f.card("a", "base a"); f.card("b", "base b"); val first = f.publish(setOf(a, b)); val applied = f.apply(f.plan(emptyMap()))
        f.card("a", "remote a"); f.card("b", "remote b"); f.publish(setOf(a, b), parents = setOf(first.reference()))
        Inputs(f, applied).use { input ->
            val preview = input.prepare(mapOf(a to keep(local), b to LanSyncGroupResolutionChoice.Later))
            assertEquals(input.capture.snapshot.hashes(), preview.target.hashes())
            assertEquals(setOf(a), preview.resolvedUnits); assertEquals(setOf(b), preview.heldUnits)
            assertEquals(applied, preview.appliedBefore); assertEquals(setOf(a), preview.previous.keys)
            assertEquals(applied, input.applied)
            val later = input.prepare(emptyMap())
            assertTrue(later.resolvedUnits.isEmpty()); assertEquals(setOf(a, b), later.heldUnits)
            assertEquals(input.capture.snapshot, later.target)
        }
    }

    @Test fun aRemoteDeletionNeedsItsFrozenConfirmationAndNoUnsignedEmptyTombstoneIsInvented() = withFixture { f ->
        val unit = "character_cards/a"; f.card("a", "original"); f.publish(setOf(unit)); val applied = f.apply(f.plan(emptyMap()))
        val deletion = f.publish(emptySet(), setOf(unit))
        Inputs(f, applied).use { input ->
            val preview = input.prepare(mapOf(unit to keep(version(deletion))))
            assertTrue(preview.target.records.isEmpty()); assertEquals(setOf(unit), preview.resolvedUnits)
            assertEquals(setOf(unit), preview.requiredBindings.keys)
            assertFailsWith<IllegalArgumentException> { preview.acknowledge(preview.binding) }
            assertFailsWith<IllegalArgumentException> { preview.acknowledge("0".repeat(64), preview.requiredBindings) }
            preview.acknowledge(preview.binding, preview.requiredBindings)
            assertEquals(applied, input.applied)
            assertFailsWith<IllegalArgumentException> { input.prepare(mapOf("character_cards/never-existed" to keep(local))) }
        }
    }

    @Test fun missingBodiesManifestsAndResourceHandlesCannotBecomeAcknowledgedCandidates() = withFixture { f ->
        val unit = "character_cards/a"; f.card("a", "original"); val first = f.publish(setOf(unit)); val applied = f.apply(f.plan(emptyMap()))
        f.card("a", "remote"); val next = f.publish(setOf(unit))
        Inputs(f, applied).use { input ->
            val choices = mapOf(unit to keep(version(next)))
            assertEquals("SYNC_GROUP_RESOLUTION_BODY_MISSING", assertFailsWith<IllegalArgumentException> {
                input.prepare(choices, bodies = input.dependencies - LanSyncGroupMembershipRequest(first.reference(), unit))
            }.message)
            val empty = LanSyncGroupRecordEvidence(requireNotNull(input.view.history), requireNotNull(input.view.ledgerCheckpoint))
            assertEquals("SYNC_GROUP_RESOLUTION_MEMBERSHIP_MISSING", assertFailsWith<IllegalArgumentException> { input.prepare(choices, manifests = empty) }.message)
            val request = LanSyncGroupMembershipRequest(next.reference(), unit)
            val body = requireNotNull(input.dependencies.getValue(request).available)
            val extra = body.resources + ("b".repeat(64) to f.target.resolve("not-an-admitted-resource"))
            val changed = LanSyncGroupAvailableUnit(body.version, body.checkpoint, body.decoded, extra)
            assertEquals("SYNC_GROUP_RESOLUTION_RESOURCE_MISSING", assertFailsWith<IllegalArgumentException> {
                input.prepare(choices, bodies = input.dependencies + (request to LanSyncGroupDependencyResult(LanSyncGroupDependencyStage.UNIT_CONTENT_AVAILABLE, changed)))
            }.message)
        }
    }

    @Test fun laterOnARelatedComponentCannotBeCombinedWithAnIsolatedKeep() = withFixture { f ->
        val format = FormatCard("format", "synthetic format", "synthetic fixture content", createdAt = 1)
        Files.createDirectories(f.source.data.resolve("entities/format_cards"))
        LanSyncJsonFiles.write(f.source.data.resolve("entities/format_cards/format.json"), FormatCard.serializer(), format)
        Files.createDirectories(f.source.data.resolve("entities/chat_sessions"))
        LanSyncJsonFiles.write(f.source.data.resolve("entities/chat_sessions/session.json"), ChatSession.serializer(),
            ChatSession("session", "card", "session", formatCardId = "format", createdAt = 1, updatedAt = 1))
        val units = setOf("format_cards/format", "conversation/session"); val remote = f.publish(units)
        Inputs(f, emptyMap()).use { input ->
            assertEquals("SYNC_GROUP_RESOLUTION_COMPONENT", assertFailsWith<IllegalArgumentException> {
                input.prepare(mapOf("conversation/session" to keep(version(remote)), "format_cards/format" to LanSyncGroupResolutionChoice.Later))
            }.message)
            val held = input.prepare(units.associateWith { LanSyncGroupResolutionChoice.Later })
            assertTrue(held.resolvedUnits.isEmpty()); assertTrue(held.target.records.isEmpty())
        }
    }

    @Test fun singletonCopyingAndHashOnlyVersionSubstitutionAreRejected() = withFixture { f ->
        Files.createDirectories(f.source.data.resolve("entities"))
        LanSyncJsonFiles.write(f.source.data.resolve("entities/player_setting.json"), PlayerSetting.serializer(), PlayerSetting(playerName = "synthetic", updatedAt = 1))
        val unit = "player_setting/singleton"; val remote = f.publish(setOf(unit)); val applied = f.apply(f.plan(emptyMap()))
        Inputs(f, applied).use { input ->
            assertEquals("SYNC_GROUP_RESOLUTION_SINGLETON_OR_EMPTY_COPY", assertFailsWith<IllegalArgumentException> {
                input.prepare(mapOf(unit to keep(local, mapOf("phone" to version(remote)))))
            }.message)
            val substituted = LanSyncGroupResolutionSource.Version(remote.reference().copy(bodyHash = "f".repeat(64)))
            assertEquals("SYNC_GROUP_RESOLUTION_NOT_A_FRONTIER", assertFailsWith<IllegalArgumentException> { input.prepare(mapOf(unit to keep(substituted))) }.message)
        }
    }

    @Test fun previewFreezesChoicesAndRejectsLaterSourceFilesHeadsCheckpointOrReceiptChanges() = withFixture { f ->
        val unit = "character_cards/a"; f.card("a", "original"); val first = f.publish(setOf(unit)); val applied = f.apply(f.plan(emptyMap()))
        f.card("a", "remote"); val next = f.publish(setOf(unit))
        Inputs(f, applied).use { input ->
            val copies = linkedMapOf<String, LanSyncGroupResolutionSource>("phone" to version(next))
            val preview = input.prepare(mapOf(unit to keep(local, copies)))
            val original = preview.target.records.map { it.body.toString() }; copies.clear()
            assertEquals(1, (preview.choices.getValue(unit) as LanSyncGroupResolutionChoice.Keep).copies.size)
            preview.requireFresh(input.capture, input.view, applied)
            assertFailsWith<IllegalArgumentException> { preview.requireFresh(input.capture.copy(sourceFiles = emptyMap()), input.view, applied) }
            assertFailsWith<IllegalArgumentException> { preview.requireFresh(input.capture, input.view.copy(ledgerCheckpoint = first.reference()), applied) }
            assertFailsWith<IllegalArgumentException> { preview.requireFresh(input.capture, input.view, emptyMap()) }
            f.card("a", "later remote"); f.publish(setOf(unit))
            assertFailsWith<IllegalArgumentException> { preview.requireFresh(input.capture, f.view(), applied) }
            localCard(f, "a", "later local")
            f.workspace.capture(f.source.owner.fingerprint).use { fresh ->
                assertFailsWith<IllegalArgumentException> { preview.requireFresh(fresh, input.view, applied) }
            }
            assertEquals(original, preview.target.records.map { it.body.toString() })
            @Suppress("UNCHECKED_CAST")
            val mutable = preview.target.records as MutableList<LanSyncRecord>
            assertFailsWith<UnsupportedOperationException> { mutable.clear() }
        }
    }

    @Test fun distinctVersionCopiesHaveStableDisjointTypedIdentities() = withFixture { f ->
        val unit = "character_cards/a"; f.card("a", "base"); val base = f.publish(setOf(unit)); val applied = f.apply(f.plan(emptyMap()))
        f.card("a", "left"); val left = f.publish(setOf(unit), parents = setOf(base.reference()))
        f.card("a", "right"); val right = f.publish(setOf(unit), author = 1, parents = setOf(base.reference()))
        Inputs(f, applied).use { input ->
            val op = UUID.randomUUID().toString()
            val choices = mapOf(unit to keep(local, linkedMapOf("first" to version(left), "second" to version(right))))
            val preview = input.prepare(choices, op); val repeated = input.prepare(choices, op)
            assertEquals(preview.binding, repeated.binding); assertEquals(preview.target, repeated.target)
            assertEquals(3, preview.target.records.size); assertEquals(3, preview.target.records.map { it.id }.toSet().size)
            assertEquals(setOf("base", "left", "right"), preview.target.records.map { it.text("name") }.toSet())
            assertEquals(3, preview.resolvedUnits.size)
            preview.resolvedUnits.filter { it != unit }.forEach { assertTrue(preview.previous.getValue(it).isEmpty()) }
        }
    }

    @Test fun equalMediaIdsWithDifferentBytesAreCopiedWithoutOverwritingEitherResource() = withFixture { f ->
        val image = f.source.data.resolve("images/shared.png"); Files.createDirectories(image.parent)
        val original = LanSyncPreviewSample.image(); Files.write(image, original)
        val path = f.source.data.resolve("entities/character_cards/a.json"); Files.createDirectories(path.parent)
        LanSyncJsonFiles.write(path, CharacterCard.serializer(), CharacterCard("a", "synthetic", avatar = image.toString(), createdAt = 1, updatedAt = 1))
        val unit = "character_cards/a"; f.publish(setOf(unit)); val applied = f.apply(f.plan(emptyMap()))
        Files.write(image, original + byteArrayOf(0))
        val remote = f.publish(setOf(unit))
        Inputs(f, applied).use { input ->
            val old = input.capture.snapshot.records.single().text("avatar")!!.split('/')
            val incoming = requireNotNull(input.dependencies.getValue(LanSyncGroupMembershipRequest(remote.reference(), unit)).available)
            val next = incoming.decoded.payload.records.single().text("avatar")!!.split('/')
            assertEquals(old[2], next[2]); assertNotEquals(old[3], next[3])
            val preview = input.prepare(mapOf(unit to keep(local, mapOf("phone" to version(remote)))))
            val refs = preview.target.records.map { requireNotNull(it.text("avatar")).split('/') }
            assertEquals(2, refs.map { it[2] }.toSet().size); assertEquals(setOf(old[3], next[3]), refs.map { it[3] }.toSet())
            assertEquals(setOf(old[3], next[3]), preview.target.resources.keys)
            preview.resourceSources.forEach { (hash, resource) -> resource.paths.forEach { file ->
                assertEquals(hash, LanSyncResourceStore.hashFile(file, resource.identity.length))
            } }
            val request = LanSyncGroupMembershipRequest(remote.reference(), unit)
            val absent = incoming.resources.mapValues { f.target.resolve("absent-resource") }
            val unavailable = LanSyncGroupAvailableUnit(incoming.version, incoming.checkpoint, incoming.decoded, absent)
            assertEquals("SYNC_GROUP_RESOLUTION_RESOURCE_MISSING", assertFailsWith<IllegalArgumentException> {
                input.prepare(mapOf(unit to keep(version(remote))), bodies = input.dependencies +
                    (request to LanSyncGroupDependencyResult(LanSyncGroupDependencyStage.UNIT_CONTENT_AVAILABLE, unavailable)))
            }.message)
        }
    }

    @Test fun aDerivedCopyMediaIdCannotAliasAnIndependentExistingIdentityEvenForEqualBytes() = withFixture { f ->
        val image = f.source.data.resolve("images/shared.png"); Files.createDirectories(image.parent); Files.write(image, LanSyncPreviewSample.image())
        val path = f.source.data.resolve("entities/character_cards/a.json"); Files.createDirectories(path.parent)
        LanSyncJsonFiles.write(path, CharacterCard.serializer(), CharacterCard("a", "synthetic", avatar = image.toString(), createdAt = 1, updatedAt = 1))
        val unit = "character_cards/a"; val remote = f.publish(setOf(unit)); val applied = f.apply(f.plan(emptyMap()))
        Inputs(f, applied).use { input ->
            val op = UUID.randomUUID().toString(); val choices = mapOf(unit to keep(local, mapOf("phone" to version(remote))))
            val preview = input.prepare(choices, op)
            val copied = preview.media.single { it.branch.startsWith("copy-") }
            val original = input.capture.snapshot.records.single()
            val ref = requireNotNull(original.text("avatar")).split('/')
            val extra = original.copy(id = "b", body = JsonObject(original.body + mapOf("id" to JsonPrimitive("b"),
                "avatar" to JsonPrimitive("ccb-lan-v1/${ref[1]}/${copied.target}/${ref[3]}/${ref[4]}"))))
            // Pure synthetic capture only; no native file or approved source is manufactured.
            val capture = input.capture.copy(snapshot = input.capture.snapshot.copy(records = input.capture.snapshot.records + extra))
            val native = LanSyncGroupNativePlanner.prepare(capture, input.view, applied, input.dependencies, input.evidence)
            assertEquals("SYNC_GROUP_RESOLUTION_MEDIA_COLLISION", assertFailsWith<IllegalArgumentException> {
                LanSyncGroupResolution.prepare(native, capture, input.view, input.dependencies, input.evidence, choices, op)
            }.message)
        }
    }

    @Test fun anUnselectedConversationKeepsItsExactBodyDespiteRecordIterationOrder() = withFixture { f ->
        f.card("a", "remote independent card")
        val sessions = f.target.resolve("entities/chat_sessions"); val messages = f.target.resolve("entities/chat_messages")
        Files.createDirectories(sessions); Files.createDirectories(messages)
        LanSyncJsonFiles.write(sessions.resolve("s.json"), ChatSession.serializer(), ChatSession("s", "missing-archived-card", "local", createdAt = 1, updatedAt = 1))
        LanSyncJsonFiles.write(messages.resolve("s_m.json"), ChatMessage.serializer(), ChatMessage("m", "s", MessageRole.USER, "local text", createdAt = 1, updatedAt = 1))
        val unit = "character_cards/a"; val remote = f.publish(setOf(unit))
        Inputs(f, emptyMap()).use { input ->
            val preview = input.prepare(mapOf(unit to keep(version(remote))))
            assertEquals(setOf(unit), preview.resolvedUnits)
            assertEquals(input.capture.snapshot.records.associateBy { it.key }, preview.target.records.filter { it.unit == "conversation/s" }.associateBy { it.key })
            assertEquals(setOf("conversation/s"), preview.heldUnits)
        }
    }

    @Test fun keepingAConversationGraphRetainsMessageVersionsMemoryVoicesAndImmutableV8Shells() = runBlocking {
        withFixture { f -> runBlocking {
            val storage = JsonFileStorage(f.source.data); val chats = ChatRepository(storage); val memory = MemoryRepository(storage, strictReads = true)
            f.card("card", "synthetic card")
            chats.createSession(ChatSession("session", "card", "synthetic conversation", createdAt = 1, updatedAt = 1))
            val image = f.source.data.resolve("images/shared.png"); Files.createDirectories(image.parent); Files.write(image, LanSyncPreviewSample.image())
            chats.addMessage(ChatMessage("message", "session", MessageRole.ASSISTANT, "original", images = listOf(image.toString()),
                alternatives = listOf("older", "original"), alternativeVersionIds = listOf("v0", "v1"), currentAlternativeIndex = 1,
                currentAlternativeVersionId = "v1", sourceTurnId = "turn", sourceTurnOrder = 1, createdAt = 1, updatedAt = 1, orderKey = 12345))
            memory.saveNodes(listOf(MemoryNode("node", "session", MemoryTier.EPISODE, sourceTurnIds = listOf("turn"), content = "memory", createdAt = 1)))
            memory.saveState(MemorySessionState("session", episodePage = MemoryPageState(MemoryTier.EPISODE, activeNodeIds = listOf("node"))))
            storage.saveEntity("voice_anchor_states", UUID.nameUUIDFromBytes("message\u0000v1".toByteArray()).toString(),
                VoiceAnchorState("message", "v1", "session", "original", listOf(VoiceAnchor("anchor", "DIALOGUE", "synthetic", "original", 0, 8, 1)), updatedAt = 1), VoiceAnchorState.serializer())
            val packages = SaveSlotPackageStorage(f.source.data.toFile(), { _, _ -> error("original image only") })
            val slot = packages.createPackage(SaveSlot(id = "slot", sessionId = "session", name = "synthetic archive", createdAt = 1),
                SaveSlotImagePolicy.ORIGINAL, false, { emit -> chats.getMessages("session").forEach { emit(it) } }, {}, emptyList())
            storage.saveEntity("save_slots", slot.id, slot, SaveSlot.serializer())
            val conversation = "conversation/session"; val card = "character_cards/card"
            f.publish(setOf(conversation, card)); val applied = f.apply(f.plan(emptyMap()))
            val oldMessage = chats.getMessages("session").single()
            storage.saveEntity("chat_messages", "session_message", oldMessage.copy(content = "remote", alternatives = listOf("older", "remote")), ChatMessage.serializer())
            val remote = f.publish(setOf(conversation))
            val packagePath = f.source.data.resolve("save_slot_packages/${requireNotNull(slot.packageRef).fileName}")
            val originalPackage = Files.readAllBytes(packagePath)
            Inputs(f, applied).use { input ->
                val cardFact = requireNotNull(input.view.history).frontier(card).single().version
                val choices = mapOf(conversation to keep(local, mapOf("phone" to version(remote))),
                    card to keep(local, mapOf("phone" to LanSyncGroupResolutionSource.Version(cardFact))))
                val op = UUID.randomUUID().toString(); val preview = input.prepare(choices, op)
                val again = input.prepare(choices, op); assertEquals(preview.target, again.target)
                LanSyncScope.validateGraph(preview.target)
                val fork = preview.target.records.single { it.type == "chat_sessions" && it.id != "session" }
                val forkCard = preview.target.records.single { it.type == "character_cards" && it.id != "card" }
                assertEquals(forkCard.id, fork.text("characterCardId")); assertEquals("session", preview.forkParents[fork.id])
                assertEquals("session", fork.body.getValue("lanSyncOrigin").jsonObject.getValue("parentSessionId").jsonPrimitive.content)
                val message = preview.target.records.single { it.type == "chat_messages" && it.text("sessionId") == fork.id }
                val decoded = lanSyncJson.decodeFromJsonElement(ChatMessage.serializer(), message.body)
                assertTrue(message.id.length <= 128); assertNotEquals("message", message.id)
                assertEquals(listOf("v0", "v1"), decoded.alternativeVersionIds); assertEquals("turn", decoded.sourceTurnId)
                val node = preview.target.records.single { it.type == "memory_nodes" && it.text("sessionId") == fork.id }
                val memoryState = preview.target.records.single { it.type == "memory_states" && it.text("sessionId") == fork.id }
                assertEquals(node.id, memoryState.body.getValue("episodePage").jsonObject.getValue("activeNodeIds").jsonArray.single().jsonPrimitive.content)
                val anchor = preview.target.records.single { it.type == "voice_anchor_states" && it.text("sessionId") == fork.id }
                assertEquals(message.id, anchor.text("messageId")); assertEquals("v1", anchor.text("messageVersionId"))
                val copiedSlot = preview.target.records.single { it.type == "save_slots" && it.text("sessionId") == fork.id }
                val sourceSlot = preview.units.getValue(conversation).versions.getValue(remote.reference()).single { it.type == "save_slots" }
                assertNotEquals(sourceSlot.id, copiedSlot.id)
                val oldRef = sourceSlot.body.getValue("packageRef").jsonObject; val newRef = copiedSlot.body.getValue("packageRef").jsonObject
                assertEquals(oldRef.filterKeys { it != "fileName" }, newRef.filterKeys { it != "fileName" })
                val oldWire = oldRef.getValue("fileName").jsonPrimitive.content.split('/'); val newWire = newRef.getValue("fileName").jsonPrimitive.content.split('/')
                assertEquals(listOf(oldWire[1], oldWire[3], oldWire[4]), listOf(newWire[1], newWire[3], newWire[4]))
                assertContentEquals(originalPackage, Files.readAllBytes(packagePath))
                assertTrue(preview.resourceSources.isNotEmpty()); assertTrue(preview.media.any { it.source != it.target })
                assertEquals(preview.target.resources.keys, preview.resourceSources.keys)
            }
        } }
    }

    @Test fun crossRecordMovesRequireExactRemovalAcknowledgementAndCannotUseAllHeadsAsConsent() = withFixture { f ->
        fun task(id: String) = MomentTask(id, "card", "session", 1, postId = "post", createdAt = 1, updatedAt = 1)
        val tasks = f.source.data.resolve("entities/moment_tasks"); val posts = f.source.data.resolve("entities/moment_posts")
        Files.createDirectories(tasks); Files.createDirectories(posts)
        LanSyncJsonFiles.write(tasks.resolve("old.json"), MomentTask.serializer(), task("old"))
        LanSyncJsonFiles.write(posts.resolve("post.json"), MomentPost.serializer(), MomentPost("post", "card", "session", senderName = "synthetic", text = "retained post", scheduledAt = 1, generatedAt = 1))
        val old = "moment-task/old"; val moved = "moment-task/new"
        val first = f.publish(setOf(old)); val applied = f.apply(f.plan(emptyMap()))
        val deletion = f.publish(emptySet(), setOf(old), author = 1, parents = setOf(first.reference()))
        Files.delete(tasks.resolve("old.json")); LanSyncJsonFiles.write(tasks.resolve("new.json"), MomentTask.serializer(), task("new"))
        val movement = f.publish(setOf(moved), setOf(old), parents = setOf(first.reference()))
        Inputs(f, applied).use { input ->
            val preview = input.prepare(mapOf(old to keep(version(deletion)), moved to keep(version(movement))))
            assertTrue(preview.requiredRestorations.any { it.record == "moment_posts/post" && it.formerUnit == old && it.removal == deletion.reference() })
            assertFailsWith<IllegalArgumentException> { preview.acknowledge(preview.binding, preview.requiredBindings) }
            preview.acknowledge(preview.binding, preview.requiredBindings, preview.requiredRestorations)
            assertEquals(setOf(moved), preview.target.units().keys)
            assertEquals(applied, preview.appliedBefore)
        }
    }
}
