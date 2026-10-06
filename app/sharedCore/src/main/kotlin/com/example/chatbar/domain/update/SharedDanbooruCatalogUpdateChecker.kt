package com.example.chatbar.domain.update
import com.example.chatbar.domain.image.DanbooruCatalogMetadata
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

class SharedDanbooruCatalogUpdateChecker(
    private val catalog: com.example.chatbar.domain.image.NovelAiTagLookup,
    private val userAgent: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {
    suspend fun checkLatestCatalog(): DanbooruCatalogUpdateInfo? = withContext(Dispatchers.IO) {
        val current = catalog.catalogMetadata()
        val commit = try { fetchLatestCommit() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        val content = fetchContents(commit?.sha?.takeIf(String::isNotBlank) ?: DanbooruCatalogSource.SOURCE_BRANCH)
        if (content.sha.equals(current.sourceSha, ignoreCase = true)) return@withContext null
        if (content.sha.isBlank() || content.size <= 0L || content.downloadUrl.isBlank()) {
            throw IOException("GitHub 返回的词库文件信息不完整")
        }
        val pinnedDownloadUrl = commit?.sha?.takeIf(String::isNotBlank)?.let { commitSha ->
            "https://raw.githubusercontent.com/${DanbooruCatalogSource.SOURCE_OWNER}/" +
                "${DanbooruCatalogSource.SOURCE_REPOSITORY}/$commitSha/${DanbooruCatalogSource.SOURCE_PATH}"
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
        val request = githubRequest(url)
        return execute(request) { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("GitHub 词库 API HTTP ${response.code}")
            json.decodeFromString(GitHubContentFile.serializer(), body)
        }
    }

    private suspend fun fetchLatestCommit(): GitHubCommitItem? {
        val url = "https://api.github.com/repos/${DanbooruCatalogSource.SOURCE_OWNER}/" +
            "${DanbooruCatalogSource.SOURCE_REPOSITORY}/commits" +
            "?path=${DanbooruCatalogSource.SOURCE_PATH}&per_page=1"
        val request = githubRequest(url)
        return execute(request) { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("GitHub 词库提交 API HTTP ${response.code}")
            json.decodeFromString(ListSerializer(GitHubCommitItem.serializer()), body).firstOrNull()
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
