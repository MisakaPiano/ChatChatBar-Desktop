package com.example.chatbar.domain.update

import com.example.chatbar.domain.image.DanbooruCatalogMetadata
import com.example.chatbar.domain.ProxyAwareClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

object DanbooruCatalogSource {
    const val SOURCE_OWNER = "ffdkj"
    const val SOURCE_REPOSITORY = "ffdkj-Danbooru_Tag-Chinese-English-Translation-Table"
    const val SOURCE_PATH = "tag.sqlite"
    const val SOURCE_BRANCH = "main"
    const val SOURCE_PAGE_URL = "https://github.com/$SOURCE_OWNER/$SOURCE_REPOSITORY"
}

data class DanbooruCatalogUpdateInfo(
    val currentMetadata: DanbooruCatalogMetadata,
    val latestSourceSha: String,
    val latestCommitTime: String,
    val downloadUrl: String,
    val sourcePageUrl: String,
    val sizeBytes: Long
)

enum class DanbooruCatalogCheckStage { LOCAL_METADATA, COMMITS, CONTENTS }

enum class DanbooruCatalogCheckKind { HTTP, NETWORK, INVALID_RESPONSE, LOCAL }

class DanbooruCatalogCheckFailure internal constructor(
    val stage: DanbooruCatalogCheckStage,
    val kind: DanbooruCatalogCheckKind,
    val httpStatus: Int? = null,
    val rateLimited: Boolean = false,
    val retryAfterSeconds: Long? = null,
    internal val hasRetryAfter: Boolean = false,
    cause: Exception? = null,
) : IOException(
    "Danbooru 词库检查失败 ($stage / $kind" + (httpStatus?.let { " / HTTP $it" } ?: "") + ")",
    cause
)

class SharedDanbooruCatalogUpdateChecker(
    private val catalog: com.example.chatbar.domain.image.NovelAiTagLookup,
    private val userAgent: String,
    private val client: OkHttpClient = ProxyAwareClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val retryTransientHttp: Boolean = false,
) {
    suspend fun checkLatestCatalog(): DanbooruCatalogUpdateInfo? = withContext(Dispatchers.IO) {
        val current = try {
            catalog.catalogMetadata()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw DanbooruCatalogCheckFailure(DanbooruCatalogCheckStage.LOCAL_METADATA, DanbooruCatalogCheckKind.LOCAL, cause = failure)
        }
        val commit = try {
            fetchLatestCommit()?.takeIf { GIT_SHA.matches(it.sha) }
        } catch (failure: DanbooruCatalogCheckFailure) {
            if (failure.rateLimited || failure.hasRetryAfter) throw failure
            null
        }
        val commitSha = commit?.sha
        val content = fetchContents(commitSha ?: DanbooruCatalogSource.SOURCE_BRANCH)
        if (!GIT_SHA.matches(content.sha)) {
            throw DanbooruCatalogCheckFailure(DanbooruCatalogCheckStage.CONTENTS, DanbooruCatalogCheckKind.INVALID_RESPONSE)
        }
        if (content.sha.equals(current.sourceSha, ignoreCase = true)) return@withContext null
        if (content.size <= 0L || content.downloadUrl.isBlank()) {
            throw DanbooruCatalogCheckFailure(DanbooruCatalogCheckStage.CONTENTS, DanbooruCatalogCheckKind.INVALID_RESPONSE)
        }
        val pinnedDownloadUrl = commitSha?.let {
            "https://raw.githubusercontent.com/${DanbooruCatalogSource.SOURCE_OWNER}/" +
                "${DanbooruCatalogSource.SOURCE_REPOSITORY}/$it/${DanbooruCatalogSource.SOURCE_PATH}"
        } ?: content.downloadUrl
        DanbooruCatalogUpdateInfo(
            currentMetadata = current,
            latestSourceSha = content.sha,
            latestCommitTime = commit?.commit?.committer?.date.orEmpty(),
            downloadUrl = pinnedDownloadUrl,
            sourcePageUrl = content.htmlUrl.ifBlank { DanbooruCatalogSource.SOURCE_PAGE_URL },
            sizeBytes = content.size
        )
    }

    private suspend fun fetchContents(ref: String): GitHubContentFile {
        val url = "https://api.github.com/repos/${DanbooruCatalogSource.SOURCE_OWNER}/" +
            "${DanbooruCatalogSource.SOURCE_REPOSITORY}/contents/${DanbooruCatalogSource.SOURCE_PATH}" +
            "?ref=$ref"
        return readGitHub(githubRequest(url), DanbooruCatalogCheckStage.CONTENTS) {
            json.decodeFromString(GitHubContentFile.serializer(), it)
        }
    }

    private suspend fun fetchLatestCommit(): GitHubCommitItem? {
        val url = "https://api.github.com/repos/${DanbooruCatalogSource.SOURCE_OWNER}/" +
            "${DanbooruCatalogSource.SOURCE_REPOSITORY}/commits" +
            "?path=${DanbooruCatalogSource.SOURCE_PATH}&per_page=1"
        return readGitHub(githubRequest(url), DanbooruCatalogCheckStage.COMMITS) {
            json.decodeFromString(ListSerializer(GitHubCommitItem.serializer()), it).firstOrNull()
        }
    }

    private suspend fun <T> readGitHub(request: Request, stage: DanbooruCatalogCheckStage, decode: (String) -> T): T {
        suspend fun readOnce(): T = execute(request) { response ->
            if (!response.isSuccessful) {
                val retryAfter = response.header("Retry-After")
                throw DanbooruCatalogCheckFailure(
                    stage, DanbooruCatalogCheckKind.HTTP, response.code,
                    rateLimited = response.code == 429 ||
                        (response.code == 403 && response.header("X-RateLimit-Remaining")?.trim() == "0"),
                    retryAfterSeconds = retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 },
                    hasRetryAfter = retryAfter != null,
                )
            }
            decode(response.body?.string().orEmpty())
        }
        return try {
            try {
                readOnce()
            } catch (failure: DanbooruCatalogCheckFailure) {
                if (!retryTransientHttp || failure.hasRetryAfter || failure.httpStatus?.let(TRANSIENT_HTTP::contains) != true) throw failure
                delay(350)
                readOnce()
            }
        } catch (failure: DanbooruCatalogCheckFailure) {
            throw failure
        } catch (failure: SerializationException) {
            throw DanbooruCatalogCheckFailure(stage, DanbooruCatalogCheckKind.INVALID_RESPONSE, cause = failure)
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            throw DanbooruCatalogCheckFailure(stage, DanbooruCatalogCheckKind.NETWORK, cause = failure)
        }
    }

    private suspend fun <T> execute(request: Request, read: (okhttp3.Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try { call.execute().use(read) } finally { cancellation.cancel() }
    }

    private fun githubRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("Cache-Control", "no-cache")
        .header("User-Agent", userAgent)
        .get()
        .build()

    private companion object {
        val GIT_SHA = Regex("[0-9a-fA-F]{40}")
        val TRANSIENT_HTTP = setOf(502, 503, 504)
    }
}

@Serializable
private data class GitHubContentFile(
    val sha: String = "",
    val size: Long = 0L,
    @SerialName("download_url") val downloadUrl: String = "",
    @SerialName("html_url") val htmlUrl: String = ""
)

@Serializable
private data class GitHubCommitItem(
    val sha: String = "",
    val commit: GitHubCommitDetails = GitHubCommitDetails()
)

@Serializable
private data class GitHubCommitDetails(
    val committer: GitHubCommitter = GitHubCommitter()
)

@Serializable
private data class GitHubCommitter(
    val date: String = ""
)
