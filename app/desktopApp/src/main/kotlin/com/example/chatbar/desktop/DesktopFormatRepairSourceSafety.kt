package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ConditionalMessageUpdateResult
import java.nio.file.Path
import kotlinx.coroutines.CancellationException

internal class DesktopRepairAdmissionException(val code: String) : IllegalStateException(code)

/** Ordinary source mutation, with the same voice owner and source-fingerprint invalidation as
 * message editing. No derived records or media are rewritten/deleted. Unlike the legacy absence
 * proof, this admits existing memory, RAG, recordings and independent archive snapshots.
 * The model stream owns no data lease. The final bounded proof and repository CAS share one
 * normal lease; pending maintenance refuses immediately while the voice mutex is owned. */
internal class DesktopFormatRepairSourceSafety(
    private val root: Path,
    private val storage: JsonFileStorage,
    private val gate: DesktopDataOperationCoordinator,
    private val tasks: DesktopTaskRuntime,
    private val audio: DesktopAudioMaintenance,
    private val voices: () -> DesktopChatVoiceRuntime,
    private val sourceChanged: (String) -> Unit,
) : DesktopRepairSafetyGate {
    private val paths = DesktopDocumentRagSource(root)
    override suspend fun check(session: ChatSession, message: ChatMessage): DesktopRepairSafety {
        if (!audio.isIdle()) throw DesktopRepairAdmissionException("AUDIO_BUSY")
        return admitted { inspect(session, message) }
    }

    override suspend fun submit(session: ChatSession, message: ChatMessage,
        write: suspend () -> ConditionalMessageUpdateResult): ConditionalMessageUpdateResult? {
        var receipt: ConditionalMessageUpdateResult? = null
        try {
            audio.withMaintenance(false, { gate.isRestartRequired }) {
                voices().mutateMessage(session.id, message.id) {
                    admitted {
                        if (inspect(session, message) == DesktopRepairSafety.VERIFIED_SAFE) {
                            write().also { receipt = it }
                        } else null
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (receipt == null) throw cancelled
        } catch (_: DesktopAudioMaintenanceBusyException) {
            throw DesktopRepairAdmissionException("AUDIO_BUSY")
        }
        // Preserve the repository receipt even if delivery out of a cancellable scope failed.
        if ((receipt as? ConditionalMessageUpdateResult.Success)?.changed == true ||
            receipt is ConditionalMessageUpdateResult.CommitUncertain) sourceChanged(session.id)
        return receipt
    }

    private suspend fun <T> admitted(block: suspend () -> T): T = try {
        gate.withImmediateNormalOperation(block)
    } catch (_: DesktopDataOperationRejectedException) {
        throw DesktopRepairAdmissionException("DATA_MAINTENANCE_BUSY")
    }

    private suspend fun inspect(session: ChatSession, message: ChatMessage): DesktopRepairSafety = try {
        check(DesktopDataRootOwnership.isHeld(root) && tasks.hasRepairDerivedAdmission(session.id, message.id))
        check(message.role == MessageRole.ASSISTANT && message.sessionId == session.id)
        val currentSession = read("chat_sessions", session.id, ChatSession.serializer())
        check(currentSession.id == session.id && currentSession.characterCardId == session.characterCardId)
        val card = read("character_cards", session.characterCardId, CharacterCard.serializer())
        check(card.id == session.characterCardId && card.pendingSpeakerRenameTasks.isEmpty())
        check(read("chat_messages", "${session.id}_${message.id}", ChatMessage.serializer()) == message)
        val index = read("chat_message_indexes", session.id, ChatMessageIndex.serializer())
        check(index.sessionId == session.id && index.entries.map { it.messageId }.distinct().size == index.entries.size)
        check(index.entries.singleOrNull { it.messageId == message.id } == message.toIndexEntry())
        DesktopRepairSafety.VERIFIED_SAFE
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { DesktopRepairSafety.UNKNOWN }

    private suspend fun <T : Any> read(type: String, id: String, serializer: kotlinx.serialization.KSerializer<T>): T =
        when (val value = storage.readEntityBoundedStrict(type, id, serializer,
            validatePath = paths::safeWindowsPath)) {
            is JsonFileStorage.EntityReadResult.Valid -> value.value
            else -> error("消息来源无法严格核对：$type")
        }
}
