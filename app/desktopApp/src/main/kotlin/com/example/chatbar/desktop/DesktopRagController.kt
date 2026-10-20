package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.data.repository.ModelRepository
import com.example.chatbar.data.repository.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class DesktopIndexProgress(val running: Boolean, val completed: Int = 0,
    val total: Int = 0, val message: String = "")
internal data class DesktopRagSettings(val endpoint: String = "", val model: String = "",
    val dimensions: Int = 1536, val configured: Boolean = false,
    val documentCount: Int = 3, val documentThreshold: Float = .55f,
    val memoryCount: Int = 3, val memoryThreshold: Float = .35f, val injectionMode: String = "STANDARD")

/** App-owned document tasks and settings. UI never receives an existing credential. */
internal class DesktopRagController(
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val historyIndex: suspend (String) -> Int = { error("历史索引不可用") },
    private val index: suspend (String, String, (Int, Int) -> Unit) -> DesktopDocumentIndexOutcome,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<Pair<String, String>, Job>()
    private val lock = Any()
    private val mutableProgress = MutableStateFlow<Map<Pair<String, String>, DesktopIndexProgress>>(emptyMap())
    val progress = mutableProgress.asStateFlow()
    suspend fun configuration(): DesktopRagSettings {
        val config = models.getEmbeddingModel()
        val current = settings.getAppSettings()
        return DesktopRagSettings(config?.baseUrl.orEmpty(), config?.modelName.orEmpty(), config?.dimensions ?: 1536,
            !config?.apiKey.isNullOrBlank(), current.docRagTopK, current.docRagSimilarityThreshold,
            current.memoryRagTopK, current.memoryRagSimilarityThreshold, current.ragInjectionMode)
    }
    suspend fun save(value: DesktopRagSettings, newCredential: String?, clearCredential: Boolean = false) {
        require(value.dimensions in 1..65536 && value.documentCount in 0..100 && value.memoryCount in 0..100)
        require(value.documentThreshold in 0f..1f && value.memoryThreshold in 0f..1f)
        require(value.injectionMode in setOf("OFF", "LIGHT", "STANDARD", "STRONG"))
        val uri = java.net.URI(value.endpoint)
        require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(value.model.isNotBlank())
        val old = models.getEmbeddingModel()
        val key = if (clearCredential) "" else newCredential?.takeIf(String::isNotBlank) ?: old?.apiKey.orEmpty()
        models.saveEmbeddingModel(EmbeddingConfig("default", value.model, value.endpoint.trimEnd('/'), key, value.model, value.dimensions))
        settings.updateAppSettings { current -> current.copy(docRagTopK = value.documentCount, docRagSimilarityThreshold = value.documentThreshold,
            memoryRagTopK = value.memoryCount, memoryRagSimilarityThreshold = value.memoryThreshold,
            ragInjectionMode = value.injectionMode) }
    }
    fun start(characterId: String, documentId: String) = startWork(characterId to documentId) { progress ->
        index(characterId, documentId, progress).chunks
    }
    fun startHistory(sessionId: String) = startWork(historyKey(sessionId)) { historyIndex(sessionId) }
    fun cancelHistory(sessionId: String) = synchronized(lock) { jobs[historyKey(sessionId)]?.cancel() }
    fun historyKey(sessionId: String) = "chat-memory:$sessionId" to "history"

    private fun startWork(key: Pair<String, String>, work: suspend ((Int, Int) -> Unit) -> Int) = synchronized(lock) {
        if (jobs[key]?.isActive == true) return@synchronized
        val job = scope.launch(start = CoroutineStart.LAZY) {
            mutableProgress.update { it + (key to DesktopIndexProgress(true, message = "准备索引")) }
            try {
                val count = work { done, total ->
                    mutableProgress.update { it + (key to DesktopIndexProgress(true, done, total, "生成向量")) }
                }
                mutableProgress.update { it + (key to DesktopIndexProgress(false, count, count, if (count == 0) "索引已是最新" else "索引已保存")) }
            } catch (_: CancellationException) {
                mutableProgress.update { it + (key to DesktopIndexProgress(false, message = "已取消；原有索引保留")) }
            } catch (_: DesktopAudioMaintenanceBusyException) {
                mutableProgress.update { it + (key to DesktopIndexProgress(false, message = "音频正在使用，请停止播放后重试索引")) }
            } catch (_: Exception) {
                mutableProgress.update { it + (key to DesktopIndexProgress(false, message = "索引未完成；原数据与恢复证据保留，请检查模型或文档后重试")) }
            }
        }
        jobs[key] = job
        job.invokeOnCompletion { synchronized(lock) { if (jobs[key] === job) jobs.remove(key) } }
        job.start()
    }
    fun cancel(characterId: String, documentId: String) = synchronized(lock) { jobs[characterId to documentId]?.cancel() }
    suspend fun closeAndDrain() { scope.coroutineContext[Job]!!.cancelAndJoin() }
}
