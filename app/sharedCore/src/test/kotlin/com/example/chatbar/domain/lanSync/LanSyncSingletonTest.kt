package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.PlayerSetting
import com.example.chatbar.data.repository.SettingsRepository
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncSingletonTest {
    private fun root() = Files.createTempDirectory("lan-singleton-").toRealPath()
    private fun workspace(root: Path, state: Path) = LanSyncWorkspace(root, state, { raw ->
        Path.of(raw).let { if (it.isAbsolute) it else root.resolve(it) }
    })
    @Test fun playerProfileAndAvatarUseOriginalSingletonPathAndRefreshWithoutChangingDeviceCredentials() = runBlocking {
        val a = root(); val b = root(); val sa = root(); val sb = root()
        val left = workspace(a, sa); val right = workspace(b, sb)
        val source = SettingsRepository(JsonFileStorage(a)); val target = SettingsRepository(JsonFileStorage(b))
        source.initialize(); target.initialize()
        target.updateAppSettings { it.copy(momentsEnabled = true, novelAiToken = "synthetic-local-only-not-a-key") }
        val localSettings = b.resolve("entities/app_settings.json"); val localBytes = Files.readAllBytes(localSettings)
        val avatar = a.resolve("images/avatar.png"); Files.createDirectories(avatar.parent)
        val bytes = LanSyncPreviewSample.image(); Files.write(avatar, bytes)
        source.savePlayerSetting(PlayerSetting(playerName = "合成玩家", globalPersona = "保留用户正文\n第二行", desktopAvatar = avatar.toString()))
        left.capture("computer").use { capture -> right.capture("phone").use { before ->
            val record = capture.snapshot.records.single { it.type == "player_setting" }
            assertEquals("singleton", record.id)
            assertTrue(record.singleton)
            right.apply(LanSyncPlan(UUID.randomUUID().toString(), capture.snapshot, emptyMap()), before.snapshot.hashes(), "phone", capture.blobs)
            target.refreshPlayerSettingFromStorage()
            val imported = target.playerSetting.first()
            assertEquals("合成玩家", imported.playerName)
            assertEquals("保留用户正文\n第二行", imported.globalPersona)
            assertEquals("default", imported.id)
            assertContentEquals(bytes, Files.readAllBytes(Path.of(requireNotNull(imported.desktopAvatar))))
            assertContentEquals(localBytes, Files.readAllBytes(localSettings))
            assertFalse(Files.exists(b.resolve("entities/player_setting")))
            assertTrue(Files.isRegularFile(b.resolve("entities/player_setting.json")))
            val reopened = workspace(b, sb)
            reopened.recover()
            reopened.capture("phone").use { assertEquals(capture.snapshot.hashes(), it.snapshot.hashes()) }
        } }
        val deleted = left.capture("computer").use { it.snapshot.copy(records = emptyList(), resources = emptyMap()) }
        right.capture("phone").use { before -> right.apply(LanSyncPlan(UUID.randomUUID().toString(), deleted, emptyMap()), before.snapshot.hashes(), "phone", emptyMap()) }
        assertFalse(Files.exists(b.resolve("entities/player_setting.json")))
        target.refreshPlayerSettingFromStorage()
        assertEquals("", target.playerSetting.first().playerName)
        assertFalse(Files.exists(b.resolve("entities/player_setting.json")))
        assertContentEquals(localBytes, Files.readAllBytes(localSettings))
    }

    @Test fun singletonConflictsRequireOneVersionAndNeverForkIntoTheSamePhysicalFile() {
        fun snapshot(device: String, name: String): LanSyncSnapshot {
            val player = PlayerSetting(playerName = name, updatedAt = 1)
            val body = lanSyncJson.encodeToJsonElement(PlayerSetting.serializer(), player).jsonObject
            return LanSyncSnapshot(deviceId = device, records = listOf(LanSyncRecord("player_setting", "singleton", body)))
        }
        val pc = snapshot("computer", "电脑玩家"); val phone = snapshot("phone", "手机玩家")
        val baseline = LanSyncBaseline("phone", ownerId = "computer")
        val conflict = LanSyncCompare.compare(pc, phone, baseline).conflicts.single()
        assertFalse(conflict.canKeepBoth)
        assertEquals("SYNC_SINGLETON_KEEP_BOTH", assertFailsWith<IllegalArgumentException> {
            LanSyncPlanner.plan(pc, phone, baseline, mapOf(conflict.unit to LanSyncChoice.KEEP_BOTH), "synthetic")
        }.message)
        val selected = LanSyncPlanner.plan(pc, phone, baseline, mapOf(conflict.unit to LanSyncChoice.KEEP_PHONE), "synthetic")
        assertEquals("手机玩家", selected.snapshot.records.single().body["playerName"]?.jsonPrimitive?.content)
        assertEquals("default", selected.snapshot.records.single().body["id"]?.jsonPrimitive?.content)
        assertEquals("entities/player_setting.json", LanSyncScope.relativePath(selected.snapshot.records.single()))
    }

    @Test fun malformedKnownSingletonShapeAndCaseAliasesRefuseWithoutTouchingBytes() {
        for (name in listOf("player_setting", "PLAYER_SETTING.json", "character_cards.json")) {
            val data = root(); val state = root(); val entities = Files.createDirectory(data.resolve("entities"))
            val wrong = entities.resolve(name)
            if (name == "player_setting") Files.createDirectory(wrong) else Files.writeString(wrong, "synthetic-original")
            assertEquals("SYNC_STORAGE_SHAPE", assertFailsWith<IllegalArgumentException> { workspace(data, state).capture("synthetic") }.message)
            if (name != "player_setting") assertEquals("synthetic-original", Files.readString(wrong))
        }
    }
}
