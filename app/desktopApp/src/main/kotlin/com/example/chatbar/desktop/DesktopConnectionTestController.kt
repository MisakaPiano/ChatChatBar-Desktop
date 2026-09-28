package com.example.chatbar.desktop

import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.model.EffectiveModelResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class DesktopConnectionTestState(
    val running: Boolean = false,
    val cancelled: Boolean = false,
    val model: String? = null,
    val endpoint: String? = null,
    val chat: ConnectionProbeResult? = null,
    val embedding: ConnectionProbeResult? = null,
    val error: String? = null,
)

/** Container-owned operation; Compose only observes it and sends explicit commands. */
internal class DesktopConnectionTestController(
    private val settings: SettingsRepository,
    private val resolver: EffectiveModelResolver,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + dispatcher)
    private val mutableState = MutableStateFlow(DesktopConnectionTestState())
    val state: StateFlow<DesktopConnectionTestState> = mutableState.asStateFlow()
    private var job: Job? = null
    private var accepting = true

    fun start(): Boolean = synchronized(lock) {
        if (!accepting || job != null) return false
        mutableState.value = DesktopConnectionTestState(running = true)
        val operation = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val app = settings.readExistingAppSettings()
                if (app == null) {
                    val absent = ConnectionProbeResult(ConnectionProbeStatus.NOT_CONFIGURED)
                    mutableState.update { it.copy(chat = absent, embedding = absent) }
                } else {
                    val chat = resolver.defaultChatModel(app)
                    val embedding = resolver.embeddingModel(app)
                    val scrubber = DesktopDiagnosticScrubber(chat?.apiKey.orEmpty())
                    mutableState.update { it.copy(
                        model = chat?.let { model -> scrubber.text(model.displayName, 512) },
                        endpoint = chat?.let { model -> scrubber.url(model.baseUrl) },
                    ) }
                    val result = ModelConnectionProbe(app.allowCleartextModelApi).run(
                        chat, embedding, onChatResult = { result -> mutableState.update { it.copy(chat = result) } },
                    )
                    mutableState.update { it.copy(chat = result.chat, embedding = result.embedding) }
                }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(cancelled = true) }
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(error = "Connection test unavailable") }
            }
        }
        job = operation
        operation.invokeOnCompletion { failure ->
            synchronized(lock) {
                if (job === operation) {
                    job = null
                    mutableState.update { it.copy(running = false,
                        cancelled = it.cancelled || failure is CancellationException) }
                }
            }
        }
        operation.start()
        true
    }

    fun stop() = synchronized(lock) { job?.cancel() }

    suspend fun closeAndDrain(timeoutMillis: Long = 10_000) {
        val active = synchronized(lock) { accepting = false; job }
        active?.cancel()
        val drained = withContext(NonCancellable) {
            withTimeoutOrNull(timeoutMillis) { active?.join(); supervisor.cancelAndJoin(); true }
        }
        if (drained != true) throw DesktopTaskDrainTimeoutException("Connection test did not drain before storage shutdown")
    }
}
