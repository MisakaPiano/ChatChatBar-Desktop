package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.SyncGroupTrust
import com.example.chatbar.domain.syncStore.SyncStoreId
import com.example.chatbar.domain.syncStore.SyncVersionRef
import com.example.chatbar.domain.syncStore.webdav.WebDavEndpointPolicy
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

/** Local configuration custody only; reversible protection and every account string are synthetic. */
class LanSyncGroupCloudRoutesTest {
    private class Fixture(val root: Path) {
        val identity = LanSyncTlsIdentity.create()
        val trust = SyncGroupTrust(SyncStoreId.random(), SyncVersionRef(SyncStoreId.random(), "a".repeat(64)),
            Base64.getEncoder().encodeToString(identity.certificate.encoded))
        val file = root.resolve("cloud-routes.protected")
        val password = "synthetic private password 123".toByteArray(Charsets.UTF_8)
        fun protect(bytes: ByteArray): ByteArray = byteArrayOf(67, 82) + ByteArray(bytes.size) { (bytes[it].toInt() xor 0x5a).toByte() }
        fun unprotect(bytes: ByteArray): ByteArray {
            require(bytes.size > 2 && bytes[0] == 67.toByte() && bytes[1] == 82.toByte())
            return ByteArray(bytes.size - 2) { (bytes[it + 2].toInt() xor 0x5a).toByte() }
        }
        fun catalog(trust: SyncGroupTrust = this.trust, device: String = identity.fingerprint,
            protect: (ByteArray) -> ByteArray = { this.protect(it) }, unprotect: (ByteArray) -> ByteArray = { this.unprotect(it) },
            policy: WebDavEndpointPolicy = WebDavEndpointPolicy.HTTPS_ONLY) =
            LanSyncGroupCloudRoutes(root, trust, device, protect, unprotect, policy)
        fun draft(endpoint: String = "https://synthetic.invalid/private/", enabled: Boolean = true) =
            LanSyncGroupCloudDraft("synthetic private drive", endpoint, "synthetic private user", password, enabled)
    }
    private fun fixture(work: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("synthetic-cloud-route-custody-").toRealPath()
        val f = Fixture(root)
        try { work(f) } finally { f.password.fill(0); check(root.toFile().deleteRecursively()) }
    }
    private fun failure(code: String, work: () -> Unit): LanSyncGroupCloudRoutesException {
        val error = assertFailsWith<LanSyncGroupCloudRoutesException> { work() }
        assertEquals("SYNC_GROUP_CLOUD_ROUTES_$code", error.message)
        assertNull(error.cause)
        return error
    }

    @Test fun protectedPersistenceRedactsDtosAndErasesBorrowedTemporarySecrets() = fixture { f ->
        var writePlain: ByteArray? = null
        var readPlain: ByteArray? = null
        val routes = f.catalog(protect = { writePlain = it; f.protect(it) }, unprotect = { f.unprotect(it).also { plain -> readPlain = plain } })
        assertTrue(routes.rows().isEmpty())
        assertFalse(Files.exists(f.file), "Reading an explicitly constructed empty catalog creates no identity or protected configuration")
        val originalPassword = f.password.copyOf()
        val draft = f.draft()
        val id = routes.add(draft)
        assertContentEquals(originalPassword, f.password, "The draft password is borrowed from its caller")
        assertTrue(requireNotNull(writePlain).all { it == 0.toByte() })
        val row = routes.rows().single()
        assertEquals(id, row.id)
        assertEquals(draft.name, row.name)
        assertEquals(draft.endpoint, row.endpoint)
        assertEquals(draft.username, row.username)
        assertTrue(requireNotNull(readPlain).all { it == 0.toByte() })
        Files.walk(f.root).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach { path ->
            val persisted = Files.readAllBytes(path).toString(Charsets.ISO_8859_1)
            for (sensitive in listOf(draft.name, draft.endpoint, draft.username, originalPassword.toString(Charsets.UTF_8))) assertFalse(persisted.contains(sensitive))
        } }
        var borrowed: ByteArray? = null
        val credentials = routes.credentials(id)
        credentials.use {
            assertEquals(row.endpoint, it.endpoint)
            assertEquals(row.username, it.username)
            it.withPassword { password -> borrowed = password; assertContentEquals(originalPassword, password) }
            assertTrue(requireNotNull(borrowed).all { byte -> byte == 0.toByte() })
        }
        failure("CREDENTIALS_CLOSED") { credentials.withPassword { error("closed secret must not escape") } }
        for (text in listOf(draft.toString(), row.toString(), credentials.toString())) {
            assertTrue(text.contains("REDACTED"))
            for (sensitive in listOf(draft.name, draft.endpoint, draft.username, originalPassword.toString(Charsets.UTF_8))) assertFalse(text.contains(sensitive))
        }
        originalPassword.fill(0)
    }

    @Test fun coldReopenKeepsRouteIdentityAndEnableChangesWithoutInventingCompletion() = fixture { f ->
        val first = f.catalog()
        val id = first.add(f.draft(enabled = false))
        assertEquals(1L, first.revision.value)
        val reopened = f.catalog()
        assertEquals(first.rows(), reopened.rows())
        assertEquals(id, reopened.rows().single().id)
        assertFalse(reopened.rows().single().enabled)
        failure("DISABLED") { reopened.credentials(id) }
        assertEquals(0L, reopened.revision.value)
        reopened.setEnabled(id, false)
        assertEquals(0L, reopened.revision.value)
        reopened.setEnabled(id, true)
        assertEquals(1L, reopened.revision.value)
        reopened.credentials(id).use { credentials -> credentials.withPassword { assertContentEquals(f.password, it) } }
        assertTrue(f.catalog().rows().single().enabled)
        assertEquals(id, f.catalog().rows().single().id)
    }

    @Test fun removeRetainsTheRetiredIdentityAndReaddingSameEndpointCreatesANewTarget() = fixture { f ->
        val routes = f.catalog()
        val untouched = f.root.resolve("synthetic-unrelated-outbox.bin")
        val original = byteArrayOf(1, 2, 3, 4)
        Files.write(untouched, original)
        val old = routes.add(f.draft())
        routes.remove(old)
        assertEquals(2L, routes.revision.value)
        assertTrue(routes.rows().isEmpty())
        failure("NOT_FOUND") { routes.credentials(old) }
        routes.remove(old)
        assertEquals(2L, routes.revision.value)
        val reopened = f.catalog()
        val replacement = reopened.add(f.draft())
        assertNotEquals(old, replacement)
        assertEquals(replacement, reopened.rows().single().id)
        failure("NOT_FOUND") { reopened.setEnabled(old, true) }
        assertContentEquals(original, Files.readAllBytes(untouched))
        val plain = f.unprotect(Files.readAllBytes(f.file))
        try { assertTrue(plain.toString(Charsets.UTF_8).contains(old.value), "Retired opaque route identity remains reserved") }
        finally { plain.fill(0) }
    }

    @Test fun wrongGroupGenesisOwnerAndDeviceRefuseWithoutRewritingTheCatalog() = fixture { f ->
        val original = f.catalog()
        original.add(f.draft())
        val bytes = Files.readAllBytes(f.file)
        val other = LanSyncTlsIdentity.create()
        val cases = listOf(
            f.catalog(trust = f.trust.copy(group = SyncStoreId.random())),
            f.catalog(trust = f.trust.copy(genesis = SyncVersionRef(SyncStoreId.random(), "b".repeat(64)))),
            f.catalog(trust = f.trust.copy(ownerCertificate = Base64.getEncoder().encodeToString(other.certificate.encoded))),
            f.catalog(device = other.fingerprint))
        cases.forEach { wrong ->
            failure("BINDING") { wrong.rows() }
            failure("BINDING") { wrong.add(f.draft()) }
            assertEquals(0L, wrong.revision.value)
            assertContentEquals(bytes, Files.readAllBytes(f.file))
        }
        assertEquals(original.rows(), f.catalog(trust = f.trust.copy(checkpoint = SyncVersionRef(SyncStoreId.random(), "c".repeat(64)))).rows(),
            "A later ledger checkpoint does not replace the fixed group/genesis/owner/device binding")
    }

    @Test fun damageAndProtectionFailureKeepEvidenceAndEmitNoMutationTokenOrSecretError() = fixture { f ->
        val routes = f.catalog()
        val id = routes.add(f.draft())
        val original = Files.readAllBytes(f.file)
        var borrowed: ByteArray? = null
        val secretError = "synthetic private user synthetic private password https://synthetic.invalid/private/"
        val failedWrite = f.catalog(protect = { borrowed = it; throw IOException(secretError) })
        val writeError = failure("PROTECTION_FAILED") { failedWrite.setEnabled(id, false) }
        assertFalse(writeError.toString().contains(secretError))
        assertTrue(requireNotNull(borrowed).all { it == 0.toByte() })
        assertEquals(0L, failedWrite.revision.value)
        assertContentEquals(original, Files.readAllBytes(f.file))
        val failedRead = f.catalog(unprotect = { borrowed = it; throw IOException(secretError) })
        failure("PROTECTION_FAILED") { failedRead.rows() }
        assertTrue(requireNotNull(borrowed).all { it == 0.toByte() })
        assertEquals(0L, failedRead.revision.value)
        assertContentEquals(original, Files.readAllBytes(f.file))
        val damaged = f.protect("{synthetic malformed catalog".toByteArray(Charsets.UTF_8))
        Files.write(f.file, damaged)
        val damagedRoutes = f.catalog()
        failure("INVALID") { damagedRoutes.rows() }
        failure("INVALID") { damagedRoutes.add(f.draft()) }
        assertEquals(0L, damagedRoutes.revision.value)
        assertContentEquals(damaged, Files.readAllBytes(f.file))
    }

    @Test fun productionEndpointPolicyRejectsCredentialUrlsAndOnlySuccessfulChangesAdvanceRevision() = fixture { f ->
        val routes = f.catalog()
        assertEquals(0L, routes.revision.value)
        routes.rows()
        for (endpoint in listOf("http://synthetic.invalid/private/", "https://private-user:private-password@synthetic.invalid/",
            "https://synthetic.invalid/private/?password=secret", "https://synthetic.invalid/private/#secret", "https://synthetic.invalid/private")) {
            val error = failure("ENDPOINT") { routes.add(f.draft(endpoint)) }
            assertFalse(error.toString().contains(endpoint))
            assertEquals(0L, routes.revision.value)
            assertFalse(Files.exists(f.file))
        }
        val id = routes.add(f.draft())
        assertEquals(1L, routes.revision.value)
        routes.setEnabled(id, true)
        assertEquals(1L, routes.revision.value)
        routes.setEnabled(id, false)
        assertEquals(2L, routes.revision.value)
        failure("NOT_FOUND") { routes.remove(SyncStoreId.random()) }
        assertEquals(2L, routes.revision.value)
        routes.remove(id)
        assertEquals(3L, routes.revision.value)
        val syntheticRoot = Files.createDirectory(f.root.resolve("explicit-loopback-catalog"))
        val synthetic = LanSyncGroupCloudRoutes(syntheticRoot, f.trust, f.identity.fingerprint, f::protect, f::unprotect,
            WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP)
        val local = synthetic.add(f.draft("http://127.0.0.1:18443/private/", enabled = false))
        assertEquals(local, synthetic.rows().single().id)
        failure("ENDPOINT") { synthetic.add(f.draft("http://synthetic.invalid/private/")) }
    }
}
