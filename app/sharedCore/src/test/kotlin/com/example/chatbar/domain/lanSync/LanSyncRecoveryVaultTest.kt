package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.test.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LanSyncRecoveryVaultTest {
    private fun row(id: String, name: String, avatar: String? = null) = LanSyncRecord("character_cards", id,
        lanSyncJson.encodeToJsonElement(CharacterCard.serializer(), CharacterCard(id, name, avatar = avatar, createdAt = 1, updatedAt = 1)).jsonObject)
    private fun write(root: Path, record: LanSyncRecord) {
        val file = root.resolve("entities/${record.type}/${record.id}.json")
        Files.createDirectories(file.parent); Files.write(file, record.body.toString().toByteArray(Charsets.UTF_8))
    }

    @Test fun actualOverwriteProtectsOriginalRawBytesAllDependentResourcesAndReopensAfterWalRetirement() {
        val root = Files.createTempDirectory("lan-recovery-data-").toRealPath()
        val state = Files.createTempDirectory("lan-recovery-state-").toRealPath()
        try {
            val image = root.resolve("images/old.png"); Files.createDirectories(image.parent); val imageBytes = byteArrayOf(1, 5, 9, 3)
            Files.write(image, imageBytes)
            write(root, row("card", "必须保全的旧正文", image.toString()))
            val original = Files.readAllBytes(root.resolve("entities/character_cards/card.json"))
            val workspace = LanSyncWorkspace(root, state, { Path.of(it) })
            val transaction = UUID.randomUUID().toString()
            val plan = LanSyncPlan(transaction, LanSyncSnapshot(deviceId = "pc", records = listOf(row("card", "选定的新正文"))), emptyMap())
            workspace.capture("pc").use { before -> workspace.apply(plan, before.snapshot.hashes(), "pc", emptyMap()) }
            workspace.retire(transaction)
            val reopened = LanSyncRecoveryVault(state.resolve("recovery"))
            val entry = requireNotNull(reopened.read(transaction)); reopened.verify(entry)
            assertEquals(1L, entry.recordCount); assertEquals(0L, entry.messageCount); assertEquals(1L, entry.affectedUnits)
            assertEquals("必须保全的旧正文", reopened.snapshot(entry).records.single().text("name"))
            var rawCount = 0
            reopened.originals(entry) { raw, file ->
                rawCount++; assertEquals("entities/character_cards/card.json", raw.relative)
                assertContentEquals(original, Files.readAllBytes(file))
            }
            assertEquals(1, rawCount)
            assertContentEquals(imageBytes, Files.readAllBytes(reopened.blobs(entry).values.single()))
            assertEquals(listOf(entry), reopened.list())
            assertEquals("选定的新正文", lanSyncJson.parseToJsonElement(Files.readString(root.resolve("entities/character_cards/card.json"))).jsonObject["name"]?.jsonPrimitive?.content)
        } finally { check(root.toFile().deleteRecursively()); check(state.toFile().deleteRecursively()) }
    }

    @Test fun aFailureBeforeRecoverySealPreventsTheDestructivePublicationAndCanRetryTheExactProtection() {
        val root = Files.createTempDirectory("lan-recovery-fault-data-").toRealPath()
        val state = Files.createTempDirectory("lan-recovery-fault-state-").toRealPath()
        try {
            write(root, row("card", "保全失败时必须不变"))
            val file = root.resolve("entities/character_cards/card.json"); val original = Files.readAllBytes(file)
            val vault = LanSyncRecoveryVault(state.resolve("recovery"), observe = { if (it == "BEFORE_SEAL") throw IOException("synthetic history storage failure") })
            val workspace = LanSyncWorkspace(root, state, { Path.of(it) }, recoveryVault = vault)
            val plan = LanSyncPlan(UUID.randomUUID().toString(), LanSyncSnapshot(deviceId = "pc", records = emptyList()), emptyMap())
            workspace.capture("pc").use { before ->
                assertFailsWith<IOException> { workspace.apply(plan, before.snapshot.hashes(), "pc", emptyMap()) }
                assertContentEquals(original, Files.readAllBytes(file))
                assertFalse(workspace.applied(plan.transaction)); assertTrue(vault.list().isEmpty())
                val healthy = LanSyncRecoveryVault(state.resolve("recovery"))
                val saved = requireNotNull(healthy.protect(root, before, plan))
                assertEquals(saved, healthy.protect(root, before, plan))
                healthy.verify(saved)
            }
        } finally { check(root.toFile().deleteRecursively()); check(state.toFile().deleteRecursively()) }
    }

    @Test fun concurrentVaultInstancesNeverRewriteASealedEntryEvenAfterItsLiveSourceWasDeleted() {
        val root = Files.createTempDirectory("lan-recovery-concurrent-data-").toRealPath()
        val state = Files.createTempDirectory("lan-recovery-concurrent-state-").toRealPath()
        val pool = Executors.newFixedThreadPool(2)
        try {
            write(root, row("card", "并发保全原文"))
            val workspace = LanSyncWorkspace(root, state, { Path.of(it) })
            val plan = LanSyncPlan(UUID.randomUUID().toString(), LanSyncSnapshot(deviceId = "pc", records = emptyList()), emptyMap())
            val beforeSeal = CountDownLatch(1); val secondWaiting = CountDownLatch(1)
            val first = LanSyncRecoveryVault(state.resolve("recovery"), observe = { phase ->
                if (phase == "BEFORE_SEAL") { beforeSeal.countDown(); check(secondWaiting.await(10, TimeUnit.SECONDS)) }
            })
            val second = LanSyncRecoveryVault(state.resolve("recovery"), observe = { phase ->
                if (phase == "ENTRY_WAITING") secondWaiting.countDown()
                check(phase != "RESOURCES_PROTECTED") { "A second writer must read the sealed entry, never enter its preparation" }
            })
            workspace.capture("pc").use { capture ->
                val a = pool.submit<LanSyncRecoveryEntry> {
                    requireNotNull(first.protect(root, capture, plan)).also { Files.delete(root.resolve("entities/character_cards/card.json")) }
                }
                check(beforeSeal.await(10, TimeUnit.SECONDS))
                val b = pool.submit<LanSyncRecoveryEntry> { requireNotNull(second.protect(root, capture, plan)) }
                val entry = a.get(20, TimeUnit.SECONDS)
                assertEquals(entry, b.get(20, TimeUnit.SECONDS)); second.verify(entry)
                assertEquals(listOf(entry), second.list())
                assertFalse(Files.exists(root.resolve("entities/character_cards/card.json")))
            }
        } finally {
            pool.shutdownNow(); check(pool.awaitTermination(20, TimeUnit.SECONDS))
            check(root.toFile().deleteRecursively()); check(state.toFile().deleteRecursively())
        }
    }
}
