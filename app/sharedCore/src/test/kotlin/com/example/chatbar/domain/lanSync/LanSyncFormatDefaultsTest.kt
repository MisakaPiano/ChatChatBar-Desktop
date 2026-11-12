package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.repository.FormatCardRepository
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class LanSyncFormatDefaultsTest {
    @Test fun actualRepositoriesKeepDifferentLocalDefaultsAndAlignSharedPresetAcrossColdReopen() = runBlocking {
        val a = Files.createTempDirectory("lan-format-a-").toRealPath(); val b = Files.createTempDirectory("lan-format-b-").toRealPath()
        val repoA = FormatCardRepository(JsonFileStorage(a)); val repoB = FormatCardRepository(JsonFileStorage(b))
        val common = FormatCard("a-shared", "同源格式", "合成正文", isDefault = true,
            sourcePresetKey = "synthetic/format", sourcePresetVersion = 1, createdAt = 1)
        repoA.save(common)
        repoB.save(common.copy(id = "b-shared", isDefault = false, createdAt = 2))
        repoB.save(FormatCard("b-default", "另一默认格式", "另一合成正文", isDefault = true, createdAt = 1))
        val stateA = Files.createTempDirectory("lan-format-state-a-").toRealPath(); val stateB = Files.createTempDirectory("lan-format-state-b-").toRealPath()
        val wa = LanSyncWorkspace(a, stateA, { a.resolve(it) }); val wb = LanSyncWorkspace(b, stateB, { b.resolve(it) })
        wa.capture("computer").use { left -> wb.capture("phone").use { originalRight ->
            val projection = LanSyncPresetProjection.propose(left.snapshot.records, originalRight.snapshot.records)
            assertEquals(listOf(LanSyncIdBinding("format_cards", "b-shared", "a-shared")), projection.bindings)
            wb.capture("phone", projection).use { right ->
                val plan = LanSyncPlanner.plan(left.snapshot, right.snapshot, LanSyncBaseline("phone", ownerId = "computer"), emptyMap(), UUID.randomUUID().toString())
                wa.apply(plan, left.snapshot.hashes(), "computer", emptyMap())
                wb.apply(plan, right.snapshot.hashes(), "phone", emptyMap(), projection)
            }
            val coldA = FormatCardRepository(JsonFileStorage(a)); val coldB = FormatCardRepository(JsonFileStorage(b))
            assertEquals("a-shared", coldA.getDefault()?.id); assertEquals("b-default", coldB.getDefault()?.id)
            assertEquals(1, coldA.getAll().count { it.isDefault }); assertEquals(1, coldB.getAll().count { it.isDefault })
            assertFalse(requireNotNull(coldA.getById("b-default")).isDefault)
            assertFalse(requireNotNull(coldB.getById("b-shared")).isDefault)
            assertEquals(common.content, coldB.getById("b-shared")?.content)
            wa.capture("computer").use { afterA -> wb.capture("phone", projection).use { afterB ->
                assertEquals(afterA.snapshot.hashes(), afterB.snapshot.hashes())
            } }
        } }
    }
}
