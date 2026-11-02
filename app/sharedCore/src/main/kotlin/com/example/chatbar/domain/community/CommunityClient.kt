package com.example.chatbar.domain.community

import com.example.chatbar.domain.card.NamePolicy
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Original Community HTTP/domain owner with platform-owned local packages and session custody. */
open class CommunityClient(
    private val config: CommunityConfiguration,
    private val packages: CommunityLocalPackages,
    private val sessions: CommunitySessionStore,
    private val json: Json,
    private val client: OkHttpClient,
    loadSessionAtStartup: Boolean = true,
    private val warn: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val baseUrl = config.baseUrl.trim().trimEnd('/')
    private val anonKey = config.anonKey.trim()
    private val redirectUri = config.redirectUri.trim()
    private val _session = MutableStateFlow(if (loadSessionAtStartup) sessions.load() else null)
    private val _enabled = MutableStateFlow(false)

    val configured: Boolean = baseUrl.isNotBlank() && anonKey.isNotBlank()
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val session: StateFlow<CommunitySession?> = _session.asStateFlow()
    @Volatile
    private var warmedItemsPage: WarmedCommunityPage? = null
    @Volatile
    private var disabledMessage: String = "社区已关闭"
    @Volatile
    private var statusLoadedAt: Long = 0L

    fun discordLoginUrl(): String {
        check(configured) { "请先配置 CHATBAR_SUPABASE_URL 和 CHATBAR_SUPABASE_ANON_KEY" }
        check(enabled.value) { disabledMessage }
        return "$baseUrl/auth/v1/authorize" +
            "?provider=discord" +
            "&redirect_to=${urlEncode(redirectUri)}" +
            "&scopes=${urlEncode("identify")}"
    }

    /** Supabase PKCE uses the existing fixed redirect; no new allowlist/query nonce is required. */
    fun discordPkceLoginUrl(verifier: String): String {
        require(verifier.matches(Regex("[A-Za-z0-9._~-]{43,128}")))
        val challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        return discordLoginUrl() + "&code_challenge=$challenge&code_challenge_method=s256"
    }

    fun isPkceCallback(callback: String): Boolean = runCatching { pkceCallbackCode(callback) }.isSuccess

    private fun pkceCallbackCode(callback: String): String {
        require(callback.length in 1..16_384)
        val uri = java.net.URI(callback)
        require(uri.rawFragment.isNullOrEmpty()) { "本次登录只接受授权码返回" }
        callbackParams(callback) // Original configured origin/path validation.
        val codes = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
            val parts = pair.split('=', limit = 2)
            if (parts.size == 2 && parts[0].urlDecode() == "code") parts[1].urlDecode() else null
        }
        require(codes.size == 1 && codes.single().isNotBlank() && codes.single().length <= 4096) { "社区返回授权码缺失或重复" }
        return codes.single()
    }

    suspend fun exchangePkceCallback(callback: String, verifier: String): CommunitySession = withContext(Dispatchers.IO) {
        val code = pkceCallbackCode(callback)
        require(verifier.matches(Regex("[A-Za-z0-9._~-]{43,128}")))
        checkAvailable()
        val body = json.encodeToString(PkceTokenRequest.serializer(), PkceTokenRequest(code, verifier))
        val request = Request.Builder().url("$baseUrl/auth/v1/token?grant_type=pkce")
            .header("apikey", anonKey).header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(body.toRequestBody(JSON_MEDIA_TYPE)).build()
        val token = json.decodeFromString(TokenResponse.serializer(), executeForBody(request, "验证社区登录"))
        verifyAndSaveSession(token.accessToken, token.refreshToken, token.tokenType, token.expiresIn)
    }

    suspend fun monitorEnabledStatus() {
        while (true) {
            runCatching { refreshEnabledStatus(force = true) }
                .onFailure { error -> warn("Community status refresh failed", error) }
            delay(STATUS_POLL_INTERVAL_MS)
        }
    }

    suspend fun refreshEnabledStatus(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!configured) {
            _enabled.value = false
            disabledMessage = "请先配置 Supabase"
            return@withContext false
        }
        val now = System.currentTimeMillis()
        if (!force && now - statusLoadedAt <= STATUS_CACHE_TTL_MS) {
            return@withContext enabled.value
        }
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/community_runtime_config?id=eq.community&select=enabled,message,updated_at")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .header("Accept", "application/json")
            .get()
            .build()
        val rows = runCatching {
            val body = executeForBody(request, "读取社区状态")
            json.decodeFromString(ListSerializer(CommunityRuntimeConfigDto.serializer()), body)
        }.getOrElse { error ->
            if (error.isMissingRuntimeConfig()) {
                statusLoadedAt = now
                disabledMessage = ""
                _enabled.value = true
                warn("Community runtime config missing; using legacy enabled mode", error)
                return@withContext true
            }
            throw error
        }
        val config = rows.firstOrNull()
        val nextEnabled = config?.enabled ?: true
        disabledMessage = config?.message?.takeIf(String::isNotBlank) ?: "社区已关闭"
        statusLoadedAt = now
        _enabled.value = nextEnabled
        if (!nextEnabled) clearWarmCache()
        nextEnabled
    }

    suspend fun handleAuthCallback(callback: String): CommunitySession = withContext(Dispatchers.IO) {
        checkAvailable()
        val params = callbackParams(callback)
        params["error_description"]?.let { error(it) }
        params["error"]?.let { error(it) }
        val accessToken = params["access_token"]?.takeIf(String::isNotBlank)
            ?: error("回调缺少 access_token")
        val refreshToken = params["refresh_token"]?.takeIf(String::isNotBlank)
        val tokenType = params["token_type"]?.takeIf(String::isNotBlank) ?: "bearer"
        val expiresInSeconds = params["expires_in"]?.toLongOrNull() ?: 0L
        verifyAndSaveSession(accessToken, refreshToken, tokenType, expiresInSeconds)
    }

    private suspend fun verifyAndSaveSession(accessToken: String, refreshToken: String?, tokenType: String,
        expiresInSeconds: Long): CommunitySession {
        require(accessToken.isNotBlank())
        val user = fetchUser(accessToken)
        require(user.id.isNotBlank())
        val session = CommunitySession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenType = tokenType,
            expiresAtMillis = if (expiresInSeconds > 0L) {
                System.currentTimeMillis() + expiresInSeconds * 1000L
            } else {
                0L
            },
            userId = user.id,
            displayName = user.displayName()
        )
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        saveSession(session)
        return session
    }

    fun signOut() {
        sessions.clear()
        _session.value = null
    }

    /** Desktop may defer protected custody reads until the user opens Community. No refresh/network call. */
    fun restoreSavedSession() { _session.value = sessions.load() }

    suspend fun listItems(): List<CommunityItem> = withContext(Dispatchers.IO) {
        checkAvailable()
        queryItems(authorUserId = null, accessToken = null, offset = 0, limit = DEFAULT_LIST_LIMIT).items
    }

    suspend fun prefetchFirstItemsPage(limit: Int = DEFAULT_PAGE_SIZE): CommunityItemPage? = withContext(Dispatchers.IO) {
        if (!configured || !refreshEnabledStatus()) return@withContext null
        val safeLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        warmedItemsPage
            ?.takeIf { it.limit == safeLimit && System.currentTimeMillis() - it.loadedAt <= WARM_CACHE_TTL_MS }
            ?.page
            ?.let { return@withContext it }
        runCatching {
            val page = queryItems(authorUserId = null, accessToken = null, offset = 0, limit = safeLimit)
            warmedItemsPage = WarmedCommunityPage(
                limit = safeLimit,
                page = page,
                loadedAt = System.currentTimeMillis()
            )
            page
        }.getOrNull()
    }

    suspend fun listItemsPage(
        offset: Int,
        limit: Int,
        preferWarmCache: Boolean = false
    ): CommunityItemPage = withContext(Dispatchers.IO) {
        checkAvailable()
        val safeLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        if (preferWarmCache && offset == 0) {
            warmedItemsPage
                ?.takeIf { it.limit == safeLimit && System.currentTimeMillis() - it.loadedAt <= WARM_CACHE_TTL_MS }
                ?.page
                ?.let { return@withContext it }
        }
        val page = queryItems(authorUserId = null, accessToken = null, offset = offset, limit = safeLimit)
        if (offset == 0) {
            warmedItemsPage = WarmedCommunityPage(safeLimit, page, System.currentTimeMillis())
        }
        page
    }

    suspend fun listMyItems(): List<CommunityItem> = withContext(Dispatchers.IO) {
        checkAvailable()
        val session = requireSession()
        queryItems(authorUserId = session.userId, accessToken = session.accessToken, offset = 0, limit = DEFAULT_LIST_LIMIT).items
    }

    suspend fun readItem(id: String): CommunityItem? = withContext(Dispatchers.IO) {
        checkAvailable()
        require(id.isNotBlank())
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/community_items?id=eq.${urlEncode(id)}&select=$COMMUNITY_ITEM_SELECT")
            .header("apikey", anonKey).header("Authorization", "Bearer $anonKey").get().build()
        json.decodeFromString(ListSerializer(CommunityItemDto.serializer()), executeForBody(request, "读取社区条目"))
            .singleOrNull()?.toDomain()
    }

    suspend fun listMyItemsPage(offset: Int, limit: Int): CommunityItemPage = withContext(Dispatchers.IO) {
        checkAvailable()
        val session = requireSession()
        queryItems(authorUserId = session.userId, accessToken = session.accessToken, offset = offset, limit = limit)
    }

    private suspend fun queryItems(authorUserId: String?, accessToken: String?, offset: Int, limit: Int): CommunityItemPage {
        val authorFilter = authorUserId?.let { "&author_user_id=eq.${urlEncode(it)}" }.orEmpty()
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/community_items?select=$COMMUNITY_ITEM_SELECT$authorFilter&order=created_at.desc&offset=$safeOffset&limit=${safeLimit + 1}")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${accessToken ?: anonKey}")
            .header("Accept", "application/json")
            .get()
            .build()
        val body = executeForBody(request, "读取社区列表")
        val decoded = json.decodeFromString(ListSerializer(CommunityItemDto.serializer()), body)
            .map(CommunityItemDto::toDomain)
        val pageItems = decoded.take(safeLimit)
        return CommunityItemPage(
            items = pageItems,
            nextOffset = safeOffset + pageItems.size,
            hasMore = decoded.size > safeLimit
        )
    }

    suspend fun localCandidates(type: CommunityItemType): List<CommunityUploadCandidate> = packages.candidates(type)

    suspend fun buildDraft(
        type: CommunityItemType,
        localId: String,
        title: String,
        description: String,
        tags: List<String>
    ): CommunityPackageDraft = withContext(Dispatchers.IO) {
        val source = packages.export(type, localId)
        val sourceName = source.sourceName
        val packageText = source.packageText
        val preview = source.preview
        CommunityPackagePolicy.validate(type, packageText)
        val bytes = packageText.toByteArray(Charsets.UTF_8)
        require(bytes.size <= CommunityPackagePolicy.MAX_PACKAGE_BYTES) { "社区包超过 20 MB" }
        CommunityPackageDraft(
            type = type,
            localId = localId,
            sourceLocalName = sourceName,
            title = title.ifBlank { sourceName },
            description = description.trim(),
            tags = tags.map(String::trim).filter(String::isNotBlank).distinct().take(MAX_TAGS),
            packageText = packageText,
            sha256 = CommunityPackagePolicy.sha256(bytes),
            sizeBytes = bytes.size.toLong(),
            schemaVersion = CommunityPackagePolicy.schemaVersion(packageText),
            preview = preview
        )
    }

    suspend fun submitDraft(draft: CommunityPackageDraft): CommunityItem = withContext(Dispatchers.IO) {
        checkAvailable()
        val session = requireSession()
        publishDraft(draft = draft, session = session, itemId = null)
    }

    suspend fun updateDraft(item: CommunityItem, draft: CommunityPackageDraft): CommunityItem = withContext(Dispatchers.IO) {
        checkAvailable()
        val session = requireSession()
        require(item.authorUserId == session.userId) { "只能更新自己上传的条目" }
        require(item.type == draft.type) { "不能用不同类型覆盖社区条目" }
        require(NamePolicy.isSame(item.sourceLocalName.ifBlank { item.title }, draft.sourceLocalName)) {
            "请选择同名本地条目覆盖社区快照"
        }
        publishDraft(draft = draft, session = session, itemId = item.id)
    }

    suspend fun deleteItem(item: CommunityItem) = withContext(Dispatchers.IO) {
        checkAvailable()
        val session = requireSession()
        require(item.authorUserId == session.userId) { "只能删除自己上传的条目" }
        val body = json.encodeToString(CommunityDeleteRequest(action = "delete", itemId = item.id))
        val request = Request.Builder()
            .url("$baseUrl/functions/v1/submit-community-item")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        executeForBody(request, "删除社区条目")
    }

    private suspend fun publishDraft(
        draft: CommunityPackageDraft,
        session: CommunitySession,
        itemId: String?
    ): CommunityItem {
        ensureNoCharacterNameConflict(draft, itemId, session.accessToken)
        val filePath = storagePath(session.userId, draft.type, draft.title, "json")
        uploadObject(
            bucket = PACKAGE_BUCKET,
            path = filePath,
            bytes = draft.packageText.toByteArray(Charsets.UTF_8),
            contentType = "application/json",
            accessToken = session.accessToken
        )
        val previewPath = draft.preview?.let { preview ->
            val extension = File(preview.fileName).extension.ifBlank { "jpg" }
            val path = storagePath(session.userId, draft.type, draft.title, extension, prefix = "previews")
            uploadObject(PREVIEW_BUCKET, path, preview.bytes, preview.contentType, session.accessToken)
            path
        }
        val payload = CommunitySubmitRequest(
            itemId = itemId,
            type = draft.type.wireName,
            title = draft.title,
            description = draft.description,
            tags = draft.tags,
            sourceLocalName = draft.sourceLocalName,
            filePath = filePath,
            previewPath = previewPath,
            sha256 = draft.sha256,
            sizeBytes = draft.sizeBytes,
            schemaVersion = draft.schemaVersion
        )
        val body = json.encodeToString(CommunitySubmitRequest.serializer(), payload)
        val request = Request.Builder()
            .url("$baseUrl/functions/v1/submit-community-item")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val responseBody = executeForBody(request, "发布社区条目")
        return json.decodeFromString(CommunityItemDto.serializer(), responseBody).toDomain()
    }

    private suspend fun ensureNoCharacterNameConflict(
        draft: CommunityPackageDraft,
        itemId: String?,
        accessToken: String
    ) {
        if (draft.type != CommunityItemType.CHARACTER) return
        val existing = queryItems(authorUserId = null, accessToken = accessToken, offset = 0, limit = DEFAULT_LIST_LIMIT).items
            .firstOrNull { item ->
                item.id != itemId &&
                    item.type == CommunityItemType.CHARACTER &&
                    (
                        NamePolicy.isSame(item.title, draft.title) ||
                            NamePolicy.isSame(item.sourceLocalName, draft.sourceLocalName)
                        )
            }
        require(existing == null) { "社区已有同名角色卡：${existing?.title}" }
    }

    suspend fun readPackage(item: CommunityItem): String = fetchPackage(item, countDownload = false)

    suspend fun downloadPackage(item: CommunityItem): String = fetchPackage(item, countDownload = true)

    private suspend fun fetchPackage(item: CommunityItem, countDownload: Boolean): String = withContext(Dispatchers.IO) {
        checkAvailable()
        val request = Request.Builder()
            .url("$baseUrl/storage/v1/object/public/$PACKAGE_BUCKET/${encodePath(item.filePath)}")
            .header("apikey", anonKey)
            .get()
            .build()
        val body = executeForBody(request, "下载社区包")
        if (countDownload) runCatching { incrementDownloadCount(item.id) }
            .onFailure { if (it is CancellationException) throw it; warn("Community download count update failed", it) }
        body
    }

    fun previewUrl(item: CommunityItem): String? {
        val path = item.previewPath?.takeIf(String::isNotBlank) ?: return null
        if (!configured || !enabled.value) return null
        return "$baseUrl/storage/v1/object/public/$PREVIEW_BUCKET/${encodePath(path)}"
    }

    suspend fun readPreview(item: CommunityItem): ByteArray? {
        checkAvailable()
        val url = previewUrl(item) ?: return null
        return executeForBytes(Request.Builder().url(url).header("apikey", anonKey).get().build(),
            "读取社区缩略图", 3 * 1024 * 1024)
    }

    fun clearWarmCache() {
        warmedItemsPage = null
    }

    private suspend fun requireSession(): CommunitySession {
        val current = _session.value ?: error("请先用 Discord 登录")
        if (!current.isExpired) return current
        val refreshed = runCatching { refreshSession(current) }.getOrElse {
            if (it is CancellationException) throw it
            if (it is CommunityHttpException && it.refreshCredentialRejected) {
                signOut()
                error("登录已过期，请重新登录")
            }
            throw IllegalStateException("登录暂时无法刷新；原登录已保留，请重试", it)
        }
        saveSession(refreshed)
        return refreshed
    }

    private suspend fun refreshSession(current: CommunitySession): CommunitySession {
        val refreshToken = current.refreshToken?.takeIf(String::isNotBlank) ?: error("缺少 refresh_token")
        val body = json.encodeToString(RefreshTokenRequest.serializer(), RefreshTokenRequest(refreshToken))
        val request = Request.Builder()
            .url("$baseUrl/auth/v1/token?grant_type=refresh_token")
            .header("apikey", anonKey)
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val responseBody = executeForBody(request, "刷新社区登录")
        val token = json.decodeFromString(TokenResponse.serializer(), responseBody)
        val user = token.user ?: fetchUser(token.accessToken)
        return CommunitySession(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken ?: refreshToken,
            tokenType = token.tokenType.ifBlank { current.tokenType },
            expiresAtMillis = if (token.expiresIn > 0L) {
                System.currentTimeMillis() + token.expiresIn * 1000L
            } else {
                0L
            },
            userId = user.id.ifBlank { current.userId },
            displayName = user.displayName().ifBlank { current.displayName }
        )
    }

    private suspend fun fetchUser(accessToken: String): SupabaseUser {
        val request = Request.Builder()
            .url("$baseUrl/auth/v1/user")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()
        val body = executeForBody(request, "读取 Discord 用户")
        return json.decodeFromString(SupabaseUser.serializer(), body)
    }

    private suspend fun uploadObject(
        bucket: String,
        path: String,
        bytes: ByteArray,
        contentType: String,
        accessToken: String
    ) {
        val mediaType = contentType.toMediaType()
        val request = Request.Builder()
            .url("$baseUrl/storage/v1/object/$bucket/${encodePath(path)}")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", contentType)
            .header("x-upsert", "false")
            .post(bytes.toRequestBody(mediaType))
            .build()
        executeForBody(request, "上传社区文件")
    }

    private suspend fun incrementDownloadCount(itemId: String) {
        val body = """{"item_id":"$itemId"}"""
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/rpc/increment_community_download_count")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        executeForBody(request, "更新下载次数")
    }

    private suspend fun executeForBody(request: Request, action: String): String =
        executeForBytes(request, action, CommunityPackagePolicy.MAX_PACKAGE_BYTES.toInt()).toString(Charsets.UTF_8)

    private suspend fun executeForBytes(request: Request, action: String, maximumBytes: Int): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val result = response.use { response ->
                        val responseBody = response.body
                        require(responseBody == null || responseBody.contentLength() <= maximumBytes) { "社区响应超过允许大小" }
                        val bytes = responseBody?.byteStream()?.readNBytes(maximumBytes + 1) ?: ByteArray(0)
                        require(bytes.size <= maximumBytes) { "社区响应超过允许大小" }
                        if (!response.isSuccessful) {
                            val detail = bytes.toString(Charsets.UTF_8).take(MAX_ERROR_BODY)
                            val error = runCatching { json.parseToJsonElement(detail).jsonObject }.getOrNull()
                            val code = (error?.get("error_code") ?: error?.get("code") ?: error?.get("error"))
                                ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                                ?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,80}")) }
                            throw CommunityHttpException(action, response.code, code,
                                missingRuntimeConfig = code == "PGRST205" && detail.contains("community_runtime_config"))
                        }
                        bytes
                    }
                    continuation.resume(result)
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun Throwable.isMissingRuntimeConfig(): Boolean =
        this is CommunityHttpException && missingRuntimeConfig

    private fun saveSession(session: CommunitySession) {
        sessions.save(session)
        _session.value = session
    }

    private suspend fun checkAvailable() {
        checkConfigured()
        check(refreshEnabledStatus()) { disabledMessage }
    }

    private fun checkConfigured() {
        check(configured) { "请先配置 CHATBAR_SUPABASE_URL 和 CHATBAR_SUPABASE_ANON_KEY" }
    }

    private fun storagePath(
        userId: String,
        type: CommunityItemType,
        title: String,
        extension: String,
        prefix: String = "packages"
    ): String {
        val safeTitle = safePathPart(title)
        val ext = safePathPart(extension).ifBlank { "json" }
        return "$userId/$prefix/${type.wireName}/${UUID.randomUUID()}_$safeTitle.$ext"
    }

    private fun safePathPart(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_')
            .take(MAX_PATH_PART)
            .ifBlank { "item" }

    private fun callbackParams(callback: String): Map<String, String> {
        val uri = java.net.URI(callback)
        val expected = java.net.URI(redirectUri)
        require(uri.scheme == expected.scheme && uri.host == expected.host && uri.port == expected.port &&
            uri.path == expected.path && uri.userInfo == null) { "社区登录回调地址不匹配" }
        return buildMap {
            listOfNotNull(uri.rawQuery, uri.rawFragment).forEach { part ->
                part.split("&").forEach { pair ->
                    val index = pair.indexOf('=')
                    if (index > 0) put(pair.substring(0, index).urlDecode(), pair.substring(index + 1).urlDecode())
                }
            }
        }
    }

    private fun String.urlDecode(): String =
        URLDecoder.decode(this, StandardCharsets.UTF_8.name())

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { urlEncode(it).replace("+", "%20") }

    private fun SupabaseUser.displayName(): String {
        val metadataName = userMetadata.firstString("full_name")
            ?: userMetadata.firstString("name")
            ?: userMetadata.firstString("preferred_username")
            ?: userMetadata.firstString("user_name")
        return metadataName ?: email?.substringBefore('@') ?: "Discord 用户"
    }

    private fun JsonObject.firstString(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)

    companion object {
        private const val PACKAGE_BUCKET = "community-packages"
        private const val PREVIEW_BUCKET = "community-previews"
        private const val COMMUNITY_ITEM_SELECT =
            "id,type,title,description,tags,author_user_id,author_name,source_local_name,file_path,preview_path,sha256,size_bytes,schema_version,download_count,created_at,updated_at"
        private const val DEFAULT_LIST_LIMIT = 100
        private const val DEFAULT_PAGE_SIZE = 20
        private const val MAX_PAGE_SIZE = 100
        private const val STATUS_CACHE_TTL_MS = 5_000L
        private const val STATUS_POLL_INTERVAL_MS = 5_000L
        private const val WARM_CACHE_TTL_MS = 60_000L
        private const val MAX_ERROR_BODY = 600
        private const val MAX_TAGS = 8
        private const val MAX_PATH_PART = 80
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private data class WarmedCommunityPage(
    val limit: Int,
    val page: CommunityItemPage,
    val loadedAt: Long
)

@Serializable
private data class CommunityRuntimeConfigDto(
    val enabled: Boolean = true,
    val message: String = ""
)

@Serializable
private data class SupabaseUser(
    val id: String = "",
    val email: String? = null,
    @SerialName("user_metadata")
    val userMetadata: JsonObject = JsonObject(emptyMap())
)

@Serializable
private data class RefreshTokenRequest(
    @SerialName("refresh_token")
    val refreshToken: String
)

@Serializable
private data class PkceTokenRequest(
    @SerialName("auth_code") val authCode: String,
    @SerialName("code_verifier") val codeVerifier: String,
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("token_type")
    val tokenType: String = "bearer",
    @SerialName("expires_in")
    val expiresIn: Long = 0L,
    val user: SupabaseUser? = null
)

data class CommunityConfiguration(val baseUrl: String, val anonKey: String, val redirectUri: String = "chatbar://auth/callback")
interface CommunitySessionStore {
    fun load(): CommunitySession?
    fun save(session: CommunitySession)
    fun clear()
}
interface CommunityLocalPackages {
    suspend fun candidates(type: CommunityItemType): List<CommunityUploadCandidate>
    suspend fun export(type: CommunityItemType, localId: String): CommunityLocalPackage
}
data class CommunityLocalPackage(val sourceName: String, val packageText: String, val preview: CommunityPreviewDraft? = null)

/** Status/code projection only. Never retain an arbitrary auth/server response body in errors. */
class CommunityHttpException internal constructor(action: String, val status: Int, private val errorCode: String?,
    internal val missingRuntimeConfig: Boolean = false,
) : IllegalStateException("${action}失败：HTTP $status") {
    val refreshCredentialRejected: Boolean get() = status in setOf(400, 401, 403) && errorCode in setOf(
        "refresh_token_not_found", "refresh_token_already_used", "refresh_token_expired", "invalid_refresh_token",
        "session_not_found", "invalid_grant",
    )
}
