package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.*
import java.nio.file.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Materializes both selected sides ONLY inside an already fenced, new, non-runnable root. */
internal object DesktopMergeRootPreparation {
    class Result internal constructor(val plan: DesktopMergeDestinationPlan, val files: Map<String, String>) : AutoCloseable {
        override fun close() { plan.close() }
        override fun toString() = "MergeRootPrepared(files=${files.size})"
    }

    suspend fun computerEntities(snapshot: DesktopFullBackupMapping.Export): Map<String, JsonObject> {
        currentCoroutineContext().ensureActive()
        val files = snapshot.files.filterKeys { it.startsWith("entities/") }
        return DesktopBackupRecords.view(files.keys) { path ->
            // This view outlives the inspection coroutine; callers check their own active operation.
            DesktopBackupRecords.entity(path, files.getValue(path))
        }
    }

    suspend fun prepare(targetRoot: Path, authority: DesktopFreshRootAuthority, pending: DesktopFreshRootAuthority.Pending,
        computer: DesktopFullBackupMapping.Export, phone: DesktopFullBackupStaging, selection: DesktopMergePreview.Selection,
        workspace: Path, preferences: BackupPreferences = computer.preferences,
        afterTargetFile: suspend () -> Unit = {}): Result = withContext(Dispatchers.IO) {
        authority.requirePreparing(pending, targetRoot)
        var preparedPlan: DesktopMergeDestinationPlan? = null
        try {
            check(computer.credentialFreeSource) { "MERGE_COMPUTER_CREDENTIAL_SNAPSHOT_REQUIRED" }
            phone.verifyUnchanged()
            val computerProof = computer.files.mapValues { (_, file) -> DesktopArchiveFiles.hash(file, DesktopFullBackupSource.MAX_FILE) }
            val pcEntities = computerEntities(computer)
            val pcMedia = computerProof.filterKeys { !it.startsWith("entities/") }
            val phoneMedia = phone.source.inventory.filterKeys { !it.startsWith("entities/") && !it.startsWith("save_slot_packages/") }.mapValues { it.value.sha256 }
            check(DesktopMergePreview.create(pcEntities, emptyMap(), pcMedia, emptyMap()).pcFingerprint == selection.pcFingerprint) { "MERGE_COMPUTER_PREVIEW_CHANGED" }
            fun checkSelection(units: List<DesktopMergePreview.Unit>, source: Map<String, JsonObject>) {
                for (unit in units) for ((path, value) in unit.records) {
                    val original = requireNotNull(source[path]) { "MERGE_SELECTION_SOURCE_MISSING" }
                    check(value.all { (field, item) -> original[field] == item } &&
                        (unit.category == "app_settings" || value.keys == original.keys)) { "MERGE_SELECTION_SOURCE_CHANGED" }
                }
            }
            checkSelection(selection.computer, pcEntities); checkSelection(selection.phone, phone.entities)
            val catalog = linkedMapOf<String, DesktopMergeSaveSlotPackage.Catalog>()
            for (unit in selection.phone) for ((path, value) in unit.records) {
                if (DesktopBackupPolicy.entityType(path) != "save_slots" || value["packageRef"] == null || value["packageRef"] == JsonNull) continue
                val slot = DesktopMergeRecord(path, value)
                val packageName = requireNotNull(slot.text("packageRef.fileName"))
                require(packageName.matches(Regex("[A-Za-z0-9_-]{1,128}\\.cbsave"))) { "MERGE_PACKAGE_SOURCE_NAME" }
                catalog[path] = DesktopMergeSaveSlotPackage.catalog(requireNotNull(phone.files["save_slot_packages/$packageName"]), slot, workspace)
            }
            val context = currentCoroutineContext()
            val plan = DesktopMergeDestinationPlan.create(selection, pcMedia, phoneMedia, catalog, workspace) { context.ensureActive() }.also { preparedPlan = it }
            var expectedBytes = 0L
            for (file in computer.files.values + phone.files.values) expectedBytes += Files.size(file)
            check(Files.getFileStore(targetRoot).usableSpace >= expectedBytes * 3 + (32L shl 20)) { "MERGE_PREPARATION_SPACE" }
            val historicalVoices = DesktopHistoricalVoiceCustody.preserve(phone, plan, targetRoot, authority, pending)
            DesktopBackupScratch.create(workspace, "merge-files-").use { scratch ->
                val input = linkedMapOf<String, Path>()
                val overrides = linkedMapOf<String, JsonObject>()
                val entities = DesktopBackupRecords.view(plan.entities.keys) { path -> overrides[path] ?: plan.entities.getValue(path) }
                for (request in plan.packages) {
                    val source = requireNotNull(phone.files[request.original])
                    DesktopMergeSaveSlotPackage.prepare(source, request.slot, plan.identity, plan.incomingRecords, workspace).use { prepared ->
                        check(prepared.originalSha256 == request.expectedSha256) { "MERGE_PACKAGE_CHANGED_AFTER_CATALOG" }
                        val target = scratch.directory.resolve("package-${input.size}")
                        check(scratch.copy(prepared.rewritten, target, DesktopSaveSlotArchive.MAX_ARCHIVE) == prepared.rewrittenSha256)
                        input[request.destination] = target
                        val mappedSlot = entities.keys.filter { DesktopBackupPolicy.entityType(it) == "save_slots" }
                            .map { path -> java.util.AbstractMap.SimpleImmutableEntry(path, entities.getValue(path)) }.single { (path, value) ->
                            value["id"]?.jsonPrimitive?.content == plan.identity.value("save_slots", requireNotNull(request.slot.text("id"))) }
                        check(prepared.manifest["id"] == mappedSlot.value["id"] && prepared.manifest["sessionId"] == mappedSlot.value["sessionId"]) { "MERGE_PACKAGE_TARGET_OWNER" }
                        val reference = prepared.manifest.getValue("packageRef").jsonObject
                        overrides[mappedSlot.key] = JsonObject(prepared.manifest + ("packageRef" to JsonObject(reference + ("byteLength" to JsonPrimitive(prepared.bytes)))))
                        DesktopSaveSlotSourceCustody.preserve(targetRoot, request.slot.relative,
                            Files.readAllBytes(phone.files.getValue(request.slot.relative)), prepared.original, phone.source.sourceHash,
                            phone.source.manifest.appVersionCode, phone.source.manifest.formatVersion, phone.source.manifest.scope)
                    }
                }
                for ((path, value) in entities) {
                    context.ensureActive()
                    val file = scratch.directory.resolve("entity-${input.size}")
                    scratch.write(file, value.toString().toByteArray(Charsets.UTF_8)); check(input.put(path, file) == null)
                }
                for ((path, resource) in plan.resources) {
                    context.ensureActive()
                    val source = (if (resource.side == DesktopMergeDestinationPlan.Side.COMPUTER) computer.files else phone.files).getValue(resource.original)
                    val file = scratch.directory.resolve("media-${input.size}")
                    check(scratch.copy(source, file, DesktopFullBackupSource.MAX_FILE) == resource.sha256) { "MERGE_RESOURCE_CHANGED" }
                    check(input.put(path, file) == null)
                }
                DesktopFullBackupMapping(targetRoot).restoreMerged(input, workspace, preferences).use { mapped ->
                    DesktopFullBackupGraphValidation.validate(mapped.directory, historicalVoices, targetRoot)
                    // Root ownership/capability is rechecked before any live-shaped files reach the inert root.
                    authority.requirePreparing(pending, targetRoot)
                    for ((relative, file) in mapped.files) {
                        context.ensureActive()
                        val destination = targetRoot.resolve(relative)
                        Files.createDirectories(destination.parent); DesktopArchiveFiles.safe(destination.parent, true)
                        check(DesktopArchiveFiles.copyNew(file, destination, DesktopFullBackupSource.MAX_FILE) == mapped.proof.getValue(relative))
                        afterTargetFile()
                    }
                }
            }
            DesktopPreservationStore.preserveMoments(phone, targetRoot)
            phone.verifyUnchanged()
            for ((path, file) in computer.files) check(DesktopArchiveFiles.hash(file, DesktopFullBackupSource.MAX_FILE) == computerProof.getValue(path)) { "MERGE_COMPUTER_SNAPSHOT_CHANGED" }
            authority.requirePreparing(pending, targetRoot)
            DesktopFullBackupGraphValidation.validate(targetRoot)
            Result(plan, DesktopFullBackupMapping(targetRoot).inventory(StrictBackupZipReader.Limits().expanded))
        } catch (failure: Throwable) {
            pending.invalidate()
            try { preparedPlan?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}
