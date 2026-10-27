package com.example.chatbar.desktop

import com.example.chatbar.domain.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class DesktopFishCredentialState(
    val configured: Boolean = false, val busy: Boolean = false, val error: String? = null,
)

/** Only presence/error crosses the UI boundary; existing secret never becomes editor state. */
internal class DesktopFishCredentialController(private val credentials: DesktopFishAudioCredentials) {
    private val mutable = MutableStateFlow(DesktopFishCredentialState())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var closed = false
    suspend fun closeAndDrain() = mutex.withLock { closed = true }
    suspend fun refresh() = operation { }
    suspend fun save(value: String): Boolean = operation {
        require(value.isNotBlank() && value.trim().length <= 8192 && value.trim().none(Char::isWhitespace))
        credentials.save(value)
    }
    suspend fun clear(): Boolean = operation { credentials.clear() }
    private suspend fun operation(action: () -> Unit): Boolean = mutex.withLock {
        if (closed) return@withLock false
        mutable.update { it.copy(busy = true, error = null) }
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                action()
                mutable.value = DesktopFishCredentialState(configured = credentials.load() != null)
            }
            true
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            // Preserve last confirmed status on failure. Never expose an OS-store exception/token.
            mutable.update { it.copy(busy = false, error = "Fish Audio 安全存储操作失败，请检查输入和存储权限") }
            false
        }
    }
}

internal data class DesktopFishPickerState(
    val query: FishAudioModelQuery = FishAudioModelQuery(),
    val page: FishAudioModelPage = FishAudioModelPage(),
    val loading: Boolean = false, val error: String? = null,
) {
    val hasNext: Boolean get() = page.hasMore ?: (query.pageNumber * query.pageSize < page.total)
}

/** The caller supplies a composition-owned scope. No orphan job or implicit binding. */
internal class DesktopFishPickerController(
    private val scope: CoroutineScope,
    private val queryModels: suspend (FishAudioModelQuery) -> FishAudioModelPage,
) : AutoCloseable {
    private val mutable = MutableStateFlow(DesktopFishPickerState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var closed = false
    private var job: Job? = null
    fun invalidate(query: FishAudioModelQuery) {
        generation++
        job?.cancel(); job = null
        mutable.value = DesktopFishPickerState(query = query)
    }
    fun search(query: FishAudioModelQuery = state.value.query) {
        if (closed) return
        invalidate(query)
        val request = generation
        mutable.update { it.copy(loading = true) }
        job = scope.launch {
            try {
                val page = queryModels(query)
                if (!closed && generation == request) mutable.update { it.copy(page = page) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (!closed && generation == request) mutable.update {
                    it.copy(error = if (failure is FishAudioApiException) failure.message else "音色查询失败，请检查网络后重试")
                }
            } finally {
                if (!closed && generation == request) mutable.update { it.copy(loading = false) }
            }
        }
    }
    fun selectable(id: String): FishAudioModel? = state.value.takeIf { !closed && !it.loading && it.error == null }
        ?.page?.items?.firstOrNull { it.id == id }
    override fun close() { closed = true; generation++; job?.cancel(); job = null }
}
