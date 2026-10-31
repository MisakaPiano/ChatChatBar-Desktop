package com.example.chatbar.desktop.backup

import java.util.UUID
import kotlinx.serialization.json.*

/** Frozen selection -> typed identities and collision-free target names. Optional owned record scratch, never secret writes or activation. */
internal class DesktopMergeDestinationPlan private constructor(
    val entities: Map<String, JsonObject>,
    val resources: Map<String, Resource>,
    val packages: List<Package>,
    val identity: DesktopMergeIdentity,
    val incomingRecords: List<DesktopMergeRecord>,
    val pcFingerprint: String,
    private val recordScratch: DesktopBackupScratch? = null,
) : AutoCloseable {
    override fun close() { recordScratch?.close() }
    enum class Side { COMPUTER, PHONE }
    class Resource internal constructor(val side: Side, val original: String, val sha256: String) {
        override fun toString() = "MergeResource($side)"
    }
    class Package internal constructor(val original: String, val destination: String, val slot: DesktopMergeRecord,
        val expectedSha256: String) {
        override fun toString() = "MergePackage()"
    }
    override fun toString() = "MergeDestinationPlan(entities=${entities.size},resources=${resources.size},packages=${packages.size})"

    companion object {
        fun create(selection: DesktopMergePreview.Selection, computerMedia: Map<String, String>, phoneMedia: Map<String, String>,
            catalogs: Map<String, DesktopMergeSaveSlotPackage.Catalog> = emptyMap(),
            recordWorkspace: java.nio.file.Path? = null, checkActive: () -> Unit = {}): DesktopMergeDestinationPlan {
            val recordScratch = recordWorkspace?.let { DesktopBackupScratch.create(it, "merge-records-") }
            try {
            checkActive()
            val identity = DesktopMergeIdentity()
            require(computerMedia.keys.none { it.startsWith("entities/") } && phoneMedia.keys.none {
                it.startsWith("entities/") || it.startsWith("save_slot_packages/")
            }) { "MERGE_MEDIA_INPUT_CATEGORY" }
            val phone = selection.phone.flatMap { unit -> DesktopMergeRecord.from(unit.records) }
            val packageSlots = phone.filter { it.type == "save_slots" && it.value["packageRef"] != null && it.value["packageRef"] != JsonNull }
            require(catalogs.keys == packageSlots.mapTo(hashSetOf()) { it.relative }) { "MERGE_PACKAGE_CATALOG_REQUIRED" }
            val declarations = linkedSetOf<Pair<String, String>>()
            for (unit in selection.phone.filter { it.key in selection.forkIncomingUnits }) {
                for ((path, value) in unit.records) {
                    checkActive(); declarations += identity.declarations(DesktopMergeRecord(path, value), checkActive)
                    catalogs[path]?.let { declarations += it.declarations }
                    require(declarations.size <= 80000) { "MERGE_DECLARATION_BUDGET" }
                }
            }
            // All messages, including historical-only nested ones, share a compact monotonically
            // assigned prefix. Their old lexical order survives equal timestamp/orderKey ties.
            val prefix = UUID.randomUUID().toString().replace("-", "")
            declarations.filter { it.first == "chat_messages" }.map { it.second }.sorted().forEachIndexed { index, old ->
                identity.bind("chat_messages", old, "$prefix-${index.toString(16).padStart(8, '0')}")
            }
            declarations.filter { it.first != "chat_messages" }.forEach { (type, id) -> identity.bind(type, id, UUID.randomUUID().toString()) }
            val memory = phone + catalogs.values.map { it.manifest }
            val mapper = DesktopMergeGraphRemapper(memory, identity, checkActive)
            val entities: MutableMap<String, JsonObject> = recordScratch?.let(::DesktopBackupRecordStore) ?: linkedMapOf()
            val resources = linkedMapOf<String, Resource>()
            val names = hashSetOf<String>(); val pcNames = linkedMapOf<String, String>(); val phoneNames = linkedMapOf<String, String>()
            fun addMedia(side: Side, original: String, hash: String): String {
                DesktopBackupPolicy.path(original); require(hash.matches(Regex("[a-f0-9]{64}"))) { "MERGE_MEDIA_HASH" }
                val destination = if (side == Side.PHONE && original.substringBefore('/') in setOf("images", "documents", "audio", "draft_assets")) {
                    val ext = original.substringAfterLast('.', "bin").lowercase()
                    require(ext.matches(Regex("[a-z0-9]{1,10}"))) { "MERGE_MEDIA_EXTENSION" }
                    original.substringBefore('/') + "/import-" + DesktopBackupPolicy.digest((original + ":" + hash).toByteArray(Charsets.UTF_8)).take(40) + ".$ext"
                } else if (original.startsWith("images/") || original.startsWith("documents/")) DesktopBackupPolicy.desktopName(original) else original
                val existing = resources[destination]
                if (existing != null) require(existing.sha256 == hash) { "MERGE_RESOURCE_COLLISION" }
                else {
                    require(names.add(destination.lowercase())) { "MERGE_TARGET_CASE_COLLISION" }
                    resources[destination] = Resource(side, original, hash)
                }
                return destination
            }
            for ((path, hash) in computerMedia) { checkActive(); pcNames[path] = addMedia(Side.COMPUTER, path, hash) }
            for ((path, hash) in phoneMedia) {
                checkActive()
                // Identical/self import selects no incoming records. A byte-identical file at
                // the same logical path is already retained, including its ownership receipt.
                // Do not share live media between newly selected/forked incoming consumers.
                phoneNames[path] = if (selection.phone.isEmpty() && computerMedia[path] == hash)
                    pcNames.getValue(path) else addMedia(Side.PHONE, path, hash)
            }
            fun resource(names: Map<String, String>, value: String, fields: List<String>): String {
                if (value.startsWith("chatbar-save-slot-") || value.startsWith("https:") || value.startsWith("http:") || value.isBlank()) return value
                if (value.startsWith("chatbar-vibe-file:")) {
                    val path = "novelai-vibe-payloads/" + value.removePrefix("chatbar-vibe-file:")
                    require(names[path] == path) { "MERGE_VIBE_REFERENCE" }; return value
                }
                require(value.startsWith(DesktopBackupPolicy.PREFIX)) { "MERGE_RESOURCE_NOT_PORTABLE" }
                val relative = value.removePrefix(DesktopBackupPolicy.PREFIX)
                // Pending cleanup references are not permission to synthesize or select a different file.
                val mapped = names[relative] ?: error("MERGE_RESOURCE_NOT_SELECTED")
                return DesktopBackupPolicy.PREFIX + mapped
            }
            fun add(record: DesktopMergeRecord, mapped: JsonObject, settings: Boolean) {
                val destination = destination(record, mapped)
                val old = entities[destination]
                if (old != null && settings && record.type == "app_settings") {
                    require(old.keys.intersect(mapped.keys).isEmpty()) { "MERGE_SETTING_SELECTION_CONFLICT" }
                    entities[destination] = JsonObject(old + mapped)
                } else {
                    require(old == null && names.add(destination.lowercase())) { "MERGE_ENTITY_TARGET_COLLISION" }
                    entities[destination] = mapped
                }
            }
            for (unit in selection.computer) for ((path, value) in unit.records) {
                checkActive()
                val mapped = backupJson.parseToJsonElement(DesktopBackupPolicy.mapResources(value) { reference, fields -> resource(pcNames, reference, fields) }.toString(Charsets.UTF_8)).jsonObject
                add(DesktopMergeRecord(path, value), mapped, unit.category == "app_settings")
            }
            for (unit in selection.phone) for ((path, value) in unit.records) {
                checkActive(); val record = DesktopMergeRecord(path, value)
                var mapped = mapper.rewrite(record) { reference, fields -> resource(phoneNames, reference, fields) }
                if (record.type == "chat_message_indexes" && "fileFingerprint" in mapped) mapped = JsonObject(mapped + ("fileFingerprint" to JsonPrimitive(0)))
                if (record in packageSlots || packageSlots.any { it.relative == record.relative }) {
                    val reference = mapped.getValue("packageRef").jsonObject
                    mapped = JsonObject(mapped + ("packageRef" to JsonObject(reference + mapOf(
                        "fileName" to JsonPrimitive(identity.value("save_slots", requireNotNull(record.text("id"))) + ".cbsave"),
                        "byteLength" to JsonPrimitive(0))))) // Publisher replaces this with verified output length.
                }
                add(record, mapped, unit.category == "app_settings")
            }
            val packages = packageSlots.map { slot ->
                val source = requireNotNull(slot.text("packageRef.fileName"))
                val dest = "save_slot_packages/" + identity.value("save_slots", requireNotNull(slot.text("id"))) + ".cbsave"
                require(names.add(dest.lowercase())) { "MERGE_PACKAGE_TARGET_COLLISION" }
                Package("save_slot_packages/$source", dest, slot, catalogs.getValue(slot.relative).sha256)
            }
            return DesktopMergeDestinationPlan(if (recordScratch == null) entities.toMap() else entities, resources.toMap(), packages, identity, memory, selection.pcFingerprint, recordScratch)
            } catch (failure: Throwable) {
                try { recordScratch?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        private fun destination(record: DesktopMergeRecord, mapped: JsonObject): String {
            fun string(name: String) = mapped[name]?.jsonPrimitive?.contentOrNull
            // These are semantic singleton roles in the actual shared baseline repository,
            // physically stored with key "default"; their embedded logical ID is not a filename.
            if (record.type in setOf("embedding_model_config", "retrieval_model_config"))
                return "entities/${record.type}/default.json"
            if (record.singleton) return DesktopBackupPolicy.desktopName(record.relative, mapped)
            val key = when (record.type) {
                "chat_messages" -> requireNotNull(string("sessionId")) + "_" + requireNotNull(string("id"))
                "voice_anchor_states" -> {
                    val message = requireNotNull(string("messageId")); val version = string("messageVersionId")
                    if (version == null) message else UUID.nameUUIDFromBytes("$message\u0000$version".toByteArray(Charsets.UTF_8)).toString()
                }
                "chat_message_indexes", "chat_message_order_backups", "chat_drafts", "chat_scroll_positions", "memory_states" -> requireNotNull(string("sessionId"))
                else -> requireNotNull(string("id")) { "MERGE_ENTITY_TARGET_ID" }
            }
            return DesktopBackupPolicy.desktopName("entities/${record.type}/$key.json", mapped)
        }
    }
}
