package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * 全局玩家角色设定
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class PlayerSetting(
    val id: String = "default",
    val playerName: String = "", // 玩家名称
    val globalPersona: String = "", // 全局玩家角色设定
    val updatedAt: Long = System.currentTimeMillis(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val desktopAvatar: String? = null
)
