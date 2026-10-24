package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.*
import java.nio.file.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

class DesktopFreshRootAuthorityTest {
    private class FakeProtector : SecretProtector {
        override fun protect(plaintext: ByteArray) = byteArrayOf(71) + plaintext.map { (it.toInt() xor 93).toByte() }
        override fun unprotect(ciphertext: ByteArray): ByteArray {
            check(ciphertext.first() == 71.toByte())
            return ciphertext.drop(1).map { (it.toInt() xor 93).toByte() }.toByteArray()
        }
    }
    private suspend fun fixture(block: suspend (Path, Path, FakeProtector) -> Unit) {
        val parent = Files.createTempDirectory(Files.createDirectories(Path.of("build/preview-synthetic/fresh-root").toAbsolutePath()), "case-")
        val root = Files.createDirectory(parent.resolve("data"))
        val credentials = parent.resolve("credentials")
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(
            DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")))).ownership
        try { block(root, credentials, FakeProtector()) } finally { owner.close() }
    }
    private fun file(root: Path) = root.resolve("entities/app_settings.json").also {
        Files.createDirectories(it.parent); Files.writeString(it, "{}", StandardOpenOption.CREATE_NEW)
    }
    private suspend fun proof(file: Path) = mapOf("entities/app_settings.json" to DesktopArchiveFiles.hash(file, 1024))

    @Test fun preparingRootCannotBecomeRunnable() = runBlocking<Unit> { fixture { root, base, fake ->
        val authority = DesktopFreshRootAuthority(root, base, fake)
        authority.begin()
        assertFailsWith<IllegalStateException> { authority.verifyAndResolve() }
        assertFailsWith<IllegalStateException> { DesktopFullBackupCommitJournal(root).recover() }
        assertTrue(Files.isRegularFile(root.resolve(DesktopFreshRootAuthority.FENCE).resolve("intent.json")))
    } }

    @Test fun bothNewKeysAndDataAreVerifiedBeforeActivationAndReopen() = runBlocking<Unit> { fixture { root, base, fake ->
        val authority = DesktopFreshRootAuthority(root, base, fake); val pending = authority.begin()
        pending.store.save(DesktopCredentialKey.NovelAiToken, "FAKE_NEW_NAI")
        pending.store.save(DesktopCredentialKey.FishAudioApiKey, "FAKE_NEW_FISH")
        authority.complete(pending, proof(file(root)))
        assertEquals(pending.credentialRoot, authority.verifyAndResolve())
        val reopened = WindowsSecretStore(DesktopFreshRootAuthority(root, base, fake).verifyAndResolve(), fake)
        assertEquals("FAKE_NEW_NAI", reopened.load(DesktopCredentialKey.NovelAiToken))
        assertEquals("FAKE_NEW_FISH", reopened.load(DesktopCredentialKey.FishAudioApiKey))
        assertEquals(pending.credentialRoot, authority.verifyAndResolve())
    } }

    @Test fun existingRootAndExistingGlobalCredentialBytesAreNeverOverwritten() = runBlocking<Unit> { fixture { root, base, fake ->
        Files.createDirectories(base)
        val old = base.resolve("existing-global-sentinel"); Files.writeString(old, "OLD_SYNTHETIC")
        val authority = DesktopFreshRootAuthority(root, base, fake); val pending = authority.begin()
        pending.store.save(DesktopCredentialKey.FishAudioApiKey, "FAKE_NEW")
        assertEquals("OLD_SYNTHETIC", Files.readString(old))
        assertFails { pending.store.save(DesktopCredentialKey.FishAudioApiKey, "FAKE_DIFFERENT") }
        assertEquals("FAKE_NEW", pending.store.load(DesktopCredentialKey.FishAudioApiKey))
        assertFails { authority.begin() }
        assertEquals("OLD_SYNTHETIC", Files.readString(old))
    } }

    @Test fun secondKeyFailureLeavesInertRootAndRetainsFirstKey() = runBlocking<Unit> { fixture { root, base, fake ->
        var calls = 0
        val fault = object : SecretProtector {
            override fun protect(plaintext: ByteArray): ByteArray { check(++calls != 2) { "SYNTHETIC_SECOND_KEY_FAILURE" }; return fake.protect(plaintext) }
            override fun unprotect(ciphertext: ByteArray) = fake.unprotect(ciphertext)
        }
        val authority = DesktopFreshRootAuthority(root, base, fault); val pending = authority.begin()
        pending.store.save(DesktopCredentialKey.NovelAiToken, "FAKE_FIRST")
        assertFails { pending.store.save(DesktopCredentialKey.FishAudioApiKey, "FAKE_SECOND") }
        assertFails { authority.verifyAndResolve() }
        assertEquals("FAKE_FIRST", pending.store.load(DesktopCredentialKey.NovelAiToken))
    } }

    @Test fun changedDataBeforeCompletionOrFirstActivationFailsClosed() = runBlocking<Unit> { fixture { root, base, fake ->
        val authority = DesktopFreshRootAuthority(root, base, fake); val pending = authority.begin(); val data = file(root)
        val hashes = proof(data); Files.writeString(data, "{\"changed\":true}")
        assertFails { authority.complete(pending, hashes) }
        assertFails { authority.verifyAndResolve() }
        assertEquals("{\"changed\":true}", Files.readString(data))
    } }

    @Test fun dataChangedAfterCompleteIsNotAcceptedAsAnInitialImport() = runBlocking<Unit> { fixture { root, base, fake ->
        val authority = DesktopFreshRootAuthority(root, base, fake); val pending = authority.begin(); val data = file(root)
        authority.complete(pending, proof(data)); Files.writeString(data, "{\"foreign\":true}")
        assertFails { authority.verifyAndResolve() }
        assertEquals("{\"foreign\":true}", Files.readString(data))
    } }

    @Test fun missingOrChangedProtectedKeyBlocksFirstActivation() = runBlocking<Unit> { fixture { root, base, fake ->
        val authority = DesktopFreshRootAuthority(root, base, fake); val pending = authority.begin()
        pending.store.save(DesktopCredentialKey.NovelAiToken, "FAKE_ONLY")
        authority.complete(pending, proof(file(root)))
        val key = Files.newDirectoryStream(pending.credentialRoot, "*.ccbsecret").use { it.single() }
        val original = Files.readAllBytes(key); Files.write(key, byteArrayOf(1, 2, 3))
        assertFails { authority.verifyAndResolve() }; Files.write(key, original)
        assertEquals(pending.credentialRoot, authority.verifyAndResolve())
    } }

    @Test fun contextCorruptionAndUnavailableProtectorNeverBecomeAbsentCredentials() = runBlocking<Unit> { fixture { root, base, fake ->
        val authority = DesktopFreshRootAuthority(root, base, fake); val pending = authority.begin()
        authority.complete(pending, proof(file(root)))
        val context = root.resolve(DesktopFreshRootAuthority.FENCE).resolve("context.protected")
        val original = Files.readAllBytes(context); Files.write(context, byteArrayOf(9))
        assertFails { authority.verifyAndResolve() }; Files.write(context, original)
        val unavailable = object : SecretProtector {
            override fun protect(plaintext: ByteArray): ByteArray = error("UNAVAILABLE")
            override fun unprotect(ciphertext: ByteArray): ByteArray = error("UNAVAILABLE")
        }
        assertFails { DesktopFreshRootAuthority(root, base, unavailable).verifyAndResolve() }
        assertEquals(pending.credentialRoot, authority.verifyAndResolve())
    } }

    @Test fun clonedDataRootCannotReuseAnotherRootsProtectedNamespace() = runBlocking<Unit> { fixture { root, base, fake ->
        val authority = DesktopFreshRootAuthority(root, base, fake); val pending = authority.begin()
        authority.complete(pending, proof(file(root)))
        val clone = Files.createDirectory(root.resolveSibling("clone"))
        Files.walk(root).use { stream -> stream.forEach { source ->
            val relative = root.relativize(source)
            if (relative.toString().isNotEmpty() && source.fileName.toString() != DesktopDataRootOwnership.LOCK_FILE_NAME) {
                val target = clone.resolve(relative)
                if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
            }
        } }
        val cloneOwner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(
            DesktopDataRootResolution.Resolved(clone, DesktopDataRootProvenance.CLI_OVERRIDE, clone.parent.resolve("bootstrap.json")))).ownership
        try { assertFails { DesktopFreshRootAuthority(clone, base, fake).verifyAndResolve() } }
        finally { cloneOwner.close() }
        assertEquals(pending.credentialRoot, authority.verifyAndResolve())
    } }

    @Test fun activeRootPermitsOrdinaryLaterEditsWithoutUsingObsoleteGenesisProof() = runBlocking<Unit> { fixture { root, base, fake ->
        val authority = DesktopFreshRootAuthority(root, base, fake); val pending = authority.begin(); val data = file(root)
        pending.store.save(DesktopCredentialKey.NovelAiToken, "FAKE_FIRST")
        authority.complete(pending, proof(data)); authority.verifyAndResolve()
        Files.writeString(data, "{\"normalEdit\":true}")
        WindowsSecretStore(pending.credentialRoot, fake).save(DesktopCredentialKey.NovelAiToken, "FAKE_UPDATED")
        assertEquals(pending.credentialRoot, authority.verifyAndResolve())
        assertEquals("FAKE_UPDATED", WindowsSecretStore(pending.credentialRoot, fake).load(DesktopCredentialKey.NovelAiToken))
    } }

    @Test fun nativeDpapiRoundtripIsExplicitlyOptInAndUsesOnlyTaskOwnedDirectories() = runBlocking<Unit> {
        assumeTrue(System.getenv("CCB_PREVIEW_NATIVE_DPAPI") == "1")
        fixture { root, _, _ ->
            val local = Files.createDirectory(root.resolveSibling("test-local-app-data"))
            val base = local.resolve(WindowsSecretRootResolver.DIRECTORY_NAME)
            val authority = DesktopFreshRootAuthority(root, base)
            val pending = authority.begin()
            pending.store.save(DesktopCredentialKey.NovelAiToken, "FAKE_NATIVE_NAI_NOT_AN_API_KEY")
            pending.store.save(DesktopCredentialKey.FishAudioApiKey, "FAKE_NATIVE_FISH_NOT_AN_API_KEY")
            authority.complete(pending, proof(file(root)))
            val selected = WindowsSecretRootResolver.resolve(mapOf("LOCALAPPDATA" to local.toString()), root)
            assertEquals(pending.credentialRoot, selected)
            val reopened = WindowsSecretStore(selected, WindowsDpapiSecretProtector())
            assertEquals("FAKE_NATIVE_NAI_NOT_AN_API_KEY", reopened.load(DesktopCredentialKey.NovelAiToken))
            assertEquals("FAKE_NATIVE_FISH_NOT_AN_API_KEY", reopened.load(DesktopCredentialKey.FishAudioApiKey))
        }
    }
}
