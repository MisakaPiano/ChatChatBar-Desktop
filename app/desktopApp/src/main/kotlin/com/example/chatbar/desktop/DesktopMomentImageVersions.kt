package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.MomentImageVersion
import com.example.chatbar.data.local.entity.MomentPost
import java.util.UUID

/** Selected fields remain authoritative; a legacy current image joins retained candidates on first edit. */
internal object DesktopMomentImageVersions {
    const val MAX_CANDIDATES = 4096
    fun retained(post: MomentPost): List<MomentImageVersion> {
        val versions = post.desktopImageVersions
        require(versions.size <= MAX_CANDIDATES && versions.map { it.id }.distinct().size == versions.size)
        require(versions.map { it.imagePath }.distinct().size == versions.size)
        versions.forEach { version ->
            require(version.id.isNotBlank() && version.id.length <= 128)
            require(version.imagePath.isNotBlank() && version.imagePath.length <= 1024)
            version.generatedImageMetadata?.let { require(it.imagePath == version.imagePath) }
        }
        val selected = post.imagePath?.takeIf(String::isNotBlank) ?: return versions
        val match = versions.firstOrNull { it.imagePath == selected }
        if (match != null) {
            require(match.imagePrompt == post.imagePrompt && match.generatedImageMetadata == post.generatedImageMetadata)
            // Android's four-field candidates have neither per-image brief nor time. The current
            // post still owns its known selected brief; do not invent metadata for other versions.
            val missingSelectedBrief = match.createdAt == 0L && match.imageBrief.isEmpty()
            require(match.imageBrief == post.imageBrief || missingSelectedBrief)
            return if (missingSelectedBrief && post.imageBrief.isNotEmpty()) versions.map {
                if (it.id == match.id) it.copy(imageBrief = post.imageBrief) else it
            } else versions
        }
        // A stable legacy identity avoids changing selection merely by viewing old posts.
        require(versions.size < MAX_CANDIDATES)
        val id = UUID.nameUUIDFromBytes((post.id + "\n" + selected).toByteArray(Charsets.UTF_8)).toString()
        require(versions.none { it.id == id })
        return versions + MomentImageVersion(id, selected, post.imagePrompt, post.generatedImageMetadata, post.imageBrief, post.updatedAt)
    }

    fun append(post: MomentPost, image: DesktopMomentImageResult, now: Long): MomentPost {
        require(image.metadata.imagePath == image.path)
        val before = retained(post)
        require(before.size < MAX_CANDIDATES) { "图片版本数量已达上限，请先移除不再需要的版本" }
        require(before.none { it.imagePath == image.path }) { "新图片路径与保留版本重复" }
        val version = MomentImageVersion(UUID.randomUUID().toString(), image.path, image.imagePrompt, image.metadata, image.imageBrief, now)
        val retained = post.copy(desktopImageVersions = before + version)
        return select(retained, version.id).copy(updatedAt = now)
    }

    fun select(post: MomentPost, versionId: String): MomentPost {
        val versions = retained(post)
        val selected = versions.singleOrNull { it.id == versionId } ?: error("图片版本已变化，请重新打开")
        return post.copy(imagePath = selected.imagePath, imagePrompt = selected.imagePrompt,
            generatedImageMetadata = selected.generatedImageMetadata, imageBrief = selected.imageBrief, desktopImageVersions = versions)
    }

    fun remove(post: MomentPost, versionId: String): Pair<MomentPost, String> {
        val versions = retained(post)
        val deleted = versions.singleOrNull { it.id == versionId } ?: error("图片版本已变化，请重新打开")
        require(versions.size > 1) { "至少保留一个图片版本" }
        val remaining = versions.filterNot { it.id == versionId }
        val without = post.copy(desktopImageVersions = remaining)
        val result = if (post.imagePath == deleted.imagePath) {
            val selected = remaining.last()
            without.copy(imagePath = selected.imagePath, imagePrompt = selected.imagePrompt,
                generatedImageMetadata = selected.generatedImageMetadata, imageBrief = selected.imageBrief)
        } else without
        return result to deleted.imagePath
    }
}
