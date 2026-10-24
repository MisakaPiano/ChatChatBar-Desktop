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
) {
    private val mutex = Mutex()
    private val workspace = root.resolve("backups/.full-app-work")
    private val archive get() = DesktopFullBackupArchive(workspace)
    private val mapping = DesktopFullBackupMapping(root, asset)
    val restartRequired get() = gate.isRestartRequired

    /** Legacy v1 compatibility entry retained for existing internal restore regression. */
    suspend fun export(destination: Path, password: CharArray?): String = exportSnapshot(destination, password, false)

    /** User-facing backup supports the current mapped code86 transport and owned preservation domains. */
    suspend fun exportModern(destination: Path, password: CharArray?): String = exportSnapshot(destination, password, true)

    private suspend fun exportSnapshot(destination: Path, password: CharArray?, modern: Boolean): String = try {
        mutex.withLock {
            var snapshot: DesktopFullBackupMapping.Export? = null
            try {
            beforeSnapshot()
            audio.withMaintenance(true, { gate.isRestartRequired }) {
                gate.withExclusiveMaintenance {
                    ensureWorkspace()
                    if (modern) {
                        check(DesktopDataRootOwnership.isHeld(root))
                        check(!DesktopIndexCommitJournal(root).hasPending() && !DesktopSessionCommitJournal(root).hasPending() && !journal.hasPending())
                        DesktopFullBackupGraphValidation.validate(root)
                    }
                    snapshot = mapping.export(workspace, if (modern) 86 else 84,
                        if (modern) StrictBackupZipReader.Limits().expanded else DesktopBackupPolicy.MAX_TOTAL)
                }
            }
            // Slow compression/encryption is outside exclusive. It reads only the frozen staging copy.
            requireNotNull(snapshot).let {
                if (modern) DesktopFullBackupWriter.export(it.files, destination, password, it.preferences)
                else archive.export(it.files, destination, root.toString(), password, it.preferences)
            }
            } finally { snapshot?.close() }
        }
    } finally { password?.fill('\u0000') }

    suspend fun release(prepared: DesktopFullBackupArchive.Prepared) = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock { prepared.close() }
    }

    suspend fun inspect(source: Path, password: CharArray?): DesktopFullBackupArchive.Prepared = try {
        mutex.withLock { gate.withNormalOperation { ensureWorkspace(); archive.inspect(source, password) } }
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
