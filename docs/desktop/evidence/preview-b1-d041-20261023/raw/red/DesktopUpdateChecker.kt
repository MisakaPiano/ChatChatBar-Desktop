package com.example.chatbar.desktop

import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*

internal data class DesktopReleaseVersion(val numbers: List<Long>, val preview: List<String>) : Comparable<DesktopReleaseVersion> {
    override fun compareTo(other: DesktopReleaseVersion): Int {
        numbers.zip(other.numbers).forEach { (left, right) -> left.compareTo(right).takeIf { it != 0 }?.let { return it } }
        if (preview.isEmpty() != other.preview.isEmpty()) return if (preview.isEmpty()) 1 else -1
        preview.zip(other.preview).forEach { (left, right) ->
            val l = left.toLongOrNull(); val r = right.toLongOrNull()
            val order = when { l != null && r != null -> l.compareTo(r); l != null -> -1; r != null -> 1; else -> left.compareTo(right) }
            if (order != 0) return order
        }
        return preview.size.compareTo(other.preview.size)
    }
    companion object {
        fun parse(value: String): DesktopReleaseVersion {
            require(value.length in 5..80)
            val match = requireNotNull(Regex("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?").matchEntire(value))
            val preview = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.').orEmpty()
            require(preview.all { token -> token.length <= 20 && (token.any { !it.isDigit() } || token == "0" || !token.startsWith('0')) })
            require(preview.filter { token -> token.all(Char::isDigit) }.all { it.toLongOrNull() != null })
            return DesktopReleaseVersion((1..3).map { match.groupValues[it].toLong() }, preview)
        }
    }
}

internal data class DesktopRelease(val version: String, val published: Instant, val notes: String, val url: String, val preview: Boolean)
internal data class DesktopUpdateResult(val latest: DesktopRelease?, val newer: Boolean)

internal object DesktopUpdatePolicy {
    const val REPOSITORY = "MisakaPiano/ChatChatBar-Desktop"
    const val API = "https://api.github.com/repos/$REPOSITORY/releases?per_page=100"
    const val TAG_PREFIX = "desktop-v"
    const val MAX_BYTES = 1024 * 1024
    fun trustedPage(url: String, version: String): Boolean = runCatching {
        DesktopReleaseVersion.parse(version)
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawPath == "/$REPOSITORY/releases/tag/$TAG_PREFIX$version"
    }.getOrDefault(false)

    fun select(bytes: ByteArray, installed: String, includePreview: Boolean): DesktopUpdateResult {
        require(bytes.size <= MAX_BYTES)
        val current = DesktopReleaseVersion.parse(installed)
        val entries = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonArray
        require(entries.size <= 100)
        val releases = entries.mapNotNull { entry ->
            val obj = entry.jsonObject
            if (obj.getValue("draft").jsonPrimitive.boolean) return@mapNotNull null
            val tag = obj.getValue("tag_name").jsonPrimitive.content
            if (!tag.startsWith(TAG_PREFIX)) return@mapNotNull null // Android/inherited tags are not Desktop versions.
            val version = tag.removePrefix(TAG_PREFIX)
            val parsed = DesktopReleaseVersion.parse(version)
            val preview = obj.getValue("prerelease").jsonPrimitive.boolean
            require(preview == parsed.preview.isNotEmpty()) { "INCONSISTENT_RELEASE_CHANNEL" }
            val url = obj.getValue("html_url").jsonPrimitive.content
            require(trustedPage(url, version)) { "UNTRUSTED_RELEASE_PAGE" }
            val published = Instant.parse(obj.getValue("published_at").jsonPrimitive.content)
            val notes = obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
            require(notes.length <= 128 * 1024 && notes.none { it.code < 32 && it !in "\n\r\t" })
            if (!includePreview && preview) null else DesktopRelease(version, published, notes, url, preview)
        }
        require(releases.map { it.version }.distinct().size == releases.size)
        val latest = releases.maxByOrNull { DesktopReleaseVersion.parse(it.version) }
        return DesktopUpdateResult(latest, latest != null && DesktopReleaseVersion.parse(latest.version) > current)
    }
}

/** Explicit, unauthenticated single check. No provider credentials, redirects, retry or download. */
internal class DesktopUpdateChecker(private val client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .callTimeout(20, TimeUnit.SECONDS).build(),
    private val endpoint: String = DesktopUpdatePolicy.API) {
    suspend fun check(installed: String, includePreview: Boolean): DesktopUpdateResult = withContext(Dispatchers.IO) {
        check(endpoint == DesktopUpdatePolicy.API || URI(endpoint).host in setOf("127.0.0.1", "localhost", "::1"))
        // A GET has no one-shot request body. Bound actual wire attempts at the network layer,
        // including OkHttp's HTTP follow-up paths which retryOnConnectionFailure does not cover.
        val sent = java.util.concurrent.atomic.AtomicBoolean()
        val singleAttempt = client.newBuilder().addNetworkInterceptor { chain ->
            if (!sent.compareAndSet(false, true)) throw java.net.ProtocolException("UPDATE_RETRY_DISABLED")
            chain.proceed(chain.request())
        }.build()
        val call = singleAttempt.newCall(Request.Builder().url(endpoint)
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "ChatChatBar-Desktop-UpdateCheck").build())
        coroutineScope {
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    check(response.code == 200) { "UPDATE_HTTP_${response.code}" }
                    val body = requireNotNull(response.body)
                    check(body.contentLength() <= DesktopUpdatePolicy.MAX_BYTES)
                    val output = java.io.ByteArrayOutputStream()
                    body.byteStream().use { stream ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = stream.read(buffer); if (n < 0) break
                            check(output.size() + n <= DesktopUpdatePolicy.MAX_BYTES)
                            output.write(buffer, 0, n)
                        }
                    }
                    DesktopUpdatePolicy.select(output.toByteArray(), installed, includePreview)
                }
            } finally { cancellation.cancelAndJoin() }
        }
    }
}

internal data class DesktopBuildIdentity(val version: String, val commit: String) {
    companion object {
        fun load(): DesktopBuildIdentity {
            val properties = java.util.Properties()
            DesktopBuildIdentity::class.java.getResourceAsStream("/desktop-build.properties")?.use { properties.load(it) }
            return DesktopBuildIdentity(properties.getProperty("version", "0.1.0-preview.1"), properties.getProperty("commit", "development"))
        }
    }
}
