package com.example.chatbar.desktop

import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.domain.card.*
import com.example.chatbar.domain.community.*
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient

internal data class DesktopCommunityUpdate(val localId: String, val localName: String, val remote: CommunityItem)
internal fun communityPreviewKey(item: CommunityItem) = "${item.id}|${item.updatedAt}|${item.sha256}|${item.previewPath}"
internal data class DesktopCommunityState(
    val enabled: Boolean = false, val checking: Boolean = true, val busy: Boolean = false,
    val mine: Boolean = false, val query: String = "", val type: CommunityItemType? = null,
    val items: List<CommunityItem> = emptyList(), val offset: Int = 0, val hasMore: Boolean = false,
    val displayName: String? = null, val loginOpen: Boolean = false,
    val detail: CommunityItem? = null, val detailPayload: CommunityImportPayload? = null,
    val previews: Map<String, ByteArray> = emptyMap(), val updates: List<DesktopCommunityUpdate> = emptyList(),
    val uploadType: CommunityItemType? = null, val candidates: List<CommunityUploadCandidate> = emptyList(),
    val prepared: CommunityPackageDraft? = null, val replacing: CommunityItem? = null, val deleting: CommunityItem? = null,
    val preparedPayload: CommunityImportPayload? = null,
    val prefetchedOffset: Int? = null,
    val error: Boolean = false, val notice: String = "", val recheckRequired: Boolean = false,
) {
    val filteredItems get() = items.filter { (type == null || it.type == type) &&
        (query.isBlank() || listOf(it.title, it.description, it.authorName, it.tags.joinToString(" ")).any { text -> text.contains(query, ignoreCase = true) }) }
    override fun toString() = "DesktopCommunityState(enabled=$enabled,busy=$busy,items=${items.size},error=$error)"
}

/** No authenticated startup requests. User actions own auth/import/publication and their cancellation. */
internal class DesktopCommunityController(
    private val client: CommunityClient,
    private val http: OkHttpClient,
    private val typed: DesktopTypedTransferController,
    private val characters: CharacterRepository,
    private val characterTransfers: CharacterCardTransferCore,
    private val formatTransfers: FormatCardTransferService,
    private val worldTransfers: WorldBookTransferService,
    private val gate: DesktopDataOperationCoordinator,
    private val browse: (URI) -> Unit = { java.awt.Desktop.getDesktop().browse(it) },
    private val pollMillis: Long = 5_000,
    workDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val loginReturn: DesktopCommunityLoginReturn = UnavailableCommunityLoginReturn,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + workDispatcher)
    private val mutable = MutableStateFlow(DesktopCommunityState())
    val state = mutable.asStateFlow()
    private val started = AtomicBoolean()
    private val ownership = Any()
    private var accepting = true
    private var work: Job? = null
    private var sessionLoaded = false
    private var loginStartedAt = 0L
    private var mutationInFlight = false
    private var pendingCallback: String? = null
    private var callbackClaimed = false
    private var loginVerifier: String? = null
    private var nextMetadata: Pair<Pair<Boolean, Int>, CommunityItemPage>? = null
    private var startupWarmed = false

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            while (isActive) { expireIdleLogin(); delay(pollMillis.coerceAtMost(5_000).coerceAtLeast(10)) }
        }
        scope.launch {
            while (isActive) {
                try {
                    refreshStatus()
                    if (state.value.enabled && !startupWarmed) {
                        startupWarmed = true
                        try { cachePreviews(client.listItemsPage(0, 20, preferWarmCache = true).items) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { reportWarning() }
                    }
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutable.update { it.copy(checking = false, enabled = false, error = true, notice = "社区状态读取失败，请稍后重试") } }
                delay(pollMillis)
            }
        }
    }
    private fun expireIdleLogin() = synchronized(ownership) {
        if (work == null && state.value.loginOpen && clock() - loginStartedAt > 600_000) {
            closeLogin()
            if (!state.value.error) mutable.update { it.copy(notice = "本次社区登录已过期，请重新发起") }
        }
    }
    private suspend fun refreshStatus() {
        val enabled = client.refreshEnabledStatus(true)
        if (!enabled) { startupWarmed = false; nextMetadata = null }
        mutable.update { it.copy(checking = false, enabled = enabled,
            notice = if (!enabled) "官方社区暂未开放" else if (it.notice == "官方社区暂未开放") "" else it.notice,
            previews = if (enabled) it.previews else emptyMap(),
            prefetchedOffset = if (enabled) it.prefetchedOffset else null) }
    }
    fun open() = action {
        if (!sessionLoaded) { client.restoreSavedSession(); sessionLoaded = true }
        mutable.update { it.copy(displayName = client.session.value?.displayName) }
        refreshPage(reset = true, preferWarmCache = true)
        checkUpdates()
    }
    fun refresh() = action {
        refreshStatus()
        check(state.value.enabled) { "社区关闭" }
        refreshPage(reset = true)
        checkUpdates()
        if (state.value.mine) mutable.update { it.copy(recheckRequired = false) }
    }
    fun section(mine: Boolean) = action {
        if (mine) check(client.session.value != null) { "需要登录" }
        mutable.update { it.copy(mine = mine) }; refreshPage(reset = true)
        if (mine) mutable.update { it.copy(recheckRequired = false) }
    }
    fun nextPage() = action { if (state.value.hasMore) refreshPage(reset = false) }
    fun prefetchNextPage() {
        val current = state.value
        if (!current.hasMore || current.prefetchedOffset == current.offset) return
        action {
            val before = state.value
            if (!before.hasMore || before.prefetchedOffset == before.offset) return@action
            mutable.update { it.copy(prefetchedOffset = before.offset) }
            val page = if (before.mine) client.listMyItemsPage(before.offset, 20)
                else client.listItemsPage(before.offset, 20)
            nextMetadata = (before.mine to before.offset) to page
            cachePreviews(page.items)
        }
    }
    fun query(value: String) { mutable.update { it.copy(query = value) } }
    fun filter(type: CommunityItemType?) { mutable.update { it.copy(type = type) } }
    fun reportWarning() { mutable.update { it.copy(error = true, notice = "社区附加步骤未完成，请刷新核对") } }

    private suspend fun refreshPage(reset: Boolean, preferWarmCache: Boolean = false) {
        val before = state.value
        val cached = if (reset) null else nextMetadata?.takeIf { it.first == (before.mine to before.offset) }?.second
        nextMetadata = null
        val page = cached ?: if (before.mine) client.listMyItemsPage(if (reset) 0 else before.offset, 20)
            else client.listItemsPage(if (reset) 0 else before.offset, 20, preferWarmCache = reset && preferWarmCache)
        mutable.update { it.copy(items = ((if (reset) emptyList() else it.items) + page.items).distinctBy(CommunityItem::id),
            offset = page.nextOffset, hasMore = page.hasMore, prefetchedOffset = null, displayName = client.session.value?.displayName) }
        cachePreviews(page.items)
    }
    private suspend fun cachePreviews(items: List<CommunityItem>) {
        for (item in items) {
            val key = communityPreviewKey(item)
            if (item.previewPath.isNullOrBlank() || state.value.previews.containsKey(key)) continue
            try {
                val bytes = client.readPreview(item) ?: continue
                val decoded = DesktopImageEditing.decode(bytes, longestSide = 160)
                val png = try { DesktopImageEditing.png(decoded) } finally { decoded.flush() }
                mutable.update { it.copy(previews = (it.previews + (key to png)).entries.toList().takeLast(100).associate { entry -> entry.key to entry.value }) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error = true, notice = "部分社区缩略图读取失败；条目仍可打开") } }
        }
    }
    private suspend fun decode(item: CommunityItem, raw: String): CommunityImportPayload {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        require(item.sizeBytes == bytes.size.toLong() && item.sha256.equals(CommunityPackagePolicy.sha256(bytes), ignoreCase = true))
        CommunityPackagePolicy.validate(item.type, raw)
        require(CommunityPackagePolicy.schemaVersion(raw) == item.schemaVersion)
        return decodePayload(item.type, raw, item.title)
    }
    private fun decodePayload(type: CommunityItemType, raw: String, title: String): CommunityImportPayload = when (type) {
            CommunityItemType.CHARACTER -> CommunityImportPayload.Character(characterTransfers.decode(raw))
            CommunityItemType.FORMAT -> CommunityImportPayload.Format(formatTransfers.decode(raw))
            CommunityItemType.WORLD_BOOK -> CommunityImportPayload.WorldBook(worldTransfers.decode(raw, title))
    }
    fun detail(item: CommunityItem) = action {
        mutable.update { it.copy(detail = null, detailPayload = null) }
        val payload = decode(item, client.readPackage(item))
        mutable.update { it.copy(detail = item, detailPayload = payload) }
    }
    fun closeDetail() { if (!state.value.busy) mutable.update { it.copy(detail = null, detailPayload = null) } }
    fun importItem(item: CommunityItem, localUpdateId: String? = null) = action {
        val payload = decode(item, client.downloadPackage(item))
        typed.importCommunity(item, payload, localUpdateId)
        mutable.update { it.copy(detail = null, detailPayload = null,
            error = typed.state.value.error != null, notice = typed.state.value.error ?: typed.state.value.status.orEmpty()) }
    }
    private suspend fun checkUpdates() {
        val updates = mutableListOf<DesktopCommunityUpdate>()
        for (card in characters.getAll().filter { it.isCommunityDownload }) {
            val remote = client.readItem(requireNotNull(card.communityItemId)) ?: continue
            if (remote.type == CommunityItemType.CHARACTER &&
                (remote.updatedAt != card.communityItemUpdatedAt || !remote.sha256.equals(card.communityItemSha256, ignoreCase = true)))
                updates += DesktopCommunityUpdate(card.id, card.name, remote)
        }
        mutable.update { it.copy(updates = updates) }
    }

    fun beginLogin() = action {
        closeLogin()
        val verifier = ByteArray(32).also(java.security.SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val url = client.discordPkceLoginUrl(verifier)
        when (loginReturn.prepare()) {
            DesktopCommunityReturnStatus.OTHER_HANDLER -> { mutable.update { it.copy(error = true, notice = "社区登录返回协议已由其他应用处理，未修改其设置") }; return@action }
            DesktopCommunityReturnStatus.UNAVAILABLE -> { mutable.update { it.copy(error = true, notice = "当前程序尚无可用的社区登录返回入口") }; return@action }
            DesktopCommunityReturnStatus.READY -> Unit
        }
        synchronized(ownership) {
            loginStartedAt = clock(); loginVerifier = verifier; callbackClaimed = false; pendingCallback = null
            mutable.update { it.copy(loginOpen = true) }
        }
        try { browse(URI(url)) } catch (error: Exception) { closeLogin(); throw error }
    }
    fun receiveLoginReturn(callback: String): Boolean = synchronized(ownership) {
        if (!accepting || !state.value.loginOpen || callbackClaimed ||
            clock() - loginStartedAt !in 0..600_000 || !DesktopExternalActivation.validCommunityCallback(callback) || (loginVerifier == null || !client.isPkceCallback(callback))) return@synchronized false
        callbackClaimed = true
        pendingCallback = callback
        if (work == null) { pendingCallback = null; finishLogin(callback) }
        true
    }
    fun finishLogin(callback: String) = action {
        val link = callback.trim()
        val verifier = synchronized(ownership) {
            require(state.value.loginOpen && clock() - loginStartedAt in 0..600_000 && client.isPkceCallback(link))
            requireNotNull(loginVerifier)
        }
        val session = client.exchangePkceCallback(link, verifier)
        sessionLoaded = true
        closeLogin()
        mutable.update { it.copy(loginOpen = false, displayName = session.displayName, notice = "社区登录已保存") }
    }
    fun closeLogin() {
        synchronized(ownership) { mutable.update { it.copy(loginOpen = false) }; loginStartedAt = 0; loginVerifier = null; pendingCallback = null; callbackClaimed = false }
        releaseLoginReturn()
    }
    private fun releaseLoginReturn() {
        try { loginReturn.release() }
        catch (_: Exception) { reportWarning(); System.err.println("COMMUNITY_LOGIN_RETURN_RELEASE_UNCONFIRMED") }
    }
    fun signOut() = action {
        client.signOut(); sessionLoaded = true
        mutable.update { it.copy(displayName = null, mine = false, uploadType = null, prepared = null, preparedPayload = null) }
        refreshPage(reset = true)
    }
    fun beginUpload(type: CommunityItemType, replacing: CommunityItem? = null) = action {
        check(client.session.value != null)
        val candidates = client.localCandidates(type).filter { candidate ->
            replacing == null || NamePolicy.isSame(candidate.title, replacing.sourceLocalName.ifBlank { replacing.title }) }
        mutable.update { it.copy(uploadType = type, candidates = candidates, prepared = null, preparedPayload = null, replacing = replacing) }
    }
    fun prepareUpload(localId: String, title: String, description: String, tags: String) = action {
        val before = state.value
        val draft = client.buildDraft(requireNotNull(before.uploadType), localId,
            before.replacing?.title ?: title, description, tags.split(',', '，'))
        val payload = decodePayload(draft.type, draft.packageText, draft.title)
        mutable.update { it.copy(prepared = draft, preparedPayload = payload) }
    }
    fun editPrepared() { if (!state.value.busy) mutable.update { it.copy(prepared = null, preparedPayload = null) } }
    fun closeUpload() { if (!state.value.busy) mutable.update { it.copy(uploadType = null, prepared = null, preparedPayload = null, replacing = null) } }
    fun publish() = action {
        check(!state.value.recheckRequired)
        val draft = requireNotNull(state.value.prepared)
        check(client.localCandidates(draft.type).any { it.id == draft.localId && NamePolicy.isSame(it.title, draft.sourceLocalName) })
        mutationInFlight = true
        state.value.replacing?.let { client.updateDraft(it, draft) } ?: client.submitDraft(draft)
        mutationInFlight = false
        mutable.update { it.copy(uploadType = null, prepared = null, preparedPayload = null, replacing = null, notice = "社区快照已发布") }
        refreshPage(reset = true)
    }
    fun askDelete(item: CommunityItem) { if (!state.value.busy) mutable.update { it.copy(deleting = item) } }
    fun cancelDelete() { if (!state.value.busy) mutable.update { it.copy(deleting = null) } }
    fun deleteConfirmed() = action {
        check(!state.value.recheckRequired)
        val item = requireNotNull(state.value.deleting)
        mutationInFlight = true; client.deleteItem(item); mutationInFlight = false
        mutable.update { it.copy(deleting = null, items = it.items.filterNot { row -> row.id == item.id }, notice = "社区条目已删除") }
    }
    fun stop() { synchronized(ownership) { work }?.cancel() }
    private fun action(block: suspend () -> Unit) {
        synchronized(ownership) {
        if (!accepting || work != null) return
        mutable.update { it.copy(busy = true, error = false, notice = "") }
        val owned = scope.launch(start = CoroutineStart.LAZY) {
            try { gate.withNormalOperation { block() } }
            catch (cancelled: CancellationException) {
                mutable.update { it.copy(error = mutationInFlight, recheckRequired = it.recheckRequired || mutationInFlight,
                    notice = if (mutationInFlight) "远端结果尚未确认，请刷新“我的”核对；未自动重发" else "已停止当前社区操作") }
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(error = true, displayName = client.session.value?.displayName,
                    recheckRequired = it.recheckRequired || mutationInFlight,
                    notice = if (mutationInFlight) "远端结果尚未确认，请刷新“我的”核对；未自动重发" else "社区操作未完成，请检查服务、登录、所选条目或网络后重试") }
            }
        }
        work = owned
        owned.invokeOnCompletion { synchronized(ownership) {
            if (work === owned) {
                mutationInFlight = false
                work = null
                val callback = pendingCallback
                pendingCallback = null
                if (callback != null && accepting && state.value.loginOpen) finishLogin(callback)
                else mutable.update { it.copy(busy = false) }
            }
        } }
        owned.start()
        }
    }
    suspend fun closeAndDrain() {
        synchronized(ownership) { accepting = false; pendingCallback = null; loginVerifier = null; callbackClaimed = true }
        job.cancel()
        withContext(NonCancellable) { job.children.toList().joinAll() }
        releaseLoginReturn()
        http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
    }
}
