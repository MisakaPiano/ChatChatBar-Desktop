package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.DesktopSessionInventory
import kotlinx.serialization.json.*

/** User-driven selection over immutable, credential-free inputs. Publication is a separate operation. */
internal class DesktopMergePreview private constructor(
    private val candidateConflicts: List<Conflict>,
    private val pc: Map<String, Unit>,
    private val incoming: Map<String, Unit>,
    private val identical: Set<String>,
    val pcFingerprint: String,
    private val sourceScope: InspectionScope?,
    private val incomingCatalog: Boolean,
    private val catalogConflict: Boolean,
    private val pcMedia: Map<String, String>, private val phoneMedia: Map<String, String>,
    private val computerSettings: JsonObject?, private val incomingSettings: JsonObject?,
) {
    enum class Choice { KEEP_PC, TAKE_PHONE, BOTH, SKIP }
    enum class Reason { SAME_ID_DIFFERENT_CONTENT, SAME_NAME, ACCOUNT_IDENTITY, OWNED_ID_COLLISION, CREDENTIAL_CONFLICT, TARGET_IDENTITY }
    class Conflict internal constructor(val key: String, val label: String, val category: String,
        val reasons: Set<Reason>, val choices: Set<Choice>, internal val pcKeys: Set<String>, internal val targetPcKey: String? = null) {
        override fun toString() = "MergeConflict($category,$reasons)"
        internal fun sameTargets(other: Conflict) = key == other.key && category == other.category &&
            reasons == other.reasons && choices == other.choices && pcKeys == other.pcKeys && targetPcKey == other.targetPcKey
    }
    class Unit internal constructor(val key: String, val category: String, val label: String,
        val records: Map<String, JsonObject>, internal val signature: String,
        internal val messageIds: Set<String> = emptySet(), internal val forkable: Boolean = false,
        internal val ownedIds: Set<Pair<String, String>> = emptySet(),
        internal val primaryId: String? = null, internal val cardOwner: String? = null,
        internal val draftType: String? = null, internal val draftTarget: String? = null) {
        override fun toString() = "MergeUnit($category,${records.size})"
    }
    class Alias internal constructor(val type: String, val sourceId: String, val targetId: String,
        internal val source: Unit, internal val target: Unit) {
        override fun toString() = "MergeAlias($type)"
    }
    class Selection internal constructor(val computer: List<Unit>, val phone: List<Unit>,
        val forkIncomingUnits: Set<String>, val pcFingerprint: String,
        val ignoredIncomingUnits: Set<String> = emptySet(),
        val replacedComputerUnits: Map<String, Unit> = emptyMap(),
        val incomingScope: InspectionScope? = null,
        val computerVoiceSources: List<DesktopMergeVoiceSourcePolicy.Source> = emptyList(),
        val aliases: List<Alias> = emptyList(),
        val catalogFromPhone: Boolean = false,
        val mode: DesktopBackupRestoreMode = DesktopBackupRestoreMode.MERGE_CHOICES,
        val renamedIncomingUnits: Map<String, String> = emptyMap(),
        val retainComputerCatalog: Boolean = true,
        val keepComputerMedia: Boolean = true,
        val defaultSettingFields: Set<String> = emptySet(),
        val searchSettingOverrides: JsonObject = JsonObject(emptyMap())) {
        override fun toString() = "MergeSelection(pc=${computer.size},phone=${phone.size},forks=${forkIncomingUnits.size})"
    }

    private data class CardDomain(val incomingCopy: Boolean, val id: String, val forked: Boolean = false)
    val conflicts: List<Conflict> get() = effectiveConflicts(emptyMap())
    fun modePlan(mode: DesktopBackupRestoreMode, checkActive: () -> kotlin.Unit = {}): DesktopMergeRestoreModes.Plan =
        DesktopMergeRestoreModes.plan(pc, incoming, pcFingerprint, sourceScope, incomingCatalog, mode, pcMedia, phoneMedia,
            checkActive, computerSettings, incomingSettings)

    private fun incomingDomain(type: String, id: String?, choices: Map<String, Choice>): CardDomain? {
        if (id == null) return null
        val key = "$type:$id"
        val conflict = candidateConflicts.firstOrNull { it.key == key }
            ?: return CardDomain(key !in pc, id)
        val choice = choices[key] ?: return null
        if (choice == Choice.BOTH) return CardDomain(true, id, key in pc || Reason.OWNED_ID_COLLISION in conflict.reasons)
        return conflict.targetPcKey?.let(pc::get)?.primaryId?.let { CardDomain(false, it) }
    }

    private fun draftNeedsImport(unit: Unit, choices: Map<String, Choice>): Boolean {
        if (unit.key !in identical) return true
        val domain = unit.draftTarget?.let { incomingDomain(DesktopMergeEditorDraftBindings.category(unit.draftType), it, choices) }
        return domain != null && (domain.forked || domain.id != unit.draftTarget)
    }

    private fun draftConflicts(choices: Map<String, Choice>): List<Conflict> = incoming.values.mapNotNull { unit ->
        if (unit.category != "edit_drafts" || !draftNeedsImport(unit, choices)) return@mapNotNull null
        val domain = unit.draftTarget?.let { incomingDomain(DesktopMergeEditorDraftBindings.category(unit.draftType), it, choices) }
        if (unit.draftTarget != null && domain == null || domain?.forked == true) return@mapNotNull null
        val id = DesktopMergeEditorDraftBindings.id(unit.draftType, domain?.id)
        val target = pc["edit_drafts:$id"] ?: return@mapNotNull null
        Conflict(unit.key, unit.label, unit.category,
            setOf(if (unit.primaryId == target.primaryId) Reason.SAME_ID_DIFFERENT_CONTENT else Reason.TARGET_IDENTITY),
            setOf(Choice.KEEP_PC, Choice.SKIP, Choice.TAKE_PHONE), setOf(target.key), target.key)
    }

    /** Card choices determine the named-session domain without allocating IDs or touching source data. */
    fun effectiveConflicts(choices: Map<String, Choice>): List<Conflict> {
        return candidateConflicts.mapNotNull { conflict ->
            if (conflict.category == "edit_drafts") return@mapNotNull null
            if (conflict.category != "chat_sessions") return@mapNotNull conflict
            val candidate = incoming.getValue(conflict.key)
            val exact = pc[candidate.key]
            val domain = incomingDomain("character_cards", candidate.cardOwner, choices)
            val named = if (exact != null || domain == null || domain.incomingCopy) emptyList() else conflict.pcKeys.mapNotNull(pc::get).filter {
                it.cardOwner == domain.id && normalizedLabel(it.label) == normalizedLabel(candidate.label)
            }
            val target = exact?.key ?: named.singleOrNull()?.key
            val collided = conflict.pcKeys.mapNotNull(pc::get).filter {
                it.key != candidate.key && it.key != target && it.ownedIds.any(candidate.ownedIds::contains)
            }
            val peers = (listOfNotNull(exact) + named + collided).distinctBy { it.key }
            if (peers.isEmpty()) return@mapNotNull null
            val reasons = conflict.reasons - setOf(Reason.SAME_NAME, Reason.OWNED_ID_COLLISION) + buildSet {
                if (named.isNotEmpty()) add(Reason.SAME_NAME)
                if (collided.isNotEmpty()) add(Reason.OWNED_ID_COLLISION)
            }
            val allowed = buildSet {
                add(Choice.KEEP_PC); add(Choice.SKIP)
                if (collided.isEmpty() && target != null) add(Choice.TAKE_PHONE)
                if (candidate.forkable) add(Choice.BOTH)
            }
            Conflict(conflict.key, conflict.label, conflict.category, reasons, allowed, peers.mapTo(hashSetOf()) { it.key }, target)
        } + draftConflicts(choices)
    }

    fun choose(choices: Map<String, Choice>, key: String, choice: Choice): Map<String, Choice> {
        val previous = effectiveConflicts(choices).associateBy { it.key }
        require(choice in requireNotNull(previous[key]).choices) { "MERGE_CHOICE_NOT_APPLICABLE" }
        val proposed = choices + (key to choice)
        val next = effectiveConflicts(proposed).associateBy { it.key }
        return proposed.filter { (id, selected) -> next[id]?.let { target ->
            selected in target.choices && previous[id]?.sameTargets(target) == true
        } == true }
    }

    fun select(choices: Map<String, Choice>, checkActive: () -> kotlin.Unit = {}): Selection {
        val conflicts = effectiveConflicts(choices)
        require(choices.keys == conflicts.mapTo(hashSetOf()) { it.key }) { "MERGE_EXPLICIT_CHOICES_REQUIRED" }
        val computer = pc.toMutableMap()
        val phone = incoming.filter { (key, unit) -> key !in identical || unit.category == "edit_drafts" && draftNeedsImport(unit, choices) }.toMutableMap()
        val forks = hashSetOf<String>()
        val replaced = linkedMapOf<String, Unit>()
        val aliases = mutableListOf<Alias>()
        conflicts.forEach { conflict ->
            checkActive()
            val choice = choices.getValue(conflict.key)
            require(choice in conflict.choices) { "MERGE_CHOICE_NOT_APPLICABLE" }
            if (conflict.key == CATALOG_KEY) return@forEach
            val candidate = incoming.getValue(conflict.key)
            val target = conflict.targetPcKey?.let(pc::get)
            if (choice != Choice.BOTH && target != null && candidate.primaryId != null && target.primaryId != null)
                aliases += Alias(candidate.category, candidate.primaryId, target.primaryId, candidate, target)
            when (choice) {
                Choice.KEEP_PC, Choice.SKIP -> phone.remove(conflict.key)
                Choice.TAKE_PHONE -> {
                    val targetKey = requireNotNull(conflict.targetPcKey) { "MERGE_REPLACEMENT_TARGET_AMBIGUOUS" }
                    replaced[conflict.key] = pc.getValue(targetKey)
                    computer.remove(targetKey)
                }
                Choice.BOTH -> {
                    // Same-name independent identities stay intact. Any colliding identity needs
                    // a complete dependency remapper, never a filename-only rename.
                    if (conflict.key in pc || Reason.OWNED_ID_COLLISION in conflict.reasons) forks += conflict.key
                }
            }
        }
        for (conflict in conflicts.filter { it.category == "character_cards" && choices[it.key] in setOf(Choice.KEEP_PC, Choice.SKIP) }) {
            val ignored = incoming.getValue(conflict.key)
            if (conflict.targetPcKey == null && "character_cards:${ignored.primaryId}" !in pc)
                require(phone.values.none { it.category == "chat_sessions" && it.cardOwner == ignored.primaryId }) {
                    "MERGE_SKIPPED_CARD_HAS_SELECTED_CONVERSATIONS"
                }
        }
        val aliasIdentity = DesktopMergeIdentity().also { identity -> aliases.filter { it.sourceId != it.targetId }
            .forEach { identity.bind(it.type, it.sourceId, it.targetId) } }
        val voiceSources = mutableListOf<DesktopMergeVoiceSourcePolicy.Source>()
        for ((key, original) in replaced) {
            checkActive()
            val candidate = phone.getValue(key)
            val retained = DesktopMergeRetainedMedia(sourceScope, original, aliasIdentity, checkActive).retainedSessionRecords(candidate)
            if (retained.records.isNotEmpty()) computer["retained:$key"] = Unit("retained:$key", original.category,
                original.label, retained.records, original.signature)
            voiceSources += retained.voiceSources
        }
        return Selection(computer.values.toList(), phone.values.toList(), forks, pcFingerprint,
            choices.filterValues { it == Choice.KEEP_PC || it == Choice.SKIP }.keys.toSet(), replaced, sourceScope, voiceSources, aliases,
            incomingCatalog && (!catalogConflict || choices[CATALOG_KEY] == Choice.TAKE_PHONE))
    }

    companion object {
        const val CATALOG_KEY = "tagCatalog:installed"
        private fun normalizedLabel(value: String) = java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT)
        /** Scope governs settings projection and preservation of explicitly unselected media. */
        fun create(pcEntities: Map<String, JsonObject>, phoneEntities: Map<String, JsonObject>,
            pcMedia: Map<String, String>, phoneMedia: Map<String, String>, scope: InspectionScope? = null,
            credentialConflicts: Set<String> = emptySet()): DesktopMergePreview {
            val groupedStudio = (pcEntities.keys + phoneEntities.keys).any { DesktopBackupPolicy.entityType(it) == "desktop_studio_edit_history" }
            val pc = units(pcEntities, pcMedia, null, localMoments = true, groupedStudio = groupedStudio)
            val phone = units(phoneEntities, phoneMedia, scope, groupedStudio = groupedStudio)
            val identical = hashSetOf<String>()
            val conflicts = mutableListOf<Conflict>()
            phone.values.forEach { candidate ->
                val exact = pc[candidate.key]
                if (exact != null && exact.signature == candidate.signature && candidate.key !in credentialConflicts) { identical += candidate.key; return@forEach }
                // A Moment sender is not a unique post name. Different posts by the same person coexist.
                val normalizedName = normalizedLabel(candidate.label)
                val sameName = if (exact != null || normalizedName.isEmpty() || candidate.category == "moment_posts") emptyList() else pc.values.filter {
                    it.category == candidate.category &&
                        normalizedLabel(it.label) == normalizedName &&
                        it.key != candidate.key
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
                val target = exact?.key ?: peers.singleOrNull()?.key
                if (ownedCollisions.isEmpty() && target != null) allowed += Choice.TAKE_PHONE
                if (candidate.forkable) allowed += Choice.BOTH
                conflicts += Conflict(candidate.key, candidate.label, candidate.category, reasons, allowed, peers.mapTo(hashSetOf()) { it.key }, target)
            }
            // A PC unit may be named by several incoming conflicts. Conflicting TAKE/BOTH choices
            // are rechecked by the materializer's final dependency graph, not resolved by last write.
            val fingerprint = DesktopBackupPolicy.digest(pc.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value.signature}" }.toByteArray())
            val paths = com.example.chatbar.desktop.DesktopNovelAiCatalogTransfer.wirePaths
            val localCatalog = pcMedia.filterKeys { it in paths }
            val importedCatalog = phoneMedia.filterKeys { it in paths }
            require(localCatalog.isEmpty() || localCatalog.keys == paths) { "MERGE_CATALOG_PAIR_REQUIRED" }
            require(importedCatalog.isEmpty() || importedCatalog.keys == paths) { "MERGE_CATALOG_PAIR_REQUIRED" }
            val hasIncoming = importedCatalog.isNotEmpty() && scope?.globalOptions?.contains("tagCatalog") != false
            val needsChoice = hasIncoming && localCatalog.isNotEmpty() && localCatalog != importedCatalog
            if (needsChoice) conflicts += Conflict(CATALOG_KEY, "已安装标签库", "tagCatalog",
                setOf(Reason.SAME_ID_DIFFERENT_CONTENT), setOf(Choice.KEEP_PC, Choice.TAKE_PHONE, Choice.SKIP), emptySet())
            fun settings(records: Map<String, JsonObject>): JsonObject? {
                val values = records.entries.filter { DesktopBackupPolicy.entityType(it.key) == "app_settings" }
                require(values.size <= 1) { "MERGE_SETTINGS_AUTHORITY_AMBIGUOUS" }
                return values.singleOrNull()?.value
            }
            return DesktopMergePreview(conflicts, pc, phone, identical, fingerprint, scope, hasIncoming, needsChoice,
                pcMedia.toMap(), phoneMedia.toMap(), settings(pcEntities), settings(phoneEntities))
        }

        private fun account(unit: Unit): List<JsonElement?> = unit.records.values.single().let {
            listOf(it["id"], it["baseUrl"], it["templateType"] ?: JsonPrimitive("OPENAI"), it["modelName"])
        }

        private fun units(records: Map<String, JsonObject>, media: Map<String, String>, scope: InspectionScope?, localMoments: Boolean = false,
            groupedStudio: Boolean = false): Map<String, Unit> {
            val groups = linkedMapOf<String, MutableSet<String>>()
            val settings = linkedMapOf<String, JsonObject>()
            fun add(key: String, path: String) { check(groups.getOrPut(key) { linkedSetOf() }.add(path)) }
            val sessionIds = records.filterKeys { DesktopBackupPolicy.entityType(it) == "chat_sessions" }.values.map {
                requireNotNull(it["id"]?.jsonPrimitive?.contentOrNull)
            }.toSet()
            val cardIds = records.filterKeys { DesktopBackupPolicy.entityType(it) == "character_cards" }.values.map {
                requireNotNull(it["id"]?.jsonPrimitive?.contentOrNull)
            }.toSet()
            records.forEach { (path, value) ->
                val type = DesktopBackupPolicy.entityType(path)
                if (type == "vector_chunks" && value["sourceType"]?.jsonPrimitive?.contentOrNull in setOf("DOCUMENT", "CHARACTER_SETTING")) {
                    val card = value["sourceId"]?.jsonPrimitive?.contentOrNull
                    if (card in cardIds) {
                        add("character_cards:$card", path)
                        return@forEach
                    }
                }
                if (groupedStudio && type in setOf("novelai_studio_draft", "novelai_studio_history_undo",
                    "novelai_studio_guidance_checkpoint", "desktop_studio_edit_history")) {
                    add("novelai_studio_draft:entities/novelai_studio_draft.json", path)
                    return@forEach
                }
                // Official incoming Moments retain original-byte dormant custody. Explicit Desktop image
                // records can be selected as live posts; imported tasks never inherit scheduler admission.
                if (type == "moment_tasks" && !localMoments || type == "moment_posts" && !localMoments &&
                    DesktopMomentBackupExtension.FIELD !in value) return@forEach
                if (type == "app_settings") {
                    val selected = scope?.let { selectedSettingFields(it.globalOptions) + buildSet {
                        if (it.sessions > 0) add("lastSeenChatAt")
                        if (it.moments) add("lastSeenMomentsAt")
                    } }
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
                val primary = items.entries.firstOrNull { DesktopBackupPolicy.entityType(it.key) in setOf("chat_sessions", "character_cards") ||
                    groupedStudio && key.startsWith("novelai_studio_draft:") && DesktopBackupPolicy.entityType(it.key) == "novelai_studio_draft" } ?: items.entries.first()
                val type = if (key.startsWith("setting:")) "app_settings" else DesktopBackupPolicy.entityType(primary.key)
                val value = primary.value
                val label = if (type == "edit_drafts") "编辑草稿 · " + listOf("characterPayload", "formatPayload", "worldBookPayload").firstNotNullOfOrNull {
                    (value[it] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                }.orEmpty() else if (groupedStudio && key.startsWith("novelai_studio_draft:")) "Studio 草稿与编辑历史" else if (type == "app_settings") key.removePrefix("setting:") else
                    value["displayTitleOverride"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                        ?: listOf("title", "name", "displayName", "senderName").firstNotNullOfOrNull { value[it]?.jsonPrimitive?.contentOrNull }.orEmpty()
                val signature = java.security.MessageDigest.getInstance("SHA-256")
                items.keys.sorted().forEachIndexed { index, path ->
                    val record = items.getValue(path)
                    val mapped = DesktopBackupPolicy.mapResources(record) { reference, fields ->
                        if (reference.startsWith(DesktopBackupPolicy.PREFIX)) {
                            val relative = reference.removePrefix(DesktopBackupPolicy.PREFIX)
                            media[relative]?.let { "resource-sha256:$it" } ?: if (relative.startsWith("images/") &&
                                DesktopHistoricalMomentAvatarPolicy.allows(path, fields, CURRENT_DESKTOP_BACKUP_CODE))
                                DesktopBackupPolicy.PREFIX + DesktopHistoricalMomentAvatarPolicy.portableMissingIdentity(relative) else reference
                        } else reference
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
                    type in setOf("chat_sessions", "character_cards", "model_configs", "embedding_configs", "format_cards", "world_books", "moment_posts"), ownedIds,
                    value["id"]?.jsonPrimitive?.contentOrNull,
                    if (type == "chat_sessions") value["characterCardId"]?.jsonPrimitive?.contentOrNull else null,
                    if (type == "edit_drafts") value["entityType"]?.jsonPrimitive?.contentOrNull else null,
                    if (type == "edit_drafts") value["targetId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) else null)
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
