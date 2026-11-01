package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.moment.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

internal fun interface DesktopMomentGenerator {
    suspend fun generate(card: CharacterCard, session: ChatSession, messages: List<ChatMessage>, latest: MomentPost?,
        settings: AppSettings, task: MomentTask, checkpoint: String,
        saveCheckpoint: suspend (String) -> Unit, progress: (MomentGenerationProgress) -> Unit,
        registerImageCleanup: (String) -> Unit): MomentGenerationResult
}

internal data class DesktopMomentsState(
    val enabled: Boolean = false, val autoImages: Boolean = true, val scheduling: Boolean = false,
    val minHours: Int = 2, val maxHours: Int = 13, val posts: List<MomentPost> = emptyList(),
    val tasks: List<MomentTask> = emptyList(), val cards: List<CharacterCard> = emptyList(),
    val busy: Boolean = false, val progress: String = "", val notice: String = "", val error: String? = null,
    val viewedThrough: Long = 0L,
) {
    val unread: Boolean get() = posts.any { !it.isPlaceholder && it.generatedAt > viewedThrough }
}

/** Local consent is deliberately per process. Loaded/imported enabled/PENDING/RUNNING never start AI. */
internal class DesktopMomentsController(
    private val store: DesktopMomentStore,
    private val settings: SettingsRepository,
    private val characters: CharacterRepository,
    private val chats: ChatRepository,
    private val gate: DesktopDataOperationCoordinator,
    private val tasksRuntime: DesktopTaskRuntime,
    private val generator: DesktopMomentGenerator,
    private val finishImageCleanup: suspend (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMillis: Long = 60_000,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(DesktopMomentsState())
    val state = mutableState.asStateFlow()
    private val lock = Any()
    private var accepting = true
    private var worker: Job? = null
    private var scheduler: Job? = null
    private val admitted = mutableMapOf<String, MomentTask>()
    private val preparation = MomentSchedulePreparation(characters::getAll, chats::getAllSessions,
        store::posts, store::tasks, { task ->
            store.saveTask(task)
            synchronized(lock) { admitted[task.id] = task }
        })

    init { scope.launch { refresh() } }

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
    suspend fun resumeScheduling() {
        pause()
        gate.withNormalOperation {
            check(settings.getAppSettings().momentsEnabled) { "请先开启朋友圈" }
            val pending = store.tasks().filter { it.status == MomentTaskStatus.PENDING }
            synchronized(lock) {
                check(accepting)
                admitted.clear(); pending.forEach { admitted[it.id] = it }
                mutableState.update { it.copy(scheduling = true, error = null,
                    notice = "本机调度已恢复；关闭应用后停止，重开后需再次恢复。中断记录不会自动重试。") }
                scheduler = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        while (currentCoroutineContext().isActive) {
                            tick()
                            delay(tickMillis)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { mutableState.update { it.copy(error = "朋友圈调度已暂停；请核对数据与设置后手动恢复") } }
                    finally { mutableState.update { it.copy(scheduling = false) } }
                }.also { it.start() }
            }
        }
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
            val job = launchWork(task) ?: return
            job.join()
        }
    }
    suspend fun pause() {
        val jobs = synchronized(lock) { listOfNotNull(scheduler, worker).distinct().also {
            scheduler = null; admitted.clear()
        } }
        jobs.forEach { it.cancel() }; jobs.forEach { it.join() }
        mutableState.update { it.copy(scheduling = false) }
    }
    fun stop() { synchronized(lock) { worker?.cancel() } }

    suspend fun generate(cardId: String) {
        val session = chats.getAllSessions().filter { it.characterCardId == cardId }
            .maxByOrNull { it.lastMessageTime ?: it.createdAt } ?: error("请先为该角色卡创建有交流内容的会话")
        launchWork(MomentTask.create(cardId, session.id, clock()))
    }
    suspend fun retry(post: MomentPost) {
        check(post.isPlaceholder)
        val task = store.tasks().singleOrNull { it.postId == post.id }
            ?: MomentTask.create(post.characterCardId, post.sessionId, post.scheduledAt)
        launchWork(task, post)
    }
    private fun launchWork(task: MomentTask, retry: MomentPost? = null): Job? = synchronized(lock) {
        check(accepting)
        if (worker?.isCompleted == false) {
            mutableState.update { it.copy(notice = "已有朋友圈任务，请等待或停止") }; return@synchronized null
        }
        scope.launch(start = CoroutineStart.LAZY) {
            val imageReceipts = mutableListOf<String>()
            mutableState.update { it.copy(busy = true, error = null, progress = "正在准备朋友圈") }
            try {
                tasksRuntime.withMessageMutation(task.sessionId) {
                    tasksRuntime.withCardDesign("moments:${task.sessionId}") {
                        gate.withNormalOperation { execute(task, retry) { imageReceipts += it } }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = "朋友圈操作未完成；原记录和已完成检查点保留") } }
            finally {
                mutableState.update { it.copy(busy = false, progress = "") }
                withContext(NonCancellable) {
                    for (receipt in imageReceipts) try { finishImageCleanup(receipt) }
                    catch (_: Exception) { mutableState.update { it.copy(error = "动态记录已保留；图片清理未完成，重开或重试清理时核对") } }
                    refresh()
                }
            }
        }.also { worker = it; it.start() }
    }

    private suspend fun execute(source: MomentTask, retry: MomentPost?, registerImageCleanup: (String) -> Unit) {
        val prefs = settings.getAppSettings(); check(prefs.momentsEnabled)
        val card = characters.getById(source.characterCardId)
        if (card == null || !card.momentsEnabled) {
            if (retry == null && store.tasks().any { it.id == source.id }) store.skipUnstarted(source, "角色卡已删除或未启用朋友圈")
            else error("角色卡已删除或未启用朋友圈")
            return
        }
        val session = chats.getSession(source.sessionId)?.takeIf { it.characterCardId == card.id }
            ?: error("原会话已删除；本次计划保留，请核对后处理")
        val attempt = store.begin(source, card, session, retry)
        try {
            val result = generator.generate(card, session, chats.getMessages(session.id),
                store.posts().filter { it.characterCardId == card.id && !it.isPlaceholder }.maxByOrNull { it.generatedAt },
                prefs, attempt.task, retry?.generationCheckpoint.orEmpty(),
                { store.checkpoint(attempt, it) }, { progress ->
                    mutableState.update { it.copy(progress = progress.message) }
                }, registerImageCleanup)
            currentCoroutineContext().ensureActive()
            check(settings.getAppSettings().momentsEnabled && characters.getById(card.id)?.momentsEnabled == true)
            when (result) {
                is MomentGenerationResult.Posted -> store.complete(attempt, result.post)
                is MomentGenerationResult.Skipped -> store.skipped(attempt, result.reason)
            }
            mutableState.update { it.copy(notice = when (result) {
                is MomentGenerationResult.Posted -> "朋友圈已保存"
                is MomentGenerationResult.Skipped -> "本次未发布：${result.reason}"
            }) }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try { store.failed(attempt, if (failure is CancellationException) "已停止；可从已完成阶段手动重试" else "生成未完成；可从已完成阶段手动重试") }
                catch (_: Exception) { mutableState.update { it.copy(error = "动态已变化或写入未确认；保留现场，不自动重试") } }
            }
            throw failure
        }
    }
    suspend fun like(id: String) { store.toggleLike(id); refresh() }
    suspend fun edit(id: String, text: String) { store.edit(id, text); refresh() }
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
        pause(); scope.coroutineContext[Job]!!.cancelAndJoin()
    }
}
