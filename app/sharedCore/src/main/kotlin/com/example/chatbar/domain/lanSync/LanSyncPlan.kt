package com.example.chatbar.domain.lanSync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class LanSyncPlan(val transaction: String, val snapshot: LanSyncSnapshot, val parents: Map<String, String>,
    val supersedes: String? = null, val commonCoveredTypesBefore: Set<String>? = null,
    val resolvedUnits: Set<String> = emptySet(), val resolvedRecordKeys: Set<String> = emptySet(),
    val heldReviews: List<LanSyncHeldReview> = emptyList())

object LanSyncPlanner {
    fun plan(computer: LanSyncSnapshot, phone: LanSyncSnapshot, baseline: LanSyncBaseline,
        choices: Map<String, LanSyncChoice>, transaction: String): LanSyncPlan = build(computer, phone, baseline, choices, transaction, false)

    internal fun draft(computer: LanSyncSnapshot, phone: LanSyncSnapshot, baseline: LanSyncBaseline,
        choices: Map<String, LanSyncChoice>, transaction: String): LanSyncPlan = build(computer, phone, baseline, choices, transaction, true)

    private fun build(computer: LanSyncSnapshot, phone: LanSyncSnapshot, baseline: LanSyncBaseline,
        choices: Map<String, LanSyncChoice>, transaction: String, deferDependencyValidation: Boolean): LanSyncPlan {
        val comparison = LanSyncCompare.compare(computer, phone, baseline)
        require(choices.keys == comparison.conflicts.map { it.unit }.toSet()) { "SYNC_CHOICES" }
        val left = comparison.computerUnits; val right = comparison.phoneUnits
        val resolvedSourceKeys = choices.keys.flatMap { left[it].orEmpty() + right[it].orEmpty() }.map { it.key }.toSet()
        val retained = mutableListOf<LanSyncRecord>()
        val incoming = mutableListOf<LanSyncRecord>()
        val identity = LanSyncIdentity(transaction = transaction)
        val parents = linkedMapOf<String, String>()
        comparison.automatic.forEach { (unit, useLeft) ->
            if (useLeft) retained += left[unit].orEmpty() else incoming += right[unit].orEmpty()
        }
        comparison.conflicts.forEach { conflict ->
            when (choices.getValue(conflict.unit)) {
                LanSyncChoice.KEEP_COMPUTER -> retained += left[conflict.unit].orEmpty()
                LanSyncChoice.KEEP_PHONE -> incoming += right[conflict.unit].orEmpty()
                LanSyncChoice.KEEP_BOTH -> {
                    require(conflict.canKeepBoth) { "SYNC_SINGLETON_KEEP_BOTH" }
                    retained += left[conflict.unit].orEmpty()
                    val fork = right[conflict.unit].orEmpty()
                    fork.forEach { record -> identity.declarations(record).forEach { (type, id) ->
                        identity.bind(type, id, lanSyncDerivedId(transaction, "$type/$id"))
                    } }
                    incoming += fork
                    if (conflict.unit.startsWith("conversation/") && fork.isNotEmpty()) {
                        val original = conflict.unit.removePrefix("conversation/")
                        parents[identity.value("chat_sessions", original)] = original
                    }
                }
            }
        }
        // One remote-side namespace also rebinds remote conversations to a copied card/world book.
        val mapper = LanSyncGraphRemapper(phone.records, identity)
        val mapResource = resourceNamespace(retained, incoming, transaction)
        val rewritten = incoming.map { record ->
            val body = (if (record.type == "save_slots" && record.body["packageRef"] is JsonObject) {
                // The shared library envelope deliberately retains the complete immutable source archive.
                JsonObject(record.body + mapOf("id" to JsonPrimitive(identity.value("save_slots", record.id)),
                    "sessionId" to JsonPrimitive(identity.value("chat_sessions", requireNotNull(record.text("sessionId"))))))
            } else mapper.rewrite(record)).let { mapped ->
                if (record.type == "chat_sessions" && identity.value("chat_sessions", record.id) != record.id) {
                    val title = (mapped["displayTitleOverride"] as? JsonPrimitive)?.contentOrNull
                        ?: (mapped["title"] as? JsonPrimitive)?.contentOrNull ?: "对话"
                    JsonObject(mapped + mapOf("displayTitleOverride" to JsonPrimitive(title.take(60) + "（同步分支）"),
                        "lanSyncOrigin" to buildJsonObject {
                            put("parentSessionId", record.id); put("transaction", transaction); put("sourceDeviceId", phone.deviceId)
                        }))
                } else mapped
            }
            val withResources = LanSyncWorkspace.mapResources(body) { reference, _ -> mapResource(reference) }
            record.copy(id = requireNotNull(LanSyncScope.identity(record.type, withResources)), body = withResources)
        }
        val records = retained + rewritten
        val references = LanSyncScope.referencedResources(records)
        val result = LanSyncSnapshot(deviceId = computer.deviceId, records = records,
            resources = (computer.resources + phone.resources).filterKeys(references::contains),
            excludedTypes = computer.excludedTypes + phone.excludedTypes, coveredTypes = computer.coveredTypes)
        result.validate()
        if (!deferDependencyValidation) LanSyncScope.validateGraph(result)
        LanSyncResourceBudget().total(result.resources.values)
        return LanSyncPlan(transaction, result, parents, commonCoveredTypesBefore = baseline.coveredTypes,
            resolvedUnits = choices.keys.toSet(), resolvedRecordKeys = resolvedSourceKeys +
                incoming.zip(rewritten).filter { it.first.key in resolvedSourceKeys }.map { it.second.key })
    }
    /** Preserve the retained side and every incoming alias relationship, including non-fork dependents. */
    private fun resourceNamespace(retained: List<LanSyncRecord>, incoming: List<LanSyncRecord>, transaction: String): (String) -> String {
        fun parts(reference: String): List<String>? = if (!reference.startsWith("ccb-lan-v1/")) null else
            reference.split('/').takeIf { it.size == 5 && it[1] in setOf("images", "documents", "audio", "packages", "payloads") &&
                it[2].matches(Regex("[a-f0-9-]{36}")) && it[3].matches(Regex("[a-f0-9]{64}")) }
        val retainedHashes = linkedMapOf<String, String>(); val incomingHashes = linkedMapOf<String, String>(); val reserved = linkedSetOf<String>()
        fun collect(records: List<LanSyncRecord>, hashes: MutableMap<String, String>) {
            records.forEach { record -> LanSyncWorkspace.mapResources(record.body) { reference, _ ->
                parts(reference)?.let {
                    val id = "${it[1]}/${it[2]}"
                    require(hashes[id]?.let { previous -> previous == it[3] } != false) { "SYNC_RESOURCE_ID_CHANGED" }
                    hashes[id] = it[3]; reserved += id
                }; reference
            } }
        }
        collect(retained, retainedHashes); collect(incoming, incomingHashes)
        val renamed = linkedMapOf<String, String>()
        incomingHashes.toSortedMap().forEach { (original, hash) ->
            if (retainedHashes[original]?.let { it != hash } == true) {
                var attempt = 0L; var candidate: String
                do {
                    candidate = lanSyncDerivedId(transaction, "resource/$original/$hash/$attempt")
                    attempt = Math.addExact(attempt, 1L)
                } while ("${original.substringBefore('/')}/$candidate" in reserved)
                reserved += "${original.substringBefore('/')}/$candidate"; renamed["$original/$hash"] = candidate
            }
        }
        return map@ { reference ->
            val value = parts(reference) ?: return@map reference
            val replacement = renamed["${value[1]}/${value[2]}/${value[3]}"] ?: return@map reference
            "ccb-lan-v1/${value[1]}/$replacement/${value[3]}/${value[4]}"
        }
    }
}
