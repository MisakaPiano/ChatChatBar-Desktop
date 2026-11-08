package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.moment.*
import com.example.chatbar.domain.image.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private class DesktopMomentWorkEvidence(val workId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DesktopMomentWorkEvidence>
    var phase: DesktopWorkPhase? = null
    var skipped = false
}

internal fun interface DesktopMomentGenerator {
    suspend fun generate(card: CharacterCard, session: ChatSession, messages: List<ChatMessage>, latest: MomentPost?,
        settings: AppSettings, task: MomentTask, checkpoint: String,
        saveCheckpoint: suspend (String) -> Unit, progress: (MomentGenerationProgress) -> Unit,
        registerImageCleanup: (String) -> Unit): MomentGenerationResult
}

internal data class DesktopMomentLink(val id: String?, val error: String? = null)

internal data class DesktopMomentAlbumSelection(
    val filter: MomentAlbumFilter = MomentAlbumFilter(),
    val granularity: MomentAlbumDateGranularity = MomentAlbumDateGranularity.DAY,
    val selectedDate: java.time.LocalDate? = null,
)

internal data class DesktopMomentsState(
    val enabled: Boolean = false, val autoImages: Boolean = true, val scheduling: Boolean = false,
    val minHours: Int = 2, val maxHours: Int = 13, val posts: List<MomentPost> = emptyList(),
    val tasks: List<MomentTask> = emptyList(), val cards: List<CharacterCard> = emptyList(),
    val busy: Boolean = false, val progress: String = "", val notice: String = "", val error: String? = null,
    val viewedThrough: Long = 0L,
    val streamedText: String = "",
    val debug: MomentDebugGenerationResult? = null,
    val busyTargets: Set<String> = emptySet(),
    val albumSelection: DesktopMomentAlbumSelection = DesktopMomentAlbumSelection(),
) {
    val unread: Boolean get() = posts.any { !it.isPlaceholder && it.generatedAt > viewedThrough }
}

/** Local consent is root-bound; imported enabled/PENDING/RUNNING alone never start AI. */
internal class DesktopMomentsController(
    private val store: DesktopMomentStore,
    private val settings: SettingsRepository,
    private val characters: CharacterRepository,
    private val chats: ChatRepository,
    private val gate: DesktopDataOperationCoordinator,
    private val tasksRuntime: DesktopTaskRuntime,
    private val generator: DesktopMomentGenerator,
    private val finishImageCleanup: suspend (String) -> Unit = {},
    private val admissionStore: DesktopMomentAdmissionStore? = null,
    val resources: DesktopCharacterResourceStore? = null,
    val picker: DesktopFilePicker = UnconfiguredDesktopFilePicker,
    val diagnostics: DesktopTransportDiagnosticsOwner? = null,
    private val recoverImages: suspend () -> Boolean = { true },
    private val imageGenerator: DesktopMomentImageGenerator? = null,
    private val debugGenerator: DesktopMomentDebugGenerator? = null,
    private val prepareImageCleanup: (String) -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMillis: Long = 60_000,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(DesktopMomentsState())
    val state = mutableState.asStateFlow()
    fun updateAlbumSelection(change: (DesktopMomentAlbumSelection) -> DesktopMomentAlbumSelection) {
        mutableState.update { it.copy(albumSelection = change(it.albumSelection)) }
    }
    private val lock = Any()
    private val admissionMutex = Mutex()
    private var accepting = true
    private var stopRevision = 0L
    private val workers = mutableMapOf<String, Job>()
    private val workTargets = mutableMapOf<String, Set<String>>()
    private var activeWorkId: String? = null
    private val mutableWork = MutableStateFlow<List<DesktopWorkRow>>(emptyList())
    val workRows = mutableWork.asStateFlow()
    private var scheduler: Job? = null
    private val admitted = mutableMapOf<String, MomentTask>()
    private val preparation = MomentSchedulePreparation(characters::getAll, chats::getAllSessions,
        store::posts, store::tasks, { task ->
            store.saveTask(task)
            synchronized(lock) { admitted[task.id] = task }
            persistAdmission(true)
        })

    init { scope.launch {
        refresh()
        try {
            if (!recoverImages()) mutableState.update { it.copy(error = "朋友圈图片清理尚未完成；原文件和回执保留") }
            restoreLocalScheduling()
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.update { it.copy(error = "本机朋友圈调度授权未通过；请核对后手动恢复，未自动生成") } }
    } }

    /** Navigation uses the retained post identity, never a same-name or recent-session fallback. */
    suspend fun linkedTarget(postId: String, cardSettings: Boolean): DesktopMomentLink = gate.withNormalOperation {
        val post = store.posts().singleOrNull { it.id == postId }
            ?: return@withNormalOperation DesktopMomentLink(null, "该动态已删除或不可用")
        if (cardSettings) {
            if (characters.getById(post.characterCardId) == null) DesktopMomentLink(null, "所属角色卡已删除或不可用")
            else DesktopMomentLink(post.characterCardId)
        } else {
            val session = chats.getSession(post.sessionId)
            if (session == null || session.characterCardId != post.characterCardId)
                DesktopMomentLink(null, "该动态绑定的会话已删除、重新关联或不可用")
            else DesktopMomentLink(post.sessionId)
        }
    }

    fun navigationUnavailable() { mutableState.update { it.copy(notice = "绑定对象已删除、不可用或当前无法切换；未新建会话") } }

    suspend fun refresh() {
        try {
            gate.withNormalOperation {
                settings.initialize(); characters.initialize(); chats.initialize()
                val prefs = settings.getAppSettings()
                val posts = store.posts(); val tasks = store.tasks(); val cards = characters.getAll()
                mutableState.update { it.copy(enabled = prefs.momentsEnabled, autoImages = prefs.momentsImagesEnabled,
                    minHours = prefs.momentsMinDelayHours, maxHours = prefs.momentsMaxDelayHours,
                    posts = posts, tasks = tasks, cards = cards, viewedThrough = prefs.lastSeenMomentsAt) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.update { it.copy(error = "朋友圈数据未完整读取；原数据保留，请重试刷新") } }
    }

    suspend fun setEnabled(enabled: Boolean) {
        if (!enabled) pause()
        gate.withNormalOperation { settings.updateAppSettings { it.copy(momentsEnabled = enabled) } }
        refresh()
    }
    suspend fun configure(images: Boolean, minHours: Int, maxHours: Int) {
        val range = MomentPolicy.normalizedDelayHours(minHours, maxHours)
        pause()
        gate.withNormalOperation {
            val old = settings.getAppSettings()
            settings.updateAppSettings { it.copy(momentsImagesEnabled = images,
                momentsMinDelayHours = range.minHours, momentsMaxDelayHours = range.maxHours) }
            if (old.momentsMinDelayHours != range.minHours || old.momentsMaxDelayHours != range.maxHours)
                store.deletePendingFutureTasks(clock())
        }
        mutableState.update { it.copy(notice = "设置已保存；本机调度保持暂停，点击恢复后重新准备未来计划") }
        refresh()
    }

    /** A click authorizes the exact existing pending snapshots and only future records created by this owner. */
    suspend fun resumeScheduling() = admissionMutex.withLock {
        stopLocalScheduling(persistPause = true)
        gate.withNormalOperation {
            check(settings.getAppSettings().momentsEnabled) { "请先开启朋友圈" }
            val pending = store.tasks().filter { it.status == MomentTaskStatus.PENDING }
            synchronized(lock) { admitted.clear(); pending.forEach { admitted[it.id] = it } }
            persistAdmission(true)
            startScheduler("本机调度已恢复；正常重开保留授权，关闭应用时不生成。中断记录不会自动重试。")
        }
    }

    private suspend fun restoreLocalScheduling() = admissionMutex.withLock {
        val localStore = admissionStore ?: return@withLock
        val local = localStore.load()
        if (!local.active || state.value.error != null || !settings.getAppSettings().momentsEnabled) return@withLock
        val pending = store.tasks().filter { task -> task.status == MomentTaskStatus.PENDING &&
            local.tasks[task.id] == localStore.taskHash(task) }
        synchronized(lock) { admitted.clear(); pending.forEach { admitted[it.id] = it } }
        startScheduler("已恢复此资料目录的本机调度；导入或发生变化的未批准计划保持暂停。")
    }

    private suspend fun persistAdmission(active: Boolean) {
        val local = admissionStore ?: return
        val approved = synchronized(lock) { admitted.values.associate { it.id to local.taskHash(it) } }
        local.save(DesktopMomentAdmission(active, approved))
    }

    private fun startScheduler(notice: String) = synchronized(lock) {
        check(accepting && scheduler?.isActive != true)
        mutableState.update { it.copy(scheduling = true, error = null, notice = notice) }
        scheduler = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (currentCoroutineContext().isActive) { tick(); delay(tickMillis) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = "朋友圈调度已暂停；请核对数据与设置后手动恢复") } }
            finally { mutableState.update { it.copy(scheduling = false) } }
        }.also { it.start() }
    }
    private suspend fun tick() {
        val prefs = settings.getAppSettings()
        check(prefs.momentsEnabled)
        gate.withNormalOperation { preparation.ensureSchedules(clock(), prefs) }
        refresh()
        val due = store.tasks().filter { task -> task.status == MomentTaskStatus.PENDING && task.scheduledAt <= clock() &&
            synchronized(lock) { admitted[task.id] == task } }
        for (task in due) {
            currentCoroutineContext().ensureActive()
            // Preserve the original binding while it exists; only a missing source uses the original fallback.
            val ready = if (chats.getSession(task.sessionId) == null) {
                val fallback = chats.getAllSessions().filter {
                    it.characterCardId == task.characterCardId && MomentPolicy.isRecentlyActive(it.lastMessageTime, clock())
                }.maxByOrNull { it.lastMessageTime ?: it.createdAt }
                if (fallback == null) {
                    store.skipUnstarted(task, "原会话已删除且48小时内无活跃会话")
                    continue
                }
                store.rebindPending(task, fallback.id).also {
                    synchronized(lock) { admitted[it.id] = it }
                    persistAdmission(true)
                }
            } else task
            val job = launchWork(ready) ?: return
            job.join()
        }
    }
    suspend fun pause() = admissionMutex.withLock { stopLocalScheduling(persistPause = true) }
    private suspend fun stopLocalScheduling(persistPause: Boolean) {
        val jobs = synchronized(lock) { (listOfNotNull(scheduler) + workers.values).distinct().also {
            stopRevision++; scheduler = null; admitted.clear()
        } }
        jobs.forEach { it.cancel() }; jobs.forEach { it.join() }
        mutableState.update { it.copy(scheduling = false) }
        if (persistPause) persistAdmission(false)
    }
    private fun markStopRequested(id: String?) {
        if (id != null) mutableWork.update { rows -> rows.map {
            if (it.id == id && it.active) it.copy(stopRequested = true, canStop = false) else it
        } }
    }
    fun stop() { synchronized(lock) {
        val id = activeWorkId ?: workers.keys.firstOrNull() ?: return@synchronized
        workers[id]?.let { it.cancel(); markStopRequested(id) }
    } }
    fun stopTask(id: String): Boolean = synchronized(lock) {
        val job = workers[id]
        if (job == null || job.isCompleted) false
        else { job.cancel(); markStopRequested(id); true }
    }

    suspend fun generate(cardId: String, debug: Boolean = false) {
        val session = chats.getAllSessions().filter { it.characterCardId == cardId }
            .maxByOrNull { it.lastMessageTime ?: it.createdAt } ?: error("请先为该角色卡创建有交流内容的会话")
        launchWork(MomentTask.create(cardId, session.id, clock()), debug = debug)
    }
    suspend fun retry(post: MomentPost) {
        check(post.isPlaceholder)
        val task = store.tasks().singleOrNull { it.postId == post.id }
            ?: MomentTask.create(post.characterCardId, post.sessionId, post.scheduledAt)
        launchWork(task, post)
    }
    suspend fun retryInterruptedTask(task: MomentTask) {
        launchWork(task, recoverMissingPost = true)
    }
    private fun launchWork(task: MomentTask, retry: MomentPost? = null, debug: Boolean = false,
        recoverMissingPost: Boolean = false): Job? =
        launchOwnedWork(task.sessionId, (retry?.id ?: task.postId)?.let { "post:$it" } ?: "card:${task.characterCardId}") { register ->
            withContext(DesktopUsageParent(DesktopUsageContext(DesktopUsageSource.MOMENTS,
                taskId = task.id, sessionId = task.sessionId, cardId = task.characterCardId))) {
                execute(task, retry, register, debug, recoverMissingPost)
            }
        }

    suspend fun generateImage(post: MomentPost, draft: NovelAiImageRegenerationDraft? = null) {
        val service = requireNotNull(imageGenerator) { "朋友圈图片服务不可用" }
        launchOwnedWork(post.sessionId, "post:${post.id}", DesktopUsageContext(DesktopUsageSource.MOMENTS,
            sessionId = post.sessionId, cardId = post.characterCardId, subjectId = post.id)) { register ->
            val original = store.imageSource(post)
            check(DesktopMomentImageVersions.retained(original).size < DesktopMomentImageVersions.MAX_CANDIDATES) {
                "图片版本数量已达上限，请先移除不再需要的版本"
            }
            val prefs = settings.getAppSettings(); check(prefs.momentsEnabled)
            val image = service.generate(original, characters.getById(original.characterCardId),
                chats.getSession(original.sessionId), prefs, draft, register,
                ::reportProgress)
            currentCoroutineContext().ensureActive()
            check(chats.getSession(original.sessionId)?.characterCardId == original.characterCardId &&
                characters.getById(original.characterCardId) != null) { "原会话或角色卡已变化，本次图片不再写入" }
            store.replaceImage(original, image)?.let(register)
            mutableState.update { it.copy(notice = if (original.imagePath.isNullOrBlank()) "动态图片已保存，正文与点赞保留"
                else "新图片版本已保存并切换显示，历史图片版本保留") }
        }
    }

    suspend fun selectImageVersion(post: MomentPost, versionId: String) {
        store.selectImage(post, versionId)
        refresh()
    }

    suspend fun removeImageVersion(post: MomentPost, versionId: String) {
        val receipt = store.removeImage(post, versionId)
        try { if (receipt != null) finishImageCleanup(receipt) } finally { refresh() }
    }

    suspend fun imageDraft(post: MomentPost): NovelAiImageRegenerationDraft = withContext(Dispatchers.IO) {
        val original = store.imageSource(post)
        val reference = requireNotNull(original.imagePath?.takeIf(String::isNotBlank))
        val media = requireNotNull(resources)
        val bytes = media.readScreenshotImageBytes(reference)
        val metadata = original.generatedImageMetadata?.takeIf { it.imagePath == reference }
            ?: if (reference.startsWith("images/")) DesktopImageMetadata.read(media.resolveOwnedReference(reference).toString()) else null
        if (metadata != null) return@withContext metadata.toRegenerationDraft()
        check(original.imagePrompt.isNotBlank()) { "原图没有可复用的Prompt" }
        val pixels = DesktopImageEditing.decode(bytes)
        val preset = when { pixels.width == pixels.height -> NovelAiImageSizePreset.SQUARE
            pixels.width > pixels.height -> NovelAiImageSizePreset.HORIZONTAL
            else -> NovelAiImageSizePreset.PORTRAIT }
        NovelAiPromptPlan(original.imagePrompt, emptyList(), sizePreset = preset).toRegenerationDraft().copy(width = pixels.width, height = pixels.height)
    }

    suspend fun useImageForCard(post: MomentPost, avatar: Boolean) = withContext(Dispatchers.IO) {
        val media = requireNotNull(resources)
        val receipt = gate.withExclusiveMaintenance {
            val source = store.imageSource(post)
            val reference = requireNotNull(source.imagePath?.takeIf(String::isNotBlank))
            media.readScreenshotImageBytes(reference)
            val latest = (characters.readDurable(source.characterCardId) as? com.example.chatbar.data.local.JsonFileStorage.EntityReadResult.Valid)?.value
                ?: error("原角色卡不存在或无法读取")
            val previous = if (avatar) latest.avatar else latest.chatBackground
            val cleanup = previous?.takeIf { it.startsWith("images/") && it != reference }?.let(prepareImageCleanup)
            characters.update(if (avatar) latest.copy(avatar = reference) else latest.copy(chatBackground = reference))
            cleanup
        }
        if (receipt != null) finishImageCleanup(receipt)
        mutableState.update { it.copy(notice = if (avatar) "已设为角色卡头像" else "已设为角色卡背景") }
        refresh()
    }

    private fun launchOwnedWork(sessionId: String, target: String, usageContext: DesktopUsageContext? = null,
        work: suspend ((String) -> Unit) -> Unit): Job? = synchronized(lock) {
        check(accepting)
        if (workTargets.values.any { target in it }) {
            mutableState.update { it.copy(notice = "该角色卡或动态已有任务，请等待或停止对应任务") }; return@synchronized null
        }
        val workId = UUID.randomUUID().toString()
        val openingStopRevision = stopRevision
        workTargets[workId] = setOf(target)
        var outcome = DesktopWorkPhase.COMPLETED
        val evidence = DesktopMomentWorkEvidence(workId)
        mutableWork.update { rows -> (listOf(DesktopWorkRow(workId, DesktopWorkKind.MOMENT, DesktopWorkPhase.RUNNING,
            clock(), target = DesktopWorkTarget(DesktopPrimaryRoute.MOMENTS, sessionId = sessionId, detail = DesktopWorkDetail.MOMENTS_HOME),
            canStop = true, contextLabel = "朋友圈任务（忙时排队）")) + rows).let(::boundedWorkRows) }
        val job = scope.launch(evidence, start = CoroutineStart.LAZY) {
            val imageReceipts = mutableListOf<String>()
            try {
                tasksRuntime.withCardDesign("moments:$target", publishWorkRow = false) {
                    tasksRuntime.withNovelAiWork {
                        synchronized(lock) {
                            if (!accepting || openingStopRevision != stopRevision) throw CancellationException("Moments work stopped before execution")
                            activeWorkId = workId
                        }
                        mutableState.update { it.copy(error = null, progress = "正在准备朋友圈", streamedText = "") }
                        try {
                            gate.withNormalOperation {
                                if (usageContext == null) work { imageReceipts += it }
                                else withContext(DesktopUsageParent(usageContext.copy(taskId = workId))) {
                                    work { imageReceipts += it }
                                }
                            }
                        } finally {
                            withContext(NonCancellable) {
                                for (receipt in imageReceipts) try { finishImageCleanup(receipt) }
                                catch (_: Exception) { outcome = DesktopWorkPhase.UNCONFIRMED; mutableState.update { it.copy(error = "动态记录已保留；图片清理未完成，重开或重试清理时核对") } }
                                refresh()
                            }
                            mutableState.update { it.copy(progress = "") }
                            synchronized(lock) { if (activeWorkId == workId) activeWorkId = null }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                if (outcome != DesktopWorkPhase.UNCONFIRMED) outcome = DesktopWorkPhase.CANCELLED
                throw cancelled
            } catch (_: Exception) {
                if (outcome != DesktopWorkPhase.UNCONFIRMED) outcome = DesktopWorkPhase.FAILED
                mutableState.update { it.copy(error = "朋友圈操作未完成；原记录和已完成检查点保留") }
            }
        }
        workers[workId] = job
        mutableState.update { it.copy(busy = true, busyTargets = workTargets.values.flatten().toSet()) }
        job.invokeOnCompletion { failure ->
            synchronized(lock) {
                workers.remove(workId); workTargets.remove(workId)
                mutableState.update { it.copy(busy = workers.isNotEmpty(), busyTargets = workTargets.values.flatten().toSet()) }
            }
            val actual = if (outcome == DesktopWorkPhase.UNCONFIRMED || evidence.phase == DesktopWorkPhase.UNCONFIRMED)
                DesktopWorkPhase.UNCONFIRMED else evidence.phase ?: outcome
            val phase = when {
                actual == DesktopWorkPhase.UNCONFIRMED -> DesktopWorkPhase.UNCONFIRMED
                failure != null && failure !is CancellationException -> DesktopWorkPhase.UNCONFIRMED
                failure is CancellationException && actual == DesktopWorkPhase.COMPLETED -> DesktopWorkPhase.CANCELLED
                else -> actual
            }
            mutableWork.update { rows ->
                if (evidence.skipped && phase == DesktopWorkPhase.COMPLETED && failure == null) rows.filterNot { it.id == workId }
                else rows.map { if (it.id == workId) it.copy(phase = phase, canStop = false, stopRequested = false) else it }.let(::boundedWorkRows)
            }
        }
        job.start()
        job
    }

    private fun boundedWorkRows(rows: List<DesktopWorkRow>): List<DesktopWorkRow> {
        var completed = 0
        return rows.filter { it.active || ++completed <= 40 }
    }

    private suspend fun execute(source: MomentTask, retry: MomentPost?, registerImageCleanup: (String) -> Unit,
        debug: Boolean, recoverMissingPost: Boolean) {
        val prefs = settings.getAppSettings(); check(prefs.momentsEnabled)
        val card = characters.getById(source.characterCardId)
        if (card == null || !card.momentsEnabled) {
            if (source.status == MomentTaskStatus.PENDING && store.tasks().any { it.id == source.id })
                store.skipUnstarted(source, "角色卡已删除或未启用朋友圈")
            else error("角色卡已删除或未启用朋友圈")
            currentCoroutineContext()[DesktopMomentWorkEvidence]?.skipped = true
            return
        }
        val session = chats.getSession(source.sessionId)?.takeIf { it.characterCardId == card.id }
            ?: error("原会话已删除；本次计划保留，请核对后处理")
        val attempt = store.begin(source, card, session, retry, recoverMissingPost)
        try {
            val workId = requireNotNull(currentCoroutineContext()[DesktopMomentWorkEvidence]).workId
            synchronized(lock) {
                val postTarget = "post:${attempt.placeholder.id}"
                check(workTargets.none { (id, targets) -> id != workId && postTarget in targets })
                workTargets[workId] = requireNotNull(workTargets[workId]) + postTarget
                mutableState.update { it.copy(busyTargets = workTargets.values.flatten().toSet()) }
            }
            val messages = chats.getMessages(session.id)
            val latest = store.posts().filter { it.characterCardId == card.id && !it.isPlaceholder }.maxByOrNull { it.generatedAt }
            val result = if (debug) {
                val generated = requireNotNull(debugGenerator).generate(card, session, messages, latest, prefs, attempt.task, registerImageCleanup)
                mutableState.update { it.copy(debug = generated) }
                generated.post?.let { MomentGenerationResult.Posted(it) } ?: error("调试生成未完成，请查看本次调试交换记录")
            } else generator.generate(card, session, messages, latest,
                prefs, attempt.task, retry?.generationCheckpoint.orEmpty(),
                { store.checkpoint(attempt, it) }, ::reportProgress, registerImageCleanup)
            currentCoroutineContext().ensureActive()
            check(settings.getAppSettings().momentsEnabled && characters.getById(card.id)?.momentsEnabled == true &&
                chats.getSession(session.id)?.characterCardId == card.id)
            when (result) {
                is MomentGenerationResult.Posted -> store.complete(attempt, result.post)
                is MomentGenerationResult.Skipped -> if (retry == null) {
                    store.skipped(attempt, result.reason)
                    currentCoroutineContext()[DesktopMomentWorkEvidence]?.skipped = true
                } else {
                    store.failed(attempt, "本次未发布：${result.reason}")
                    currentCoroutineContext()[DesktopMomentWorkEvidence]?.phase = DesktopWorkPhase.FAILED
                }
            }
            mutableState.update { it.copy(notice = when (result) {
                is MomentGenerationResult.Posted -> "朋友圈已保存"
                is MomentGenerationResult.Skipped -> "本次未发布：${result.reason}"
            }) }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try { store.failed(attempt, if (failure is CancellationException) "已停止；可从已完成阶段手动重试" else "生成未完成；可从已完成阶段手动重试") }
                catch (_: Exception) {
                    currentCoroutineContext()[DesktopMomentWorkEvidence]?.phase = DesktopWorkPhase.UNCONFIRMED
                    mutableState.update { it.copy(error = "动态已变化或写入未确认；保留现场，不自动重试") }
                }
            }
            throw failure
        }
    }
    suspend fun like(id: String) { store.toggleLike(id); refresh() }
    private fun reportProgress(progress: MomentGenerationProgress) {
        val label = when (progress.phase) {
            MomentGenerationProgressPhase.JUDGING -> "正在判断发布时机"
            MomentGenerationProgressPhase.WRITING -> "正在生成动态文案"
            MomentGenerationProgressPhase.DESIGNING_IMAGE -> "正在设计动态配图"
            MomentGenerationProgressPhase.GENERATING_IMAGE -> "正在生成配图 ${(progress.progress * 100).toInt()}%"
            MomentGenerationProgressPhase.SAVING -> "正在保存动态"
            MomentGenerationProgressPhase.DONE -> "生成完成"
        }
        mutableState.update { it.copy(progress = label,
            streamedText = progress.streamedText.ifBlank { progress.message }.takeLast(128_000)) }
    }
    suspend fun edit(id: String, text: String, sender: com.example.chatbar.domain.moment.MomentSender? = null) {
        store.edit(id, text, sender); refresh()
    }
    suspend fun delete(id: String) { store.delete(id); refresh() }
    suspend fun reconcileSaved() {
        val count = store.reconcileSaved()
        mutableState.update { it.copy(notice = "已核对 $count 条已保存记录；没有发送AI请求") }; refresh()
    }
    suspend fun markViewed() {
        val latest = state.value.posts.maxOfOrNull(MomentPost::generatedAt) ?: return
        gate.withNormalOperation {
            if (settings.getAppSettings().lastSeenMomentsAt < latest)
                settings.updateAppSettings { it.copy(lastSeenMomentsAt = maxOf(it.lastSeenMomentsAt, latest)) }
        }
        mutableState.update { it.copy(viewedThrough = maxOf(it.viewedThrough, latest)) }
    }
    suspend fun closeAndDrain() {
        synchronized(lock) { accepting = false }
        admissionMutex.withLock { stopLocalScheduling(persistPause = false) }
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }
}
