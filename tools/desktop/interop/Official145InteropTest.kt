package com.example.chatbar.domain.backup

import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.ui.chat.ChatViewModel
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString

/** Run only in a newly created synthetic emulator, against unmodified official 1198a13c main sources.
 * Uses the actual Android ViewModel/import/load/send/export path; never reads bundled seed content.
 */
class Official145InteropTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val output get() = File(context.getExternalFilesDir(null), "p11-interop").also { it.mkdirs() }
    private suspend fun until(check: suspend () -> Boolean) = withTimeout(60_000) {
        while (!check()) delay(100)
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test fun officialBackupCorpusAndDesktopReturnValidation() = runBlocking {
        check(android.os.Build.MODEL.contains("sdk")) { "SYNTHETIC_EMULATOR_ONLY" }
        val source = File(context.cacheDir, "official-backup-corpus-${System.nanoTime()}").also { it.mkdirs() }
        fun write(path: String, value: String) { File(source,path).also { it.parentFile!!.mkdirs(); it.writeText(value) } }
        write("entities/app_settings.json", backupJson.encodeToString(AppSettings()))
        write("entities/chat_drafts/p11.json", backupJson.encodeToString(ChatDraft("p11", "synthetic portable draft")))
        fun archive(name: String, preferences: BackupPreferences = BackupPreferences(), password: CharArray? = null) {
            File(output,name).outputStream().use { out ->
                BackupArchive({}, { _,_,_ -> }, { error("EXTERNAL_RESOURCE_FORBIDDEN") }).create(source,
                    File(context.cacheDir,"work-$name"), out, password,84,"1.4.5",preferences)
            }
        }
        archive("official-supported.cbbackup")
        archive("official-encrypted.cbbackup", password="synthetic-interop-only".toCharArray())
        archive("official-credential.cbbackup", BackupPreferences(fishAudioKey="synthetic-never-real"))
        write("entities/app_settings.json", "{\"futureField\":\"synthetic-preserve-me\"}")
        archive("official-unknown-field.cbbackup")
        write("entities/app_settings.json", backupJson.encodeToString(AppSettings()))
        write("entities/moment_posts/p11.json", backupJson.encodeToString(MomentPost("p11","card","session",senderName="Synthetic",text="Synthetic post",scheduledAt=1,generatedAt=1)))
        archive("official-moment-post.cbbackup")
        // Each unsupported store gets its own independently produced archive.
        check(File(source,"entities/moment_posts/p11.json").delete())
        write("entities/moment_tasks/p11.json", backupJson.encodeToString(MomentTask("p11","card","session",1)))
        archive("official-moment-task.cbbackup")
        val target=File(context.cacheDir,"desktop-return-target").also { it.mkdirs() }
        val stage=File(context.cacheDir,"desktop-return-stage").also { it.mkdirs() }
        instrumentation.context.assets.open("desktop-supported.cbbackup").use { input ->
            BackupArchive({}, { _,_,_ -> }, { error("EXTERNAL_RESOURCE_FORBIDDEN") }).extract(input,null,stage,target,84)
        }
        BackupEntityValidator.validateTree(context,File(stage,"files")) {}
        File(output,"official-backup-result.txt").writeText("OFFICIAL_ANDROID_BACKUP_CREATE_AND_DESKTOP_RETURN_VALIDATION_PASS\ncredentialsSyntheticOnly=true\n")
    }

    @Test fun desktopArchiveThroughOfficialAndroidAppAndBack() = runBlocking {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) {
            "SYNTHETIC_EMULATOR_ONLY"
        }
        val app = ChatBarApp.instance
        until { app.dependenciesReady.value }
        app.initializePersistentState().join()
        val card = CharacterCard.create("P11 synthetic interop", greeting = "")
        app.characterRepository.save(card)
        val session = ChatSession.create(card.id, "P11 isolated roundtrip").copy(
            id = "p11-android-roundtrip-${System.nanoTime()}", longTermMemoryEnabled = false, automaticImageGenerationEnabled = false)
        app.chatRepository.createSession(session)
        app.settingsRepository.updateAppSettings { it.copy(allowCleartextModelApi = true, ragInjectionMode = "OFF") }
        app.chatRepository.updateSessionDraft(session.id, "android-local-draft")
        val vm = withContext(Dispatchers.Main) { ChatViewModel(session.id) }
        until { vm.session.value != null }
        val imported = instrumentation.context.assets.open("desktop.cbsave").use { vm.importSaveSlotJson(it) }
        val summary = requireNotNull(app.saveSlotRepository.getSummaryById(imported.id))
        withContext(Dispatchers.Main) { vm.loadSaveSlot(summary) }
        until { !vm.saveSlotOperation.value.busy && app.chatRepository.getMessages(session.id).size >= 4 }
        val before = app.chatRepository.getMessages(session.id)
        assertEquals(4, before.size)
        assertEquals(listOf("version-1", "version-unused"), before.single { it.id == "a1" }.alternativeVersionIds)
        assertEquals("synthetic unused alternative", before.single { it.id == "a1" }.alternatives[1])
        val sourceIds = before.map { it.id }.toSet()
        assertTrue(before.filter { it.role == MessageRole.ASSISTANT }.all { it.currentAlternativeVersionId != null })
        val images = before.flatMap { it.images }.map(::File)
        assertTrue(images.isNotEmpty() && images.all { it.isFile && it.length() > 0 })
        val voices = app.voiceMessageRepository.listForSession(session.id)
        assertEquals(4, voices.size)
        assertTrue(voices.all { it.messageId in sourceIds && File(it.audioPath).isFile && it.durationMs > 10_000 })
        assertTrue(app.ragRepository.getAllChunksForSession(session.id).isNotEmpty())
        assertNotNull(app.longTermMemoryService.snapshot(session.id))
        // Official SaveSlot has no draft field: a local destination draft is not a portable source draft.
        val draftAfterLoad = app.chatRepository.getSessionDraft(session.id)
        File(output, "scope.txt").writeText("official=1198a13cf3008de1358b8a69aa5e40b64797c8d9\nphysicalDevice=NOT_TESTED\ndraftPortable=false\ndestinationDraftRetained=${draftAfterLoad == "android-local-draft"}\n")
        File(output, "media-before.txt").writeText(images.map { "image ${hash(it)}" }.plus(voices.map { "audio ${hash(File(it.audioPath))}" }).joinToString("\n"))
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 45_000
            val reply = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.soTimeout = 20_000
                    val input = socket.getInputStream().buffered()
                    fun line(): String {
                        val bytes = java.io.ByteArrayOutputStream()
                        while (true) { val value = input.read(); check(value >= 0); if (value == 10) break; if(value != 13) bytes.write(value) }
                        return bytes.toString("UTF-8")
                    }
                    check(line().startsWith("POST /v1/chat/completions "))
                    var length = 0
                    while (true) { val header = line(); if(header.isEmpty()) break
                        if(header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                        check(!header.startsWith("Authorization:", true))
                    }
                    check(length in 1..1_048_576)
                    repeat(length) { check(input.read() >= 0) }
                    val body = "data: {\"choices\":[{\"delta\":{\"content\":\"Android synthetic continued reply\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n"+body).toByteArray())
                }
            }
            val model = ModelConfig.create("p11-offline", "http://127.0.0.1:${server.localPort}/v1", "", "P11 offline").copy(id="unified-offline")
            app.modelRepository.saveModel(model)
            val restored = requireNotNull(app.chatRepository.getSession(session.id))
            app.chatRepository.updateSession(restored.copy(modelId=model.id, longTermMemoryEnabled=false))
            withContext(Dispatchers.Main) { vm.loadSessionData(); vm.refreshConfigurations() }
            until { vm.session.value?.modelId == model.id && vm.isModelUsable.value && !vm.isArchived.value }
            assertTrue(withContext(Dispatchers.Main) { vm.sendMessage("Android synthetic continuation") })
            reply.await()
            until { app.chatRepository.getMessages(session.id).any { it.content.contains("Android synthetic continued reply") } }
        }
        withContext(Dispatchers.Main) { vm.createSaveSlot("Android returned", null, SaveSlotImagePolicy.ORIGINAL, true) }
        until { !vm.saveSlotOperation.value.busy && app.saveSlotRepository.getBySessionId(session.id).any { it.name == "Android returned" } }
        val returned = app.saveSlotRepository.getBySessionId(session.id).single { it.name == "Android returned" }
        File(output, "android-return.cbsave").outputStream().use { vm.exportSaveSlotJson(returned.id, it) }
        assertTrue(app.chatRepository.getMessages(session.id).map { it.id }.containsAll(sourceIds))
        File(output, "android-result.txt").writeText("OFFICIAL_ANDROID_IMPORT_LOAD_CONTINUE_EXPORT_PASS\nmessages=${app.chatRepository.getMessages(session.id).size}\nvoices=${voices.size}\n")
    }
}
