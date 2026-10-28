package com.example.chatbar.desktop

import com.example.chatbar.data.snapshot.AppDataSnapshotService
import com.example.chatbar.data.snapshot.SnapshotPurpose
import com.example.chatbar.desktop.backup.*
import com.example.chatbar.desktop.security.DesktopSecretStore
import java.nio.file.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Application-owned merge: old data/keys remain untouched; only a verified NEW root is published. */
internal class DesktopMergeImportService(
    private val source: DesktopDataRootResolution.Resolved,
    private val gate: DesktopDataOperationCoordinator,
    private val oldStore: DesktopSecretStore,
    private val beforeSnapshot: suspend () -> Unit,
    private val quiesce: suspend (suspend () -> Unit) -> Unit,
    private val destinationPreparer: DesktopMigrationDestinationPreparer = DesktopMigrationDestinationPreparer(),
    private val authorityTransaction: DesktopBootstrapAuthorityTransaction = DesktopBootstrapAuthorityTransaction(),
    private val freshAuthority: (Path) -> DesktopFreshRootAuthority = { DesktopFreshRootAuthority(it) },
    private val asset: (String) -> ByteArray = { error("BUNDLED_ASSET_UNAVAILABLE") },
    private val workspace: Path = source.appDataRoot.resolve("backups/.merge-import-work"),
) {
    class ModelDetails internal constructor(val computer: List<String>, val phone: List<String>) {
        override fun toString() = "MergeModelDetails(REDACTED)"
    }
    class Preview internal constructor(internal val phone: DesktopFullBackupStaging,
        internal val computer: DesktopFullBackupMapping.Export, internal val credentials: DesktopMergeCredentials,
        val data: DesktopMergePreview, internal val desktopSettings: ByteArray?, val modelDetails: Map<String, ModelDetails>) : AutoCloseable {
        val code get() = phone.source.manifest.appVersionCode
        val versionName get() = phone.source.manifest.appVersionName
        val fileCount get() = phone.source.inventory.size
        val hasDesktopParticipation get() = phone.entities.values.any(DesktopBackupPolicy::hasDesktopParticipation)
        val credentialConflicts get() = credentials.globalConflicts
        private val incomingPreferences get() = backupJson.decodeFromJsonElement<InspectionPreferences>(phone.preferences)
        val imagePreferencesChoiceRequired get() = (phone.source.manifest.scope?.globalOptions?.contains("imagePreferences") != false) &&
            (computer.preferences.brushSize != incomingPreferences.brushSize || computer.preferences.brushType != incomingPreferences.brushType)
        internal fun selectedPreferences(fromPhone: Boolean?): BackupPreferences {
            require(!imagePreferencesChoiceRequired || fromPhone != null) { "MERGE_PREFERENCES_CHOICE_REQUIRED" }
            if (fromPhone != true || phone.source.manifest.scope?.globalOptions?.contains("imagePreferences") == false) return computer.preferences
            return BackupPreferences(brushSize = incomingPreferences.brushSize, brushType = incomingPreferences.brushType)
        }
        override fun close() {
            try { credentials.close() } finally { try { computer.close() } finally { phone.close() } }
            desktopSettings?.fill(0)
        }
        override fun toString() = "MergePreview(code=$code,files=$fileCount)"
    }
    enum class Publication { COMMITTED, INDETERMINATE }
    enum class Stage { AUTHENTICATING_SOURCE, READING_COMPUTER, DRAINING_TASKS, CHECKING_SOURCE,
        SAFETY_SNAPSHOT, PREPARING_ROOT, PROTECTING_CREDENTIALS, VERIFYING_RESULT, SWITCHING_STARTUP }
    data class Progress(val stage: Stage, val verifiedFiles: Int = 0)
    internal data class CommitEvidence(val snapshot: com.example.chatbar.data.snapshot.AppDataSnapshot,
        val authority: DesktopBootstrapAuthorityCommitResult)
    internal var committedEvidence: CommitEvidence? = null
        private set
    private val mutex = Mutex()
    private val root = source.appDataRoot
    private val mapping = DesktopFullBackupMapping(root, asset)
    private var prepared: Preview? = null
    private var retainedDestination: DesktopPreparedMigrationDestination? = null
    internal val ownsPublishedDestination get() = retainedDestination != null
    private var closed = false
    val restartRequired get() = gate.isRestartRequired

    suspend fun inspect(path: Path, password: CharArray?): Preview = inspectWithProgress(path, password) {}

    suspend fun inspectWithProgress(path: Path, password: CharArray?, progress: (Progress) -> Unit): Preview = try {
        mutex.withLock {
            check(!closed && !restartRequired) { "MERGE_IMPORT_UNAVAILABLE" }
            check(source.provenance.isBootstrapControlledMigrationSource()) { "MERGE_BOOTSTRAP_SOURCE_REQUIRED" }
            releaseLocked()
            progress(Progress(Stage.AUTHENTICATING_SOURCE))
            val phone = gate.withNormalOperation { ensureWorkspace(); DesktopFullBackupStaging.prepare(path, password, workspace) }
            var pc: DesktopFullBackupMapping.Export? = null
            var keys: DesktopMergeCredentials? = null
            try {
                // This is a preview, not permission to change current writers or publish a root.
                progress(Progress(Stage.READING_COMPUTER))
                beforeSnapshot()
                val result = gate.withExclusiveMaintenance {
                    checkOwnedSource()
                    val snapshot = mapping.export(workspace, 86, StrictBackupZipReader.Limits().expanded, forMerge = true).also { pc = it }
                    val entities = DesktopMergeRootPreparation.computerEntities(snapshot)
                    val captured = DesktopMergeCredentials.capture(entities, phone, oldStore).also { keys = it }
                    val data = DesktopMergePreview.create(entities, phone.entities,
                        snapshot.files.filterKeys { !it.startsWith("entities/") }.mapValues { DesktopArchiveFiles.hash(it.value, DesktopFullBackupSource.MAX_FILE) },
                        phone.source.inventory.filterKeys { !it.startsWith("entities/") }.mapValues { it.value.sha256 },
                        phone.source.manifest.scope, captured.dataConflictKeys)
                    Preview(phone, snapshot, captured, data, readDesktopSettings(), modelDetails(data, entities, phone.entities))
                }
                prepared = result
                result
            } catch (failure: Throwable) {
                try { keys?.close() } finally { try { pc?.close() } finally { phone.close() } }
                throw failure
            }
        }
    } finally { password?.fill('\u0000') }

    suspend fun commit(preview: Preview, destination: Path, choices: Map<String, DesktopMergePreview.Choice>,
        credentialChoices: Map<ImportCredentialKind, DesktopMergeCredentials.Choice>, confirmed: Boolean, imagePreferencesFromPhone: Boolean? = null): Publication =
        commitWithProgress(preview, destination, choices, credentialChoices, confirmed, imagePreferencesFromPhone) {}

    suspend fun commitWithProgress(preview: Preview, destination: Path, choices: Map<String, DesktopMergePreview.Choice>,
        credentialChoices: Map<ImportCredentialKind, DesktopMergeCredentials.Choice>, confirmed: Boolean,
        imagePreferencesFromPhone: Boolean? = null, progress: (Progress) -> Unit): Publication = mutex.withLock {
        check(!closed && !restartRequired && prepared === preview) { "MERGE_PREVIEW_EXPIRED" }
        check(confirmed) { "MERGE_EXPLICIT_CONFIRMATION_REQUIRED" }
        committedEvidence = null
        val selection = preview.data.select(choices)
        val preferences = preview.selectedPreferences(imagePreferencesFromPhone)
        // Destination is never a current root or a silently reused nonempty directory.
        val destinationHandle = when (val prepared = destinationPreparer.prepare(source, destination)) {
            is DesktopMigrationDestinationResult.Prepared -> prepared.destination
            is DesktopMigrationDestinationResult.Failure -> error("MERGE_DESTINATION_${prepared.kind}")
        }
        var published = false
        var publication: Publication? = null
        try {
            progress(Progress(Stage.DRAINING_TASKS))
            quiesce {
                beforeSnapshot() // Drain composer/reading-position work before acquiring exclusive.
                gate.withExclusiveMaintenance {
                    progress(Progress(Stage.CHECKING_SOURCE))
                    checkOwnedSource()
                    check(mapping.inventory(StrictBackupZipReader.Limits().expanded) == preview.computer.proof) { "MERGE_COMPUTER_CHANGED_REINSPECT" }
                    val settings = readDesktopSettings()
                    try { check(if (settings == null) preview.desktopSettings == null else preview.desktopSettings?.contentEquals(settings) == true) { "MERGE_COMPUTER_CHANGED_REINSPECT" } }
                    finally { settings?.fill(0) }
                    preview.credentials.verifyComputerUnchanged()
                    preview.phone.verifyUnchanged()
                    progress(Progress(Stage.SAFETY_SNAPSHOT))
                    val snapshot = AppDataSnapshotService(root).createSnapshot(SnapshotPurpose.PRE_RESTORE)
                    check(snapshot.validation.valid) { "MERGE_SAFETY_SNAPSHOT_FAILED" }
                    val target = destinationHandle.destinationRoot
                    val authority = freshAuthority(target)
                    val pending = authority.begin()
                    progress(Progress(Stage.PREPARING_ROOT))
                    var verifiedFiles = 0
                    val result = DesktopMergeRootPreparation.prepare(target, authority, pending, preview.computer,
                        preview.phone, selection, workspace, preferences) {
                        verifiedFiles++
                        if (verifiedFiles == 1 || verifiedFiles % 128 == 0) progress(Progress(Stage.PREPARING_ROOT, verifiedFiles))
                    }
                    progress(Progress(Stage.PROTECTING_CREDENTIALS))
                    preview.credentials.writeNewRoot(target, authority, pending, result.plan, selection, credentialChoices, confirmed)
                    val files = result.files.toMutableMap()
                    preview.desktopSettings?.let {
                        val path = target.resolve(DesktopSettingsStore.SETTINGS_FILE_NAME)
                        Files.write(path, it, StandardOpenOption.CREATE_NEW)
                        java.nio.channels.FileChannel.open(path, StandardOpenOption.WRITE).use { channel -> channel.force(true) }
                        files[DesktopSettingsStore.SETTINGS_FILE_NAME] = DesktopArchiveFiles.hash(path, 1L shl 20)
                    }
                    progress(Progress(Stage.VERIFYING_RESULT))
                    preview.credentials.verifyComputerUnchanged()
                    check(mapping.inventory(StrictBackupZipReader.Limits().expanded) == preview.computer.proof) { "MERGE_COMPUTER_CHANGED_REINSPECT" }
                    val latestSettings = readDesktopSettings()
                    try { check(if (latestSettings == null) preview.desktopSettings == null else preview.desktopSettings?.contentEquals(latestSettings) == true) { "MERGE_COMPUTER_CHANGED_REINSPECT" } }
                    finally { latestSettings?.fill(0) }
                    currentCoroutineContext().ensureActive()
                    // UI notification is before the irreversible section; no callback can fail after publication.
                    progress(Progress(Stage.SWITCHING_STARTUP))
                    currentCoroutineContext().ensureActive()
                    // Once publication may occur, cancellation cannot release the new root or reopen old writers.
                    withContext(NonCancellable) {
                        authority.complete(pending, files)
                        val commit = try { authorityTransaction.commitCustomRoot(source, target) }
                        catch (_: Throwable) { DesktopBootstrapAuthorityCommitResult.CommitIndeterminate("Merge authority result unavailable") }
                        when (commit) {
                            is DesktopBootstrapAuthorityCommitResult.Committed -> publication = Publication.COMMITTED
                            is DesktopBootstrapAuthorityCommitResult.CommitIndeterminate -> publication = Publication.INDETERMINATE
                            else -> error("MERGE_AUTHORITY_NOT_COMMITTED")
                        }
                        retainedDestination = destinationHandle
                        published = true
                        requireRestart()
                        committedEvidence = CommitEvidence(snapshot, commit)
                    }
                }
            }
            requireNotNull(publication)
        } finally {
            if (!published) destinationHandle.close()
            // Volatile keys and inspection scratch have no lifetime beyond this confirmed attempt.
            withContext(NonCancellable) { releaseLocked() }
        }
    }

    suspend fun discard() = mutex.withLock { releaseLocked() }
    suspend fun close() = mutex.withLock {
        closed = true
        try { releaseLocked() } finally { retainedDestination?.close(); retainedDestination = null }
    }
    private fun modelDetails(data: DesktopMergePreview, computer: Map<String, JsonObject>, phone: Map<String, JsonObject>): Map<String, ModelDetails> {
        fun rows(records: Map<String, JsonObject>, keys: Set<String>): List<String> = records.mapNotNull { (path, value) ->
            val type = DesktopBackupPolicy.entityType(path)
            if (type !in setOf("model_configs", "retrieval_model_config", "embedding_configs", "embedding_model_config")) return@mapNotNull null
            fun text(field: String) = value[field]?.jsonPrimitive?.contentOrNull.orEmpty()
            if ("$type:${text("id")}" !in keys) return@mapNotNull null
            // Explicit local UI projection. Never include apiKey, raw JSON, credential relation or a digest.
            listOf(if (type in setOf("model_configs", "retrieval_model_config")) text("templateType").ifEmpty { "OPENAI" } else "Embedding", text("baseUrl"), text("modelName"), "ID: ${text("id")}").joinToString(" · ")
        }
        return data.conflicts.mapNotNull { conflict ->
            val pc = rows(computer, conflict.pcKeys); val incoming = rows(phone, setOf(conflict.key))
            if (pc.isEmpty() && incoming.isEmpty()) null else conflict.key to ModelDetails(pc, incoming)
        }.toMap()
    }
    private fun releaseLocked() { prepared?.let { prepared = null; it.close() } }
    private fun checkOwnedSource() {
        check(DesktopDataRootOwnership.isHeld(root)) { "MERGE_SOURCE_NOT_OWNED" }
        check(!DesktopIndexCommitJournal(root).hasPending() && !DesktopSessionCommitJournal(root).hasPending() &&
            !DesktopFullBackupCommitJournal(root).hasPending()) { "MERGE_SOURCE_RECOVERY_PENDING" }
    }
    private suspend fun readDesktopSettings(): ByteArray? {
        val path = root.resolve(DesktopSettingsStore.SETTINGS_FILE_NAME)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        DesktopArchiveFiles.safe(path, false)
        check(Files.size(path) <= 1L shl 20) { "MERGE_DESKTOP_SETTINGS_BUDGET" }
        check(DesktopSettingsStore(root, gate).load() !is DesktopSettingsLoadResult.Failure) { "MERGE_DESKTOP_SETTINGS_INVALID" }
        return Files.readAllBytes(path)
    }
    private fun ensureWorkspace() {
        var ancestor = workspace
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.parent
        DesktopArchiveFiles.safe(ancestor, true)
        Files.createDirectories(workspace); DesktopArchiveFiles.safe(workspace, true)
    }
}
