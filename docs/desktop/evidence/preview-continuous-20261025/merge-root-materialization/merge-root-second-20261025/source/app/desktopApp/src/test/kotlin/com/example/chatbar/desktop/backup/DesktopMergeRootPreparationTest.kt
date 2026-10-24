package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.desktop.*
import com.example.chatbar.desktop.security.*
import java.nio.file.*
import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class DesktopMergeRootPreparationTest {
    private val fake = object : SecretProtector {
        override fun protect(plaintext: ByteArray) = byteArrayOf(61) + plaintext.map { (it.toInt() xor 93).toByte() }
        override fun unprotect(ciphertext: ByteArray) = ciphertext.also { check(it.first() == 61.toByte()) }.drop(1).map { (it.toInt() xor 93).toByte() }.toByteArray()
    }
    private fun own(root: Path, base: Path) = (DesktopDataRootOwnership.acquire(DesktopDataRootResolution.Resolved(root,
        DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json"))) as DesktopDataRootOwnershipResult.Acquired).ownership
    private fun <T> bytes(serializer: KSerializer<T>, value: T) = backupJson.encodeToJsonElement(serializer, value).toString().toByteArray(Charsets.UTF_8)
    private fun records(label: String, unique: String): Map<String, ByteArray> = linkedMapOf(
        "entities/character_cards/c.json" to bytes(CharacterCard.serializer(), CharacterCard("c", label, createdAt = 1, updatedAt = 1)),
        "entities/chat_sessions/s.json" to bytes(ChatSession.serializer(), ChatSession("s", "c", "$label branch", createdAt = 1, updatedAt = 1)),
        "entities/chat_messages/s_m.json" to bytes(ChatMessage.serializer(), ChatMessage("m", "s", MessageRole.USER, "$label content", currentAlternativeVersionId = "v", createdAt = 1, updatedAt = 1)),
        "entities/chat_sessions/$unique.json" to bytes(ChatSession.serializer(), ChatSession(unique, "c", "$label unique", createdAt = 1, updatedAt = 1)),
        "entities/chat_messages/${unique}_${unique}-message.json" to bytes(ChatMessage.serializer(), ChatMessage("$unique-message", unique, MessageRole.USER, "$label unique content", createdAt = 1, updatedAt = 1)))
    private suspend fun fixture(action: suspend (Path, Path, DesktopFullBackupMapping.Export, DesktopFullBackupStaging, DesktopMergePreview.Selection) -> Unit) {
        val base = Files.createTempDirectory(Files.createDirectories(Path.of("build/preview-synthetic/merge-root").toAbsolutePath()), "case-")
        val pcRoot = Files.createDirectory(base.resolve("pc")); val original = records("Computer", "pc-only")
        for ((name, value) in original) { val target = pcRoot.resolve(name); Files.createDirectories(target.parent); Files.write(target, value) }
        val pcWork = Files.createDirectory(base.resolve("pc-work")); val work = Files.createDirectory(base.resolve("work"))
        own(pcRoot, base).use {
            DesktopFullBackupMapping(pcRoot).export(pcWork, 86, StrictBackupZipReader.Limits().expanded).use { pc ->
                val source = Files.write(base.resolve("synthetic.cbbackup"), CensusWireFixtures.bytes(prefs = "{}", files = records("Phone", "phone-only")))
                val before = Files.readAllBytes(source)
                DesktopFullBackupStaging.prepare(source, null, work).use { phone ->
                    val pcEntities = DesktopMergeRootPreparation.computerEntities(pc)
                    val preview = DesktopMergePreview.create(pcEntities, phone.entities, emptyMap(), emptyMap())
                    val selected = preview.select(preview.conflicts.associate { it.key to DesktopMergePreview.Choice.BOTH })
                    action(base, pcRoot, pc, phone, selected)
                }
                assertContentEquals(before, Files.readAllBytes(source))
            }
        }
        for ((path, value) in original) assertContentEquals(value, Files.readAllBytes(pcRoot.resolve(path)))
    }

    @Test fun `actual prepared root contains both branches and can complete reopen and continue`() = runBlocking<Unit> {
        fixture { base, _, pc, phone, selected ->
            val root = Files.createDirectory(base.resolve("merged")); val credentials = Files.createDirectory(base.resolve("credentials"))
            val authority = DesktopFreshRootAuthority(root, credentials, fake)
            var namespace: Path? = null
            var incoming = ""
            own(root, base).use {
                val pending = authority.begin()
                val result = DesktopMergeRootPreparation.prepare(root, authority, pending, pc, phone, selected, base.resolve("work"))
                assertFails { authority.verifyAndResolve() }
                // No runtime repositories before the genesis proof is complete.
                incoming = result.plan.identity.value("chat_sessions", "s")
                assertTrue(Files.isRegularFile(root.resolve("entities/chat_sessions/$incoming.json")))
                pending.store.save(DesktopCredentialKey.NovelAiToken, "FAKE_NEW_ROOT_ONLY")
                assertEquals("FAKE_NEW_ROOT_ONLY", pending.store.load(DesktopCredentialKey.NovelAiToken))
                authority.complete(pending, result.files); namespace = pending.credentialRoot
            }
            own(root, base).use {
                assertEquals(namespace, DesktopFreshRootAuthority(root, credentials, fake).verifyAndResolve())
                DesktopFullBackupGraphValidation.validate(root)
                val chats = ChatRepository(JsonFileStorage(root))
                assertEquals(4, chats.getAllSessions().size)
                assertEquals("Computer content", chats.getMessages("s").single().content)
                assertEquals("Phone content", chats.getMessages(incoming).single().content)
                assertEquals("Computer unique content", chats.getMessages("pc-only").single().content)
                assertEquals("Phone unique content", chats.getMessages("phone-only").single().content)
                chats.addMessage(ChatMessage("continued", "pc-only", MessageRole.USER, "Synthetic continuation", createdAt = 2, updatedAt = 2))
                assertEquals(2, chats.getMessages("pc-only").size)
            }
        }
    }

    @Test fun `cancellation after real file writes leaves non runnable evidence and original sides intact`() = runBlocking<Unit> {
        fixture { base, _, pc, phone, selected ->
            val root = Files.createDirectory(base.resolve("cancelled")); val authority = DesktopFreshRootAuthority(root, Files.createDirectory(base.resolve("credentials")), fake)
            own(root, base).use {
                val pending = authority.begin(); var wrote = false
                assertFailsWith<CancellationException> { DesktopMergeRootPreparation.prepare(root, authority, pending, pc, phone, selected, base.resolve("work")) {
                    wrote = true; throw CancellationException("Synthetic cancel")
                } }
                assertTrue(wrote); assertTrue(Files.isDirectory(root.resolve("entities")))
                assertFails { authority.verifyAndResolve() }
            }
        }
    }

    @Test fun `changed preview snapshot and wrong target root refuse before publication`() = runBlocking<Unit> {
        fixture { base, _, pc, phone, selected ->
            val root = Files.createDirectory(base.resolve("merged")); val other = Files.createDirectory(base.resolve("other"))
            val authority = DesktopFreshRootAuthority(root, Files.createDirectory(base.resolve("credentials")), fake)
            own(root, base).use {
                val pending = authority.begin()
                assertEquals("FRESH_IMPORT_TARGET_ROOT_MISMATCH", assertFails { DesktopMergeRootPreparation.prepare(other, authority, pending, pc, phone, selected, base.resolve("work")) }.message)
                assertTrue(Files.list(other).use { it.findAny().isEmpty })
                val changed = pc.files.getValue("entities/chat_messages/s_m.json")
                Files.write(changed, bytes(ChatMessage.serializer(), ChatMessage("m", "s", MessageRole.USER, "Changed after preview", createdAt = 1, updatedAt = 1)))
                assertEquals("MERGE_COMPUTER_PREVIEW_CHANGED", assertFails { DesktopMergeRootPreparation.prepare(root, authority, pending, pc, phone, selected, base.resolve("work")) }.message)
                assertFalse(Files.exists(root.resolve("entities"))); assertFails { authority.verifyAndResolve() }
            }
        }
    }
}
