// Test-only verify85 companion; production inputs are frozen independently.
package com.example.chatbar.lanSync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.PlayerSetting
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.NovelAiStudioStateRepository
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.lanSync.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Opt-in, two separate runner invocations around a data-preserving84→85 cloud install. */
class LanDelivered84UpgradeTest {
    private val json = Json { encodeDefaults = true }

    @Suppress("DEPRECATION")
    private fun context(code: Int): Context {
        assertTrue("Only this explicit synthetic upgrade control may run fixtures",
            InstrumentationRegistry.getArguments().getString("lanDelivered84UpgradeFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(PACKAGE, context.packageName)
        assertEquals(code, context.packageManager.getPackageInfo(context.packageName, 0).versionCode)
        val signatures = requireNotNull(context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_SIGNATURES).signatures)
        assertEquals(SIGNING_CERT, lanSyncHash(signatures.single().toByteArray()))
        return context
    }

    private fun image(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(android.graphics.Color.GREEN)
            java.io.ByteArrayOutputStream().use { output ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private fun root(context: Context) = context.filesDir.canonicalFile.toPath().resolve("lan-delivered84-upgrade-proof-v1")
    private fun inventory(root: Path): Map<String, String> = Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toArray().map { it as Path }
            .associate { root.relativize(it).toString().replace('\\', '/') to lanSyncHash(Files.readAllBytes(it)) }
    }
    private fun store(root: Path): LanSyncStateStore {
        val protection = AndroidLanSyncKeyProtection(KEY_ALIAS)
        return LanSyncStateStore(root.resolve("state"), protection::protect, protection::unprotect)
    }
    private fun workspace(root: Path) = LanSyncWorkspace(root.resolve("data"), root.resolve("capture-scratch"), { Paths.get(it) })

    @Test fun verifySyntheticRepositoryAndProtectedPeerIdentityAfterUpgrade(): Unit = runBlocking {
        val root = root(context(85))
        assertTrue("The84 seed must survive installation; never recreate it here", Files.isRegularFile(root.resolve("proof.json")))
        val evidence = json.parseToJsonElement(String(Files.readAllBytes(root.resolve("proof.json")), Charsets.UTF_8)).jsonObject
        assertEquals(PACKAGE, evidence.getValue("package").jsonPrimitive.content)
        assertEquals(84, evidence.getValue("seedVersionCode").jsonPrimitive.int)
        assertEquals(evidence.getValue("uid").jsonPrimitive.int, android.os.Process.myUid())
        assertEquals(evidence.getValue("firstInstallTime").jsonPrimitive.long, context(85).packageManager.getPackageInfo(PACKAGE, 0).firstInstallTime)
        val expected = evidence.getValue("files").jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(expected, inventory(root).filterKeys { it != "proof.json" })
        assertTrue("Existing Keystore alias is required; new-key creation is not upgrade proof",
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(KEY_ALIAS))
        val state = store(root); val identity = state.identity()
        assertEquals(evidence.getValue("identity").jsonPrimitive.content, identity.fingerprint)
        assertEquals(listOf(LanSyncPeer(PEER, "合成可信电脑", "192.168.99.2", 42424)), state.peers())
        val baseline = state.baseline(PEER, identity.fingerprint)
        assertEquals(evidence.getValue("completedTransaction").jsonPrimitive.content, baseline.revision)
        val pending = requireNotNull(state.pending(PEER))
        assertEquals(identity.fingerprint, pending.ownerId)
        assertEquals(baseline.hashes, pending.expectedLocal)
        assertArrayEquals(SENTINEL.toByteArray(), AndroidLanSyncKeyProtection(KEY_ALIAS).unprotect(Files.readAllBytes(root.resolve("state/sentinel.protected"))))
        val data = root.resolve("data"); val storage = JsonFileStorage(data)
        val cards = CharacterRepository(storage).getAll()
        assertEquals("合成升级保全角色", cards.single().name)
        val settings = SettingsRepository(storage); settings.refreshPlayerSettingFromStorage()
        assertEquals("合成升级玩家", settings.playerSetting.first().playerName)
        val studio = NovelAiStudioStateRepository(storage); studio.initialize()
        val history = studio.history.first().single()
        assertEquals("合成升级原文\n保留第二行", history.recipe.basePrompt)
        assertEquals(VIBE, NovelAiInlinePayloadStore(data.toFile()).resolve(requireNotNull(history.recipe.imageGuidance.vibes.single().encodedVibe)))
        assertArrayEquals(image(), Files.readAllBytes(Paths.get(history.images.single().path)))
        val processed = requireNotNull(storage.loadEntity(LanSyncPostProcess.TYPE, "upgrade-result", NovelAiPostProcessResultRecord.serializer()))
        processed.validateMetadata()
        assertEquals(processed.imageSha256, lanSyncHash(Files.readAllBytes(Paths.get(processed.imagePath))))
        val capturedResources = workspace(root).capture(identity.fingerprint).use { capture ->
            assertEquals(baseline.hashes, capture.snapshot.hashes())
            capture.snapshot.resources.toMap()
        }
        // The original inventory must survive unchanged. Only this capture's
        // content-addressed resource cache may add files, never arbitrary scratch.
        val after = inventory(root).filterKeys { it != "proof.json" }
        expected.forEach { (path, hash) -> assertEquals("Original changed: " + path, hash, after[path]) }
        Files.walk(root).use { paths ->
            paths.forEach { assertFalse("Unexpected symbolic link: " + it, Files.isSymbolicLink(it)) }
        }
        (after.keys - expected.keys).forEach { path ->
            val hash = path.removePrefix("capture-scratch/resources/")
            assertTrue("Unexpected new file: " + path,
                path == "capture-scratch/resources/" + hash && hash.matches(Regex("[a-f0-9]{64}")))
            val length = requireNotNull(capturedResources[hash]) { "Resource absent from this capture: " + path }
            val resource = root.resolve(path)
            assertEquals("Resource length: " + path, length, Files.size(resource))
            assertEquals("Resource bytes/name: " + path, hash, LanSyncWorkspace.hashFile(resource, length))
            assertEquals(hash, after.getValue(path))
        }
    }

    companion object {
        private const val PACKAGE = "com.example.chatbar.lansyncnext"
        private const val KEY_ALIAS = "ccb-lan-delivered84-upgrade-fixture-v1"
        private const val SIGNING_CERT = "801f54622926f9ca6710086ac2732854a31b546f8ec2fd38cd0c6c11837a1aa7"
        private val PEER = "a".repeat(64)
        private val VIBE = "synthetic-upgrade-vibe-".repeat(250)
        private const val SENTINEL = "synthetic-upgrade-protected-value"
    }
}
