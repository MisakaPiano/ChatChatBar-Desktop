package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import kotlinx.serialization.json.*

sealed interface LanSyncGroupResolutionSource {
    object Local : LanSyncGroupResolutionSource
    data class Version(val reference: SyncVersionRef) : LanSyncGroupResolutionSource
}

sealed interface LanSyncGroupResolutionChoice {
    object Later : LanSyncGroupResolutionChoice
    /** Equal copy names across related units form one copied graph, not independent ID namespaces. */
    data class Keep(val primary: LanSyncGroupResolutionSource,
        val copies: Map<String, LanSyncGroupResolutionSource> = emptyMap()) : LanSyncGroupResolutionChoice
}

data class LanSyncGroupResolvedIdentity(val branch: String, val type: String, val source: String, val target: String)
data class LanSyncGroupResolvedMedia(val branch: String, val kind: String, val source: String, val hash: String, val target: String)
/** Borrowed verified paths, not owned files; keep the capture open or reacquire and verify before use. */
data class LanSyncGroupResolutionResource(val identity: SyncPlaintextIdentity, val paths: Set<Path>)
data class LanSyncGroupResolutionUnitReview(val unit: String, val binding: String, val current: List<LanSyncRecord>,
    val versions: Map<SyncVersionRef, List<LanSyncRecord>>, val proposed: List<LanSyncRecord>,
    val localImpact: List<LanSyncRecordImpact>, val versionImpact: Map<SyncVersionRef, List<LanSyncRecordImpact>>,
    val restorations: Set<LanSyncGroupRecordRemoval>, val needsConfirmation: Boolean)

/** Acknowledged candidate only: it is neither SourceAuthorization nor permission to apply/sign. */
class LanSyncGroupResolvedCandidate internal constructor(val preview: LanSyncGroupResolutionPreview)

/**
 * Frozen, credential-free candidate. Native admission must still recheck freshness, verify and seal
 * every resource/preimage, persist the operation, and author a new revision with exact unit previous.
 * This object never advances native receipts, writes files, allocates author counters or signs.
 */
class LanSyncGroupResolutionPreview internal constructor(val operation: String, val binding: String,
    val checkpoint: SyncVersionRef, val heads: Set<SyncVersionRef>, val expected: Map<String, String>,
    val sourceFiles: Map<String, String>, val appliedBefore: Map<String, LanSyncGroupAppliedUnit>,
    val target: LanSyncSnapshot, val resolvedUnits: Set<String>, val heldUnits: Set<String>,
    val previous: Map<String, Set<SyncVersionRef>>, val resourceSources: Map<String, LanSyncGroupResolutionResource>,
    val identities: List<LanSyncGroupResolvedIdentity>, val media: List<LanSyncGroupResolvedMedia>,
    val forkParents: Map<String, String>, val choices: Map<String, LanSyncGroupResolutionChoice>,
    val units: Map<String, LanSyncGroupResolutionUnitReview>, private val before: LanSyncSnapshot) {
    val requiredBindings: Map<String, String> get() = resolutionMap(units.filterValues { it.needsConfirmation }.mapValues { it.value.binding })
    val requiredRestorations: Set<LanSyncGroupRecordRemoval> get() = resolutionSet(units.values.flatMap { it.restorations })

    fun acknowledge(binding: String, confirmations: Map<String, String> = emptyMap(),
        restorations: Set<LanSyncGroupRecordRemoval> = emptySet()): LanSyncGroupResolvedCandidate {
        require(binding == this.binding && confirmations == requiredBindings) { "SYNC_GROUP_RESOLUTION_CONFIRMATION" }
        require(restorations == requiredRestorations) { "SYNC_GROUP_RESOLUTION_RESTORATION_ACK" }
        return LanSyncGroupResolvedCandidate(this)
    }

    /** Run again inside the actual native-data gate; resource bytes still need fresh verification. */
    fun requireFresh(current: LanSyncCapture, reconciled: SyncInboxReconciliation,
        applied: Map<String, LanSyncGroupAppliedUnit>) {
        require(!reconciled.trustBlocked && reconciled.ledgerCheckpoint == checkpoint && reconciled.history?.heads() == heads &&
            current.sourceFiles == sourceFiles && current.snapshot == before && applied == appliedBefore) { "SYNC_GROUP_RESOLUTION_STALE" }
    }
}

/** Read-only planning over an admitted fixed view; hashes resource handles again without file mutation. */
object LanSyncGroupResolution {
    private data class Body(val source: LanSyncGroupResolutionSource, val author: String,
        val records: List<LanSyncRecord>, val resources: Map<String, Long>, val paths: Map<String, Path>)

    fun prepare(native: LanSyncGroupNativePlan, current: LanSyncCapture, reconciled: SyncInboxReconciliation,
        dependencies: Map<LanSyncGroupMembershipRequest, LanSyncGroupDependencyResult>,
        evidence: LanSyncGroupRecordEvidence, choices: Map<String, LanSyncGroupResolutionChoice>, operation: String): LanSyncGroupResolutionPreview {
        require(operation.matches(Regex("[a-f0-9-]{36}")) && UUID.fromString(operation).toString() == operation) { "SYNC_GROUP_RESOLUTION_OPERATION" }
        require(!reconciled.trustBlocked && reconciled.ledgerCheckpoint == native.checkpoint && reconciled.history?.heads() == native.heads) { "SYNC_GROUP_RESOLUTION_VIEW" }
        require(current.snapshot == native.before && current.sourceFiles == native.sourceFiles && current.snapshot.hashes() == native.expected) { "SYNC_GROUP_RESOLUTION_SOURCE" }
        val before = freezeSnapshot(current.snapshot); before.validate(); LanSyncScope.validateGraph(before)
        val history = requireNotNull(reconciled.history).snapshot(); evidence.requireView(history, native.checkpoint)
        val facts = history.admittedVersions().associateBy { it.reference() }; val local = before.units()
        val verifiedResources = hashSetOf<Pair<Path, SyncPlaintextIdentity>>()
        fun verifyResources(manifest: Map<String, Long>, paths: Map<String, Path>) {
            require(manifest.keys == paths.keys) { "SYNC_GROUP_RESOLUTION_RESOURCE_MISSING" }
            manifest.forEach { (hash, length) ->
                val path = paths.getValue(hash); val identity = SyncPlaintextIdentity(length, hash)
                if (verifiedResources.add(path to identity)) {
                    require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "SYNC_GROUP_RESOLUTION_RESOURCE_MISSING" }
                    require(LanSyncResourceStore.hashFile(path, length) == hash) { "SYNC_GROUP_RESOLUTION_RESOURCE_CHANGED" }
                }
            }
        }
        val frozenChoices = resolutionMap(choices.toSortedMap().mapValues { (_, choice) -> when (choice) {
            LanSyncGroupResolutionChoice.Later -> choice
            is LanSyncGroupResolutionChoice.Keep -> choice.copy(copies = resolutionMap(choice.copies.toSortedMap()))
        } })
        require(frozenChoices.keys.all { it in native.units }) { "SYNC_GROUP_RESOLUTION_SELECTION" }
        val selected = frozenChoices.filterValues { it is LanSyncGroupResolutionChoice.Keep }.keys
        selected.forEach { unit ->
            val row = native.units.getValue(unit)
            require(row.stage != LanSyncGroupNativeStage.AUTHOR_FORK && row.frontiers.none { facts.getValue(it.version).author in history.authorForks() }) { "SYNC_GROUP_RESOLUTION_AUTHOR_FORK" }
            require(row.frontiers == history.frontier(unit) && row.currentHash == native.expected[unit]) { "SYNC_GROUP_RESOLUTION_VIEW" }
            require(native.components.getValue(unit).all { related -> related in selected || native.units.getValue(related).stage == LanSyncGroupNativeStage.UNCHANGED }) { "SYNC_GROUP_RESOLUTION_COMPONENT" }
        }
        val allBodies = linkedMapOf<String, Map<SyncVersionRef, Body>>()
        fun remote(unit: String, reference: SyncVersionRef): Body {
            val fact = requireNotNull(facts[reference]) { "SYNC_GROUP_RESOLUTION_VERSION" }
            val change = requireNotNull(fact.changes[unit]) { "SYNC_GROUP_RESOLUTION_VERSION" }
            require(fact.author !in history.authorForks()) { "SYNC_GROUP_RESOLUTION_AUTHOR_FORK" }
            if (change.content == null) return Body(LanSyncGroupResolutionSource.Version(reference), fact.author, emptyList(), emptyMap(), emptyMap())
            val result = dependencies[LanSyncGroupMembershipRequest(reference, unit)]
            val available = result?.available
            require(result?.stage == LanSyncGroupDependencyStage.UNIT_CONTENT_AVAILABLE && available != null) { "SYNC_GROUP_RESOLUTION_BODY_MISSING" }
            val decoded = available.decoded; val payload = decoded.payload
            require(available.version == reference && available.checkpoint == native.checkpoint && payload.unit == unit &&
                payload.coveredTypes == before.coveredTypes && decoded.plaintext == change.content && decoded.valueHash == change.valueHash &&
                (change.membership == null || change.membership == decoded.membership)) { "SYNC_GROUP_RESOLUTION_BODY_CHANGED" }
            val snapshot = freezeSnapshot(before.copy(records = payload.records, resources = payload.resources))
            snapshot.validate(); LanSyncScope.validateResourceReferences(snapshot)
            require(snapshot.units().keys == setOf(unit) && snapshot.hashes()[unit] == change.valueHash &&
                snapshot.records.all { it.body == LanSyncScope.normalize(it.type, it.body) }) { "SYNC_GROUP_RESOLUTION_BODY_CHANGED" }
            snapshot.records.forEach(LanSyncScope::validateRecord)
            verifyResources(snapshot.resources, available.resources)
            require(evidence.checkCommitted(reference, unit).missing.isEmpty()) { "SYNC_GROUP_RESOLUTION_MEMBERSHIP_MISSING" }
            return Body(LanSyncGroupResolutionSource.Version(reference), fact.author, snapshot.records, snapshot.resources, resolutionMap(available.resources))
        }
        selected.sorted().forEach { unit ->
            val row = native.units.getValue(unit)
            val references = row.applied?.frontiers.orEmpty() + row.frontiers.map { it.version }
            allBodies[unit] = references.sortedBy { it.id.value }.associateWith { remote(unit, it) }
        }
        fun body(unit: String, source: LanSyncGroupResolutionSource): Body = when (source) {
            LanSyncGroupResolutionSource.Local -> {
                val rows = local[unit].orEmpty(); val hashes = LanSyncScope.referencedResources(rows)
                Body(source, before.deviceId, rows, before.resources.filterKeys(hashes::contains), current.blobs.filterKeys(hashes::contains))
            }
            is LanSyncGroupResolutionSource.Version -> {
                require(native.units.getValue(unit).frontiers.any { it.version == source.reference }) { "SYNC_GROUP_RESOLUTION_NOT_A_FRONTIER" }
                requireNotNull(allBodies.getValue(unit)[source.reference]) { "SYNC_GROUP_RESOLUTION_BODY_MISSING" }
            }
        }
        val primary = linkedMapOf<String, Body>(); val branches = sortedMapOf<String, MutableMap<String, Body>>()
        selected.sorted().forEach { unit ->
            val choice = frozenChoices.getValue(unit) as LanSyncGroupResolutionChoice.Keep
            require(choice.copies.keys.all { it.matches(Regex("[a-zA-Z0-9_-]{1,80}")) }) { "SYNC_GROUP_RESOLUTION_BRANCH" }
            require((choice.copies.values + choice.primary).distinct().size == choice.copies.size + 1) { "SYNC_GROUP_RESOLUTION_DUPLICATE_VARIANT" }
            primary[unit] = body(unit, choice.primary)
            choice.copies.forEach { (branch, source) ->
                val copy = body(unit, source)
                require(copy.records.isNotEmpty() && (copy.records + primary.getValue(unit).records).none { it.singleton }) { "SYNC_GROUP_RESOLUTION_SINGLETON_OR_EMPTY_COPY" }
                branches.getOrPut(branch) { linkedMapOf() }[unit] = copy
            }
        }

        val resources = linkedMapOf<String, Long>(); val paths = linkedMapOf<String, MutableSet<Path>>()
        fun retain(body: Body) {
            verifyResources(body.resources, body.paths)
            body.resources.forEach { (hash, length) ->
                require(resources[hash]?.let { it == length } != false) { "SYNC_RESOURCE_MANIFEST" }
                resources[hash] = length; paths.getOrPut(hash) { linkedSetOf() }.add(body.paths.getValue(hash))
            }
        }
        val remainder = local.filterKeys { it !in selected }.values.flatten()
        val remainingHashes = LanSyncScope.referencedResources(remainder)
        retain(Body(LanSyncGroupResolutionSource.Local, before.deviceId, remainder,
            before.resources.filterKeys(remainingHashes::contains), current.blobs.filterKeys(remainingHashes::contains)))
        primary.values.forEach(::retain); branches.values.flatMap { it.values }.forEach(::retain)

        val identities = mutableListOf<LanSyncGroupResolvedIdentity>(); val media = linkedMapOf<List<String>, LanSyncGroupResolvedMedia>()
        val mediaOwners = linkedMapOf<String, String>(); val forkParents = linkedMapOf<String, String>()
        fun rewriteResources(record: LanSyncRecord, branch: String, copy: Boolean): LanSyncRecord = record.copy(body = LanSyncWorkspace.mapResources(record.body) { ref, _ ->
            if (!ref.startsWith("ccb-lan-v1/")) ref else {
                val parts = ref.split('/'); require(parts.size == 5) { "SYNC_RESOURCE_REFERENCE" }
                val key = listOf(branch, parts[1], parts[2], parts[3])
                val mapped = media.getOrPut(key) {
                    val occupied = mediaOwners["${parts[1]}/${parts[2]}"]
                    val id = if (copy || occupied != null && occupied != parts[3]) lanSyncDerivedId(operation, key.joinToString("/")) else parts[2]
                    val previous = mediaOwners["${parts[1]}/$id"]
                    require(if (id != parts[2] || copy) previous == null else previous == null || previous == parts[3]) { "SYNC_GROUP_RESOLUTION_MEDIA_COLLISION" }
                    mediaOwners["${parts[1]}/$id"] = parts[3]
                    LanSyncGroupResolvedMedia(branch, parts[1], parts[2], parts[3], id)
                }
                "ccb-lan-v1/${parts[1]}/${mapped.target}/${parts[3]}/${parts[4]}"
            }
        })
        val records = remainder.sortedBy { it.key }.map { rewriteResources(it, "retained", false) }.toMutableList()
        val proposed = selected.associateWith { mutableListOf<LanSyncRecord>() }
        primary.forEach { (unit, value) -> value.records.sortedBy { it.key }.forEach { row ->
            rewriteResources(row, "primary", false).let { records += it; proposed.getValue(unit) += it }
        } }
        branches.forEach { (branch, sourceUnits) ->
            // One namespace per complete named source graph; changing any selected source changes it.
            val seed = lanSyncHash(buildJsonObject {
                put("operation", operation); put("branch", branch)
                put("sources", JsonObject(sourceUnits.toSortedMap().mapValues { (unit, source) -> sourceJson(source.source, native.expected[unit]) }))
            })
            val identity = LanSyncIdentity(transaction = seed)
            val originals = sourceUnits.values.flatMap { it.records }
            originals.forEach { row -> identity.declarations(row).sortedWith(compareBy({ it.first }, { it.second })).forEach { (type, id) ->
                val target = lanSyncDerivedId(seed, "$type/$id"); identity.bind(type, id, target)
                identities += LanSyncGroupResolvedIdentity(branch, type, id, target)
            } }
            val mapper = LanSyncGraphRemapper(originals, identity)
            sourceUnits.forEach { (unit, value) -> value.records.sortedBy { it.key }.forEach { row ->
                var mapped = if (row.type == "save_slots" && row.body["packageRef"] is JsonObject)
                    JsonObject(row.body + mapOf("id" to JsonPrimitive(identity.value("save_slots", row.id)),
                        "sessionId" to JsonPrimitive(identity.value("chat_sessions", requireNotNull(row.text("sessionId"))))))
                else mapper.rewrite(row)
                if (row.type == "chat_sessions") {
                    val title = (mapped["displayTitleOverride"] as? JsonPrimitive)?.contentOrNull ?: (mapped["title"] as? JsonPrimitive)?.contentOrNull ?: "对话"
                    mapped = JsonObject(mapped + mapOf("displayTitleOverride" to JsonPrimitive(title.take(60) + "（同步分支）"),
                        "lanSyncOrigin" to buildJsonObject { put("parentSessionId", row.id); put("transaction", operation); put("sourceDeviceId", value.author) }))
                    forkParents[identity.value("chat_sessions", row.id)] = row.id
                }
                val targetId = requireNotNull(LanSyncScope.identity(row.type, mapped))
                if (targetId != row.id) identities += LanSyncGroupResolvedIdentity(branch, row.type, row.id, targetId)
                rewriteResources(row.copy(id = targetId, body = mapped), "copy-$seed", true).let { records += it; proposed.getValue(unit) += it }
            } }
        }
        val references = LanSyncScope.referencedResources(records)
        val target = freezeSnapshot(before.copy(records = records.sortedBy { it.key }, resources = resources.filterKeys(references::contains)))
        target.validate(); LanSyncScope.validateGraph(target)
        val targetUnits = target.units()
        // Include same-value resolutions and copied units; held originals can never be claimed here.
        val resolved = selected + (targetUnits.keys - local.keys)
        require((local.keys + targetUnits.keys).filter { unit ->
            local[unit].orEmpty().associateBy { it.key } != targetUnits[unit].orEmpty().associateBy { it.key }
        }.all { it in resolved }) { "SYNC_GROUP_RESOLUTION_REGROUPED" }
        val gates = resolved.associateWith { unit -> evidence.checkProposal(unit, targetUnits[unit].orEmpty().map { it.key }.toSet()) { former, removal ->
            native.appliedBefore[former]?.frontiers?.any { history.isAncestorOrSelf(removal, it) } == true
        } }
        require(gates.values.all { it.missing.isEmpty() }) { "SYNC_GROUP_RESOLUTION_MEMBERSHIP_MISSING" }
        val reviews = selected.sorted().associateWith { unit ->
            val chosen = resolutionList(proposed.getValue(unit).map(::freezeRecord))
            val versions = resolutionMap(allBodies.getValue(unit).mapValues { it.value.records })
            val localImpact = resolutionList(LanSyncImpactReview.changes(local[unit].orEmpty(), chosen).map(::freezeImpact))
            val impact = resolutionMap(versions.mapValues { resolutionList(LanSyncImpactReview.changes(it.value, chosen).map(::freezeImpact)) })
            val restoration = resolutionSet((setOf(unit) + LanSyncMoments.units(chosen).keys).flatMap { gates[it]?.removals.orEmpty() })
            val confirmation = restoration.isNotEmpty() || (localImpact + impact.values.flatten()).any { it.needsConfirmation }
            val unitBinding = lanSyncHash(buildJsonObject {
                put("unit", unit); put("choice", choiceJson(frozenChoices.getValue(unit), native.expected[unit]))
                put("proposed", recordsJson(chosen)); put("local", recordsJson(local[unit].orEmpty()))
                put("versions", JsonArray(versions.entries.sortedBy { it.key.id.value }.map { (ref, rows) -> buildJsonObject { put("reference", refJson(ref)); put("records", recordsJson(rows)) } }))
                put("restorations", removalsJson(restoration))
            })
            LanSyncGroupResolutionUnitReview(unit, unitBinding, resolutionList(local[unit].orEmpty()), versions, chosen, localImpact, impact, restoration, confirmation)
        }
        val previous = resolutionMap(resolved.sorted().associateWith { resolutionSet(history.frontier(it, native.heads).map { row -> row.version }) })
        val held = native.units.filter { (unit, row) -> unit !in selected && row.stage != LanSyncGroupNativeStage.UNCHANGED }.keys
        val sources = resolutionMap(target.resources.mapValues { (hash, length) -> LanSyncGroupResolutionResource(SyncPlaintextIdentity(length, hash), resolutionSet(paths.getValue(hash))) })
        val applied = resolutionMap(native.appliedBefore.mapValues { it.value.copy(frontiers = resolutionSet(it.value.frontiers)) })
        val binding = lanSyncHash(buildJsonObject {
            put("operation", operation); put("checkpoint", refJson(native.checkpoint)); put("heads", refsJson(native.heads))
            put("before", recordsJson(before.records)); put("coverage", JsonArray(before.coveredTypes.sorted().map(::JsonPrimitive)))
            put("raw", JsonObject(native.sourceFiles.toSortedMap().mapValues { JsonPrimitive(it.value) }))
            put("applied", JsonObject(applied.toSortedMap().mapValues { (_, row) -> buildJsonObject { put("hash", row.valueHash?.let(::JsonPrimitive) ?: JsonNull); put("frontiers", refsJson(row.frontiers)) } }))
            put("choices", JsonObject(frozenChoices.mapValues { (unit, choice) -> choiceJson(choice, native.expected[unit]) }))
            put("target", recordsJson(target.records)); put("resources", JsonObject(target.resources.toSortedMap().mapValues { JsonPrimitive(it.value) }))
            put("reviews", JsonObject(reviews.mapValues { JsonPrimitive(it.value.binding) }))
            put("resolved", JsonArray(resolved.sorted().map(::JsonPrimitive))); put("held", JsonArray(held.sorted().map(::JsonPrimitive)))
        })
        return LanSyncGroupResolutionPreview(operation, binding, native.checkpoint, resolutionSet(native.heads), resolutionMap(native.expected),
            resolutionMap(native.sourceFiles), applied, target, resolutionSet(resolved), resolutionSet(held), previous, sources,
            resolutionList(identities.distinct()), resolutionList(media.values), resolutionMap(forkParents), frozenChoices, resolutionMap(reviews), before)
    }

    private fun sourceJson(source: LanSyncGroupResolutionSource, localHash: String?): JsonElement = when (source) {
        LanSyncGroupResolutionSource.Local -> buildJsonObject { put("local", localHash?.let(::JsonPrimitive) ?: JsonNull) }
        is LanSyncGroupResolutionSource.Version -> buildJsonObject { put("version", refJson(source.reference)) }
    }
    private fun choiceJson(choice: LanSyncGroupResolutionChoice, localHash: String?): JsonElement = when (choice) {
        LanSyncGroupResolutionChoice.Later -> JsonPrimitive("later")
        is LanSyncGroupResolutionChoice.Keep -> buildJsonObject { put("primary", sourceJson(choice.primary, localHash)); put("copies", JsonObject(choice.copies.mapValues { sourceJson(it.value, localHash) })) }
    }
    private fun refJson(ref: SyncVersionRef) = buildJsonObject { put("id", ref.id.value); put("bodyHash", ref.bodyHash) }
    private fun refsJson(refs: Set<SyncVersionRef>) = JsonArray(refs.sortedBy { it.id.value }.map(::refJson))
    private fun recordsJson(rows: List<LanSyncRecord>) = JsonArray(rows.sortedBy { it.key }.map { buildJsonObject { put("type", it.type); put("id", it.id); put("body", it.body) } })
    private fun removalsJson(rows: Set<LanSyncGroupRecordRemoval>) = JsonArray(rows.sortedWith(compareBy({ it.record }, { it.formerUnit }, { it.removal.id.value })).map {
        buildJsonObject { put("record", it.record); put("unit", it.formerUnit); put("removal", refJson(it.removal)) }
    })
    private fun freezeRecord(row: LanSyncRecord) = row.copy(body = freezeJson(row.body).jsonObject)
    private fun freezeImpact(row: LanSyncRecordImpact) = row.copy(removedVersionIds = resolutionSet(row.removedVersionIds),
        removedEmbeddedMessageIds = resolutionSet(row.removedEmbeddedMessageIds), fields = resolutionList(row.fields))
    private fun freezeJson(value: JsonElement, depth: Int = 0): JsonElement {
        require(depth <= 128) { "MERGE_JSON_DEPTH" }
        return when (value) {
            is JsonObject -> JsonObject(resolutionMap(value.mapValues { freezeJson(it.value, depth + 1) }))
            is JsonArray -> JsonArray(resolutionList(value.map { freezeJson(it, depth + 1) }))
            is JsonPrimitive -> value
        }
    }
    private fun freezeSnapshot(value: LanSyncSnapshot) = value.copy(records = resolutionList(value.records.map(::freezeRecord)),
        resources = resolutionMap(value.resources), excludedTypes = resolutionSet(value.excludedTypes), coveredTypes = resolutionSet(value.coveredTypes))
}

private fun <K, V> resolutionMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))
private fun <T> resolutionSet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
private fun <T> resolutionList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
