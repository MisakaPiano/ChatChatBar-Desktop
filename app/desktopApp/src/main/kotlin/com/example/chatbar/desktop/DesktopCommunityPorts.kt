package com.example.chatbar.desktop

import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.data.repository.WorldBookRepository
import com.example.chatbar.desktop.security.DesktopCredentialKey
import com.example.chatbar.desktop.security.DesktopSecretStore
import com.example.chatbar.domain.card.CharacterCardTransferCore
import com.example.chatbar.domain.card.FormatCardTransferService
import com.example.chatbar.domain.card.WorldBookTransferService
import com.example.chatbar.domain.community.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal class DesktopCommunitySessionStore(private val secrets: DesktopSecretStore, private val json: Json) : CommunitySessionStore {
    override fun load(): CommunitySession? = secrets.load(DesktopCredentialKey.DormantCommunitySession)?.let {
        json.decodeFromString(CommunitySession.serializer(), it).also(::validate)
    }
    override fun save(session: CommunitySession) {
        validate(session)
        secrets.save(DesktopCredentialKey.DormantCommunitySession, json.encodeToString(CommunitySession.serializer(), session))
    }
    override fun clear() { secrets.delete(DesktopCredentialKey.DormantCommunitySession) }
    private fun validate(value: CommunitySession) { require(value.accessToken.isNotBlank() && value.userId.isNotBlank()) }
}

/** Only visible app entities and the existing typed exporters; no file-picker upload. */
internal class DesktopCommunityLocalPackages(
    private val characters: CharacterRepository,
    private val formats: FormatCardRepository,
    private val worlds: WorldBookRepository,
    private val characterTransfers: CharacterCardTransferCore,
    private val formatTransfers: FormatCardTransferService,
    private val worldTransfers: WorldBookTransferService,
    private val resources: DesktopCharacterResourceStore,
    private val gate: AppDataOperationGate,
) : CommunityLocalPackages {
    override suspend fun candidates(type: CommunityItemType): List<CommunityUploadCandidate> = gate.withNormalOperation {
        when (type) {
            CommunityItemType.CHARACTER -> characters.getAll().filterNot { it.isCommunityDownload }.map {
                CommunityUploadCandidate(it.id, type, it.name, "${it.characters.size} / ${it.customDocuments.size}") }
            CommunityItemType.FORMAT -> formats.getAll().map { CommunityUploadCandidate(it.id, type, it.name) }
            CommunityItemType.WORLD_BOOK -> worlds.getAll().map { CommunityUploadCandidate(it.id, type, it.name, "${it.entries.size}") }
        }
    }
    override suspend fun export(type: CommunityItemType, localId: String): CommunityLocalPackage = gate.withNormalOperation {
        when (type) {
            CommunityItemType.CHARACTER -> {
                val card = requireNotNull(characters.getById(localId))
                require(!card.isCommunityDownload) { "下载角色卡不能上传，请先复制为本地角色卡" }
                val preview = card.avatar?.takeIf(String::isNotBlank)?.let { path ->
                    withContext(Dispatchers.IO) { preview(resources.readScreenshotImageBytes(path, 20 * 1024 * 1024)) }
                }
                CommunityLocalPackage(card.name, characterTransfers.exportJson(localId), preview)
            }
            CommunityItemType.FORMAT -> CommunityLocalPackage(requireNotNull(formats.getById(localId)).name, formatTransfers.exportJson(localId))
            CommunityItemType.WORLD_BOOK -> CommunityLocalPackage(requireNotNull(worlds.getById(localId)).name, worldTransfers.exportJson(localId))
        }
    }
    private fun preview(bytes: ByteArray): CommunityPreviewDraft {
        val source = DesktopImageEditing.decode(bytes, longestSide = 160)
        val scale = minOf(1.0, 160.0 / maxOf(source.width, source.height))
        val image = BufferedImage((source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { graphics -> try { graphics.drawImage(source, 0, 0, image.width, image.height, null) } finally { graphics.dispose() } }
        source.flush()
        try {
            for (quality in listOf(.56f, .46f, .40f)) {
                val output = ByteArrayOutputStream()
                val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
                try {
                    ImageIO.createImageOutputStream(output).use { target ->
                        writer.output = target
                        val parameters = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality }
                        writer.write(null, IIOImage(image, null, null), parameters)
                    }
                } finally { writer.dispose() }
                val result = output.toByteArray()
                if (result.size in 1..80 * 1024) return CommunityPreviewDraft("preview.jpg", result, "image/jpeg")
            }
            error("社区缩略图未能压缩到 80 KB")
        } finally { image.flush() }
    }
}
