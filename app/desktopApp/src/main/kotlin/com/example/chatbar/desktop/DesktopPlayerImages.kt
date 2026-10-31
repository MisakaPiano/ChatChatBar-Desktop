package com.example.chatbar.desktop

import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.card.PackagedImage
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Independent, immediate avatar save; name/persona drafts remain owned by their existing editor. */
internal class DesktopPlayerImages(
    private val settings: SettingsRepository,
    private val resources: DesktopCharacterResourceStore,
    private val picker: DesktopFilePicker,
    private val gate: AppDataOperationGate,
    private val cleanup: suspend (List<String>) -> Unit = {},
) {
    data class Change(val cleanupNotice: String? = null)
    fun read(reference: String): ByteArray = resources.readBytes(reference)

    suspend fun choose(): Change? {
        val path = picker.pickOpenFile(DesktopFileType("玩家头像", listOf("png", "jpg", "jpeg", "webp", "gif"))) ?: return null
        val bytes = withContext(Dispatchers.IO) {
            require(Files.size(path) <= DesktopImageEditing.MAX_BYTES) { "图片大小超过 32 MB" }
            DesktopImageEditing.png(DesktopImageEditing.decode(Files.readAllBytes(path), longestSide = 1024))
        }
        return replace(bytes)
    }

    suspend fun replace(bytes: ByteArray?): Change {
        val previous = gate.withNormalOperation {
            withContext(NonCancellable + Dispatchers.IO) {
                val reference = bytes?.let {
                    require(it.size <= DesktopImageEditing.MAX_BYTES)
                    DesktopImageEditing.decode(it)
                    resources.materializeImage(PackagedImage("player.png", Base64.getEncoder().encodeToString(it)),
                        System.currentTimeMillis(), "player" + UUID.randomUUID())
                }
                // An uncertain write retains its newly copied image; never delete a possibly live avatar.
                var previous: String? = null
                settings.updatePlayerSetting { current ->
                    previous = current.desktopAvatar
                    current.copy(desktopAvatar = reference)
                }
                previous
            }
        }
        return try {
            previous?.takeIf { Regex("images/card_[0-9]+_player[0-9a-f-]+\\.png").matches(it) }?.let { cleanup(listOf(it)) }
            Change()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { Change("头像已保存；旧头像清理未完成，原文件保留。") }
    }
}
