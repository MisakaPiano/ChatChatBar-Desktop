package com.example.chatbar.lanSync

import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.lanSync.LanSyncGroupEnrollmentStage
import com.example.chatbar.domain.lanSync.LanSyncGroupPanelStage
import com.example.chatbar.domain.lanSync.lanSyncHash
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.KeyStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test

class LanDelivered86UpgradeTest {
    @Suppress("DEPRECATION")
    @Test fun verifyConversationGroupAndOldRecoveryCode(): Unit = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("lanDelivered86UpgradeFixture") == "true")
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        val installed = app.packageManager.getPackageInfo(PACKAGE, PackageManager.GET_SIGNATURES)
        check(app.packageName == PACKAGE && installed.versionCode == 87)
        check(lanSyncHash(requireNotNull(installed.signatures).single().toByteArray()) == SIGNER)
        val files = app.filesDir.canonicalFile.toPath()
        val groups = app.noBackupFilesDir.canonicalFile.toPath().resolve("lan-sync-v1")
        val proofPath = files.resolve("$ROOT/proof.json")
        check(Files.isRegularFile(proofPath, NOFOLLOW_LINKS))
        val proof = JSONObject(Files.readAllBytes(proofPath).toString(Charsets.UTF_8))
        check(proof.getString("package") == PACKAGE && proof.getInt("seedVersionCode") == 86)
        check(proof.getString("sourceSha") == "f5fc060546eb9479b2566375761903d383c538b1")
        check(proof.getInt("uid") == Process.myUid() && proof.getLong("firstInstallTime") == installed.firstInstallTime)
        check(proof.getString("dataDirHash") == lanSyncHash(app.applicationInfo.dataDir.toByteArray()))
        verifyRetainedFiles(files, groups, proof.getJSONObject("files"))
        withTimeout(90_000) { while (!app.lanSyncStartupReady) delay(25) }

        val oldSession = requireNotNull(app.chatRepository.getSession(SESSION))
        check(oldSession.characterCardId == CARD && oldSession.title == "合成升级会话")
        check(app.chatRepository.getMessagesReadOnly(SESSION).map { Triple(it.id, it.role, it.content) } ==
            listOf(Triple("old-user", MessageRole.USER, "合成旧消息第一行\n第二行。"),
                Triple("old-assistant", MessageRole.ASSISTANT, "保留原顺序与字节。")))
        val card = requireNotNull(app.jsonFileStorage.loadEntity("character_cards", CARD, CharacterCard.serializer()))
        val media = files.resolve(AVATAR)
        check(card.avatar == media.toString())
        val image = requireNotNull(BitmapFactory.decodeFile(media.toString()))
        try {
            check(image.width == 8 && image.height == 8 && image.getPixel(0, 0) == Color.GREEN)
        } finally {
            image.recycle()
        }

        val owner = app.lanSyncOwner
        val page = ActivityScenario.launch(LanSyncActivity::class.java)
        try {
            val coordinator = owner.groupCoordinator
            try {
                val ready = withTimeout(90_000) {
                    coordinator.state.first { it.stage == LanSyncGroupPanelStage.READY && !it.busy }
                }
                check(ready.paused && ready.localManagementAvailable && !ready.managementUnlocked)
                check(!ready.recoveryJoining && !ready.canResumeExternalRecovery)
                verifyGroup(app, proof.getJSONObject("group"))
                val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                check(keystore.containsAlias(KEY_ALIAS) && keystore.containsAlias("chatbar-lan-sync-identity-v1"))
                val protectedCode = files.resolve("$ROOT/old-recovery-code.protected")
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
                verifyGroup(app, proof.getJSONObject("group"))
            } finally {
                coordinator.lockManagement()
            }
        } finally {
            page.close()
        }
        verifyRetainedFiles(files, groups, proof.getJSONObject("files"))
    }

    private suspend fun verifyGroup(app: ChatBarApp, old: JSONObject) {
        val enrollment = app.lanSyncOwner.groupEnrollment
        check(requireNotNull(enrollment.profile()).stage == LanSyncGroupEnrollmentStage.READY)
        val access = requireNotNull(enrollment.runtimeAccess())
        val ledger = access.ledger().current()
        val member = ledger.members.getValue(access.device)
        check(member.active && member.certificate == access.certificate && ledger.group == access.trust.group)
        check(old.getString("id") == access.trust.group.value && old.getString("device") == access.device)
        check(old.getString("memberCertHash") == lanSyncHash(access.certificate.toByteArray()))
        check(old.getString("managementCertHash") == lanSyncHash(access.trust.ownerCertificate.toByteArray()))
        check(old.getString("ledgerId") == ledger.reference().id.value &&
            old.getString("ledgerHash") == ledger.reference().bodyHash)
        check(old.getString("epoch") == ledger.epoch.value && old.getInt("memberCount") == ledger.members.size)
    }

    private fun verifyRetainedFiles(files: Path, groups: Path, old: JSONObject) {
        val expected = FILES.associate { "files/$it" to files.resolve(it) } +
            GROUP_FILES.associate { "group/$it" to groups.resolve(it) }
        check(old.keys().asSequence().toSet() == expected.keys)
        for ((name, path) in expected) {
            check(Files.isRegularFile(path, NOFOLLOW_LINKS) &&
                lanSyncHash(Files.readAllBytes(path)) == old.getString(name)) { "Retained file changed: $name" }
        }
    }

    companion object {
        private const val PACKAGE = "com.example.chatbar.lansyncnext"
        private const val SIGNER = "801f54622926f9ca6710086ac2732854a31b546f8ec2fd38cd0c6c11837a1aa7"
        private const val ROOT = "lan-delivered86-upgrade-proof-v1"
        private const val KEY_ALIAS = "ccb-lan-delivered86-synthetic-recovery-proof-v1"
        private const val CARD = "ccb-upgrade86-character"
        private const val SESSION = "ccb-upgrade86-conversation"
        private const val AVATAR = "images/ccb-upgrade86-avatar.png"
        private val FILES = listOf(AVATAR, "entities/character_cards/$CARD.json",
            "entities/chat_sessions/$SESSION.json", "entities/chat_messages/${SESSION}_old-user.json",
            "entities/chat_messages/${SESSION}_old-assistant.json", "$ROOT/old-recovery-code.protected")
        private val GROUP_FILES = listOf("identity.protected", "group-profile/profile.protected",
            "group-profile/management-vault.bin")
    }
}
