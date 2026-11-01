package com.example.chatbar.domain.worldbook

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.domain.card.extractJsonObjectCandidates
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.prompt.WorldBookAiPromptAuthority
import com.example.chatbar.domain.search.CharacterReferenceDocument
import com.example.chatbar.domain.search.CharacterResearchOptions
import com.example.chatbar.domain.search.ResearchBrief
import com.example.chatbar.domain.search.ResearchDebugSnapshot
import com.example.chatbar.domain.search.WorldBookResearchPort
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

@Serializable
private data class CreateBatchResponse(
    val entries: List<CreateEntryResponse> = emptyList(),
    val hasMore: Boolean = false
)


@Serializable
private data class FillBatchResponse(
    val entries: List<FillEntryResponse> = emptyList()
)


@OptIn(ExperimentalSerializationApi::class)
class WorldBookAiCore(
    private val defaultModel: suspend () -> ModelConfig?,
    private val gateway: WorldBookAiGateway,
    private val researchService: WorldBookResearchPort,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
        allowTrailingComma = true
    }
) {
    suspend fun createEntriesStreaming(
        request: String,
        book: WorldBook,
        modelOverride: ModelConfig? = null,
        researchOptions: CharacterResearchOptions = CharacterResearchOptions(),
        referenceDocument: CharacterReferenceDocument? = null,
        resumeFrom: WorldBookCreateCheckpoint? = null,
        onCheckpoint: (WorldBookCreateCheckpoint) -> Unit = {},
        onStatus: (String) -> Unit = {},
        onRawText: (String) -> Unit = {},
        onResearchDebug: (ResearchDebugSnapshot) -> Unit = {},
        onVisibleOutput: (String, String, String) -> Unit = { _, _, _ -> }
    ): WorldBookCreateResult = withContext(Dispatchers.IO) {
        require(request.isNotBlank() || referenceDocument != null || researchOptions.urls.isNotEmpty()) {
            "请输入世界书需求，或提供参考文档、网址"
        }
        val model = modelOverride ?: defaultModel()
            ?: error("未配置可用对话模型")
        var checkpoint = resumeFrom ?: WorldBookCreateCheckpoint()
        var candidates = checkpoint.candidates
        var researchDebug = checkpoint.researchDebug
        var researchSession = checkpoint.researchSession
        var rawOutputs = checkpoint.rawOutputs
        val recordVisibleOutput: (String, String, String) -> Unit = { key, title, text ->
            rawOutputs = rawOutputs.upsertRawOutput(key, title, text)
            checkpoint = checkpoint.copy(rawOutputs = rawOutputs)
            onCheckpoint(checkpoint)
            onVisibleOutput(key, title, text)
        }

        val research = researchService.research(
            request = request,
            book = book,
            targets = emptyList(),
            model = model,
            options = researchOptions,
            referenceDocument = referenceDocument,
            session = researchSession,
            resumeFrom = researchDebug,
            onSession = { session ->
                researchSession = session
                checkpoint = checkpoint.copy(researchSession = session)
                onCheckpoint(checkpoint)
            },
            onDebug = { debug ->
                researchDebug = debug
                onResearchDebug(debug)
                checkpoint = checkpoint.copy(researchDebug = debug, researchSession = researchSession)
                onCheckpoint(checkpoint)
            },
            onVisibleOutput = recordVisibleOutput,
            onStatus = onStatus
        )
        researchDebug = research.debug.takeIf(ResearchDebugSnapshot::hasContent)
        researchSession = research.session
        checkpoint = checkpoint.copy(researchDebug = researchDebug, researchSession = researchSession)
        onCheckpoint(checkpoint)

        var hasMore = true
        var warning = checkpoint.warning
        while (hasMore && candidates.size < WORLD_BOOK_AI_CREATE_LIMIT) {
            val batchNumber = checkpoint.batchNumber
            val remaining = WORLD_BOOK_AI_CREATE_LIMIT - candidates.size
            onStatus("正在创建条目第 $batchNumber 批；已完成 ${candidates.size}/$WORLD_BOOK_AI_CREATE_LIMIT")
            val raw = streamJsonTask(
                operation = WorldBookAiOperation.CREATE,
                systemPrompt = WorldBookAiPromptAuthority.WORLD_BOOK_CREATE_ENTRIES_SYSTEM_PROMPT,
                userPrompt = buildCreatePayload(request, book, candidates, research.brief, remaining),
                model = model,
                outputKey = "create-batch-$batchNumber",
                outputTitle = "创建条目第 $batchNumber 批输出",
                onStatus = onStatus,
                onRawText = onRawText,
                onVisibleOutput = recordVisibleOutput
            )
            val response = parseCreateResponse(raw) ?: repairCreateResponse(
                raw = raw,
                model = model,
                batchNumber = batchNumber,
                onStatus = onStatus,
                onRawText = onRawText,
                onVisibleOutput = recordVisibleOutput
            ) ?: error("创建条目第 $batchNumber 批输出无法解析")

            val newCandidates = constrainCreatedEntries(book, candidates, response.entries, remaining)
            candidates = candidates + newCandidates
            val continuation = decideCreateContinuation(
                responseHasMore = response.hasMore,
                newCandidateCount = newCandidates.size,
                totalCandidateCount = candidates.size
            )
            hasMore = continuation.shouldContinue
            if (continuation.warning.isNotBlank()) {
                warning = continuation.warning
                onStatus(warning)
            }
            checkpoint = WorldBookCreateCheckpoint(
                candidates = candidates,
                batchNumber = batchNumber + 1,
                researchDebug = researchDebug,
                researchSession = researchSession,
                rawOutputs = rawOutputs,
                warning = warning
            )
            onCheckpoint(checkpoint)
        }
        onStatus("条目创建候选已完成：${candidates.size} 条")
        WorldBookCreateResult(candidates, checkpoint, warning)
    }

    suspend fun fillEmptyContentsStreaming(
        request: String,
        book: WorldBook,
        targets: List<WorldBookEntry>,
        modelOverride: ModelConfig? = null,
        researchOptions: CharacterResearchOptions = CharacterResearchOptions(),
        referenceDocument: CharacterReferenceDocument? = null,
        resumeFrom: WorldBookFillCheckpoint? = null,
        onCheckpoint: (WorldBookFillCheckpoint) -> Unit = {},
        onStatus: (String) -> Unit = {},
        onRawText: (String) -> Unit = {},
        onResearchDebug: (ResearchDebugSnapshot) -> Unit = {},
        onVisibleOutput: (String, String, String) -> Unit = { _, _, _ -> }
    ): WorldBookFillResult = withContext(Dispatchers.IO) {
        val frozenTargets = targets.filter { it.content.isBlank() }
        require(frozenTargets.isNotEmpty()) { "当前没有正文为空的世界书条目" }
        val model = modelOverride ?: defaultModel()
            ?: error("未配置可用对话模型")
        var checkpoint = resumeFrom ?: WorldBookFillCheckpoint()
        var candidates = checkpoint.candidates
        var targetIndex = checkpoint.nextTargetIndex.coerceIn(0, frozenTargets.size)
        var researchSession = checkpoint.researchSession
        var rawOutputs = checkpoint.rawOutputs
        val recordVisibleOutput: (String, String, String) -> Unit = { key, title, text ->
            rawOutputs = rawOutputs.upsertRawOutput(key, title, text)
            checkpoint = checkpoint.copy(rawOutputs = rawOutputs)
            onCheckpoint(checkpoint)
            onVisibleOutput(key, title, text)
        }

        while (targetIndex < frozenTargets.size) {
            val batchNumber = checkpoint.batchNumber
            val batch = frozenTargets.subList(targetIndex, minOf(targetIndex + WORLD_BOOK_AI_BATCH_SIZE, frozenTargets.size))
            var activeDebug = checkpoint.activeResearchDebug
            onStatus("正在填充第 $batchNumber 批；${targetIndex}/${frozenTargets.size} 条已完成")
            val research = researchService.research(
                request = request,
                book = book,
                targets = batch,
                model = model,
                options = researchOptions,
                referenceDocument = referenceDocument,
                session = researchSession,
                resumeFrom = activeDebug,
                onSession = { session ->
                    researchSession = session
                    checkpoint = checkpoint.copy(researchSession = session)
                    onCheckpoint(checkpoint)
                },
                onDebug = { debug ->
                    activeDebug = debug
                    onResearchDebug(debug)
                    checkpoint = checkpoint.copy(
                        activeResearchDebug = debug,
                        researchSession = researchSession
                    )
                    onCheckpoint(checkpoint)
                },
                onVisibleOutput = { key, title, text ->
                    recordVisibleOutput("batch-$batchNumber-$key", "第 $batchNumber 批 · $title", text)
                },
                onStatus = onStatus
            )
            val raw = streamJsonTask(
                operation = WorldBookAiOperation.FILL,
                systemPrompt = WorldBookAiPromptAuthority.WORLD_BOOK_FILL_CONTENT_SYSTEM_PROMPT,
                userPrompt = buildFillPayload(request, book, batch, research.brief),
                model = model,
                outputKey = "fill-batch-$batchNumber",
                outputTitle = "填充内容第 $batchNumber 批输出",
                onStatus = onStatus,
                onRawText = onRawText,
                onVisibleOutput = recordVisibleOutput
            )
            val firstParse = parseFillResponse(raw, batch)
            firstParse?.rejections?.forEach(onStatus)
            val parsed = firstParse?.candidates?.takeIf { it.size == batch.size } ?: repairFillResponse(
                raw = raw,
                targets = batch,
                model = model,
                batchNumber = batchNumber,
                onStatus = onStatus,
                onRawText = onRawText,
                onVisibleOutput = recordVisibleOutput
            ) ?: error("填充内容第 $batchNumber 批输出无法解析或缺少目标条目")
            candidates = candidates + parsed
            targetIndex += batch.size
            checkpoint = WorldBookFillCheckpoint(
                candidates = candidates,
                nextTargetIndex = targetIndex,
                batchNumber = batchNumber + 1,
                activeResearchDebug = null,
                researchSession = researchSession,
                rawOutputs = rawOutputs
            )
            onCheckpoint(checkpoint)
        }
        onStatus("条目内容候选已完成：${candidates.size} 条")
        WorldBookFillResult(candidates, checkpoint)
    }

    private suspend fun streamJsonTask(
        operation: WorldBookAiOperation,
        stage: WorldBookAiStage = WorldBookAiStage.GENERATE,
        systemPrompt: String,
        userPrompt: String,
        model: ModelConfig,
        outputKey: String,
        outputTitle: String,
        onStatus: (String) -> Unit,
        onRawText: (String) -> Unit,
        onVisibleOutput: (String, String, String) -> Unit
    ): String {
        val visible = StringBuilder()
        return gateway.complete(
            operation = operation,
            stage = stage,
            messages = listOf(
                ChatApiMessage.text("system", systemPrompt.trimIndent()),
                ChatApiMessage.text("user", userPrompt)
            ),
            modelConfig = model,
            onDelta = { chunk ->
                visible.append(chunk)
                val text = visible.toString()
                onRawText(text)
                onVisibleOutput(outputKey, outputTitle, text)
            },
            onReasoningDelta = { onStatus("AI 正在处理（思考中）") }
        )
    }

    private suspend fun repairCreateResponse(
        raw: String,
        model: ModelConfig,
        batchNumber: Int,
        onStatus: (String) -> Unit,
        onRawText: (String) -> Unit,
        onVisibleOutput: (String, String, String) -> Unit
    ): CreateBatchResponse? {
        onStatus("正在修复创建条目第 $batchNumber 批 JSON")
        val repaired = streamJsonTask(
            WorldBookAiOperation.CREATE, WorldBookAiStage.REPAIR,
            WorldBookAiPromptAuthority.WORLD_BOOK_CREATE_ENTRIES_REPAIR_PROMPT,
            raw,
            model,
            "create-repair-$batchNumber",
            "创建条目第 $batchNumber 批修复输出",
            onStatus,
            onRawText,
            onVisibleOutput
        )
        return parseCreateResponse(repaired)
    }

    private suspend fun repairFillResponse(
        raw: String,
        targets: List<WorldBookEntry>,
        model: ModelConfig,
        batchNumber: Int,
        onStatus: (String) -> Unit,
        onRawText: (String) -> Unit,
        onVisibleOutput: (String, String, String) -> Unit
    ): List<WorldBookContentCandidate>? {
        onStatus("正在修复填充内容第 $batchNumber 批 JSON")
        val repairInput = buildJsonObject {
            put("targets", buildJsonArray {
                targets.forEach { target ->
                    add(buildJsonObject {
                        put("targetId", target.id)
                        put("name", target.name)
                        put("keys", json.encodeToJsonElement(target.keys))
                    })
                }
            })
            put("text", raw)
        }.toString()
        val repaired = streamJsonTask(
            WorldBookAiOperation.FILL, WorldBookAiStage.REPAIR,
            WorldBookAiPromptAuthority.WORLD_BOOK_FILL_CONTENT_REPAIR_PROMPT,
            repairInput,
            model,
            "fill-repair-$batchNumber",
            "填充内容第 $batchNumber 批修复输出",
            onStatus,
            onRawText,
            onVisibleOutput
        )
        val parsed = parseFillResponse(repaired, targets)
        parsed?.rejections?.forEach(onStatus)
        return parsed?.candidates?.takeIf { it.size == targets.size }
    }

    private fun buildCreatePayload(
        request: String,
        book: WorldBook,
        candidates: List<WorldBookEntryPlanCandidate>,
        research: ResearchBrief?,
        remaining: Int
    ): String = buildJsonObject {
        put("request", request.trim())
        put("currentBook", book.promptSummary())
        put("existingAndPlanned", buildJsonArray {
            book.entries.forEach { entry -> add(entry.planSummaryJson()) }
            candidates.forEach { candidate ->
                add(buildJsonObject {
                    put("name", candidate.name)
                    put("keys", json.encodeToJsonElement(candidate.keys))
                })
            }
        })
        research?.takeIf(ResearchBrief::hasContent)?.let {
            put("externalResearch", it.toWorldBookPromptJson())
        }
        put("batchLimit", minOf(WORLD_BOOK_AI_BATCH_SIZE, remaining))
        put("remainingLimit", remaining)
    }.toString()

    private fun buildFillPayload(
        request: String,
        book: WorldBook,
        targets: List<WorldBookEntry>,
        research: ResearchBrief?
    ): String = buildJsonObject {
        put("request", request.trim())
        put("currentBook", book.promptSummary())
        put("targets", buildJsonArray {
            targets.forEach { entry ->
                add(buildJsonObject {
                    put("targetId", entry.id)
                    put("name", entry.name)
                    put("keys", json.encodeToJsonElement(entry.keys))
                })
            }
        })
        research?.takeIf(ResearchBrief::hasContent)?.let {
            put("externalResearch", it.toWorldBookPromptJson())
        }
    }.toString()

    private fun ResearchBrief.toWorldBookPromptJson() = buildJsonObject {
        put("facts", json.encodeToJsonElement(facts))
        put("notes", json.encodeToJsonElement(notes))
    }

    private fun parseCreateResponse(raw: String): CreateBatchResponse? =
        raw.extractJsonObjectCandidates().firstNotNullOfOrNull { candidate ->
            runCatching { json.decodeFromString(CreateBatchResponse.serializer(), candidate) }.getOrNull()
        }

    private fun parseFillResponse(
        raw: String,
        targets: List<WorldBookEntry>
    ): FillConstraintResult? {
        val response = raw.extractJsonObjectCandidates().firstNotNullOfOrNull { candidate ->
            runCatching { json.decodeFromString(FillBatchResponse.serializer(), candidate) }.getOrNull()
        } ?: return null
        return constrainFilledEntries(targets, response.entries)
    }

    companion object {
        internal fun constrainCreatedEntries(book: WorldBook, completed: List<WorldBookEntryPlanCandidate>,
            rawEntries: List<CreateEntryResponse>, remaining: Int,
            idFactory: () -> String = { UUID.randomUUID().toString() }): List<WorldBookEntryPlanCandidate> =
            WorldBookAiPolicy.constrainCreatedEntries(book, completed, rawEntries, remaining, idFactory)

        internal fun constrainFilledEntries(targets: List<WorldBookEntry>, rawEntries: List<FillEntryResponse>): FillConstraintResult =
            WorldBookAiPolicy.constrainFilledEntries(targets, rawEntries)

        internal fun decideCreateContinuation(responseHasMore: Boolean, newCandidateCount: Int,
            totalCandidateCount: Int): CreateContinuationDecision =
            WorldBookAiPolicy.decideCreateContinuation(responseHasMore, newCandidateCount, totalCandidateCount)

        fun applyCreatedEntries(current: List<WorldBookEntry>, candidates: List<WorldBookEntryPlanCandidate>,
            selectedIds: Set<String>, idFactory: () -> String = { UUID.randomUUID().toString() }): List<WorldBookEntry> =
            WorldBookAiPolicy.applyCreatedEntries(current, candidates, selectedIds, idFactory)

        fun applyFilledContents(current: List<WorldBookEntry>, candidates: List<WorldBookContentCandidate>,
            selectedIds: Set<String>): List<WorldBookEntry> =
            WorldBookAiPolicy.applyFilledContents(current, candidates, selectedIds)
    }
}

private fun List<WorldBookAiRawOutput>.upsertRawOutput(
    key: String,
    title: String,
    text: String
): List<WorldBookAiRawOutput> {
    val replacement = WorldBookAiRawOutput(key, title, text)
    val index = indexOfFirst { it.key == key }
    if (index < 0) return this + replacement
    return toMutableList().also { it[index] = replacement }
}

private fun WorldBook.promptSummary(): String = WorldBookAiPromptAuthority.worldBookPromptSummary(this)

private fun WorldBookEntry.planSummaryJson() = buildJsonObject {
    put("name", name)
    put("keys", buildJsonArray { keys.forEach { add(JsonPrimitive(it)) } })
}
