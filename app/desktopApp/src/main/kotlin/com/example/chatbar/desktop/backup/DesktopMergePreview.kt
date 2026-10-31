package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.DesktopSessionInventory
import kotlinx.serialization.json.*

/** User-driven selection over immutable, credential-free inputs. Publication is a separate operation. */
internal class DesktopMergePreview private constructor(
    val conflicts: List<Conflict>,
    private val pc: Map<String, Unit>,
    private val incoming: Map<String, Unit>,
    private val identical: Set<String>,
    val pcFingerprint: String,
) {
    enum class Choice { KEEP_PC, TAKE_PHONE, BOTH, SKIP }
    enum class Reason { SAME_ID_DIFFERENT_CONTENT, SAME_NAME, ACCOUNT_IDENTITY, OWNED_ID_COLLISION, CREDENTIAL_CONFLICT }
    class Conflict internal constructor(val key: String, val label: String, val category: String,
        val reasons: Set<Reason>, val choices: Set<Choice>, internal val pcKeys: Set<String>) {
        override fun toString() = "MergeConflict($category,$reasons)"
    }
    class Unit internal constructor(val key: String, val category: String, val label: String,
        val records: Map<String, JsonObject>, internal val signature: String,
        internal val messageIds: Set<String> = emptySet(), internal val forkable: Boolean = false,
        internal val ownedIds: Set<Pair<String, String>> = emptySet()) {
        override fun toString() = "MergeUnit($category,${records.size})"
    }
    class Selection internal constructor(val computer: List<Unit>, val phone: List<Unit>,
        val forkIncomingUnits: Set<String>, val pcFingerprint: String,
        val ignoredIncomingUnits: Set<String> = emptySet()) {
        override fun toString() = "MergeSelection(pc=${computer.size},phone=${phone.size},forks=${forkIncomingUnits.size})"
    }

    fun select(choices: Map<String, Choice>): Selection {
        require(choices.keys == conflicts.mapTo(hashSetOf()) { it.key }) { "MERGE_EXPLICIT_CHOICES_REQUIRED" }
        val computer = pc.toMutableMap()
        val phone = incoming.filterKeys { it !in identical }.toMutableMap()
        val forks = hashSetOf<String>()
        conflicts.forEach { conflict ->
            val choice = choices.getValue(conflict.key)
            require(choice in conflict.choices) { "MERGE_CHOICE_NOT_APPLICABLE" }
            when (choice) {
                Choice.KEEP_PC, Choice.SKIP -> phone.remove(conflict.key)
                Choice.TAKE_PHONE -> conflict.pcKeys.forEach(computer::remove)
                Choice.BOTH -> {
                    // Same-name independent identities stay intact. Any colliding identity needs
                    // a complete dependency remapper, never a filename-only rename.
                    if (conflict.key in pc || Reason.OWNED_ID_COLLISION in conflict.reasons) forks += conflict.key
                }
            }
        }
        return Selection(computer.values.toList(), phone.values.toList(), forks, pcFingerprint,
            choices.filterValues { it == Choice.KEEP_PC || it == Choice.SKIP }.keys.toSet())
    }

    companion object {
        /** Scope filters AppSettings fields: omitted/unselected values must not reset computer settings. */
        fun create(pcEntities: Map<String, JsonObject>, phoneEntities: Map<String, JsonObject>,
            pcMedia: Map<String, String>, phoneMedia: Map<String, String>, scope: InspectionScope? = null,
            credentialConflicts: Set<String> = emptySet()): DesktopMergePreview {
            val pc = units(pcEntities, pcMedia, null)
            val phone = units(phoneEntities, phoneMedia, scope)
            val identical = hashSetOf<String>()
            val conflicts = mutableListOf<Conflict>()
            phone.values.forEach { candidate ->
                val exact = pc[candidate.key]
                if (exact != null && exact.signature == candidate.signature && candidate.key !in credentialConflicts) { identical += candidate.key; return@forEach }
                val sameName = if (candidate.label.isEmpty()) emptyList() else pc.values.filter {
                    it.category == candidate.category && it.label == candidate.label && it.key != candidate.key
                }
                val ownedCollisions = if (candidate.category == "chat_sessions") pc.values.filter {
                    it.category == "chat_sessions" && it.key != candidate.key && it.ownedIds.any(candidate.ownedIds::contains)
                } else emptyList()
                val peers = (listOfNotNull(exact) + sameName + ownedCollisions).distinctBy { it.key }
                if (peers.isEmpty()) return@forEach
                val reasons = buildSet {
                    if (exact != null) add(Reason.SAME_ID_DIFFERENT_CONTENT)
                    if (sameName.isNotEmpty()) add(Reason.SAME_NAME)
                    if (candidate.key in credentialConflicts) add(Reason.CREDENTIAL_CONFLICT)
                    if (ownedCollisions.isNotEmpty()) add(Reason.OWNED_ID_COLLISION)
                    if (exact != null && candidate.category in setOf("model_configs", "embedding_configs") &&
                        account(exact) != account(candidate)) add(Reason.ACCOUNT_IDENTITY)
                }
                val allowed = mutableSetOf(Choice.KEEP_PC, Choice.SKIP)
                if (ownedCollisions.isEmpty()) allowed += Choice.TAKE_PHONE
                if (candidate.forkable) allowed += Choice.BOTH
                conflicts += Conflict(candidate.key, candidate.label, candidate.category, reasons, allowed, peers.mapTo(hashSetOf()) { it.key })
            }
            // A PC unit may be named by several incoming conflicts. Conflicting TAKE/BOTH choices
            // are rechecked by the materializer's final dependency graph, not resolved by last write.
            val fingerprint = DesktopBackupPolicy.digest(pc.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value.signature}" }.toByteArray())
            return DesktopMergePreview(conflicts, pc, phone, identical, fingerprint)
        }

        private fun account(unit: Unit): List<JsonElement?> = unit.records.values.single().let {
            listOf(it["id"], it["baseUrl"], it["templateType"] ?: JsonPrimitive("OPENAI"), it["modelName"])
        }

        private fun units(records: Map<String, JsonObject>, media: Map<String, String>, scope: InspectionScope?): Map<String, Unit> {
            val groups = linkedMapOf<String, MutableSet<String>>()
            val settings = linkedMapOf<String, JsonObject>()
            fun add(key: String, path: String) { check(groups.getOrPut(key) { linkedSetOf() }.add(path)) }
            val sessionIds = records.filterKeys { DesktopBackupPolicy.entityType(it) == "chat_sessions" }.values.map {
                requireNotNull(it["id"]?.jsonPrimitive?.contentOrNull)
            }.toSet()
            records.forEach { (path, value) ->
                val type = DesktopBackupPolicy.entityType(path)
                if (type in setOf("moment_posts", "moment_tasks")) return@forEach // Separate original-byte dormant custody, never live merge units.
                if (type == "app_settings") {
                    val selected = scope?.let { selectedSettingFields(it.globalOptions) }
                    value.filterKeys { selected == null || it in selected }.forEach { (field, setting) ->
                        val key = "setting:$field"
                        settings[key] = JsonObject(mapOf(field to setting)); add(key, path)
                    }
                    return@forEach
                }
                val owner = when {
                    type == "chat_sessions" -> value["id"]
                    type == "vector_chunks" && value["sourceType"]?.jsonPrimitive?.content == "CHAT_MEMORY" -> value["sourceId"]
                    type in DesktopSessionInventory.TYPES && type != "vector_chunks" || type == "save_slots" -> value["sessionId"]
                    else -> null
                }?.jsonPrimitive?.contentOrNull
                if (owner != null) {
                    require(owner in sessionIds) { "MERGE_SESSION_OWNER_MISSING" }
                    add("session:$owner", path)
                } else {
                    val id = value["id"]?.jsonPrimitive?.contentOrNull ?: path
                    add("$type:$id", path)
                }
            }
            return groups.mapValues { (key, paths) ->
                val items = DesktopBackupRecords.view(paths) { path -> settings[key] ?: records.getValue(path) }
                val primary = items.entries.firstOrNull { DesktopBackupPolicy.entityType(it.key) == "chat_sessions" } ?: items.entries.first()
                val type = if (key.startsWith("setting:")) "app_settings" else DesktopBackupPolicy.entityType(primary.key)
                val value = primary.value
                val label = if (type == "app_settings") key.removePrefix("setting:") else
                    listOf("displayTitleOverride", "displayName", "name", "title").firstNotNullOfOrNull { value[it]?.jsonPrimitive?.contentOrNull }.orEmpty()
                val signature = java.security.MessageDigest.getInstance("SHA-256")
                items.keys.sorted().forEachIndexed { index, path ->
                    val record = items.getValue(path)
                    val mapped = DesktopBackupPolicy.mapResources(record) { reference, _ ->
                        if (reference.startsWith(DesktopBackupPolicy.PREFIX)) media[reference.removePrefix(DesktopBackupPolicy.PREFIX)]?.let { "resource-sha256:$it" } ?: reference else reference
                    }
                    val packageProof = if (DesktopBackupPolicy.entityType(path) == "save_slots") {
                        (record["packageRef"] as? JsonObject)?.get("fileName")?.jsonPrimitive?.contentOrNull?.let { name ->
                            require(name.matches(Regex("[A-Za-z0-9_-]{1,128}\\.cbsave"))) { "MERGE_PREVIEW_PACKAGE_NAME" }
                            "\npackage-sha256:" + requireNotNull(media["save_slot_packages/$name"]) { "MERGE_PREVIEW_PACKAGE_PROOF_MISSING" }
                        }.orEmpty()
                    } else ""
                    if (index > 0) signature.update(10.toByte())
                    signature.update(("$path:" + canonical(backupJson.parseToJsonElement(mapped.toString(Charsets.UTF_8))) + packageProof).toByteArray(Charsets.UTF_8))
                }
                val messages = items.filterKeys { DesktopBackupPolicy.entityType(it) == "chat_messages" }.values.mapTo(hashSetOf()) {
                    requireNotNull(it["id"]?.jsonPrimitive?.contentOrNull)
                }
                val ownedIds = if (type == "chat_sessions") items.mapNotNull { (path, record) ->
                    val family = DesktopBackupPolicy.entityType(path)
                    record["id"]?.jsonPrimitive?.contentOrNull?.let { family to it }
                }.toSet() else emptySet()
                Unit(key, type, label, items, signature.digest().joinToString("") { "%02x".format(it) }, messages,
                    type in setOf("chat_sessions", "character_cards", "model_configs", "embedding_configs", "format_cards", "world_books"), ownedIds)
            }
        }

        // Exact selected147 BackupSettingOptions fields; the option IDs themselves are checked by S0/source.
        private fun selectedSettingFields(options: Set<String>): Set<String> = options + buildSet {
            if ("modelConfigurationMode" in options) add("presetDefaultModelKey")
            if ("themeColor" in options) add("themeColorHistory")
        }
        private fun canonical(value: JsonElement): String = when (value) {
            is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { "${JsonPrimitive(it.key)}:${canonical(it.value)}" }
            is JsonArray -> value.joinToString(",", "[", "]", transform = ::canonical)
            else -> value.toString()
        }
    }
}
