package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.desktop.*
import com.example.chatbar.domain.voice.VoiceAnchorPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlin.test.*

class DesktopMergeOmittedVoiceRetentionTest {
    private fun <T> bytes(serializer: KSerializer<T>, value: T) = backupJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
    private fun own(root: Path, base: Path) = (DesktopDataRootOwnership.acquire(DesktopDataRootResolution.Resolved(root,
        DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json"))) as DesktopDataRootOwnershipResult.Acquired).ownership

    @Test fun `actual merge proves original voice versions before live retention and roundtrips ambiguous sources inertly`() = runBlocking<Unit> {
        data class Case(val sourceVersion: String?, val voiceVersion: String?, val targetVersion: String?,
            val targetContent: String, val targetId: String = "m", val live: Boolean)
        val oldText = "Alice：『synthetic old line』"
        val newText = "Alice：『synthetic replacement line』"
        val cases = listOf(Case("pc", "pc", "pc", oldText, live = true),
            Case("pc", "pc", "phone", newText, live = false),
            Case("pc", "pc", "pc", newText, live = false),
            Case("pc-current", "pruned", "pruned", newText, live = false),
            Case("pc", "pc", "phone", newText, targetId = "new-message", live = true),
            Case(null, null, null, newText, live = false), Case(null, null, null, oldText, live = true))
        for (case in cases) {
            val base = Files.createTempDirectory(Files.createDirectories(Path.of("build/preview-synthetic/omitted-voice").toAbsolutePath()), "case-")
            try {
                val pcRoot = Files.createDirectory(base.resolve("pc"))
                val audioBytes = fishPreviewMp3()
                val audio = DesktopVoiceAudioStore(pcRoot).persist("s", "artifact", audioBytes.inputStream(), audioBytes.size.toLong())
                val card = bytes(CharacterCard.serializer(), CharacterCard("c", "Synthetic", createdAt = 1, updatedAt = 1))
                val session = bytes(ChatSession.serializer(), ChatSession("s", "c", "Synthetic", createdAt = 1, updatedAt = 1))
                val message = ChatMessage("m", "s", MessageRole.ASSISTANT, oldText,
                    currentAlternativeVersionId = case.sourceVersion, createdAt = 1, updatedAt = 1)
                val anchors = VoiceAnchorPolicy.initialState("m", oldText, case.voiceVersion, "s", includeNarration = true)
                val anchor = anchors.anchors.single()
                val voice = GeneratedVoiceMessage.create("s", "m", case.voiceVersion, anchor.id, 0, anchor.segmentKind,
                    anchor.speakerName.orEmpty(), anchor.sourceText, anchor.sourceText, anchor.sourceText,
                    "c", "Synthetic", FishAudioVoiceBinding("synthetic", "Synthetic"), "s2.1-pro-free",
                    audio.path, audio.durationMs, audio.byteLength).copy(id = "voice")
                val anchorId = case.voiceVersion?.let { UUID.nameUUIDFromBytes("m\u0000$it".toByteArray(Charsets.UTF_8)).toString() } ?: "m"
                val original = mapOf("entities/character_cards/c.json" to card, "entities/chat_sessions/s.json" to session,
                    "entities/chat_messages/s_m.json" to bytes(ChatMessage.serializer(), message),
                    "entities/generated_voice_messages/voice.json" to bytes(GeneratedVoiceMessage.serializer(), voice),
                    "entities/voice_anchor_states/$anchorId.json" to bytes(VoiceAnchorState.serializer(), anchors))
                original.forEach { (path, raw) -> val file = pcRoot.resolve(path); Files.createDirectories(file.parent); Files.write(file, raw) }
                val target = ChatMessage(case.targetId, "s", MessageRole.ASSISTANT, case.targetContent,
                    currentAlternativeVersionId = case.targetVersion, createdAt = 1, updatedAt = 2)
                val incoming = mapOf("entities/character_cards/c.json" to card, "entities/chat_sessions/s.json" to session,
                    "entities/chat_messages/s_${case.targetId}.json" to bytes(ChatMessage.serializer(), target))
                val scope = OfficialWireScope(emptySet(), 1, 1, 1, 0, 1, false, false, false, false,
                    sessionDefaultChoice = OfficialWireSessionChoice(voice = false))
                val source = Files.write(base.resolve("synthetic.cbbackup"), CensusWireFixtures.bytes(prefs = "{}", files = incoming,
                    code = 88, version = 2, scope = scope))
                val sourceBytes = Files.readAllBytes(source)
                own(pcRoot, base).use {
                    DesktopFullBackupMapping(pcRoot).export(Files.createDirectory(base.resolve("pc-work")), 88,
                        StrictBackupZipReader.Limits().expanded, forMerge = true).use { pc ->
                        DesktopFullBackupStaging.prepare(source, null, Files.createDirectory(base.resolve("phone-work"))).use { phone ->
                            val preview = DesktopMergePreview.create(DesktopMergeRootPreparation.computerEntities(pc), phone.entities,
                                pc.proof.filterKeys { !it.startsWith("entities/") }, phone.source.inventory.filterKeys { !it.startsWith("entities/") }.mapValues { it.value.sha256 },
                                phone.source.manifest.scope)
                            val selected = preview.select(preview.conflicts.associate { it.key to DesktopMergePreview.Choice.TAKE_PHONE })
                            assertEquals(if (case.live) 0 else 2, selected.computerVoiceSources.size, case.toString())
                            val captured = listOf("entities/generated_voice_messages/voice.json", "entities/chat_messages/s_m.json",
                                "entities/voice_anchor_states/$anchorId.json").map { Files.readAllBytes(pc.files.getValue(it)) } + listOf(audioBytes)
                            val root = Files.createDirectory(base.resolve("merged"))
                            val authority = DesktopFreshRootAuthority(root, Files.createDirectory(base.resolve("credentials")), MergeRootSyntheticProtector)
                            own(root, base).use {
                                val pending = authority.begin()
                                DesktopMergeRootPreparation.prepare(root, authority, pending, pc, phone, selected,
                                    Files.createDirectory(base.resolve("prepare-work"))).use { prepared ->
                                    authority.complete(pending, prepared.files); authority.verifyAndResolve()
                                    val storage = JsonFileStorage(root)
                                    val messages = ChatRepository(storage).getMessages("s")
                                    assertEquals(case.targetContent, messages.single { it.id == case.targetId }.content)
                                    val voices = VoiceMessageRepository(storage, strictReads = true)
                                    assertEquals(if (case.live) 1 else 0, voices.listForSession("s").size, case.toString())
                                    if (case.live) {
                                        val owner = messages.single { it.id == "m" }
                                        val kept = voices.listForCurrentVersion(owner).single()
                                        assertEquals(voice.sourceText, kept.sourceText)
                                        assertContentEquals(audioBytes, Files.readAllBytes(Path.of(kept.audioPath)))
                                    } else {
                                        assertTrue(voices.listForCurrentVersion(messages.single()).isEmpty())
                                        val custody = root.resolve(DesktopHistoricalVoiceCustody.ROOT)
                                        val originals = Files.walk(custody).use { paths -> paths.filter { Files.isRegularFile(it) }.map { Files.readAllBytes(it) }.toList() }
                                        captured.forEach { raw -> assertTrue(originals.any { it.contentEquals(raw) }, "snapshot original missing") }
                                        DesktopHistoricalVoiceCustody.validate(root)
                                        DesktopFullBackupMapping(root).export(Files.createDirectory(base.resolve("export-work")), 88,
                                            StrictBackupZipReader.Limits().expanded).use { exported ->
                                            val output = base.resolve("roundtrip.cbbackup")
                                            DesktopFullBackupWriter.export(exported.files, output, null, exported.preferences)
                                            DesktopFullBackupStaging.prepare(output, null, Files.createDirectory(base.resolve("roundtrip-work"))).use { returned ->
                                                assertTrue(returned.files.keys.any { it.startsWith(DesktopHistoricalVoiceCustody.ROOT + "/") })
                                                val restored = Files.createDirectory(base.resolve("restored"))
                                                DesktopFullBackupMapping(restored).restoreMerged(returned.files,
                                                    Files.createDirectory(base.resolve("restore-work")), BackupPreferences()).use { mapped ->
                                                    DesktopHistoricalVoiceCustody.validate(mapped.directory)
                                                    DesktopFullBackupGraphValidation.validate(mapped.directory, futureRoot = restored)
                                                    assertTrue(VoiceMessageRepository(JsonFileStorage(mapped.directory), strictReads = true).listForSession("s").isEmpty())
                                                }
                                            }
                                        }
                                        val record = Files.walk(custody).use { paths -> paths.filter { Files.isRegularFile(it) && it.parent.fileName.toString() == "records" }.findFirst().orElseThrow() }
                                        val before = Files.readAllBytes(record)
                                        Files.write(record, before.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
                                        assertFails { DesktopHistoricalVoiceCustody.validate(root) }
                                        Files.write(record, before); DesktopHistoricalVoiceCustody.validate(root)
                                    }
                                }
                            }
                        }
                    }
                }
                assertContentEquals(sourceBytes, Files.readAllBytes(source)); assertContentEquals(audioBytes, Files.readAllBytes(Path.of(audio.path)))
                original.forEach { (path, raw) -> assertContentEquals(raw, Files.readAllBytes(pcRoot.resolve(path))) }
            } finally { check(base.toFile().deleteRecursively()) }
        }
    }
}
