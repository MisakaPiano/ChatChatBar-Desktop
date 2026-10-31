package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.*
import java.nio.file.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application-owned full-archive workflow; no provider or credential API is injected. */
internal class DesktopFullBackupService(
    private val root: Path,
    private val gate: DesktopDataOperationCoordinator,
    private val audio: DesktopAudioMaintenance,
    private val beforeSnapshot: suspend () -> Unit = {},
    private val pauseScheduler: suspend () -> Unit = {},
    private val resumeScheduler: suspend () -> Unit = {},
    asset: (String) -> ByteArray = { error("BUNDLED_ASSET_UNAVAILABLE") },
    private val journal: DesktopFullBackupCommitJournal = DesktopFullBackupCommitJournal(root),
    private val secretStore: (() -> com.example.chatbar.desktop.security.DesktopSecretStore)? = null,
) {
    private val mutex = Mutex()
    private val workspace = root.resolve("backups/.full-app-work")
    private val archive get() = DesktopFullBackupArchive(workspace)
    private val mapping = DesktopFullBackupMapping(root, asset)
    val restartRequired get() = gate.isRestartRequired

    suspend fun catalog(): BackupExportCatalog = gate.withNormalOperation {
        val context = currentCoroutineContext()
        val files = mapping.inventory(StrictBackupZipReader.Limits().expanded).keys.associateWith(root::resolve)
        BackupExportCatalog.read(root.toFile(), files) { context.ensureActive() }
    }

    /** Legacy v1 compatibility entry retained for existing internal restore regression. */
    suspend fun export(destination: Path, password: CharArray?): String = exportSnapshot(destination, password, false)

    /** User-facing backup supports the current mapped code86 transport and owned preservation domains. */
    suspend fun exportModern(destination: Path, password: CharArray?, includeCredentials: Boolean = false, selection: AppBackupSelection? = null): String =
        exportSnapshot(destination, password, true, includeCredentials, selection)

    private suspend fun exportSnapshot(destination: Path, password: CharArray?, modern: Boolean, includeCredentials: Boolean = false,
        selection: AppBackupSelection? = null): String = try {
        mutex.withLock {
            var snapshot: DesktopFullBackupMapping.Export? = null
            var credentials: DesktopBackupExportCredentials? = null
            var stage = "PREPARE"
            try {
            beforeSnapshot()
            audio.withMaintenance(true, { gate.isRestartRequired }) {
                gate.withExclusiveMaintenance {
                    ensureWorkspace()
                    if (modern) {
                        stage = "GRAPH"
                        check(DesktopDataRootOwnership.isHeld(root))
                        check(!DesktopIndexCommitJournal(root).hasPending() && !DesktopSessionCommitJournal(root).hasPending() && !journal.hasPending())
                        DesktopFullBackupGraphValidation.validate(root)
                    }
                    stage = "FILES"
                    snapshot = mapping.export(workspace, if (modern) 86 else 84,
                        if (modern) StrictBackupZipReader.Limits().expanded else DesktopBackupPolicy.MAX_TOTAL, selection = selection)
                    if (includeCredentials) {
                        stage = "CREDENTIALS"
                        val entities = requireNotNull(snapshot).files.filterKeys { it.startsWith("entities/") }.mapValues { (path, file) ->
                            DesktopBackupPolicy.entity(path, Files.readAllBytes(file), false, 86)
                        }
                        credentials = DesktopBackupExportCredentials.capture(requireNotNull(secretStore).invoke(), entities,
                            selection?.globalOptions ?: inspectionGlobalOptions)
                    }
                }
            }
            // Slow compression/encryption is outside exclusive. It reads only the frozen staging copy.
            requireNotNull(snapshot).let {
                if (modern) DesktopFullBackupWriter.export(it.files, destination, password,
                    if (selection == null || "imagePreferences" in selection.globalOptions) it.preferences else BackupPreferences(), credentials, selection)
                else archive.export(it.files, destination, root.toString(), password, it.preferences)
            }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: DesktopFullBackupWriter.Refusal) { throw failure }
            catch (failure: DesktopBackupCompatibilityException) { throw failure }
            catch (failure: DesktopBackupScratchCleanupException) { throw failure }
            catch (failure: DesktopBackupMissingResource) { throw failure }
            catch (failure: IllegalStateException) {
                // These fixed custody rejection codes are consumed by existing diagnostics.
                // Keep their identity instead of replacing a classified refusal with a generic stage.
                if (failure.message in setOf("UNCLASSIFIED_ROOT_ENTRY", "UNCLASSIFIED_HIDDEN_BACKUP_ENTRY",
                    "DESKTOP_CUSTODY_REQUIRES_FULL_SELECTION")) throw failure
                throw DesktopBackupExportFailure(stage)
            }
            catch (_: Exception) { throw DesktopBackupExportFailure(stage) }
            finally { credentials?.close(); snapshot?.close() }
        }
    } finally { password?.fill('\u0000') }

    suspend fun release(prepared: DesktopFullBackupArchive.Prepared) = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock { prepared.close() }
    }

    suspend fun inspect(source: Path, password: CharArray?): DesktopFullBackupArchive.Prepared = try {
        mutex.withLock { gate.withNormalOperation { ensureWorkspace(); archive.inspect(source, password) } }
    } finally { password?.fill('\u0000') }

    data class FileCheck(val summary: AppBackupSummary, val desktopOnly: Boolean)

    /** Current GUI check authenticates the whole streamed file without preparing legacy replacement. */
    suspend fun verifyFile(source: Path, password: CharArray?): FileCheck = try {
        mutex.withLock { gate.withNormalOperation {
            val context = currentCoroutineContext()
            var desktopOnly = false
            val verified = DesktopFullBackupSource.verify(source, password, object : DesktopFullBackupSource.TentativeSink {
                override fun entity(path: String, original: kotlinx.serialization.json.JsonObject, raw: ByteArray) {
                    desktopOnly = desktopOnly || DesktopBackupPolicy.isDesktopOnly(path) || DesktopBackupPolicy.hasDesktopParticipation(original)
                }
                override fun preferences(original: kotlinx.serialization.json.JsonObject) {}
                override fun media(path: String, input: java.io.InputStream) {
                    desktopOnly = desktopOnly || DesktopBackupPolicy.isDesktopOnly(path)
                }
            }) { context.ensureActive() }
            val m = verified.manifest
            FileCheck(AppBackupSummary(m.formatVersion, m.appVersionCode, m.appVersionName, m.createdAt,
                m.sourceFilesRoot, m.counts, m.fileCount, m.totalBytes, m.preferencesSha256), desktopOnly)
        } }
    } finally { password?.fill('\u0000') }

    /** Explicit confirmation schedules replacement for the next owned, pre-repository startup. */
    suspend fun scheduleRestore(prepared: DesktopFullBackupArchive.Prepared) = mutex.withLock {
        val plan = gate.withNormalOperation {
            archive.verify(prepared)
            mapping.restore(prepared)
        }
        plan.use {
        gate.withNormalOperation { DesktopFullBackupGraphValidation.validate(plan.directory) }
        beforeSnapshot()
        // Never hold audio maintenance while joining a scheduler that could be requesting audio.
        pauseScheduler()
        try {
            audio.withMaintenance(true, { gate.isRestartRequired }) {
                gate.withExclusiveMaintenance {
                    check(DesktopDataRootOwnership.isHeld(root))
                    check(!DesktopIndexCommitJournal(root).hasPending() && !DesktopSessionCommitJournal(root).hasPending())
                    archive.verify(prepared); mapping.verify(plan)
                    val old = mapping.inventory()
                    val changes = linkedMapOf<String, Path?>()
                    old.keys.forEach { changes[it] = null }
                    plan.files.forEach { (path, file) -> changes[path] = file }
                    try {
                        journal.schedule(changes, changes.mapValues { (path, _) -> old[path] }, onScheduled = { requireRestart() })
                    } catch (failure: Throwable) {
                        if (journal.hasPending()) requireRestart()
                        throw failure
                    }
                }
            }
        } finally { withContext(NonCancellable) { resumeScheduler() } }
        }
    }

    private fun ensureWorkspace() {
        var ancestor = workspace
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.parent
        DesktopArchiveFiles.safe(ancestor, true)
        Files.createDirectories(workspace); DesktopArchiveFiles.safe(workspace, true)
    }
}

internal class DesktopBackupExportFailure(val stage: String) : IllegalStateException("FULL_BACKUP_EXPORT_$stage")
