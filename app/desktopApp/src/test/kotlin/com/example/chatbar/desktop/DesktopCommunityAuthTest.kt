package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.community.*
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import kotlin.test.*

class DesktopCommunityAuthTest {
    @Test fun `PKCE bound warm return verifies user persists protected custody and refuses replacement replay cancel and expiry`() = runBlocking {
        val parent = Files.createTempDirectory("ccb-community-auth-")
        val root = Files.createDirectory(parent.resolve("root"))
        val values = mutableMapOf<DesktopCredentialKey, String>()
        val secrets = object : DesktopSecretStore {
            override fun load(key: DesktopCredentialKey) = synchronized(values) { values[key] }
            override fun save(key: DesktopCredentialKey, secret: String) { synchronized(values) { values[key] = secret } }
            override fun delete(key: DesktopCredentialKey) { synchronized(values) { values.remove(key) }; Unit }
        }
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
            parent.resolve("bootstrap.json")), secretStoreFactory = { secrets })
        try {
            MockWebServer().use { server ->
                val users = AtomicInteger()
                val exchanges = AtomicInteger()
                val codes = java.util.concurrent.ConcurrentHashMap<String, String>()
                val browser = mutableListOf<URI>()
                fun challenge(verifier: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
                codes["attacker-code"] = challenge("attacker".repeat(8))
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when {
                        request.path!!.startsWith("/rest/v1/community_runtime_config?") -> MockResponse().setBody("""[{"enabled":true}]""")
                        request.path!!.startsWith("/rest/v1/community_items?") -> MockResponse().setBody("[]")
                        request.path == "/auth/v1/token?grant_type=pkce" -> {
                            exchanges.incrementAndGet()
                            val fields = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                            val code = fields.getValue("auth_code").jsonPrimitive.content
                            val verifier = fields.getValue("code_verifier").jsonPrimitive.content
                            if (codes[code] == challenge(verifier)) {
                                codes.remove(code)
                                MockResponse().setBody("""{"access_token":"synthetic-access","refresh_token":"synthetic-refresh","expires_in":3600}""")
                            } else MockResponse().setResponseCode(400).setBody("""{"error_code":"bad_code_verifier"}""")
                        }
                        request.path == "/auth/v1/user" -> { users.incrementAndGet(); MockResponse().setBody("""{"id":"owner","user_metadata":{"name":"Synthetic Owner"}}""") }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
                val http = OkHttpClient.Builder().callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
                val sessions = DesktopCommunitySessionStore(secrets, app.transferJson)
                val packages = DesktopCommunityLocalPackages(app.characterRepository, app.formatCardRepository, app.worldBookRepository,
                    app.characterTransfers, app.formatTransfers, app.worldBookTransfers, app.characterResourceStore, app.dataOperationCoordinator)
                val client = CommunityClient(CommunityConfiguration(server.url("/").toString(), "synthetic-public"),
                    packages, sessions, app.transferJson, http, loadSessionAtStartup = false)
                val returning = object : DesktopCommunityLoginReturn {
                    @Volatile var active = false
                    var releases = 0
                    override fun prepare(): DesktopCommunityReturnStatus { active = true; return DesktopCommunityReturnStatus.READY }
                    override fun release() { if (active) releases++; active = false }
                }
                val now = AtomicLong(1_000_000)
                val owner = DesktopCommunityController(client, http, app.createTypedTransferController(UnconfiguredDesktopFilePicker),
                    app.characterRepository, app.characterTransfers, app.formatTransfers, app.worldBookTransfers, app.dataOperationCoordinator,
                    browse = { uri ->
                        val query = uri.rawQuery.split('&').associate { pair -> val parts = pair.split('=', limit = 2)
                            URLDecoder.decode(parts[0], Charsets.UTF_8) to URLDecoder.decode(parts[1], Charsets.UTF_8) }
                        assertEquals("chatbar://auth/callback", query["redirect_to"])
                        assertEquals("s256", query["code_challenge_method"])
                        synchronized(browser) { browser += uri; codes["code-${browser.size}"] = requireNotNull(query["code_challenge"]) }
                    }, pollMillis = 20, loginReturn = returning, clock = now::get)
                val activation = requireNotNull(DesktopExternalActivation.open(parent.resolve("activation"), emptyList()))
                activation.communityAuthReceiver = owner::receiveLoginReturn
                try {
                    owner.refresh(); idle(owner)
                    fun latestCode(): String = synchronized(browser) { "code-${browser.size}" }
                    fun callback(code: String) = "chatbar://auth/callback?code=$code"
                    owner.beginLogin(); idle(owner)
                    val first = latestCode()
                    assertFalse(owner.receiveLoginReturn(callback(first) + "#access_token=attacker"))
                    assertFalse(owner.receiveLoginReturn(callback(first).replace("chatbar://auth", "chatbar://other")))
                    assertTrue(values.isEmpty()); assertEquals(0, users.get())
                    assertTrue(owner.receiveLoginReturn(callback("attacker-code"))); idle(owner)
                    assertTrue(owner.state.value.error); assertTrue(values.isEmpty()); assertEquals(0, users.get())
                    owner.beginLogin(); idle(owner); val second = latestCode(); assertNotEquals(first, second)
                    assertTrue(owner.receiveLoginReturn(callback(first))); idle(owner)
                    assertTrue(owner.state.value.error); assertTrue(values.isEmpty()); assertEquals(0, users.get())
                    owner.beginLogin(); idle(owner); val current = latestCode()
                    DesktopExternalActivation.forwardCommunityCallback(parent.resolve("activation"), callback(current))
                    idle(owner)
                    assertEquals("Synthetic Owner", owner.state.value.displayName)
                    assertFalse(owner.state.value.loginOpen); assertEquals(1, users.get()); assertEquals(3, returning.releases)
                    assertEquals("owner", sessions.load()!!.userId)
                    assertEquals(setOf(DesktopCredentialKey.DormantCommunitySession), values.keys)
                    assertTrue(activation.queue.state.value.files.isEmpty())
                    assertFalse(Files.exists(requireNotNull(activation.queue.recordPath)))
                    assertFails { DesktopExternalActivation.forwardCommunityCallback(parent.resolve("activation"), callback(current)) }
                    assertEquals(3, exchanges.get()); assertEquals(1, users.get())
                    owner.beginLogin(); idle(owner); val cancelled = latestCode()
                    owner.closeLogin(); assertFalse(owner.receiveLoginReturn(callback(cancelled)))
                    owner.beginLogin(); idle(owner); val expired = latestCode()
                    now.addAndGet(600_001); assertFalse(owner.receiveLoginReturn(callback(expired)))
                    owner.start()
                    withTimeout(5_000) { while (owner.state.value.loginOpen || returning.active) delay(10) }
                    assertFalse(returning.active); assertEquals(5, returning.releases)
                    assertEquals(1, users.get()); assertEquals("owner", sessions.load()!!.userId)
                } finally { activation.close(); owner.closeAndDrain() }
            }
        } finally { app.close(); parent.toFile().deleteRecursively() }
    }
    private suspend fun idle(owner: DesktopCommunityController) = withTimeout(5_000) { while (owner.state.value.busy) delay(10) }
}
