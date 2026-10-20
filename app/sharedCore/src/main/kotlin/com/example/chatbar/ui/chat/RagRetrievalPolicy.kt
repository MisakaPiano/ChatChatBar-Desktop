package com.example.chatbar.ui.chat

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.rag.*
import java.util.Locale

fun VectorChunk.messageIds(): Set<String> {
    val metadataIds = metadata["messageIds"]
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    return (metadataIds + listOfNotNull(messageId)).toSet()
}

const val DOCUMENT_LEXICAL_ACCEPT_THRESHOLD = 0.24f
const val MEMORY_LEXICAL_ACCEPT_THRESHOLD = 0.18f
const val RRF_K = 60f

data class DocumentRank(
    val chunk: VectorChunk,
    val vectorScore: Float,
    val lexicalScore: Float,
    val combinedScore: Float,
    val vectorRank: Int? = null,
    val lexicalRank: Int? = null,
    val rrfScore: Float = 0f
)

fun routeCandidateLimit(topK: Int, totalSize: Int): Int {
    if (totalSize <= 0) return 0
    return maxOf(topK * 6, 30).coerceAtMost(totalSize)
}

fun rankChunksByMultiRoute(
    chunks: List<VectorChunk>,
    queryEmbedding: List<Float>,
    ragQuery: String,
    routeLimit: Int
): List<DocumentRank> {
    if (chunks.isEmpty() || routeLimit <= 0) return emptyList()

    val vectorScores = chunks.associateWith { chunk ->
        VectorSearchEngine().cosineSimilarity(queryEmbedding, chunk.embedding)
    }
    val lexicalScores = chunks.associateWith { chunk -> chunk.lexicalScore(ragQuery) }

    val vectorRanks = vectorScores.entries
        .sortedByDescending { it.value }
        .take(routeLimit)
        .mapIndexed { index, entry -> entry.key.id to index + 1 }
        .toMap()

    val lexicalRanks = lexicalScores.entries
        .filter { it.value > 0f }
        .sortedByDescending { it.value }
        .take(routeLimit)
        .mapIndexed { index, entry -> entry.key.id to index + 1 }
        .toMap()

    val chunksById = chunks.associateBy { it.id }
    val candidateIds = (vectorRanks.keys + lexicalRanks.keys).distinct()
    return candidateIds.mapNotNull { id ->
        val chunk = chunksById[id] ?: return@mapNotNull null
        val vectorRank = vectorRanks[id]
        val lexicalRank = lexicalRanks[id]
        val vectorScore = vectorScores[chunk] ?: 0f
        val lexicalScore = lexicalScores[chunk] ?: 0f
        DocumentRank(
            chunk = chunk,
            vectorScore = vectorScore,
            lexicalScore = lexicalScore,
            combinedScore = vectorScore + lexicalScore,
            vectorRank = vectorRank,
            lexicalRank = lexicalRank,
            rrfScore = vectorRank.rrfScore() + lexicalRank.rrfScore()
        )
    }.sortedWith(
        compareByDescending<DocumentRank> { it.rrfScore }
            .thenByDescending { it.combinedScore }
    )
}

fun Int?.rrfScore(): Float {
    return this?.let { 1f / (RRF_K + it) } ?: 0f
}

fun List<DocumentRank>.withSourceDiversity(topK: Int): List<DocumentRank> {
    if (topK <= 0 || isEmpty()) return emptyList()
    val sourceCap = maxOf(1, (topK + 1) / 2)
    val selected = mutableListOf<DocumentRank>()
    val sourceCounts = mutableMapOf<String, Int>()

    for (rank in this) {
        val source = rank.chunk.sourceDiversityKey()
        val count = sourceCounts[source] ?: 0
        if (count < sourceCap) {
            selected += rank
            sourceCounts[source] = count + 1
            if (selected.size >= topK) return selected
        }
    }

    for (rank in this) {
        if (rank !in selected) {
            selected += rank
            if (selected.size >= topK) break
        }
    }
    return selected
}

fun VectorChunk.sourceDiversityKey(): String {
    return metadata["sourceLabel"]
        ?.substringBefore(" > ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: metadata["fileName"]?.trim()?.takeIf { it.isNotBlank() }
        ?: metadata["originalDocId"]?.trim()?.takeIf { it.isNotBlank() }
        ?: sourceId
}

fun buildRagRetrievalCredentialMessages(
    contextMsgs: List<ChatMessage>,
    currentUserMessageId: String? = null,
    currentUserContent: String = ""
): List<ChatMessage> {
    val lastAssistant = contextMsgs.asReversed().firstOrNull { msg ->
        msg.role == MessageRole.ASSISTANT &&
            msg.id != currentUserMessageId &&
            msg.displayContent.isNotBlank() &&
            msg.displayContent != currentUserContent
    }
    return listOfNotNull(lastAssistant)
}

fun buildRagQuery(currentUserContent: String, contextMsgs: List<ChatMessage>): String {
    val lastAssistant = contextMsgs
        .lastOrNull { it.role == MessageRole.ASSISTANT && it.displayContent.isNotBlank() }

    return buildString {
        appendLine("Current user message:")
        appendLine(currentUserContent.trim().take(800))
        if (lastAssistant != null) {
            appendLine()
            appendLine("Last assistant reply:")
            val text = lastAssistant.displayContent.replace(Regex("\\s+"), " ").trim().take(600)
            if (text.isNotBlank()) {
                appendLine("assistant: $text")
            }
        }
    }.trim().take(2400)
}

fun RetrievalPlan.toRagQuery(
    currentUserContent: String,
    contextMsgs: List<ChatMessage>
): String {
    val plannedTopics = topic
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    val plannedQueries = queries
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    val plannedEntities = entities
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    return buildString {
        if (plannedTopics.isNotEmpty()) {
            appendLine("Topic: ${plannedTopics.joinToString(", ")}")
        }
        if (plannedEntities.isNotEmpty()) {
            appendLine("Entities: ${plannedEntities.joinToString(", ")}")
        }
        if (plannedQueries.isNotEmpty()) {
            appendLine("Queries:")
            plannedQueries.take(8).forEach { appendLine(it) }
        }
        if (isBlank()) {
            appendLine(buildRagQuery(currentUserContent, contextMsgs))
        }
    }.trim().take(3200)
}

fun RetrievalPlan.toDebugLog(): String {
    return buildString {
        appendLine("--- Retrieval Planner ---")
        appendLine("topic=$topic")
        appendLine("queries=$queries")
        appendLine("entities=$entities")
        appendLine("should_recall=$shouldRecall")
    }.trim()
}

fun VectorChunk.contentSourceLabel(): String? {
    val firstLine = content.lineSequence().firstOrNull()?.trim().orEmpty()
    return firstLine
        .removePrefix("【来源】")
        .takeIf { it != firstLine && it.isNotBlank() }
}

fun VectorChunk.hasMismatchedSourceLabel(): Boolean {
    val contentSource = contentSourceLabel() ?: return false
    val contentFile = contentSource.substringBefore(" > ").trim()
    val metadataFile = metadata["fileName"]?.trim()
    val metadataSource = metadata["sourceLabel"]?.substringBefore(" > ")?.trim()
    return listOfNotNull(metadataFile, metadataSource).any { it.isNotBlank() && it != contentFile }
}

fun VectorChunk.lexicalScore(query: String): Float {
    val tokens = query.searchTokens()
    if (tokens.isEmpty()) return 0f

    val haystack = buildString {
        append(content)
        append('\n')
        metadata.values.forEach {
            append(it)
            append('\n')
        }
    }.lowercase(Locale.ROOT)

    var score = 0f
    for (token in tokens) {
        if (haystack.contains(token)) {
            score += when {
                token.length >= 5 -> 0.34f
                token.length == 4 -> 0.26f
                token.length == 3 -> 0.16f
                else -> 0.08f
            }
        }
    }
    return score.coerceAtMost(0.72f)
}

fun String.searchTokens(): Set<String> {
    val normalized = lowercase(Locale.ROOT)
    val tokens = linkedSetOf<String>()

    Regex("[a-z0-9_!！?？-]{2,}").findAll(normalized).forEach { match ->
        tokens.add(match.value)
    }

    Regex("\\p{IsHan}+").findAll(normalized).forEach { match ->
        val run = match.value
        val maxGram = minOf(6, run.length)
        for (size in 2..maxGram) {
            for (start in 0..run.length - size) {
                val token = run.substring(start, start + size)
                if (token !in RAG_LEXICAL_STOPWORDS) {
                    tokens.add(token)
                }
            }
        }
    }

    return tokens.take(240).toSet()
}

private val RAG_LEXICAL_STOPWORDS = setOf(
    "当前", "用户", "消息", "最近", "上下", "文中", "什么", "还有", "值得", "注意",
    "一下", "一个", "这个", "那个", "哪些", "怎么", "如何", "我们", "你们",
    "他们", "她们", "关于", "提到", "讨论", "对话", "内容", "相关"
)
