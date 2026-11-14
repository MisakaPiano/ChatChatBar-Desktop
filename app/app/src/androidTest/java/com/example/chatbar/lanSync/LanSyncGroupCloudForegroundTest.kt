package com.example.chatbar.lanSync

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.BuildConfig
import com.example.chatbar.ChatBarApp
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in platform foreground gate proof on a cold isolated next-preview app. It is not an account,
 * group publication, physical network, Doze, process-restart or exhausted-system-quota proof.
 */
class LanSyncGroupCloudForegroundTest {
    @Suppress("DEPRECATION")
    @Test fun actualDataSyncPromotionContinuesAfterHomeStopsAndRejectsTheNextBackgroundBatch() = runBlocking {
        // Keep these guards before obtaining owners or launching any Activity.
        assumeTrue("Only the root-owned native controller opts into this platform proof",
            InstrumentationRegistry.getArguments().getString("lanSyncCloudForegroundFixture") == "true")
        assumeTrue("Only an isolated next-preview package is eligible",
            BuildConfig.BUILD_TYPE == "lanPreview" && BuildConfig.APPLICATION_ID == NEXT_PACKAGE)
        assumeTrue("The actual foreground-service type dump is checked on Android 15 or newer", Build.VERSION.SDK_INT >= 35)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        assertEquals(NEXT_PACKAGE, app.packageName)
        withTimeout(60_000) {
            while (!app.lanSyncStartupReady) {
                check(!app.lanSyncRecoveryFailed) { "SYNTHETIC_CLOUD_STARTUP_RECOVERY_FAILED" }
                delay(100)
            }
        }
        val owner = app.lanSyncOwner
        val nativeRoot = app.noBackupFilesDir.canonicalFile.toPath().resolve("lan-sync-v1")
        val identity = nativeRoot.resolve("identity.protected")
        assertFalse("A cold package is required; existing identity must never be reset", Files.exists(identity, NOFOLLOW_LINKS))
        assertFalse("Existing group evidence must never be replaced by this proof", owner.hasGroupEvidence())
        val preferences = app.getSharedPreferences("lan-sync-background", Context.MODE_PRIVATE)
        assertFalse("A retained user pause must never be cleared by this proof", preferences.getBoolean("user-paused", false))
        val pauseBefore = preferences.all.toMap()
        val nativeBefore = inventory(nativeRoot)
        val cloud = ComponentName(app, LanSyncGroupCloudTransferService::class.java)
        val lan = ComponentName(app, LanSyncGroupForegroundService::class.java)
        assertNull(running(app, cloud)); assertNull(running(app, lan))
        val declared = app.packageManager.getServiceInfo(cloud, 0)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, declared.foregroundServiceType)
        assertFalse(declared.exported)
        val entered = CompletableDeferred<Unit>()
        val afterHome = CompletableDeferred<Unit>()
        val continued = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var scenario: ActivityScenario<LanSyncActivity>? = null
        var batch: Deferred<Boolean>? = null
        try {
            val launched = ActivityScenario.launch<LanSyncActivity>(Intent(app, LanSyncActivity::class.java))
            scenario = launched
            instrumentation.waitForIdleSync()
            await { launched.state == Lifecycle.State.RESUMED }
            launched.onActivity { assertFalse(it.isFinishing); assertFalse(it.isDestroyed) }
            val runningBatch = async(Dispatchers.Default) {
                owner.runCloudWork {
                    entered.complete(Unit)
                    afterHome.await()
                    continued.complete(Unit)
                    finish.await()
                    true
                }
            }
            batch = runningBatch
            withTimeout(20_000) { entered.await() }
            await { running(app, cloud)?.foreground == true }
            assertActualService(app, cloud)
            assertActualDataSyncDump(cloud)
            assertNull("LAN service cannot grant pure-cloud foreground protection", running(app, lan))

            assertTrue("The system must actually accept HOME", instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
            await { launched.state == Lifecycle.State.CREATED }
            afterHome.complete(Unit)
            withTimeout(20_000) { continued.await() }
            assertFalse(runningBatch.isCompleted)
            assertActualService(app, cloud)
            assertActualDataSyncDump(cloud)
            assertNull(running(app, lan))
            finish.complete(Unit)
            assertTrue(withTimeout(20_000) { runningBatch.await() })
            await { running(app, cloud) == null }
            assertNull(running(app, lan))

            var backgroundBlockRan = false
            val refused = try {
                owner.runCloudWork { backgroundBlockRan = true; true }
                null
            } catch (error: IllegalStateException) { error }
            assertNotNull("A new background batch must be rejected before starting a service", refused)
            assertEquals("SYNC_GROUP_CLOUD_BACKGROUND_FOREGROUND_REQUIRED", refused?.message)
            assertNull(refused?.cause)
            assertFalse(backgroundBlockRan)
            assertNull(running(app, cloud)); assertNull(running(app, lan))
        } finally {
            afterHome.complete(Unit); finish.complete(Unit)
            batch?.cancelAndJoin()
            scenario?.close()
        }
        await { running(app, cloud) == null && running(app, lan) == null }
        assertFalse(owner.hasGroupEvidence())
        assertFalse(Files.exists(identity, NOFOLLOW_LINKS))
        assertEquals(nativeBefore, inventory(nativeRoot))
        assertEquals(pauseBefore, preferences.all.toMap())
    }

    @Suppress("DEPRECATION")
    private fun running(app: ChatBarApp, component: ComponentName): ActivityManager.RunningServiceInfo? =
        app.getSystemService(ActivityManager::class.java).getRunningServices(100).singleOrNull { it.service == component }

    private fun assertActualService(app: ChatBarApp, component: ComponentName) {
        val actual = requireNotNull(running(app, component)) { "SYNTHETIC_CLOUD_SERVICE_MISSING" }
        assertTrue(actual.foreground)
        assertEquals(app.applicationInfo.uid, actual.uid)
        assertEquals(Process.myUid(), actual.uid)
        assertEquals(Process.myPid(), actual.pid)
    }

    /** Inspect the actual AOSP ServiceRecord type; a manifest declaration alone is insufficient. */
    private fun assertActualDataSyncDump(component: ComponentName) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys activity services $NEXT_PACKAGE")
        val dump = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader(Charsets.UTF_8).use { reader ->
            val output = StringBuilder()
            val buffer = CharArray(8192)
            while (true) {
                val count = reader.read(buffer); if (count < 0) break
                check(output.length <= 2 * 1024 * 1024 - count) { "SYNTHETIC_CLOUD_SERVICE_DUMP_BUDGET" }
                output.append(buffer, 0, count)
            }
            output.toString()
        }
        val lines = dump.lines()
        val start = lines.indexOfFirst { line -> line.trimStart().startsWith("* ServiceRecord{") &&
            (line.contains(component.flattenToString()) || line.contains(component.flattenToShortString())) }
        check(start >= 0) { "SYNTHETIC_CLOUD_SERVICE_RECORD_MISSING" }
        val end = (start + 1 until lines.size).firstOrNull { lines[it].trimStart().startsWith("* ServiceRecord{") } ?: lines.size
        val foreground = requireNotNull(lines.subList(start + 1, end).singleOrNull { it.contains("isForeground=true") && it.contains("types=0x") }) {
            "SYNTHETIC_CLOUD_FOREGROUND_TYPE_MISSING"
        }
        val mask = Regex("types=0x([0-9A-Fa-f]{8})").find(foreground)?.groupValues?.get(1)?.toLong(16)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC.toLong(), mask)
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(20_000) { while (!condition()) delay(20) }
    private fun inventory(root: Path): Map<String, String> {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return emptyMap()
        return Files.walk(root).use { paths -> paths.iterator().asSequence().filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.associate { path ->
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            root.relativize(path).toString() to digest.digest().joinToString("") { "%02x".format(it) }
        } }
    }
    companion object { private const val NEXT_PACKAGE = "com.example.chatbar.lansyncnext" }
}
