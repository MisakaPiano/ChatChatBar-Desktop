package com.example.chatbar.lanSync

import android.graphics.Bitmap
import android.graphics.Color
import android.content.pm.PackageManager
import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.lanSync.LanSyncGroupEnrollmentStage
import com.example.chatbar.domain.lanSync.LanSyncGroupPanelStage
import com.example.chatbar.domain.lanSync.LanSyncGroupRecoverySavedAdmission
import com.example.chatbar.domain.lanSync.LanSyncScope
import com.example.chatbar.domain.lanSync.lanSyncHash
import com.example.chatbar.domain.syncStore.SyncRootGroupCreateAdmission
import com.example.chatbar.domain.syncStore.SyncRootGroupCreateRequest
import com.example.chatbar.domain.syncStore.SyncStoreId
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test

class LanDelivered86UpgradeTest {
    @Suppress("DEPRECATION")
    @Test fun seedConversationGroupAndOldRecoveryCode(): Unit = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("lanDelivered86UpgradeFixture") == "true")
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        val installed = app.packageManager.getPackageInfo(PACKAGE, PackageManager.GET_SIGNATURES)
        check(app.packageName == PACKAGE && installed.versionCode == 86)
        check(lanSyncHash(requireNotNull(installed.signatures).single().toByteArray()) == SIGNER)
        withTimeout(90_000) { while (!app.lanSyncStartupReady) delay(25) }

        val files = app.filesDir.canonicalFile.toPath()
        val groups = app.noBackupFilesDir.canonicalFile.toPath().resolve("lan-sync-v1")
        check(!Files.exists(groups.resolve("group-profile"), NOFOLLOW_LINKS))
        val proofRoot = Files.createDirectory(files.resolve(ROOT))
        val media = files.resolve(AVATAR)
        Files.createDirectories(media.parent)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.GREEN)
            Files.newOutputStream(media, CREATE_NEW, WRITE).use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
        check(app.chatRepository.getSession(SESSION) == null)
        app.jsonFileStorage.saveEntity("character_cards", CARD,
            CharacterCard(CARD, "合成升级角色", avatar = media.toString(), createdAt = 1, updatedAt = 1),
            CharacterCard.serializer())
        app.chatRepository.createSession(ChatSession(SESSION, CARD, TITLE, createdAt = 100, updatedAt = 100))
        app.chatRepository.addMessage(ChatMessage("old-user", SESSION, MessageRole.USER, USER,
            createdAt = 101, updatedAt = 101))
        app.chatRepository.addMessage(ChatMessage("old-assistant", SESSION, MessageRole.ASSISTANT, ASSISTANT,
            createdAt = 102, updatedAt = 102))

        val owner = app.lanSyncOwner
        owner.pauseBackground()
        withTimeout(90_000) { owner.backgroundState.first { it.phase == AndroidLanSyncBackgroundPhase.PAUSED } }
        val enrollment = owner.groupEnrollment
        val request = SyncRootGroupCreateRequest(SyncStoreId.random(), LanSyncScope.schemas.keys.toSet(),
            lanSyncHash("synthetic-android86-retained-group".toByteArray()))
        val protectedCode = proofRoot.resolve("old-recovery-code.protected")
        enrollment.prepare(request, SyncRootGroupCreateAdmission { check(it == request) }).use { draft ->
            val code = draft.recoveryCode().toByteArray()
            try {
                Files.write(protectedCode, AndroidLanSyncKeyProtection(KEY_ALIAS).protect(code), CREATE_NEW, WRITE)
                enrollment.activate(draft, LanSyncGroupRecoverySavedAdmission { check(it == draft.profile) })
            } finally {
                code.fill(0)
            }
        }
        check(requireNotNull(enrollment.profile()).stage == LanSyncGroupEnrollmentStage.READY)

        val page = ActivityScenario.launch(LanSyncActivity::class.java)
        try {
            val coordinator = owner.groupCoordinator
            try {
                val ready = withTimeout(90_000) {
                    coordinator.state.first { it.stage == LanSyncGroupPanelStage.READY && !it.busy }
                }
                check(ready.paused && ready.localManagementAvailable && !ready.managementUnlocked)
                val recovered = AndroidLanSyncKeyProtection(KEY_ALIAS).unprotect(Files.readAllBytes(protectedCode))
                try {
                    coordinator.unlockManagement(recovered.toString(Charsets.UTF_8))
                } finally {
                    recovered.fill(0)
                }
                withTimeout(90_000) {
                    coordinator.state.first { it.stage == LanSyncGroupPanelStage.READY && !it.busy &&
                        it.paused && it.managementUnlocked && it.managementCanInvite }
                }
            } finally {
                coordinator.lockManagement()
            }
        } finally {
            page.close()
        }

        val access = requireNotNull(enrollment.runtimeAccess())
        val ledger = access.ledger().current()
        val member = ledger.members.getValue(access.device)
        check(member.active && member.certificate == access.certificate && ledger.group == access.trust.group)
        val group = JSONObject()
            .put("id", access.trust.group.value)
            .put("device", access.device)
            .put("memberCertHash", lanSyncHash(access.certificate.toByteArray()))
            .put("managementCertHash", lanSyncHash(access.trust.ownerCertificate.toByteArray()))
            .put("ledgerId", ledger.reference().id.value)
            .put("ledgerHash", ledger.reference().bodyHash)
            .put("epoch", ledger.epoch.value)
            .put("memberCount", ledger.members.size)
        val retainedFiles = JSONObject()
        for (name in FILES) {
            val path = files.resolve(name)
            check(Files.isRegularFile(path, NOFOLLOW_LINKS))
            retainedFiles.put("files/$name", lanSyncHash(Files.readAllBytes(path)))
        }
        for (name in GROUP_FILES) {
            val path = groups.resolve(name)
            check(Files.isRegularFile(path, NOFOLLOW_LINKS))
            retainedFiles.put("group/$name", lanSyncHash(Files.readAllBytes(path)))
        }
        val proof = JSONObject().put("package", PACKAGE).put("seedVersionCode", 86)
            .put("sourceSha", "f5fc060546eb9479b2566375761903d383c538b1")
            .put("uid", Process.myUid()).put("firstInstallTime", installed.firstInstallTime)
            .put("dataDirHash", lanSyncHash(app.applicationInfo.dataDir.toByteArray()))
            .put("files", retainedFiles).put("group", group)
        Files.write(proofRoot.resolve("proof.json"), proof.toString().toByteArray(), CREATE_NEW, WRITE)
    }

    companion object {
        private const val PACKAGE = "com.example.chatbar.lansyncnext"
        private const val SIGNER = "801f54622926f9ca6710086ac2732854a31b546f8ec2fd38cd0c6c11837a1aa7"
        private const val ROOT = "lan-delivered86-upgrade-proof-v1"
        private const val KEY_ALIAS = "ccb-lan-delivered86-synthetic-recovery-proof-v1"
        private const val CARD = "ccb-upgrade86-character"
        private const val SESSION = "ccb-upgrade86-conversation"
        private const val AVATAR = "images/ccb-upgrade86-avatar.png"
        private const val TITLE = "合成升级会话"
        private const val USER = "合成旧消息第一行\n第二行。"
        private const val ASSISTANT = "保留原顺序与字节。"
        private val FILES = listOf(AVATAR, "entities/character_cards/$CARD.json",
            "entities/chat_sessions/$SESSION.json", "entities/chat_messages/${SESSION}_old-user.json",
            "entities/chat_messages/${SESSION}_old-assistant.json", "$ROOT/old-recovery-code.protected")
        private val GROUP_FILES = listOf("identity.protected", "group-profile/profile.protected",
            "group-profile/management-vault.bin")
    }
}
