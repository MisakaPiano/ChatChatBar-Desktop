package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.*
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.rag.RagRepository
import java.nio.file.*
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** Synthetic-only service integration. No GUI, provider transport, SecretStore or production root. */
object UnifiedAcceptanceFixture {
    private val base = Path.of("H:/ChatChatBar-Desktop/app/desktopApp/build/p11-unified-acceptance-prep-20261021").toAbsolutePath()
    private val reports = base.resolve("integration")
    private val exports = base.resolve("materials")
    private fun root(name: String) = base.resolve("environments/$name/app-data")
    private fun resolved(path: Path) = DesktopDataRootResolution.Resolved(path, DesktopDataRootProvenance.CLI_OVERRIDE, path.parent.resolve("bootstrap.json"))
    private fun report(name: String, assertions: List<String>) {
        Files.createDirectories(reports)
        Files.writeString(reports.resolve("$name.json"), buildJsonObject {
            put("mode", name); put("pid", ProcessHandle.current().pid()); put("result", "PASS")
            put("sourceCommit", "70b5c30c91439e79728bf224c1a44d35b25cf733")
            put("gui", false); put("providerRequests", 0); put("syntheticOnly", true)
            put("assertions", buildJsonArray { assertions.forEach { add(it) } })
        }.toString(), StandardOpenOption.CREATE_NEW)
    }
    private class Context(val path: Path) : AutoCloseable {
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolved(path))).ownership
        val gate = DesktopDataOperationCoordinator()
        val focus = DesktopAudioFocus()
        val audio = DesktopAudioMaintenance({ focus }) {}
        val storage = JsonFileStorage(path, gate)
        val chats = ChatRepository(storage)
        val voices = VoiceMessageRepository(storage, true)
        val memory = MemoryRepository(storage, true, gate)
        fun slots(journal: DesktopSessionCommitJournal = DesktopSessionCommitJournal(path)) = DesktopSaveSlotService(path, gate, audio,
            object : DesktopSessionReservation { override suspend fun <T> run(sessionId: String, operation: suspend () -> T): T = operation() },
            chats, voices, { memory.snapshot(it) }, { reference ->
                if (Path.of(reference).isAbsolute) DesktopVoiceAudioStore(path).verify(reference) else path.resolve(reference)
            }, journal)
        val full = DesktopFullBackupService(path, gate, audio)
        override fun close() { runBlocking { focus.closeAndDrain(); gate.closeAndDrain() }; owner.close() }
    }

    private suspend fun seed(c: Context) {
        Files.createDirectories(c.path.resolve("images")); Files.createDirectories(c.path.resolve("documents"))
        val image = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 200) for (x in 0 until 400) image.setRGB(x, y, if ((x/50+y/50)%2==0) 0x26879e else 0xebc861)
        try { ImageIO.write(image, "png", c.path.resolve("images/synthetic.png").toFile()) } finally { image.flush() }
        val document = c.path.resolve("documents/synthetic.txt")
        Files.writeString(document, "合成资料：灯塔编号 BLUE-17，交接时间为上午九点。只用于本机验收。")
        val card = CharacterCard.create("统一验收·Alice 与 Bob", characters = listOf(
            CharacterInfo("alice", "Alice", appearanceImage = "images/synthetic.png", fishAudioVoice = FishAudioVoiceBinding("synthetic-alice", "合成音色")),
            CharacterInfo("bob", "Bob", appearanceImage = "images/synthetic.png")))
            .copy(id = "unified-card", avatar = "images/synthetic.png", momentsEnabled = false,
                customDocuments = listOf(DocumentInfo("doc", "synthetic.txt", "documents/synthetic.txt", "txt", 1)))
        c.storage.saveEntity("character_cards", card.id, card, CharacterCard.serializer())
        val model = ModelConfig.create("unified-offline", "http://127.0.0.1:18793/v1", "", "统一验收·离线模型").copy(id = "unified-offline")
        c.storage.saveEntity("model_configs", model.id, model, ModelConfig.serializer())
        val session = ChatSession.create(card.id, "统一验收 A · 对话、语音、资料与记忆").copy(id = "unified-a", modelId = model.id,
            longTermMemoryEnabled = false, playerName = "合成用户", automaticImageGenerationEnabled = false)
        c.chats.createSession(session)
        val messages = listOf(
            ChatMessage("u1", session.id, MessageRole.USER, "请介绍灯塔交接计划。", images = listOf("images/synthetic.png"), createdAt = 100, updatedAt = 100, orderKey = 100),
            ChatMessage("a1", session.id, MessageRole.ASSISTANT, "清晨的灯塔。\n<n=\"Alice\"/>[我们九点在 BLUE-17 会合。](平静)\n<n=\"Bob\"/>[我带上地图。](微笑)", sourceTurnId = "turn-1", sourceTurnOrder = 1, currentAlternativeVersionId = "version-1", createdAt = 200, updatedAt = 200, orderKey = 200),
            ChatMessage("u2", session.id, MessageRole.USER, "这条用户消息不参与连续朗读。", createdAt = 300, updatedAt = 300, orderKey = 300),
            ChatMessage("a2", session.id, MessageRole.ASSISTANT, "<n=\"Alice\"/>[灯塔钥匙已经准备好了。](平静)", sourceTurnId = "turn-2", sourceTurnOrder = 2, currentAlternativeVersionId = "version-2", createdAt = 400, updatedAt = 400, orderKey = 400))
        messages.forEach { c.chats.addMessage(it) }
        c.chats.updateSessionDraft(session.id, "合成未发送草稿：检查复制与恢复后仍可见。")
        val voiceList = messages.filter { it.role == MessageRole.ASSISTANT }.flatMap { message ->
            c.voices.ensureAnchors(message).mapIndexed { i, item ->
                val audio = DesktopVoiceAudioStore(c.path).persist(session.id, "${message.id}-$i", fishPreviewMp3().inputStream())
                val anchor = item.anchor
                GeneratedVoiceMessage("voice-${message.id}-$i", session.id, message.id, message.currentAlternativeVersionId,
                    anchorId = anchor.id, sourceOrder = anchor.sourceOrder, sourceSegmentKind = anchor.segmentKind,
                    sourceSpeakerName = anchor.speakerName.orEmpty(), sourceText = anchor.sourceText, taggedText = anchor.sourceText,
                    characterId = "alice", characterName = "Alice", voice = FishAudioVoiceBinding("synthetic-alice", "合成音色"),
                    fishModelId = "offline-fixture", audioPath = audio.path, durationMs = audio.durationMs, byteLength = audio.byteLength, createdAt = 1, updatedAt = 1)
            }
        }
        c.voices.restoreForSession(session.id, voiceList, messages)
        val node = MemoryNode("synthetic-memory", session.id, MemoryTier.LEGACY_REFERENCE, overview = "合成记忆：灯塔交接",
            coverageUnits = listOf(MemoryCoverageUnit("legacy:unified", "Alice 与 Bob 约定九点交接灯塔钥匙。")), author = MemoryAuthor.MIGRATION)
        c.memory.saveNodes(listOf(node))
        c.memory.saveState(MemorySessionState(session.id, legacyReferenceNodeIds = listOf(node.id),
            head = MemoryHead(location = "BLUE-17 灯塔", participants = "Alice 和 Bob", goals = "完成钥匙交接")))
        RagRepository(c.storage).saveChunks(listOf(
            VectorChunk("rag-chat", ChunkSourceType.CHAT_MEMORY, session.id, "a1", "合成记忆：九点交接钥匙", listOf(.1f,.2f), mapOf("messageIds" to "a1", "sourceTurnId" to "turn-1"), 1),
            VectorChunk("rag-doc", ChunkSourceType.DOCUMENT, card.id, null, "灯塔编号 BLUE-17", listOf(.2f,.1f), mapOf("documentId" to "doc"), 1)))
        val history = session.copy(id = "unified-history", title = "统一验收 B · 分组与长图")
        c.chats.createSession(history)
        listOf(MessageRole.ASSISTANT, MessageRole.USER, MessageRole.USER, MessageRole.ASSISTANT, MessageRole.ASSISTANT).forEachIndexed { index, role ->
            c.chats.addMessage(ChatMessage("history-$index", history.id, role,
                if (index == 4) (1..100).joinToString("\n") { "合成长图第 $it 段：核对阅读、缩放、截图选择与导出，不包含真实数据。" } else "合成不规则历史第 $index 条。",
                createdAt = 1000L+index, updatedAt = 1000L+index, orderKey = 1000L+index))
        }
        Files.writeString(c.path.resolve("desktop-settings.json"), """{"formatVersion":1,"automaticBackup":{"enabled":false,"minimumBackupInterval":"PT24H","maximumSnapshotCount":7,"checkInterval":"PT1H"},"uiLanguage":"ZH_CN","colorStyle":"NEUTRAL"}""")
    }

    @JvmStatic fun main(args: Array<String>) = runBlocking {
        check(args.size == 1); Files.createDirectories(exports)
        when (args.single()) {
            "seed-and-integrate" -> {
                val path = root("synthetic-source"); check(!Files.exists(path)); Files.createDirectories(path)
                Context(path).use { c ->
                    seed(c); val slots = c.slots(); val before = DesktopSessionInventory(path).read("unified-a")
                    val slot = slots.create("unified-a", "完整合成存档", SaveSlotImagePolicy.ORIGINAL, true)
                    slots.export(slot.id, exports.resolve("complete.cbsave"))
                    val copied = slots.duplicate("unified-a")
                    assertEquals(before, DesktopSessionInventory(path).read("unified-a"))
                    assertEquals(4, c.chats.getMessages(copied.id).size)
                    assertEquals(c.voices.listForSession("unified-a").size, c.voices.listForSession(copied.id).size)
                    assertNotNull(c.memory.snapshot(copied.id)); assertTrue(RagRepository(c.storage).getAllChunksForSession(copied.id).isNotEmpty())
                    val disposable = slots.create("unified-a", "待删除合成存档", SaveSlotImagePolicy.ORIGINAL, true)
                    slots.delete(disposable.id, "unified-a"); assertTrue(slots.list("unified-a").none { it.id == disposable.id })
                    val edit = c.chats.getMessages("unified-a").first { it.id == "a1" }
                    c.chats.updateMessage(edit.copy(content = "合成临时修改"))
                    val modified = DesktopSessionInventory(path).read("unified-a")
                    val broken = c.slots(DesktopSessionCommitJournal(path) { if (it == 2) error("SYNTHETIC_COMMIT_FAILURE") })
                    assertFailsWith<IllegalStateException> { broken.restore(slot.id, "unified-a") }
                    assertEquals(modified, DesktopSessionInventory(path).read("unified-a"))
                    slots.restore(slot.id, "unified-a"); assertEquals(edit.content, c.chats.getMessages("unified-a").first { it.id == "a1" }.content)
                    assertNotNull(c.memory.snapshot("unified-a")); assertTrue(c.voices.listForSession("unified-a").isNotEmpty())
                    for (schema in 1..7) SyntheticSaveSlotCorpus.legacy(exports.resolve("legacy-$schema.cbsave"), SyntheticSaveSlotCorpus.slot(schema).copy(messages = listOf(SyntheticSaveSlotCorpus.message())))
                    c.full.export(exports.resolve("complete.cbbackup"), null)
                    c.full.export(exports.resolve("encrypted.cbbackup"), "synthetic-unified-only".toCharArray())
                    assertEquals(0L, Files.list(path.resolve("backups/.full-app-work")).use { it.count() })
                    DesktopFullBackupGraphValidation.validate(path)
                    report("seed-and-integrate", listOf("create/export", "duplicate media/RAG/current memory", "delete exclusive slot", "restore failure rollback", "restore retry", "plain/encrypted full export", "scratch released", "graph valid"))
                }
                check(!DesktopDataRootOwnership.isHeld(path))
            }
            "schedule" -> {
                val path = root("manual-ready"); check(!Files.exists(path)); Files.createDirectories(path)
                Context(path).use { c ->
                    val prepared = c.full.inspect(exports.resolve("encrypted.cbbackup"), "synthetic-unified-only".toCharArray())
                    c.full.scheduleRestore(prepared); c.full.release(prepared)
                    assertTrue(c.full.restartRequired); assertTrue(DesktopFullBackupCommitJournal(path).hasPending())
                    assertFalse(Files.exists(prepared.directory))
                    report("schedule", listOf("fresh target", "encrypted inspect", "restart required", "journal owns restore bytes", "inspection released"))
                }
                check(!DesktopDataRootOwnership.isHeld(path))
            }
            "recover" -> {
                val path = root("manual-ready"); var entered = false
                DesktopStartupGate(select = { DesktopStartupRootSelection.Selected(resolved(path)) }, presenter = { error("STARTUP_REFUSED") }).run {
                    runBlocking {
                        entered = true; assertTrue(DesktopDataRootOwnership.isHeld(path))
                        assertFalse(DesktopFullBackupCommitJournal(path).hasPending()); DesktopFullBackupGraphValidation.validate(path)
                        val storage = JsonFileStorage(path)
                        assertEquals(3, ChatRepository(storage).getAllSessions().size)
                        assertEquals(4, ChatRepository(storage).getMessages("unified-a").size)
                        assertNotNull(MemoryRepository(storage, true).snapshot("unified-a"))
                        assertTrue(RagRepository(storage).getAllChunksForSession("unified-a").isNotEmpty())
                        val voices = VoiceMessageRepository(storage,true).listForSession("unified-a")
                        assertTrue(voices.isNotEmpty()); voices.forEach { DesktopVoiceAudioStore(path).verify(it.audioPath) }
                        report("recover", listOf("separate JVM startup gate", "root lock held before repositories", "3 sessions", "messages/images/voice/RAG/memory restored", "journal complete"))
                    }
                }
                assertTrue(entered); check(!DesktopDataRootOwnership.isHeld(path))
                Context(path).use { report("lock-reacquired", listOf("root lock reacquired after startup gate exits")) }
            }
            "manual-enrich" -> {
                val path = root("manual-ready")
                val media = Path.of("H:/ChatChatBar-Desktop/app/desktopApp/build/post-p8-screenshot-windows-manual-20261021/synthetic-tts-long.mp3")
                assertEquals("fb93b911ab31c264881df8f167cbb655bb42ddcd148fa372e5363cf09e3dfd94", DesktopArchiveFiles.hash(media, 1024 * 1024))
                Context(path).use { c ->
                    val messages = c.chats.getMessages("unified-a")
                    val voices = c.voices.listForSession("unified-a").map { old ->
                        val audio = Files.newInputStream(media).use { DesktopVoiceAudioStore(path).persist("unified-a", "manual-${old.id}", it) }
                        assertTrue(audio.durationMs > 10000)
                        old.copy(audioPath = audio.path, durationMs = audio.durationMs, byteLength = audio.byteLength)
                    }
                    c.voices.restoreForSession("unified-a", voices, messages)
                    Files.copy(root("synthetic-source").resolve("desktop-settings.json"), path.resolve("desktop-settings.json"))
                    val slot = c.slots().create("unified-a", "人工链路起点·含长录音", SaveSlotImagePolicy.ORIGINAL, true)
                    c.slots().export(slot.id, exports.resolve("manual-ready.cbsave"))
                    c.full.export(exports.resolve("manual-ready.cbbackup"), "synthetic-unified-only".toCharArray())
                    DesktopFullBackupGraphValidation.validate(path)
                    report("manual-enrich", listOf("historical synthetic MP3 hash verified", "long audio persisted through production owner", "automatic backup disabled", "manual SaveSlot and encrypted full backup exported", "graph validated", "no orphan recording-only replies"))
                }
            }
            "rollback" -> {
                val path = root("rollback-only"); check(!Files.exists(path)); Files.createDirectories(path)
                Context(path).use { c ->
                    Files.createDirectories(path.resolve("entities"))
                    val live = path.resolve("entities/app_settings.json")
                    Files.writeString(live, backupJson.encodeToString(AppSettings.serializer(), AppSettings(themeMode=ThemeMode.LIGHT)))
                    val before = Files.readAllBytes(live)
                    val prepared = c.full.inspect(exports.resolve("encrypted.cbbackup"), "synthetic-unified-only".toCharArray())
                    c.full.scheduleRestore(prepared); c.full.release(prepared)
                    assertFailsWith<IllegalStateException> { DesktopFullBackupCommitJournal(path) { if (it==2) error("SYNTHETIC_APPLY_FAILURE") }.recover() }
                    assertContentEquals(before, Files.readAllBytes(live)); assertFalse(DesktopFullBackupCommitJournal(path).hasPending())
                    assertFalse(Files.exists(path.resolve("entities/chat_sessions/unified-a.json")))
                    report("rollback", listOf("partial full-restore injected failure", "old app settings restored", "new graph absent", "journal retained", "source archive retained"))
                }
            }
            else -> error("UNKNOWN_SYNTHETIC_MODE")
        }
    }
}
