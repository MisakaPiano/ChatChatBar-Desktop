package com.example.chatbar.desktop

import java.awt.EventQueue
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class DesktopTrayState(val initialized: Boolean = false, val available: Boolean = false,
    val windowVisible: Boolean = true, val notificationsEnabled: Boolean = false,
    val notificationsConfigurable: Boolean = false, val error: String? = null)

internal data class DesktopTrayActions(val show: () -> Unit, val tasks: () -> Unit,
    val hide: () -> Unit, val exit: () -> Unit)

internal interface DesktopTrayBackend {
    suspend fun attach(actions: DesktopTrayActions): Boolean
    suspend fun notify(kind: DesktopWorkKind, phase: DesktopWorkPhase)
    suspend fun detach()
}

/** The existing task owners remain authoritative. Tray visibility never disposes the application. */
internal class DesktopTrayController(private val center: DesktopTaskCenter, private val settings: DesktopSettingsStore,
    private val backend: DesktopTrayBackend = DesktopAwtTrayBackend(), dispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val operations = Mutex()
    private val mutable = MutableStateFlow(DesktopTrayState())
    val state = mutable.asStateFlow()
    @Volatile private var exiting = false
    private var showWindow: () -> Unit = {}

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { center.notices.collect { notice ->
            val current = state.value
            if (!exiting && current.available && current.notificationsEnabled) {
                try { backend.notify(notice.kind, notice.phase) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutable.update { it.copy(error = "系统未接受任务提醒，请在任务中心核对结果。") } }
            }
        } }
    }

    suspend fun initialize(onShow: () -> Unit, onExit: () -> Unit) = operations.withLock {
        if (state.value.initialized || exiting) return@withLock
        showWindow = onShow
        try { when (val loaded = settings.load()) {
            is DesktopSettingsLoadResult.Loaded -> mutable.update { it.copy(
                notificationsEnabled = loaded.document.settings.taskNotifications, notificationsConfigurable = true) }
            is DesktopSettingsLoadResult.Missing -> mutable.update { it.copy(
                notificationsEnabled = loaded.document.settings.taskNotifications, notificationsConfigurable = true) }
            is DesktopSettingsLoadResult.Failure -> mutable.update { it.copy(error = "桌面设置无法读取，任务提醒暂不启用；原设置已保留。") }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(error = "桌面设置读取未完成，任务提醒暂不启用；原设置已保留。") } }
        try {
            val attached = backend.attach(DesktopTrayActions(::show, ::showTasks, ::hide, onExit))
            mutable.update { it.copy(initialized = true, available = attached) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(initialized = true, available = false,
            error = "系统托盘未能建立，窗口保持显示；仍可正常退出程序。") } }
    }

    fun hide() {
        if (state.value.available && !exiting) mutable.update { it.copy(windowVisible = false) }
    }
    fun show() {
        if (!exiting) { mutable.update { it.copy(windowVisible = true) }; showWindow() }
    }
    fun showTasks() { if (!exiting) { show(); center.requestOpen() } }
    fun prepareExit() { exiting = true }

    fun setNotifications(enabled: Boolean) {
        if (exiting || !state.value.notificationsConfigurable) return
        scope.launch { operations.withLock {
            if (exiting) return@withLock
            try {
                settings.updateLatest { it.copy(taskNotifications = enabled) }
                mutable.update { it.copy(notificationsEnabled = enabled, error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error = "任务提醒设置未保存，原设置保持。") } }
        } }
    }

    suspend fun closeAndDrain() {
        prepareExit()
        scope.coroutineContext[Job]!!.cancelAndJoin()
        operations.withLock {
            withContext(NonCancellable) {
                try { backend.detach() }
                catch (_: Exception) { System.err.println("CCB_TRAY_DETACH_FAILED") }
            }
            mutable.update { it.copy(available = false) }
        }
    }
}

/** All AWT resources stay on its event thread; only fixed kind/status text leaves the process UI. */
internal class DesktopAwtTrayBackend : DesktopTrayBackend {
    private var icon: TrayIcon? = null
    private suspend fun <T> awt(action: () -> T): T {
        if (EventQueue.isDispatchThread()) return action()
        val result = CompletableDeferred<T>()
        EventQueue.invokeLater { try { result.complete(action()) } catch (failure: Throwable) { result.completeExceptionally(failure) } }
        return result.await()
    }
    override suspend fun attach(actions: DesktopTrayActions): Boolean = awt {
        if (!SystemTray.isSupported()) return@awt false
        check(icon == null)
        val menu = PopupMenu()
        fun item(label: String, action: () -> Unit) { menu.add(MenuItem(label).apply { addActionListener { action() } }) }
        item("打开主窗口", actions.show); item("任务中心", actions.tasks); item("隐藏窗口", actions.hide)
        menu.addSeparator(); item("退出", actions.exit)
        val image = requireNotNull(ImageIO.read(DesktopBrandResources.logoBytes().inputStream()))
        val created = TrayIcon(image, "ChatChatBar", menu).apply {
            isImageAutoSize = true
            addActionListener { actions.tasks() }
        }
        SystemTray.getSystemTray().add(created)
        icon = created
        true
    }
    override suspend fun notify(kind: DesktopWorkKind, phase: DesktopWorkPhase) = awt {
        val current = checkNotNull(icon)
        current.displayMessage("ChatChatBar", "${kind.label} · ${phase.label()}",
            if (phase == DesktopWorkPhase.FAILED || phase == DesktopWorkPhase.UNCONFIRMED) TrayIcon.MessageType.ERROR else TrayIcon.MessageType.INFO)
    }
    override suspend fun detach() = awt { icon?.let { SystemTray.getSystemTray().remove(it) }; icon = null }
}
