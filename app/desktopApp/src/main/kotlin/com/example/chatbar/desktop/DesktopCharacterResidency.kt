package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.chat.CharacterResidencyPolicy
import com.example.chatbar.domain.chat.SessionCharacterParticipationPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class DesktopResidencyNotice(val sessionId: String, val messageId: String, val text: String)

/** Invoked by the existing completed-chat task only. Loading or scrolling cannot enable entries. */
internal class DesktopCharacterResidency(
    private val chats: ChatRepository,
    private val characters: CharacterRepository,
    private val gate: AppDataOperationGate,
    private val mutate: suspend (String, suspend () -> Unit) -> Unit,
) {
    private val mutableNotices = MutableStateFlow<Map<String, DesktopResidencyNotice>>(emptyMap())
    val notices = mutableNotices.asStateFlow()
    fun dismiss(expected: DesktopResidencyNotice) {
        mutableNotices.update { if (it[expected.sessionId] == expected) it - expected.sessionId else it }
    }
    fun reportFailure(sessionId: String, messageId: String) {
        val notice = DesktopResidencyNotice(sessionId, messageId, "人物驻场更新未完成，请检查会话人物设置")
        mutableNotices.update { it + (sessionId to notice) }
    }

    suspend fun completed(result: DesktopRealChatResult, stopped: () -> Boolean): String? {
        if (result.failureMessage != null || stopped() || result.assistant.role != MessageRole.ASSISTANT) return null
        val reply = result.assistant
        var notice: DesktopResidencyNotice? = null
        mutate(reply.sessionId) {
            gate.withNormalOperation {
                if (stopped()) return@withNormalOperation
                val current = chats.getMessage(reply.id, reply.sessionId) ?: return@withNormalOperation
                // Only this retained version is evidence; a stale completion must never undo a later switch.
                if (current != reply) return@withNormalOperation
                val session = chats.getSession(reply.sessionId) ?: return@withNormalOperation
                val card = characters.getById(session.characterCardId) ?: return@withNormalOperation
                val timeline = chats.getMessages(session.id)
                // Regenerating an older reply is not a fresh appearance in the latest dialogue.
                // Its display projection may change, but it cannot undo today's manual switch.
                if (timeline.filter { it.role == MessageRole.ASSISTANT }.maxWithOrNull(
                        com.example.chatbar.data.local.entity.ChatMessage.TimelineComparator)?.id != current.id) return@withNormalOperation
                val present = CharacterResidencyPolicy.presentPersonIds(card, current)
                val recent = CharacterResidencyPolicy.recent(card, timeline)
                val prior = CharacterResidencyPolicy.recent(card, timeline.filterNot { it.id == reply.id })
                val next = SessionCharacterParticipationPolicy.afterReply(session, card, present, recent.absentForTwoTurns)
                if (next != session) chats.saveSessionSettingsDraft(session, next)
                val returned = card.characters.filter { person -> person.id in present &&
                    (person.id in prior.absentForTwoTurns ||
                        (!SessionCharacterParticipationPolicy.isEnabled(session, card, person.id) &&
                            SessionCharacterParticipationPolicy.isEnabled(next, card, person.id))) }
                if (returned.isNotEmpty()) {
                    val names = returned.joinToString("、") { person ->
                        person.name + if (!SessionCharacterParticipationPolicy.cardAllows(card, person.id)) "（卡内关闭，条目保持停用）" else ""
                    }
                    val restored = returned.filter { !SessionCharacterParticipationPolicy.isEnabled(session, card, it.id) &&
                        SessionCharacterParticipationPolicy.isEnabled(next, card, it.id) }
                    notice = DesktopResidencyNotice(session.id, reply.id, "$names 已重新驻场" +
                        if (restored.isNotEmpty()) "；${restored.joinToString("、") { it.name }}的人物条目已启用" else "")
                }
            }
        }
        notice?.let { value -> mutableNotices.update { it + (value.sessionId to value) } }
        return null
    }
}
