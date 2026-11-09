package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard
import java.io.File

data class CharacterCoverAsset(val path: String, val labels: List<String>)

data class CharacterCoverBaseExport(val bytes: ByteArray, val mimeType: String, val extension: String)

/** Only references belonging to this card; never scans a directory or another card. */
object CharacterCoverAssets {
    fun available(card: CharacterCard, isAvailable: (String) -> Boolean = { File(it).isFile }): List<CharacterCoverAsset> {
        val roles = linkedMapOf<String, MutableList<String>>()
        fun add(path: String?, label: String) {
            if (!path.isNullOrBlank() && isAvailable(path)) roles.getOrPut(path) { mutableListOf() }.add(label)
        }
        add(card.coverImage, "封面原图")
        add(card.avatar, "角色卡头像")
        add(card.chatBackground, "默认聊天背景")
        card.chatBackgroundImages.forEachIndexed { index, path -> add(path, "背景图库 ${index + 1}") }
        card.characters.forEach { add(it.appearanceImage, "${it.name} · 人物图片") }
        return roles.map { (path, labels) -> CharacterCoverAsset(path, labels.distinct()) }
    }
}
