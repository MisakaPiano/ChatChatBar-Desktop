package com.example.chatbar.lanSync

import android.content.pm.PackageManager
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.lanSync.LanSyncRecord
import com.example.chatbar.domain.lanSync.LanSyncWorkspace
import com.example.chatbar.domain.lanSync.lanSyncHash
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Companion for the isolated, signed Android 85 → successor upgrade control.
 * Seed with the delivered APK and verify with the exact assigned successor APK.
 *
 * Use the native build handoff 156 and actual delivered 85 from handoff 148.
 * Insert the ordinary chat seed after saving GROUP_CARD and before the first
 * source capture. Keep the existing explicit review of just that group's card
 * so the original committed author sequence and receipt stay independently pinned.
 * GROUP_CARD must match CARD here. Store the returned digest-only proof with
 * the original version, group and app directory evidence.
 *
 * The caller should bind cache expectations before opening the real Activity:
 * use the old shadow capture for parallel bodies/resources, the old normal
 * workspace capture for workspace bodies, and exact hash/length-verified old
 * sealed source snapshot and signed unit for checkpoint and committed bodies.
 * Serialize those selected records with bodyFiles, which keeps wire byte order.
 * Load the original proof's SHA from the seed receipt. The same inventories
 * apply on both verifier calls and after successful app inspection, since
 * an earlier verifier may have populated the known derived directories.
 *
 * Pair this helper with the existing paused Activity, Keystore, author,
 * native revision, receipt and route assertions in the actual upgrade.
 */
internal object Android85RetainedEvidence {
    private const val PACKAGE = "com.example.chatbar.lansyncnext"
    private const val SIGNER = "801f54622926f9ca6710086ac2732854a31b546f8ec2fd38cd0c6c11837a1aa7"
    private const val SESSION = "ccb-v6-retained85-conversation"
    private const val CARD = "delivered85-group-upgrade-character"
    private const val TITLE = "合成升级会话"
    private val messages = listOf(
        ChatMessage("upgrade-user", SESSION, MessageRole.USER, "合成正文第一行\n第二行", createdAt = 101, updatedAt = 101),
        ChatMessage("upgrade-assistant", SESSION, MessageRole.ASSISTANT, "复核原顺序和标点。", createdAt = 102, updatedAt = 102),
    )
    private val normalizedJson = Json { encodeDefaults = true }
    private val sha256 = Regex("[a-f0-9]{64}")

    @Suppress("DEPRECATION")
    private fun requireFixture(app: ChatBarApp, version: Int) {
        check(InstrumentationRegistry.getArguments().getString("lanDelivered85UpgradeFixture") == "true")
        check(app.lanSyncStartupReady)
        check(app.packageName == PACKAGE)
        val installed = app.packageManager.getPackageInfo(PACKAGE, PackageManager.GET_SIGNATURES)
        check(installed.versionCode == version)
        check(lanSyncHash(requireNotNull(installed.signatures).single().toByteArray()) == SIGNER)
    }

    suspend fun seedOrdinaryChat(app: ChatBarApp): JsonObject {
        requireFixture(app, 85)
        val root = app.filesDir.canonicalFile.toPath()
        check(ordinaryPaths().none { Files.exists(root.resolve(it), NOFOLLOW_LINKS) })
        check(app.chatRepository.getSession(SESSION) == null)
        app.chatRepository.createSession(ChatSession(SESSION, CARD, TITLE, createdAt = 100, updatedAt = 100))
        messages.forEach { app.chatRepository.addMessage(it) }
        return ordinaryChat(app)
    }

    suspend fun verifyOrdinaryChat(app: ChatBarApp, actualCandidateCode: Int, oldEvidence: JsonObject) {
        check(actualCandidateCode > 85)
        requireFixture(app, actualCandidateCode)
        check(ordinaryChat(app) == oldEvidence) { "Existing app conversation or its stored bytes changed" }
    }

    private fun ordinaryPaths(): List<String> =
        listOf("entities/chat_sessions/$SESSION.json") +
            messages.map { "entities/chat_messages/" + SESSION + "_" + it.id + ".json" }

    private suspend fun ordinaryChat(app: ChatBarApp): JsonObject {
        val repository = app.chatRepository
        val session = requireNotNull(repository.getSession(SESSION))
        check(session.characterCardId == CARD && session.title == TITLE)
        val received = repository.getMessagesReadOnly(SESSION)
        check(received.map { Triple(it.id, it.role, it.content) } == messages.map { Triple(it.id, it.role, it.content) })
        val root = app.filesDir.canonicalFile.toPath()
        val appInfo = app.packageManager.getPackageInfo(PACKAGE, 0)
        return buildJsonObject {
            put("uid", Process.myUid())
            put("firstInstallTime", appInfo.firstInstallTime)
            put("dataDirHash", hash(app.applicationInfo.dataDir))
            put("sessionId", session.id)
            put("titleHash", hash(session.title))
            put("messages", buildJsonArray {
                received.forEach { message ->
                    add(buildJsonObject {
                        put("id", message.id)
                        put("role", message.role.name)
                        put("bodyHash", hash(message.content))
                    })
                }
            })
            put("fileHashes", buildJsonObject { ordinaryPaths().forEach { put(it, fileHash(root.resolve(it))) } })
        }
    }

    /**
     * Use normalized bodies from the original owned captures and sealed units.
     * Encoding order matters for the content-addressed filename.
     */
    fun bodyFiles(records: List<LanSyncRecord>): Map<String, Long> = records.associate { record ->
        val bytes = normalizedJson.encodeToString(JsonObject.serializer(), record.body).toByteArray(Charsets.UTF_8)
        lanSyncHash(bytes) to bytes.size.toLong()
    }

    fun verifyParallelInventory(
        root: Path,
        old: Map<String, String>,
        originalProofSha256: String,
        resources: Map<String, Long>,
        bodies: Map<String, Long>,
    ) = verifyInventory(
        root,
        old + ("proof.json" to originalProofSha256),
        mapOf("capture-scratch/resources" to resources, "capture-scratch/decoded-records" to bodies),
        setOf("capture-scratch/decoded-records"),
    )

    fun verifyGroupInventory(
        root: Path,
        old: Map<String, String>,
        workspaceBodies: Map<String, Long>,
        sealedCheckpointBodies: Map<String, Long>,
        committedUnitBodies: Map<String, Long>,
    ) = verifyInventory(
        root,
        old,
        mapOf(
            "workspace/decoded-records" to workspaceBodies,
            "workspace/recovery/decoded-records" to sealedCheckpointBodies,
            "group-profile/node/preparations/decoded-records" to committedUnitBodies,
            "group-profile/node/source/preparations/decoded-records" to committedUnitBodies,
        ),
        setOf("workspace/decoded-records", "workspace/recovery/decoded-records",
            "group-profile/node/preparations/decoded-records",
            "group-profile/node/source/preparations/decoded-records"),
    )

    private fun verifyInventory(
        root: Path,
        old: Map<String, String>,
        newDirectories: Map<String, Map<String, Long>>,
        lockDirectories: Set<String>,
    ) {
        check(old.isNotEmpty() && old.values.all(sha256::matches))
        check(Files.isDirectory(root, NOFOLLOW_LINKS))
        val observed = linkedMapOf<String, Path>()
        Files.walk(root).use { paths ->
            paths.forEach { path ->
                check(!Files.isSymbolicLink(path)) { "Unexpected symbolic link in isolated retained root" }
                if (Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                    observed[root.relativize(path).toString().replace('\\', '/')] = path
                } else {
                    check(Files.isDirectory(path, NOFOLLOW_LINKS)) { "Unexpected filesystem entry in isolated retained root" }
                }
            }
        }
        old.forEach { (relative, hash) ->
            val file = requireNotNull(observed[relative]) { "Original file missing: $relative" }
            check(fileHash(file) == hash) { "Original bytes changed: $relative" }
        }
        (observed.keys - old.keys).forEach { relative ->
            val directory = relative.substringBeforeLast('/', "")
            val name = relative.substringAfterLast('/')
            val file = observed.getValue(relative)
            val admitted = requireNotNull(newDirectories[directory]) { "Unexpected new file: $relative" }
            if (name == "write.lock" && directory in lockDirectories) {
                check(admitted.isNotEmpty() && Files.size(file) == 0L) { "Unexpected cache lock: $relative" }
            } else {
                check(sha256.matches(name)) { "Unexpected derived filename: $relative" }
                val expectedLength = requireNotNull(admitted[name]) { "Unknown derived body or resource: $relative" }
                check(Files.size(file) == expectedLength && fileHash(file) == name) { "Derived bytes changed: $relative" }
            }
        }
    }

    private fun hash(text: String) = lanSyncHash(text.toByteArray(Charsets.UTF_8))

    private fun fileHash(path: Path): String {
        check(Files.isRegularFile(path, NOFOLLOW_LINKS))
        val size = Files.size(path)
        return if (size == 0L) lanSyncHash(byteArrayOf()) else LanSyncWorkspace.hashFile(path, size)
    }
}
