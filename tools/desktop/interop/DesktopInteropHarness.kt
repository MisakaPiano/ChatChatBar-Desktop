package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.*
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.rag.RagRepository
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.*
import kotlin.test.*

/** Opt-in material runner; NOT automatically part of the normal test source set. */
class DesktopInteropHarnessTest {
    private val scope = Path.of(requireNotNull(System.getProperty("ccb.interop.scope"))).toAbsolutePath()
    private class Fixture(val root: Path) : AutoCloseable {
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(
            DesktopDataRootResolution.Resolved(root,DesktopDataRootProvenance.CLI_OVERRIDE,root.resolveSibling("bootstrap.json")))).ownership
        val gate=DesktopDataOperationCoordinator()
        val focus=DesktopAudioFocus()
        val storage=JsonFileStorage(root,gate)
        val chats=ChatRepository(storage)
        val voices=VoiceMessageRepository(storage,true)
        val memory=MemoryRepository(storage,true,gate)
        val slots=DesktopSaveSlotService(root,gate,DesktopAudioMaintenance({focus}) {},
            object: DesktopSessionReservation { override suspend fun <T> run(sessionId:String,operation:suspend()->T):T=operation() },
            chats,voices,{memory.snapshot(it)},{ref -> if(Path.of(ref).isAbsolute) DesktopVoiceAudioStore(root).verify(ref) else root.resolve(ref)})
        override fun close() { runBlocking { focus.closeAndDrain();gate.closeAndDrain() };owner.close() }
    }
    @Test fun produceDesktopMaterials(): Unit = runBlocking {
        val out=Files.createDirectory(scope.resolve("desktop-materials"))
        val root=Files.createDirectory(scope.resolve("desktop-source"))
        Fixture(root).use { f ->
            val session=ChatSession.create("synthetic-card","P11 source").copy(id="source",longTermMemoryEnabled=false)
            f.chats.createSession(session)
            val input=scope.resolve("seed.cbsave")
            val imported=f.slots.import(input,session.id)
            f.slots.restore(imported.id,session.id)
            val messages=f.chats.getMessages(session.id)
            assertEquals(4,messages.size);assertEquals(4,f.voices.listForSession(session.id).size)
            val versioned = messages.single { it.id == "a1" }
            f.chats.updateMessage(versioned.copy(alternatives=listOf(versioned.content, "synthetic unused alternative"),
                alternativeVersionIds=listOf("version-1", "version-unused"), currentAlternativeIndex=0,
                currentAlternativeVersionId="version-1"))
            f.chats.updateSessionDraft(session.id,"desktop-local-draft")
            val slot=f.slots.create(session.id,"Desktop interop",SaveSlotImagePolicy.ORIGINAL,true)
            f.slots.export(slot.id,out.resolve("desktop.cbsave"))
            assertEquals("desktop-local-draft",f.chats.getSessionDraft(session.id))
            assertNotNull(f.memory.snapshot(session.id));assertTrue(RagRepository(f.storage).getAllChunksForSession(session.id).isNotEmpty())
        }
        val work=Files.createDirectory(scope.resolve("backup-export-work"))
        val settings=scope.resolve("synthetic-settings.json")
        Files.writeString(settings,backupJson.encodeToString(AppSettings()))
        val draft=scope.resolve("synthetic-draft.json")
        Files.writeString(draft,backupJson.encodeToString(ChatDraft("p11","synthetic portable draft")))
        DesktopFullBackupArchive(work).export(mapOf("entities/app_settings.json" to settings,"entities/chat_drafts/p11.json" to draft),
            out.resolve("desktop-supported.cbbackup"),"synthetic-root",null)
    }

    @Test fun consumeOfficialAndroidMaterials() = runBlocking {
        val input=scope.resolve("android-materials")
        val root=Files.createDirectory(scope.resolve("desktop-return"))
        Fixture(root).use { f ->
            val session=ChatSession.create("synthetic-card","P11 return").copy(id="returned",longTermMemoryEnabled=false)
            f.chats.createSession(session)
            val archive=input.resolve("android-return.cbsave")
            val original=Files.readAllBytes(archive)
            val imported=f.slots.import(archive,session.id)
            f.slots.restore(imported.id,session.id)
            val messages=f.chats.getMessages(session.id)
            assertEquals(6,messages.size)
            assertEquals(listOf("version-1", "version-unused"), messages.single { it.id == "a1" }.alternativeVersionIds)
            assertEquals("synthetic unused alternative", messages.single { it.id == "a1" }.alternatives[1])
            assertTrue(messages.any { it.content.contains("Android synthetic continued reply") })
            assertEquals(4,f.voices.listForSession(session.id).size)
            f.voices.listForSession(session.id).forEach { DesktopVoiceAudioStore(root).verify(it.audioPath) }
            fun mediaHash(path: Path) = java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
            val localMedia = messages.flatMap { it.images }.map { "image ${mediaHash(root.resolve(it))}" } +
                f.voices.listForSession(session.id).map { "audio ${mediaHash(Path.of(it.audioPath))}" }
            assertEquals(Files.readAllLines(input.resolve("media-before.txt")).filter { it.isNotBlank() }.sorted(), localMedia.sorted())
            assertNotNull(f.memory.snapshot(session.id));assertTrue(RagRepository(f.storage).getAllChunksForSession(session.id).isNotEmpty())
            assertContentEquals(original,Files.readAllBytes(archive))
        }
        val container = DesktopAppContainer(resolvedRoot=DesktopDataRootResolution.Resolved(root,DesktopDataRootProvenance.CLI_OVERRIDE,scope.resolve("return-bootstrap.json")),
            secretStoreFactory={InMemoryDesktopSecretStore()})
        try {
            MockWebServer().use { server ->
                val card=CharacterCard.create("P11 synthetic card",greeting="").copy(id="synthetic-card")
                container.characterRepository.save(card)
                val model=ModelConfig.create("p11-offline",server.url("/v1").toString().trimEnd('/'),"","P11 offline")
                container.modelRepository.saveModel(model)
                container.settingsRepository.saveAppSettings(AppSettings(allowCleartextModelApi=true,ragInjectionMode="OFF"))
                val session=requireNotNull(container.chatRepository.getSession("returned"))
                container.chatRepository.updateSession(session.copy(modelId=model.id,longTermMemoryEnabled=false))
                server.enqueue(MockResponse().addHeader("Content-Type","text/event-stream").setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Desktop synthetic continued reply\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
                container.createRealChatRuntime().sendText("returned","Desktop synthetic continuation")
                assertNull(assertNotNull(server.takeRequest(10,java.util.concurrent.TimeUnit.SECONDS)).getHeader("Authorization"))
                assertTrue(container.chatRepository.getMessages("returned").any { it.content.contains("Desktop synthetic continued reply") })
            }
        } finally { container.close() }
        val work=Files.createDirectory(scope.resolve("official-inspect-work"))
        val archive=DesktopFullBackupArchive(work)
        for(name in listOf("official-supported.cbbackup","official-encrypted.cbbackup")) {
            val path=input.resolve(name);val before=Files.readAllBytes(path)
            archive.inspect(path,if(name.contains("encrypted")) "synthetic-interop-only".toCharArray() else null).use {
                assertEquals(2,it.files.size);archive.verify(it)
            }
            assertContentEquals(before,Files.readAllBytes(path))
        }
        for((name,kind) in listOf("official-credential.cbbackup" to DesktopBackupCompatibility.CREDENTIAL_APPROVAL_REQUIRED,
            "official-unknown-field.cbbackup" to DesktopBackupCompatibility.ENTITY_SCHEMA_UNSUPPORTED,
            "official-moment-post.cbbackup" to DesktopBackupCompatibility.MOMENTS_UNSUPPORTED,
            "official-moment-task.cbbackup" to DesktopBackupCompatibility.MOMENTS_UNSUPPORTED)) {
            val path=input.resolve(name);val before=Files.readAllBytes(path)
            assertEquals(kind,assertFailsWith<DesktopBackupCompatibilityException> { archive.inspect(path,null) }.kind)
            assertContentEquals(before,Files.readAllBytes(path))
            assertEquals(0,Files.list(work).use { it.count() }.toInt())
        }
    }
}
