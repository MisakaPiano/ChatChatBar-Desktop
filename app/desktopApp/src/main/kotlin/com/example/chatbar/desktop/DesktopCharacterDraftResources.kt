package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.DocumentRagStatus
import com.example.chatbar.domain.card.PackagedDocument
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.domain.card.OriginalCharacterCover
import com.example.chatbar.domain.card.PngTextChunks
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.UUID
import org.jetbrains.skia.Image

/** Temporary editor-owned files are never valid durable Character resource references. */
internal class DesktopCharacterDraftResources(
    appDataRoot: Path,
    private val durable: DesktopCharacterResourceStore,
) {
    private val root = appDataRoot.toAbsolutePath().normalize()
    private val draftRoot = root.resolve("draft_assets")

    fun stage(sessionId: String, source: Path, image: Boolean, originalCover: Boolean = false): String {
        require(Files.isRegularFile(source)) { "Selected file is not a regular file" }
        val bytes = Files.readAllBytes(source)
        require(bytes.size <= 20 * 1024 * 1024) { "Selected file exceeds 20 MB" }
        if (image) require(runCatching { Image.makeFromEncoded(bytes) }.isSuccess) { "Invalid image" }
        else require(String(bytes, Charsets.UTF_8).toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
            "Document must be UTF-8 text"
        }
        return stageBytes(sessionId, source.fileName.toString(),
            if (originalCover && PngTextChunks.isPng(bytes)) OriginalCharacterCover.imageOnlyPng(bytes) else bytes)
    }

    fun copyAsCover(sessionId: String, reference: String): String {
        val bytes = readImage(reference)
        val original = if (PngTextChunks.isPng(bytes)) OriginalCharacterCover.imageOnlyPng(bytes) else bytes
        return stageBytes(sessionId, reference.substringAfterLast('/'), original)
    }

    fun stageImage(sessionId: String, bytes: ByteArray): String {
        DesktopImageEditing.decode(bytes)
        return stageBytes(sessionId, "crop.png", bytes)
    }

    fun stageText(sessionId: String, fileName: String, content: String): String =
        stageBytes(sessionId, fileName, content.toByteArray(Charsets.UTF_8))

    fun readText(reference: String): String = Files.readString(resolveDraft(reference))

    fun readBytes(reference: String): ByteArray = Files.readAllBytes(resolveDraft(reference))

    fun readImage(reference: String): ByteArray =
        if (isDraft(reference)) readBytes(reference) else durable.readBytes(reference)

    fun readDocument(reference: String): String =
        if (isDraft(reference)) readText(reference) else durable.readText(reference)

    /** Only for rollback of a newly returned stage reference that was never assigned. */
    internal fun discardNew(reference: String) { Files.delete(resolveDraft(reference)) }

    fun isDraft(reference: String): Boolean = draftRelativeReference(reference) != null

    /** Restored draft fields may use this root's absolute path; never admit another root. */
    private fun draftRelativeReference(reference: String): String? {
        if (reference.startsWith("draft_assets/")) return reference
        val path = runCatching { Path.of(reference) }.getOrNull() ?: return null
        if (!path.isAbsolute || path.any { it.toString() == "." || it.toString() == ".." } || !path.startsWith(draftRoot)) return null
        return root.relativize(path).joinToString("/")
    }

    /** Returns a durable card and every newly written resource for rollback on entity failure. */
    fun materialize(card: CharacterCard): Pair<CharacterCard, List<String>> {
        val created = mutableListOf<String>()
        val mapped = mutableMapOf<String, String>()
        fun image(reference: String?): String? = reference?.let { source ->
            if (!isDraft(source)) source else mapped.getOrPut(source) {
                val result = durable.materializeImage(
                    PackagedImage(source.substringAfterLast('/').substringAfter('_', "image.png"),
                        Base64.getEncoder().encodeToString(readBytes(source))),
                    System.currentTimeMillis(), UUID.randomUUID().toString(),
                )
                created += result
                result
            }
        }
        fun document(reference: String, name: String, type: String): String =
            if (!isDraft(reference)) reference else mapped.getOrPut(reference) {
                val result = durable.materializeDocument(
                    PackagedDocument(name, type, readText(reference)),
                    System.currentTimeMillis(), UUID.randomUUID().toString(),
                )
                created += result
                result
            }
        fun cover(reference: String?): String? = reference?.let { source ->
            if (!isDraft(source) && source.startsWith("images/character-covers/")) source else {
                // Copy even a durable avatar/asset into an independent cover file.
                val bytes = readImage(source)
                durable.materializeCoverImage(PackagedImage(source.substringAfterLast('/'),
                    Base64.getEncoder().encodeToString(bytes)), System.currentTimeMillis(), UUID.randomUUID().toString())
                    .also(created::add)
            }
        }
        try {
            val result = card.copy(
                avatar = image(card.avatar),
                coverImage = cover(card.coverImage),
                chatBackground = image(card.chatBackground),
                chatBackgroundImages = card.chatBackgroundImages.map { requireNotNull(image(it)) },
                characters = card.characters.map { it.copy(appearanceImage = image(it.appearanceImage)) },
                customDocuments = card.customDocuments.map { doc ->
                    if (!isDraft(doc.filePath)) doc else doc.copy(
                        filePath = document(doc.filePath, doc.fileName, doc.fileType),
                        contentHash = null, indexedHash = null,
                        ragStatus = DocumentRagStatus.PENDING.name,
                        ragChunkCount = 0, ragIndexedAt = null, ragError = null,
                    )
                },
            )
            return result to created
        } catch (error: Throwable) {
            rollback(created, error)
            throw error
        }
    }

    fun rollback(created: List<String>, cause: Throwable) {
        created.asReversed().forEach { reference ->
            runCatching { durable.deleteOwned(reference) }.onFailure(cause::addSuppressed)
        }
    }

    fun discardSession(sessionId: String) {
        val dir = sessionDirectory(sessionId)
        require(!Files.isSymbolicLink(draftRoot)) { "Unsafe draft root" }
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return
        require(Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(dir)) {
            "Unsafe draft directory"
        }
        Files.list(dir).use { files ->
            files.forEach { file ->
                require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)) {
                    "Unsafe draft file"
                }
                Files.delete(file)
            }
        }
        Files.delete(dir)
    }

    fun discardObsolete(previous: CharacterCard, allCurrent: List<CharacterCard>) {
        val active = allCurrent.flatMap(::desktopCharacterResourceReferences).toSet()
        desktopCharacterResourceReferences(previous).filterNot { it in active || isDraft(it) || it.startsWith("asset:") }
            .forEach(durable::deleteOwned)
    }

    private fun stageBytes(sessionId: String, name: String, bytes: ByteArray): String {
        require(bytes.size <= 20 * 1024 * 1024) { "Draft resource exceeds 20 MB" }
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
            "Unsafe app data root"
        }
        if (!Files.exists(draftRoot, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(draftRoot)
        require(Files.isDirectory(draftRoot, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(draftRoot)) {
            "Unsafe draft root"
        }
        val dir = sessionDirectory(sessionId)
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(dir)
        require(Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(dir)) {
            "Unsafe draft directory"
        }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(90).ifBlank { "asset" }
        val fileName = "${UUID.randomUUID()}_$safeName"
        val target = dir.resolve(fileName)
        var created = false
        try { Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
            created = true; it.write(bytes)
        } } catch (error: Throwable) {
            if (created) runCatching { Files.deleteIfExists(target) }.onFailure(error::addSuppressed)
            throw error
        }
        return "draft_assets/$sessionId/$fileName"
    }

    private fun sessionDirectory(sessionId: String): Path {
        require(sessionId.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid draft session" }
        return draftRoot.resolve(sessionId)
    }

    private fun resolveDraft(reference: String): Path {
        val parts = requireNotNull(draftRelativeReference(reference)) { "Invalid draft reference" }.split('/')
        require(parts.size == 3 && parts[0] == "draft_assets") { "Invalid draft reference" }
        val dir = sessionDirectory(parts[1])
        require(parts[2].matches(Regex("[A-Za-z0-9._-]{1,130}"))) { "Invalid draft file" }
        require(!Files.isSymbolicLink(draftRoot) && !Files.isSymbolicLink(dir)) { "Unsafe draft directory" }
        val path = dir.resolve(parts[2])
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "Draft resource missing"
        }
        return path
    }
}

/** Positive retention evidence only; candidates not retained here still need the full reference authority. */
internal fun desktopCharacterResourceReferences(card: CharacterCard): List<String> = buildList {
    card.avatar?.let(::add)
    card.coverImage?.let(::add)
    card.chatBackground?.let(::add)
    addAll(card.chatBackgroundImages)
    card.characters.mapNotNullTo(this) { it.appearanceImage }
    card.customDocuments.mapTo(this) { it.filePath }
}
