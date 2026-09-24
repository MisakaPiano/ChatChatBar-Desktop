package com.example.chatbar.domain.card

import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.domain.rag.RagRepository
import java.io.File
import java.io.InputStream

internal class AndroidCharacterResourceStore(
    filesDir: File,
    private val assetReader: (String) -> InputStream,
) : CharacterResourceStore {
    private val imagesDirectory = File(filesDir, "images")
    private val documentsDirectory = File(filesDir, "documents")

    override fun readText(reference: String): String =
        if (reference.startsWith("asset:")) {
            assetReader(reference.removePrefix("asset:")).bufferedReader().use { it.readText() }
        } else {
            requireResourceFile(reference).readText()
        }

    override fun readBytes(reference: String): ByteArray =
        if (reference.startsWith("asset:")) {
            assetReader(reference.removePrefix("asset:")).use(InputStream::readBytes)
        } else {
            requireResourceFile(reference).readBytes()
        }

    override fun fileName(reference: String): String =
        reference.removePrefix("asset:").substringAfterLast('/').substringAfterLast('\\')

    override fun materializeDocument(
        document: PackagedDocument,
        timestamp: Long,
        resourceId: String,
    ): String {
        ensureOwnedDirectory(documentsDirectory)
        val safeName = CharacterResourceNaming.safeDocumentFileName(document.fileName)
        val file = File(documentsDirectory, "card_${timestamp}_${resourceId}_$safeName")
        return createOwnedFile(file) { output ->
            output.write(document.content.toByteArray(Charsets.UTF_8))
        }.absolutePath
    }

    override fun materializeImage(
        image: PackagedImage,
        timestamp: Long,
        resourceId: String,
    ): String {
        ensureOwnedDirectory(imagesDirectory)
        val extension = CharacterResourceNaming.imageExtension(image.fileName)
        val file = File(imagesDirectory, "card_${timestamp}_$resourceId.$extension")
        return createOwnedFile(file) { output ->
            when (val content = decodeCharacterPackagedImage(image.data)) {
                is CharacterPackagedImageContent.Bytes -> output.write(content.value)
                is CharacterPackagedImageContent.Asset ->
                    assetReader(content.logicalPath).use { input -> input.copyTo(output) }
            }
        }.absolutePath
    }

    override fun deleteOwned(reference: String) {
        if (reference.startsWith("asset:")) return
        val file = File(reference).canonicalFile
        require(isWithin(file, imagesDirectory) || isWithin(file, documentsDirectory)) {
            "拒绝删除 Character owned area 之外的资源：$reference"
        }
        if (file.exists() && !file.delete()) {
            error("无法删除 Character owned resource：$reference")
        }
    }

    private fun requireResourceFile(reference: String): File =
        File(reference).takeIf(File::isFile) ?: error("角色资源文件不存在：$reference")

    private fun ensureOwnedDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            error("无法创建 Character resource directory：${directory.absolutePath}")
        }
    }

    private fun createOwnedFile(file: File, write: (java.io.OutputStream) -> Unit): File {
        check(file.createNewFile()) { "Character resource 已存在：${file.absolutePath}" }
        try {
            file.outputStream().use(write)
            return file
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    private fun isWithin(file: File, directory: File): Boolean {
        val root = directory.canonicalFile.toPath()
        return file.toPath().startsWith(root) && file.toPath() != root
    }
}

internal object AndroidCharacterTransferPromptPolicy : CharacterTransferPromptPolicy {
    override fun defaultCharacterNaiNegativePrompt(): String =
        PromptTemplates.defaultCharacterNaiNegativePrompt()

    override fun effectiveCharacterNaiNegativePrompt(value: String): String =
        PromptTemplates.effectiveCharacterNaiNegativePrompt(value)
}

internal class AndroidCharacterDocumentRagCleanup(
    private val repository: RagRepository,
) : CharacterDocumentRagCleanup {
    override suspend fun deleteDocumentChunks(characterId: String) {
        repository.deleteChunksBySource(
            com.example.chatbar.data.local.entity.ChunkSourceType.DOCUMENT,
            characterId,
        )
    }
}
