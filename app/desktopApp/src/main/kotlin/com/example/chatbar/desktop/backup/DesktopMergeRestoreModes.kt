package com.example.chatbar.desktop.backup

import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.json.*

internal enum class DesktopBackupRestoreMode { MERGE_CHOICES, UPDATE_EXISTING, ADD_COPIES, REPLACE_SELECTED }

/** Plans over authenticated immutable units. Never clears a live root or changes the default merge. */
internal object DesktopMergeRestoreModes {
    class Plan(val selection: DesktopMergePreview.Selection, val added: Int, val updated: Int, val copied: Int,
        val removed: Int, val warnings: List<String>)
    private val named = setOf("character_cards", "chat_sessions", "format_cards", "world_books", "model_configs",
        "retrieval_model_config", "embedding_configs", "embedding_model_config")
    private val dependencyOptions = mapOf("model_configs" to "models", "retrieval_model_config" to "models",
        "embedding_configs" to "embedding", "embedding_model_config" to "embedding", "format_cards" to "resources", "world_books" to "resources")
    private fun normalize(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    private fun copyName(value: String, names: MutableSet<String>): String {
        val base = value.trim().ifBlank { "未命名" }.take(80)
        var number = 1
        while (true) {
            val next = base + if (number == 1) "（副本）" else "（副本 $number）"
            if (names.add(normalize(next))) return next
            number++
        }
    }

    fun plan(pc: Map<String, DesktopMergePreview.Unit>, incoming: Map<String, DesktopMergePreview.Unit>, fingerprint: String,
        scope: InspectionScope?, incomingCatalog: Boolean, mode: DesktopBackupRestoreMode,
        pcMedia: Map<String, String>, phoneMedia: Map<String, String>, active: () -> Unit): Plan {
        require(mode != DesktopBackupRestoreMode.MERGE_CHOICES)
        val replace = mode == DesktopBackupRestoreMode.REPLACE_SELECTED
        val copies = mode == DesktopBackupRestoreMode.ADD_COPIES
        val computer = if (replace && scope == null) linkedMapOf() else pc.toMutableMap()
        val phone = incoming.toMutableMap()
        val aliases = mutableListOf<DesktopMergePreview.Alias>()
        val replaced = linkedMapOf<String, DesktopMergePreview.Unit>()
        val forks = linkedSetOf<String>(); val renames = linkedMapOf<String, String>()
        val warnings = linkedSetOf<String>(); val claimed = hashSetOf<String>()
        val pcByCategory = pc.values.groupBy { it.category }
        val pcIds = pc.values.mapTo(hashSetOf()) { it.category to it.primaryId }
        val incomingIds = incoming.values.mapTo(hashSetOf()) { it.category to it.primaryId }
        var added = 0; var updated = 0; var copied = 0
        fun card(id: String?) = aliases.firstOrNull { it.type == "character_cards" && it.sourceId == id }?.targetId ?: id
        fun domain(unit: DesktopMergePreview.Unit, incomingSide: Boolean): String = unit.category + ":" +
            (if (unit.category == "chat_sessions") if (incomingSide) card(unit.cardOwner) else unit.cardOwner else "")
        val usedNames = linkedMapOf<String, MutableSet<String>>()
        pc.values.forEach { usedNames.getOrPut(domain(it, false)) { hashSetOf() }.add(normalize(it.label)) }
        val pcNames = pc.values.groupBy { domain(it, false) to normalize(it.label) }
        val sourceNames = hashMapOf<String, Map<Pair<String, String>, Int>>()
        val order = incoming.values.sortedBy { when {
            it.category == "character_cards" -> 0
            it.category == "chat_sessions" -> 2
            it.category == "edit_drafts" -> 3
            it.category in named -> 1
            else -> 4
        } }
        fun canonical(value: JsonElement): String = when (value) {
            is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { "${JsonPrimitive(it.key)}:${canonical(it.value)}" }
            is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
            else -> value.toString()
        }
        fun content(unit: DesktopMergePreview.Unit, imported: Boolean): String {
            val entry = unit.records.entries.single()
            val record = DesktopMergeRecord(entry.key, entry.value)
            val ids = DesktopMergeIdentity().also { identity -> if (imported) aliases.filter { it.sourceId != it.targetId }
                .forEach { identity.bind(it.type, it.sourceId, it.targetId) } }
            val media = if (imported) phoneMedia else pcMedia
            val value = DesktopMergeGraphRemapper(listOf(record), ids, active).rewrite(record) { reference, _ ->
                val path = when {
                    reference.startsWith(DesktopBackupPolicy.PREFIX) -> reference.removePrefix(DesktopBackupPolicy.PREFIX)
                    reference.startsWith("chatbar-vibe-file:") -> "novelai-vibe-payloads/" + reference.substringAfter(':')
                    else -> null
                }
                path?.let { media[it] }?.let { "sha256:$it" } ?: reference
            }
            return DesktopBackupPolicy.digest(canonical(JsonObject(value - setOf("id", "updatedAt"))).toByteArray())
        }
        val contentMatches = hashMapOf<String, Map<String, List<DesktopMergePreview.Unit>>>()
        val fingerprintTypes = setOf("moment_posts", "moment_tasks", "novelai_generation_history", "desktop_image_post_process_results")
        fun matchingContent(candidate: DesktopMergePreview.Unit): List<DesktopMergePreview.Unit> =
            contentMatches.getOrPut(candidate.category) { pcByCategory[candidate.category].orEmpty()
                .filter { it.records.size == 1 }.groupBy { content(it, false) } }[content(candidate, true)].orEmpty()
        for (candidate in order) {
            active()
            val primary = candidate.records.entries.firstOrNull { DesktopBackupPolicy.entityType(it.key) == candidate.category }
            val record = primary?.let { DesktopMergeRecord(it.key, it.value) }
            val singleton = candidate.category == "app_settings" || record?.singleton == true ||
                candidate.category in setOf("retrieval_model_config", "embedding_model_config")
            val copiedDraftOwner = copies && candidate.category == "edit_drafts" && candidate.draftTarget != null &&
                (DesktopMergeEditorDraftBindings.category(candidate.draftType) to candidate.draftTarget) in incomingIds
            val legacyReplacement = replace && scope == null
            var target = if (copies && !singleton || copiedDraftOwner || legacyReplacement) null else pc[candidate.key]
            if (candidate.category == "chat_sessions" && target != null && domain(candidate, true) != domain(target, false)) target = null
            if (candidate.category == "edit_drafts" && !copies && !legacyReplacement) {
                val owner = candidate.draftTarget?.let { id -> aliases.firstOrNull {
                    it.type == DesktopMergeEditorDraftBindings.category(candidate.draftType) && it.sourceId == id
                }?.targetId ?: id }
                target = pc["edit_drafts:${DesktopMergeEditorDraftBindings.id(candidate.draftType, owner)}"]
            }
            if (target == null && singleton && !copiedDraftOwner && !legacyReplacement && candidate.category != "app_settings")
                target = pcByCategory[candidate.category]?.singleOrNull()
            var ambiguous = false
            if (target == null && !copies && !replace && candidate.category in named && normalize(candidate.label).isNotEmpty()) {
                val nameKey = domain(candidate, true) to normalize(candidate.label)
                val matches = pcNames[nameKey].orEmpty()
                val sources = sourceNames.getOrPut(candidate.category) { order.filter { it.category == candidate.category }
                    .groupingBy { domain(it, true) to normalize(it.label) }.eachCount() }[nameKey] ?: 0
                ambiguous = matches.size > 1 || matches.isNotEmpty() && sources > 1
                if (!ambiguous) target = matches.singleOrNull()
            }
            if (target == null && !copies && !replace && candidate.category in fingerprintTypes &&
                candidate.records.size == 1) {
                val matches = matchingContent(candidate)
                ambiguous = matches.size > 1
                if (!ambiguous) target = matches.singleOrNull()
            }
            if (target != null && !claimed.add(target.key)) { target = null; ambiguous = true }
            val option = dependencyOptions[candidate.category]
            if (replace && scope != null && option != null && option !in scope.globalOptions && target != null) {
                phone.remove(candidate.key)
                warnings += "保留未勾选的本机依赖：${target.label.take(100)}"
                continue
            }
            if (target != null) {
                computer.remove(target.key); replaced[candidate.key] = target; updated++
                if (!copies && candidate.primaryId != null && target.primaryId != null && candidate.category == target.category && candidate.category != "edit_drafts")
                    aliases += DesktopMergePreview.Alias(candidate.category, candidate.primaryId, target.primaryId, candidate, target)
            } else {
                val canCopy = candidate.primaryId != null && !singleton && candidate.category != "edit_drafts"
                val colliding = (candidate.category to candidate.primaryId) in pcIds
                if (canCopy && (copies || ambiguous || colliding)) forks += candidate.key
                val names = usedNames.getOrPut(domain(candidate, true)) { hashSetOf() }
                val duplicateName = candidate.category in named && normalize(candidate.label) in names
                val duplicateContent = copies && !colliding && candidate.category in fingerprintTypes && candidate.records.size == 1 &&
                    pcByCategory[candidate.category].orEmpty().isNotEmpty() && matchingContent(candidate).isNotEmpty()
                if (canCopy && (ambiguous || copies && (colliding || duplicateName || duplicateContent))) copied++ else added++
                if (candidate.category in named && !replace &&
                    (ambiguous || normalize(candidate.label) in names || copies && colliding))
                    renames[candidate.key] = copyName(candidate.label, names)
                else names += normalize(candidate.label)
                if (ambiguous) warnings += "重名目标不唯一，保留原内容并新增副本：${candidate.label.take(100)}"
            }
        }
        // Modern restore modes replace the selected Studio settings family, even when empty.
        // Ordinary MERGE_CHOICES keeps its separate explicit per-unit decisions.
        if (scope?.studioSettings != false) computer.entries.removeAll { (_, unit) ->
            DesktopBackupPolicy.isStudioSettings(unit.category)
        }
        val defaultFields = if (replace && scope != null) BackupSettingOptions.fields(scope.globalOptions) -
            incoming.values.filter { it.category == "app_settings" }.map { it.label }.toSet() else emptySet()
        // Selected omitted setting fields become defaults before deciding what unselected content still needs.
        computer.entries.removeAll { (key, unit) -> unit.category == "app_settings" && key.removePrefix("setting:") in defaultFields }
        if (replace && scope != null) {
            val selectedTypes = buildSet {
                if (scope.moments) addAll(listOf("moment_posts", "moment_tasks"))
                if (scope.studioHistory) addAll(listOf("novelai_generation_history", "novelai_history_fold_preferences", "desktop_image_post_process_results"))
                dependencyOptions.filterValues { it in scope.globalOptions }.keys.forEach(::add)
                if ("editorDrafts" in scope.globalOptions) add("edit_drafts")
                if ("player" in scope.globalOptions) add("player_setting")
            }
            val removable = computer.values.filter { it.category in selectedTypes }.associateBy { it.key }.toMutableMap()
            val required = hashSetOf<Pair<String, String>>()
            val identity = DesktopMergeIdentity()
            fun references(unit: DesktopMergePreview.Unit) {
                for ((path, value) in unit.records) {
                    val record = DesktopMergeRecord(path, value)
                    fun walk(item: JsonElement, fields: List<String>) {
                        active(); require(fields.size <= 128)
                        if (identity.opaque(fields)) return
                        when (item) {
                            is JsonObject -> item.forEach { (key, child) -> walk(child, fields + key) }
                            is JsonArray -> item.forEach { walk(it, fields) }
                            is JsonPrimitive -> if (item.isString) identity.fieldSpace(record, fields)?.let { type ->
                                if (type in dependencyOptions && fields.lastOrNull() != "id") required += type to item.content
                            }
                        }
                    }
                    walk(value, emptyList())
                }
            }
            computer.values.filter { it.key !in removable }.forEach(::references)
            var changed: Boolean
            do {
                changed = false
                for (unit in removable.values.toList()) {
                    val omittedOriginal = (unit.category == "moment_posts" && !scope.momentsImages ||
                        unit.category in setOf("novelai_generation_history", "desktop_image_post_process_results") && !scope.studioHistoryImages) &&
                        (unit.category to unit.primaryId) !in incomingIds &&
                        unit.records.values.any { value -> listOf("imagePath", "images").any { field -> value[field]?.let { it != JsonNull && it != JsonArray(emptyList()) && it != JsonPrimitive("") } == true } }
                    if (unit.primaryId?.let { (unit.category to it) in required } == true || omittedOriginal) {
                        removable.remove(unit.key); references(unit); changed = true
                        warnings += "保留未选内容仍需的依赖或原图记录：${unit.label.take(100)}"
                    }
                }
            } while (changed)
            removable.keys.forEach(computer::remove)
        }
        val voiceSources = mutableListOf<DesktopMergeVoiceSourcePolicy.Source>()
        if (replace && scope == null) for (unit in phone.values) {
            if (unit.category in setOf("preset_import_state", "model_configuration_migration")) pc[unit.key]?.let { replaced[unit.key] = it }
        }
        val aliasIdentity = DesktopMergeIdentity().also { ids -> aliases.filter { it.sourceId != it.targetId }.forEach { ids.bind(it.type, it.sourceId, it.targetId) } }
        for ((key, original) in replaced) {
            val retained = DesktopMergeRetainedMedia(scope, original, aliasIdentity, active).retainedSessionRecords(phone.getValue(key))
            if (retained.records.isNotEmpty()) computer["retained:$key"] = DesktopMergePreview.Unit("retained:$key", original.category, original.label, retained.records, original.signature)
            voiceSources += retained.voiceSources
        }
        val selectedTag = scope?.globalOptions?.contains("tagCatalog") ?: true
        val retainCatalog = !(incomingCatalog || replace && selectedTag)
        val selection = DesktopMergePreview.Selection(computer.values.toList(), phone.values.toList(), forks, fingerprint,
            incoming.keys - phone.keys, replaced, scope, voiceSources, aliases, incomingCatalog,
            mode, renames, retainCatalog, !(replace && scope == null), defaultFields)
        return Plan(selection, added, updated, copied, (pc.keys - computer.keys - replaced.values.map { it.key }.toSet()).size, warnings.toList())
    }
}
