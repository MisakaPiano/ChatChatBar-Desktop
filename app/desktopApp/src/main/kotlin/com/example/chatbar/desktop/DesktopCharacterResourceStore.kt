package com.example.chatbar.desktop

import com.example.chatbar.domain.card.CharacterPackagedImageContent
import com.example.chatbar.domain.card.CharacterResourceNaming
import com.example.chatbar.domain.card.CharacterResourceStore
import com.example.chatbar.domain.card.PackagedDocument
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.domain.card.decodeCharacterPackagedImage
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal class DesktopCharacterResourceStore(
    appDataRoot: Path,
    private val assetReader: ((String) -> ByteArray)? = null,
) : CharacterResourceStore {
    private val root = appDataRoot.toAbsolutePath().normalize()

    override fun readText(reference: String): String =
        if (reference.startsWith(ASSET_PREFIX)) {
            String(readAsset(reference), Charsets.UTF_8)
        } else {
            Files.readString(requireReadableOwnedFile(reference))
        }

    override fun readBytes(reference: String): ByteArray =
        if (reference.startsWith(ASSET_PREFIX)) {
            readAsset(reference)
        } else {
            Files.readAllBytes(requireReadableOwnedFile(reference))
        }

    override fun fileName(reference: String): String =
        reference.removePrefix(ASSET_PREFIX).substringAfterLast('/').substringAfterLast('\\')

    override fun materializeDocument(
        document: PackagedDocument,
        timestamp: Long,
        resourceId: String,
    ): String {
        val safeName = CharacterResourceNaming.safeDocumentFileName(document.fileName)
        val fileName = "card_${timestamp}_${resourceId}_$safeName"
        return createOwnedFile(
            directoryName = DOCUMENTS_DIRECTORY,
            fileName = fileName,
            bytes = document.content.toByteArray(Charsets.UTF_8),
        )
    }

    override fun materializeImage(
        image: PackagedImage,
        timestamp: Long,
        resourceId: String,
    ): String {
        val extension = CharacterResourceNaming.imageExtension(image.fileName)
        val fileName = "card_${timestamp}_$resourceId.$extension"
        val bytes = when (val content = decodeCharacterPackagedImage(image.data)) {
            is CharacterPackagedImageContent.Bytes -> content.value
            is CharacterPackagedImageContent.Asset -> requireAssetReader()(content.logicalPath)
        }
        return createOwnedFile(IMAGES_DIRECTORY, fileName, bytes)
    }

    override fun deleteOwned(reference: String) {
        if (reference.startsWith(ASSET_PREFIX)) return
        val path = resolveOwnedReference(reference)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "拒绝删除非普通 Character owned resource：$reference"
        }
        Files.delete(path)
    }

    internal fun resolveOwnedReference(reference: String): Path {
        require(!reference.startsWith(ASSET_PREFIX)) { "asset 引用不属于 Desktop owned filesystem path" }
        val relative = runCatching { Path.of(reference) }
            .getOrElse { throw IllegalArgumentException("无效 Character resource reference：$reference", it) }
        require(!relative.isAbsolute) { "Desktop Character owned reference 必须是相对路径：$reference" }
        require(relative.none { it.toString() == "." || it.toString() == ".." }) {
            "Desktop Character owned reference 不允许 traversal：$reference"
        }
        val normalized = relative.normalize()
        require(normalized.nameCount == 2) { "Desktop Character owned reference 必须是直接 owned file：$reference" }
        val directoryName = normalized.getName(0).toString()
        require(directoryName == IMAGES_DIRECTORY || directoryName == DOCUMENTS_DIRECTORY) {
            "Desktop Character owned reference 不属于允许目录：$reference"
        }
        val resolved = root.resolve(normalized).normalize()
        require(resolved.startsWith(root) && resolved != root) {
            "Desktop Character owned reference 逃逸 appDataRoot：$reference"
        }
        validateOwnedDirectoryIfPresent(root.resolve(directoryName), directoryName)
        return resolved
    }

    private fun requireReadableOwnedFile(reference: String): Path {
        val path = resolveOwnedReference(reference)
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "Character resource 文件不存在或不安全：$reference"
        }
        return path
    }

    private fun createOwnedFile(directoryName: String, fileName: String, bytes: ByteArray): String {
        val directory = ensureOwnedDirectory(directoryName)
        val target = directory.resolve(fileName).normalize()
        require(target.parent == directory) { "Character resource filename 逃逸 owned directory" }
        try {
            Files.newOutputStream(
                target,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { it.write(bytes) }
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(target) }
            throw error
        }
        return "$directoryName/$fileName"
    }

    private fun ensureOwnedDirectory(directoryName: String): Path {
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
            "Desktop appDataRoot 必须是普通目录：$root"
        }
        val directory = root.resolve(directoryName)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(directory)
        }
        validateOwnedDirectoryIfPresent(directory, directoryName)
        return directory
    }

    private fun validateOwnedDirectoryIfPresent(directory: Path, directoryName: String) {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory)) {
            "Desktop Character $directoryName 必须是普通目录"
        }
    }

    private fun readAsset(reference: String): ByteArray =
        requireAssetReader()(reference.removePrefix(ASSET_PREFIX).also {
            require(it.isNotBlank()) { "Character asset 引用不能为空" }
        })

    private fun requireAssetReader(): (String) -> ByteArray =
        assetReader ?: error("Desktop Character asset resolver 尚未接入：3P / platform asset wiring required")

    private companion object {
        const val ASSET_PREFIX = "asset:"
        const val IMAGES_DIRECTORY = "images"
        const val DOCUMENTS_DIRECTORY = "documents"
    }
}
