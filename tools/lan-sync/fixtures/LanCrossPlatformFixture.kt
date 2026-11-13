package com.example.chatbar.lanSync.fixture

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.lanSync.*
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

/** Test-source only on both platforms. This is a bounded synthetic exchange, never an app import API. */
object LanCrossPlatformFixture {
    const val CARD = "cross-platform-card"
    const val SESSION = "cross-platform-session"
    const val MESSAGE = "cross-platform-message"
    const val POST = "cross-platform-post"
    const val HISTORY = "cross-platform-history"
    const val WINDOWS_MODEL = "synthetic-windows-local-model"
    const val ANDROID_MODEL = "synthetic-android-local-model"
    val vibe = "synthetic-cross-platform-vibe-".repeat(240)
    val populatedTypes = setOf("character_cards", "chat_sessions", "chat_messages", "moment_posts",
        "novelai_generation_history", "player_setting", LanSyncPostProcess.TYPE)
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private const val MAX = 8 * 1024 * 1024

    data class Packet(val snapshot: LanSyncSnapshot, val blobs: Map<String, Path>, val contract: JsonObject)

    fun fileHash(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun write(path: Path, source: String, stage: String, capture: LanSyncCapture, facts: JsonObject, parent: String? = null) {
        require(source.matches(Regex("[a-f0-9]{40}")) && stage in setOf("windows", "android"))
        require(!Files.exists(path) && capture.snapshot.coveredTypes == LanSyncScope.schemas.keys)
        require(capture.snapshot.records.size == 7 && capture.snapshot.records.map { it.type }.toSet() == populatedTypes && capture.snapshot.resources.size == 3)
        val snapshot = json.encodeToJsonElement(LanSyncSnapshot.serializer(), capture.snapshot).toString().toByteArray(Charsets.UTF_8)
        val contract = buildJsonObject {
            put("schema", "ccb-lan-cross-platform-fixture-v1"); put("sourceSha", source); put("stage", stage)
            put("protocol", LAN_SYNC_PROTOCOL); put("snapshotSha256", lanSyncHash(snapshot))
            put("coveredTypes", JsonArray(capture.snapshot.coveredTypes.sorted().map(::JsonPrimitive)))
            put("populatedTypes", JsonArray(populatedTypes.sorted().map(::JsonPrimitive)))
            put("emptyTypes", JsonArray((capture.snapshot.coveredTypes - populatedTypes).sorted().map(::JsonPrimitive)))
            put("facts", facts); parent?.let { put("parentArchiveSha256", it) }
        }
        Files.createDirectories(path.parent)
        ZipOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                require(bytes.size in 1..MAX)
                zip.putNextEntry(ZipEntry(name).apply { time = 0 }); zip.write(bytes); zip.closeEntry()
            }
            entry("contract.json", contract.toString().toByteArray(Charsets.UTF_8)); entry("snapshot.json", snapshot)
            for ((hash, size) in capture.snapshot.resources.toSortedMap()) {
                val blob = requireNotNull(capture.blobs[hash]); require(size in 1..MAX.toLong() && Files.size(blob) == size && fileHash(blob) == hash)
                entry("blobs/$hash", Files.readAllBytes(blob))
            }
        }
        require(Files.size(path) in 1..MAX.toLong())
    }

    fun read(path: Path, extraction: Path, source: String, stage: String): Packet {
        require(Files.size(path) in 1..MAX.toLong() && Files.isDirectory(extraction))
        val raw = linkedMapOf<String, ByteArray>()
        ZipFile(path.toFile()).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size in 3..32 && entries.map { it.name.lowercase(java.util.Locale.ROOT) }.toSet().size == entries.size)
            var total = 0L
            entries.forEach { entry ->
                require(!entry.isDirectory && (entry.name in setOf("contract.json", "snapshot.json") || entry.name.matches(Regex("blobs/[a-f0-9]{64}"))))
                require(entry.size in 1..MAX.toLong()); total += entry.size; require(total <= MAX)
                val bytes = zip.getInputStream(entry).use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        require(output.size().toLong() + count <= entry.size); output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                require(bytes.size.toLong() == entry.size && CRC32().apply { update(bytes) }.value == entry.crc)
                raw[entry.name] = bytes
            }
        }
        fun text(bytes: ByteArray) = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val contract = json.parseToJsonElement(text(requireNotNull(raw["contract.json"]))).jsonObject
        require(contract.getValue("schema").jsonPrimitive.content == "ccb-lan-cross-platform-fixture-v1")
        require(contract.getValue("sourceSha").jsonPrimitive.content == source && source.matches(Regex("[a-f0-9]{40}")))
        require(contract.getValue("stage").jsonPrimitive.content == stage && contract.getValue("protocol").jsonPrimitive.int == LAN_SYNC_PROTOCOL)
        val bytes = requireNotNull(raw["snapshot.json"]); require(lanSyncHash(bytes) == contract.getValue("snapshotSha256").jsonPrimitive.content)
        val snapshot = json.decodeFromJsonElement(LanSyncSnapshot.serializer(), json.parseToJsonElement(text(bytes)))
        snapshot.validate(); LanSyncScope.validateGraph(snapshot)
        require(snapshot.coveredTypes == LanSyncScope.schemas.keys && snapshot.records.map { it.type }.toSet() == populatedTypes)
        fun types(key: String) = contract.getValue(key).jsonArray.map { it.jsonPrimitive.content }.toSet()
        require(types("coveredTypes") == snapshot.coveredTypes && types("populatedTypes") == populatedTypes &&
            types("emptyTypes") == snapshot.coveredTypes - populatedTypes)
        require(raw.keys == setOf("contract.json", "snapshot.json") + snapshot.resources.keys.map { "blobs/$it" })
        val blobs = snapshot.resources.mapValues { (hash, size) ->
            val content = requireNotNull(raw["blobs/$hash"]); require(content.size.toLong() == size && lanSyncHash(content) == hash)
            extraction.resolve(hash).also { Files.write(it, content, StandardOpenOption.CREATE_NEW) }
        }
        return Packet(snapshot, blobs, contract)
    }

    fun fixtureRecipe(first: String, second: String, firstHash: String, secondHash: String, encoded: String) = NovelAiGenerationRecipe(
        basePrompt = "电脑配方原文\n{\"path\":\"正文保持原样\"}", negativePrompt = "合成负面原文",
        characters = listOf(NovelAiCharacterPromptDraft("cross-platform-scoped-role", "合成人物标签")),
        settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, effort = NovelAiEffort.HIGH, sampler = NovelAiSampler.DDIM,
            transparentBackground = true, varietyPlus = true, varietyPlusSigma = .75, seedMode = NovelAiSeedMode.FIXED, seed = 42),
        imageGuidance = NovelAiImageGuidanceDraft(baseImage = NovelAiStudioAssetRef("cross-asset-a", first, firstHash, 8, 6),
            maskImage = NovelAiStudioAssetRef("cross-asset-b", second, secondHash, 16, 12), referenceMode = NovelAiReferenceMode.VIBE,
            vibes = listOf(NovelAiVibeReferenceDraft("cross-vibe", encodedVibe = encoded))))

    suspend fun seed(root: Path, first: String, second: String, processed: NovelAiPostProcessResultRecord): JsonObject {
        val storage = JsonFileStorage(root)
        fun resource(reference: String) = Paths.get(reference).let { if (it.isAbsolute) it else root.resolve(it) }
        val firstHash = fileHash(resource(first)); val secondHash = fileHash(resource(second))
        CharacterRepository(storage).save(CharacterCard(CARD, "电脑合成角色", avatar = first, createdAt = 1, updatedAt = 1))
        val chats = ChatRepository(storage)
        chats.createSession(ChatSession(SESSION, CARD, "合成跨平台会话", modelId = WINDOWS_MODEL, createdAt = 1, updatedAt = 1))
        chats.addMessage(ChatMessage(MESSAGE, SESSION, MessageRole.ASSISTANT, "电脑当前版本", images = listOf(first, second),
            alternatives = listOf("电脑旧版本", "电脑当前版本"), alternativeVersionIds = listOf("cross-version-0", "cross-version-1"),
            currentAlternativeIndex = 1, currentAlternativeVersionId = "cross-version-1", sourceTurnId = "cross-source-turn", sourceTurnOrder = 1,
            createdAt = 1, updatedAt = 1, orderKey = 12345))
        val versions = listOf(first, second).mapIndexed { index, path -> MomentImageVersion("cross-moment-$index", path, "合成图片$index",
            GeneratedImageMetadata(path, "合成图片$index", negativePrompt = "合成负面", sizePreset = "synthetic",
                width = if (index == 0) 8 else 16, height = if (index == 0) 6 else 12), "合成版本$index", index.toLong()) }
        MomentRepository(storage).savePost(MomentPost(POST, CARD, SESSION, senderName = "合成作者", senderAvatar = first,
            text = "电脑动态正文", imagePath = second, imagePrompt = versions[1].imagePrompt,
            generatedImageMetadata = versions[1].generatedImageMetadata, imageBrief = versions[1].imageBrief,
            scheduledAt = 1, generatedAt = 1, desktopImageVersions = versions))
        SettingsRepository(storage).savePlayerSetting(PlayerSetting("cross-native-player", "电脑合成玩家", "玩家原文\n第二行", updatedAt = 1, desktopAvatar = first))
        val encoded = NovelAiInlinePayloadStore(root.toFile()).retain(vibe)
        require(encoded.startsWith(NovelAiInlinePayloadStore.PREFIX))
        NovelAiStudioStateRepository(storage).saveHistory(NovelAiGenerationHistoryEntry(HISTORY,
            listOf(NovelAiGenerationHistoryImage(first, 42), NovelAiGenerationHistoryImage(second, 43)),
            fixtureRecipe(first, second, firstHash, secondHash, encoded), createdAt = 1))
        return buildJsonObject {
            put("firstHash", firstHash); put("secondHash", secondHash)
            put("processed", json.encodeToJsonElement(NovelAiPostProcessResultRecord.serializer(), processed))
        }
    }

    /** Actual shared repository mutation, not an edited snapshot or a reconstructed Android recipe. */
    suspend fun editOnAndroid(root: Path) {
        val storage = JsonFileStorage(root); val characters = CharacterRepository(storage)
        characters.save(requireNotNull(characters.getById(CARD)).copy(name = "手机修改角色"))
        val chats = ChatRepository(storage); val session = requireNotNull(chats.getSession(SESSION))
        chats.updateSession(session.copy(modelId = ANDROID_MODEL))
        val message = chats.getMessagesReadOnly(SESSION).single()
        chats.updateMessage(message.copy(content = "手机修改当前版本", alternatives = listOf("电脑旧版本", "手机修改当前版本")))
        val moments = MomentRepository(storage); moments.updatePostText(POST, "手机修改动态"); moments.toggleLike(POST)
        SettingsRepository(storage).updatePlayerSetting { it.copy(playerName = "手机修改玩家") }
        val studio = NovelAiStudioStateRepository(storage); studio.initialize()
        val history = studio.history.first().single()
        studio.saveHistory(history.copy(recipe = history.recipe.copy(basePrompt = "手机修改配方\n正文保持原样")))
    }

    suspend fun verify(root: Path, facts: JsonObject, modified: Boolean, absolutePaths: Boolean, model: String?): Map<Path, String> {
        val storage = JsonFileStorage(root); val paths = linkedMapOf<Path, String>()
        val firstHash = facts.getValue("firstHash").jsonPrimitive.content; val secondHash = facts.getValue("secondHash").jsonPrimitive.content
        fun image(reference: String?, hash: String) {
            val raw = Paths.get(requireNotNull(reference)); require(raw.isAbsolute == absolutePaths)
            val path = (if (raw.isAbsolute) raw else root.resolve(raw)).toRealPath()
            require(path.startsWith(root) && fileHash(path) == hash); paths[path] = hash
        }
        val card = requireNotNull(CharacterRepository(storage).getById(CARD))
        require(card.name == if (modified) "手机修改角色" else "电脑合成角色"); image(card.avatar, firstHash)
        val chats = ChatRepository(storage); require(chats.getSession(SESSION)?.modelId == model)
        val message = chats.getMessagesReadOnly(SESSION).single()
        require(message.content == if (modified) "手机修改当前版本" else "电脑当前版本")
        require(message.alternatives == listOf("电脑旧版本", message.content) && message.alternativeVersionIds == listOf("cross-version-0", "cross-version-1") &&
            message.currentAlternativeVersionId == "cross-version-1" && message.currentAlternativeIndex == 1 && message.sourceTurnId == "cross-source-turn" &&
            message.sourceTurnOrder == 1L && message.orderKey == 12345L)
        require(message.images.size == 2); image(message.images[0], firstHash); image(message.images[1], secondHash)
        val post = requireNotNull(MomentRepository(storage).getPost(POST))
        require(post.text == if (modified) "手机修改动态" else "电脑动态正文"); require(post.userLiked == modified)
        require(post.desktopImageVersions.map { it.id } == listOf("cross-moment-0", "cross-moment-1"))
        post.desktopImageVersions.forEachIndexed { index, version ->
            val hash = if (index == 0) firstHash else secondHash
            image(version.imagePath, hash); image(version.generatedImageMetadata?.imagePath, hash)
        }
        image(post.imagePath, secondHash); require(post.imagePath == post.desktopImageVersions[1].imagePath)
        val settings = SettingsRepository(storage); settings.refreshPlayerSettingFromStorage(); val player = settings.playerSetting.first()
        require(player.id == "cross-native-player" && player.globalPersona == "玩家原文\n第二行" &&
            player.playerName == if (modified) "手机修改玩家" else "电脑合成玩家"); image(player.desktopAvatar, firstHash)
        require(!Files.exists(root.resolve("entities/app_settings.json")))
        val studio = NovelAiStudioStateRepository(storage); studio.initialize(); val history = studio.history.first().single()
        require(history.id == HISTORY && history.images.map { it.seed } == listOf(42L, 43L))
        image(history.images[0].path, firstHash); image(history.images[1].path, secondHash)
        val recipe = history.recipe; val guidance = recipe.imageGuidance
        require(recipe.basePrompt == if (modified) "手机修改配方\n正文保持原样" else "电脑配方原文\n{\"path\":\"正文保持原样\"}")
        require(recipe.negativePrompt == "合成负面原文" && recipe.characters.single().id == "cross-platform-scoped-role")
        require(recipe.settings == fixtureRecipe("", "", firstHash, secondHash, "").settings)
        require(guidance.baseImage?.id == "cross-asset-a" && guidance.maskImage?.id == "cross-asset-b" && guidance.vibes.single().id == "cross-vibe")
        image(guidance.baseImage?.path, firstHash); image(guidance.maskImage?.path, secondHash)
        val encoded = requireNotNull(guidance.vibes.single().encodedVibe); require(encoded.startsWith(NovelAiInlinePayloadStore.PREFIX))
        require(NovelAiInlinePayloadStore(root.toFile()).resolve(encoded) == vibe)
        val original = json.decodeFromJsonElement(NovelAiPostProcessResultRecord.serializer(), facts.getValue("processed"))
        require(original.sourceSha256 == firstHash && original.imageSha256 == secondHash)
        val processed = requireNotNull(storage.loadEntity(LanSyncPostProcess.TYPE, original.id, NovelAiPostProcessResultRecord.serializer()))
        processed.validateMetadata(); require(processed.copy(imagePath = original.imagePath) == original)
        image(processed.imagePath, secondHash)
        return paths
    }
}
