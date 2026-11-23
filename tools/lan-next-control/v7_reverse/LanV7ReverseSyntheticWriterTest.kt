package com.example.chatbar.lanSync

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.lanSync.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test

/** Non-delivery debug app+test only. Real production LAN and app repositories;
 * instrumentation calls beginJoin, NOT ordinary Android invitation/paste UI proof. */
class LanV7ReverseSyntheticWriterTest {
    @Suppress("DEPRECATION")
    @Test fun appendOneMessageAndPngAfterProductionLanJoin(): Unit = runBlocking {
        var phase = "opt-in"
        var failed: String? = null
        var page: ActivityScenario<LanSyncActivity>? = null
        try {
            val args = InstrumentationRegistry.getArguments()
            check(args.getString("v7ReverseSynthetic") == "true" && args.getString("sourceSha") == SOURCE)
            val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
            check(app.packageName == PACKAGE && app.packageManager.getPackageInfo(PACKAGE, 0).versionCode == 87)
            check(app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            phase = "startup"
            await(90_000) { app.lanSyncStartupReady }
            check(!app.lanSyncOwner.hasGroupEvidence())
            check(app.chatRepository.getSession(SESSION) == null)
            val files = app.filesDir.canonicalFile.toPath()
            val lab = Files.createDirectory(files.resolve("v7-reverse-lab"))
            check(lab.toRealPath().parent == files)
            page = ActivityScenario.launch(LanSyncActivity::class.java)
            val coordinator = app.lanSyncOwner.groupCoordinator
            await(90_000) { coordinator.state.value.stage == LanSyncGroupPanelStage.EMPTY && !coordinator.state.value.busy }
            Files.write(lab.resolve("ready.json"), "{\"kind\":\"CCB_V7_ANDROID_READY\",\"ready\":true}".toByteArray(), CREATE_NEW, WRITE)
            phase = "private-synthetic-offer"
            val request = lab.resolve("offer.json")
            await(900_000) { Files.isRegularFile(request, NOFOLLOW_LINKS) }
            check(Files.size(request) in 1L..20_000L)
            val raw = Files.readAllBytes(request)
            var code: String? = null
            try {
                val offer = JSONObject(raw.toString(Charsets.UTF_8))
                check(offer.keys().asSequence().toSet() == setOf("kind", "code", "session", "message", "text", "imageSha256"))
                check(offer.getString("kind") == "CCB_V7_LIVE_SYNTHETIC_OFFER")
                check(offer.getString("session") == SESSION && offer.getString("message") == "ccb-v7-lab-message")
                check(offer.getString("text") == "CCB-V7-synthetic-message")
                code = offer.getString("code")
                check(code!!.matches(Regex("CCB-GROUP-JOIN1:[A-Za-z0-9_-]{1,16384}")))
            } finally { raw.fill(0); Files.delete(request) }
            phase = "real-production-lan-join"
            coordinator.beginJoin(checkNotNull(code)); code = null
            await(180_000) {
                check(coordinator.state.value.error == null)
                coordinator.state.value.stage == LanSyncGroupPanelStage.READY && !coordinator.state.value.busy &&
                    !coordinator.state.value.paused && coordinator.state.value.devices.any { !it.thisDevice }
            }
            // This existing forward seed is pairing context, not another old86 acceptance.
            await(120_000) { app.chatRepository.getMessagesReadOnly(SESSION).any { it.id == "ccb-v7-lab-message" } }
            check(app.chatRepository.getMessagesReadOnly(SESSION).none { it.id == MESSAGE })
            phase = "durable-imported-session"
            val durable = app.chatRepository.requireDurableSession(SESSION)
            check(durable.id == SESSION && durable.characterCardId == "ccb-v7-lab-card")
            phase = "ordinary-repository-append"
            val image = files.resolve("images/ccb-v7-reverse.png")
            Files.createDirectories(image.parent)
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val png = try {
                for (y in 0..7) for (x in 0..7) bitmap.setPixel(x, y, if ((x + y) % 2 == 0) Color.MAGENTA else Color.CYAN)
                java.io.ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
                }
            } finally { bitmap.recycle() }
            Files.write(image, png, CREATE_NEW, WRITE)
            val added = app.chatRepository.addMessage(ChatMessage(MESSAGE, SESSION, MessageRole.USER, TEXT,
                images = listOf(image.toString()), createdAt = 31L, updatedAt = 31L))
            check(added.id == MESSAGE && added.sessionId == SESSION && added.content == TEXT && added.images == listOf(image.toString()))
            check(app.chatRepository.getMessagesReadOnly(SESSION).count { it.id == MESSAGE } == 1)
            check(Files.readAllBytes(image).contentEquals(png))
            val oracle = JSONObject().put("kind", "CCB_V7_ANDROID_REVERSE_ORACLE").put("sourceSha", SOURCE)
                .put("session", SESSION).put("message", MESSAGE).put("text", TEXT)
                .put("imageSha256", lanSyncHash(png)).put("imageBytes", png.size).put("width", 8).put("height", 8)
            Files.write(lab.resolve("oracle.json"), oracle.toString().toByteArray(Charsets.UTF_8), CREATE_NEW, WRITE)
            phase = "normal-runtime-reverse-receipt"
            val ack = lab.resolve("windows-received.json")
            await(180_000) { Files.isRegularFile(ack, NOFOLLOW_LINKS) }
            check(Files.size(ack) in 1L..512L)
            val response = JSONObject(Files.readAllBytes(ack).toString(Charsets.UTF_8))
            check(response.keys().asSequence().toSet() == setOf("kind", "received"))
            check(response.getString("kind") == "CCB_V7_WINDOWS_REVERSE_RECEIVED" && response.getBoolean("received"))
            check(app.chatRepository.getMessagesReadOnly(SESSION).count { it.id == MESSAGE && it.content == TEXT } == 1)
            check(Files.readAllBytes(image).contentEquals(png))
        } catch (_: Throwable) { failed = phase }
        finally { try { page?.close() } catch (_: Throwable) { if (failed == null) failed = "activity-close" } }
        failed?.let { throw AssertionError("V7_REVERSE_ANDROID_PHASE_FAILED:$it") }
    }

    private suspend fun await(timeout: Long, ready: suspend () -> Boolean) = withTimeout(timeout) {
        while (!ready()) delay(100)
    }
    companion object {
        private const val SOURCE = "40981ae4045114d1d2a076a82b02b7c7f5275cc7"
        private const val PACKAGE = "com.example.chatbar.lansyncnext"
        private const val SESSION = "ccb-v7-lab-session"
        private const val MESSAGE = "ccb-v7-reverse-message"
        private const val TEXT = "CCB-V7-synthetic-android-reverse"
    }
}
