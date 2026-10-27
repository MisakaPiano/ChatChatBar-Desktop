package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.rag.*
import com.example.chatbar.domain.prompt.MemoryRagTextClient
import com.example.chatbar.ui.chat.*

internal data class DesktopChatKnowledge(val cards: List<RetrievedKnowledgeCard> = emptyList(), val notice: String? = null)

/** Official split document/memory multi-route ranking with Desktop source revalidation. */
internal class DesktopChatRetrieval(
    private val configuration: suspend () -> EmbeddingConfig?,
    private val settings: suspend () -> AppSettings,
    private val documents: suspend (CharacterCard, DesktopRagEmbeddingIdentity) -> List<VectorChunk>,
    private val memories: suspend (String, Set<String>, DesktopRagEmbeddingIdentity) -> List<VectorChunk>,
    private val embed: suspend (EmbeddingConfig, String) -> List<Float>,
    private val textClient: MemoryRagTextClient,
) {
    suspend fun retrieve(card: CharacterCard, session: ChatSession, user: ChatMessage,
        context: List<ChatMessage>, inputs: DesktopFakeChatInputs, readOnly: Boolean): DesktopChatKnowledge {
        if (inputs.ragInjectionMode == "OFF") return DesktopChatKnowledge()
        if (readOnly || inputs.retrievalModel == null) return DesktopChatKnowledge(notice = "只读预览不执行检索模型请求")
        val prefs = settings()
        if (prefs.docRagTopK <= 0 && prefs.memoryRagTopK <= 0) return DesktopChatKnowledge()
        val config = configuration() ?: return DesktopChatKnowledge(notice = "未配置向量模型，本轮未召回知识")
        val identity = DesktopRagEmbeddingIdentity.from(config)
        val active = context.mapTo(hashSetOf()) { it.id }
        val doc = if (prefs.docRagTopK > 0 && card.customDocuments.any { it.ragStatus == DocumentRagStatus.INDEXED.name })
            documents(card, identity) else emptyList()
        val memory = if (prefs.memoryRagTopK > 0) memories(session.id, active, identity) else emptyList()
        if (doc.isEmpty() && memory.isEmpty()) return DesktopChatKnowledge()
        val credentials = buildRagRetrievalCredentialMessages(context, user.id, user.displayContent)
        val planned = RetrievalPlanner(textClient).plan(user.displayContent, credentials, card.name, inputs.retrievalModel)
        val plan = planned.plan
        val notice = if (plan == null) "检索规划失败，本轮使用当前提问与上条回复检索" else null
        if (plan != null && plan.topic.isEmpty() && plan.queries.isEmpty() && plan.entities.isEmpty()) return DesktopChatKnowledge()
        val query = plan?.toRagQuery(user.displayContent, credentials) ?: buildRagQuery(user.displayContent, credentials)
        val vector = embed(config, query)
        check(vector.size == identity.dimensions && vector.all(Float::isFinite) && vector.any { it != 0f }) { "查询向量无效" }
        // No stale result may survive an edit/config change while either request was in flight.
        check(configuration() == config) { "向量模型配置已变化，请重试" }
        if (doc.isNotEmpty()) check(doc == documents(card, identity)) { "文档索引已变化，请重试" }
        if (memory.isNotEmpty()) check(memory == memories(session.id, active, identity)) { "历史索引或消息已变化，请重试" }
        val docMatches = rankChunksByMultiRoute(doc, vector, query, routeCandidateLimit(prefs.docRagTopK, doc.size))
            .filter { it.vectorScore >= prefs.docRagSimilarityThreshold || it.lexicalScore >= DOCUMENT_LEXICAL_ACCEPT_THRESHOLD }
            .withSourceDiversity(prefs.docRagTopK)
        val memoryMatches = rankChunksByMultiRoute(memory, vector, query, routeCandidateLimit(prefs.memoryRagTopK, memory.size))
            .filter { it.vectorScore >= prefs.memoryRagSimilarityThreshold || it.lexicalScore >= MEMORY_LEXICAL_ACCEPT_THRESHOLD }
            .take(prefs.memoryRagTopK.coerceAtLeast(0))
        return DesktopChatKnowledge((docMatches + memoryMatches).map { RetrievedKnowledgeCard.fromChunk(it.chunk) }, notice)
    }
}
