package com.example.chatbar.domain.lanSync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class LanSyncHeldReview(
    val unit: String,
    val binding: String,
    val computerDevice: String,
    val phoneDevice: String,
    val computer: List<LanSyncRecord>,
    val phone: List<LanSyncRecord>,
    val recordKeys: Set<String>,
    val resources: Map<String, Long>,
    val ancestorHashes: Map<String, String>,
    val ancestorComponents: Map<String, Set<String>>,
    val proposalHash: String,
    val dependencyOf: Set<String> = emptySet(),
)

/** Partial-publication policy: an unconfirmed reduction preserves each endpoint's own view. */
object LanSyncPartialReview {
    fun prepare(plan: LanSyncPlan, computer: LanSyncSnapshot, phone: LanSyncSnapshot, baseline: LanSyncBaseline,
        confirmedBindings: Map<String, String> = emptyMap()): LanSyncPlan {
        require(plan.heldReviews.isEmpty()) { "SYNC_REVIEW_ALREADY_PREPARED" }
        computer.validate(); phone.validate(); plan.snapshot.validate()
        val comparison = LanSyncCompare.compare(computer, phone, baseline)
        val allUnits = linkedMapOf<String, MutableSet<String>>()
        (comparison.computerUnits.entries + comparison.phoneUnits.entries).forEach { (unit, rows) ->
            allUnits.getOrPut(unit) { linkedSetOf() }.addAll(rows.map { it.key })
        }
        val ownerByKey = allUnits.entries.flatMap { (unit, keys) -> keys.map { it to unit } }.toMap()
        plan.snapshot.units().forEach { (unit, rows) ->
            rows.forEach { row -> allUnits.getOrPut(ownerByKey[row.key] ?: unit) { linkedSetOf() }.add(row.key) }
        }
        val left = computer.records.associateBy { it.key }; val right = phone.records.associateBy { it.key }
        val desired = plan.snapshot.records.associateBy { it.key }
        val proposals = comparison.automatic.keys.filter { unit ->
            val keys = allUnits.getValue(unit)
            val target = keys.mapNotNull(desired::get)
            (LanSyncImpactReview.changes(keys.mapNotNull(left::get), target) +
                LanSyncImpactReview.changes(keys.mapNotNull(right::get), target)).any { it.needsConfirmation }
        }.toSet()
        val held = linkedMapOf<String, MutableSet<String>>()
        for (unit in proposals) {
            val keys = allUnits.getValue(unit)
            val target = keys.mapNotNull(desired::get)
            val binding = reviewBinding(unit, keys.mapNotNull(left::get), keys.mapNotNull(right::get), baseline,
                lanSyncUnitHashes(mapOf(unit to target)).getValue(unit))
            if (confirmedBindings[unit] != binding) held[unit] = linkedSetOf()
        }
        // A format is a strict live-session edge. Preserve its connected conversations when
        // either endpoint's original view must be retained, including postponed new forks.
        val keyOwners = allUnits.entries.flatMap { (unit, keys) -> keys.map { it to unit } }.toMap()
        val links = linkedMapOf<String, MutableSet<String>>()
        (computer.records + phone.records + plan.snapshot.records).filter { it.type == "chat_sessions" }.forEach { session ->
            val format = session.text("formatCardId") ?: return@forEach
            val owner = keyOwners[session.key] ?: return@forEach
            val dependency = keyOwners["format_cards/$format"] ?: return@forEach
            links.getOrPut(owner) { linkedSetOf() }.add(dependency)
            links.getOrPut(dependency) { linkedSetOf() }.add(owner)
        }
        val queue = java.util.ArrayDeque(held.keys)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (linked in links[current].orEmpty()) if (linked !in held) {
                held[linked] = linkedSetOf(current); queue.addLast(linked)
            }
        }
        require(confirmedBindings.keys.all { it in proposals }) { "SYNC_REVIEW_UNKNOWN_CONFIRMATION" }
        val ancestorByRecord = baseline.componentRecords.entries.flatMap { (unit, keys) -> keys.map { it to unit } }.toMap()
        val result = held.map { (unit, dependencies) ->
            val keys = allUnits.getValue(unit)
            val beforeLeft = keys.mapNotNull(left::get); val beforeRight = keys.mapNotNull(right::get)
            val ancestorUnits = (keys.mapNotNull(ancestorByRecord::get) + listOfNotNull(unit.takeIf { it in baseline.hashes })).toSet()
            val referenced = LanSyncScope.referencedResources(beforeLeft + beforeRight)
            val resources = (computer.resources + phone.resources).filterKeys {
                it in referenced
            }
            val proposalHash = lanSyncUnitHashes(mapOf(unit to keys.mapNotNull(desired::get))).getValue(unit)
            LanSyncHeldReview(unit, reviewBinding(unit, beforeLeft, beforeRight, baseline, proposalHash), computer.deviceId, phone.deviceId,
                beforeLeft, beforeRight, keys.toSet(), resources, baseline.hashes.filterKeys(ancestorUnits::contains),
                baseline.componentRecords.filterKeys(ancestorUnits::contains), proposalHash, dependencies)
        }
        return plan.copy(heldReviews = result).also {
            // Both graphs must be valid before this partial envelope has publication authority.
            localPlan(it, computer.deviceId); localPlan(it, phone.deviceId)
        }
    }

    fun reviewBinding(unit: String, computer: List<LanSyncRecord>, phone: List<LanSyncRecord>, baseline: LanSyncBaseline,
        proposalHash: String = lanSyncUnitHashes(mapOf(unit to emptyList())).getValue(unit)): String {
        val keys = (computer + phone).map { it.key }.toSet()
        return lanSyncHash(buildJsonObject {
            put("unit", unit)
            put("computerDevice", baseline.ownerId?.let(::JsonPrimitive) ?: JsonNull); put("phoneDevice", baseline.peerId)
            put("proposal", proposalHash)
            put("computer", lanSyncUnitHashes(mapOf(unit to computer)).getValue(unit))
            put("phone", lanSyncUnitHashes(mapOf(unit to phone)).getValue(unit))
            put("ancestor", baseline.hashes[unit]?.let(::JsonPrimitive) ?: JsonNull)
            put("components", JsonObject(baseline.componentRecords.filterValues { members ->
                members.any(keys::contains)
            }.toSortedMap().mapValues { JsonArray(it.value.sorted().map(::JsonPrimitive)) }))
        })
    }

    fun localPlan(plan: LanSyncPlan, deviceId: String): LanSyncPlan {
        if (plan.heldReviews.isEmpty()) return plan
        validate(plan)
        val blocked = plan.heldReviews.flatMap { it.recordKeys }.toSet()
        val local = plan.heldReviews.flatMap { proof ->
            when (deviceId) {
                proof.computerDevice -> proof.computer
                proof.phoneDevice -> proof.phone
                else -> error("SYNC_REVIEW_DEVICE")
            }
        }
        val records = plan.snapshot.records.filterNot { it.key in blocked } + local
        val resources = (plan.snapshot.resources + plan.heldReviews.flatMap { it.resources.entries }.associate { it.key to it.value })
            .filterKeys { it in LanSyncScope.referencedResources(records) }
        val snapshot = plan.snapshot.copy(deviceId = deviceId, records = records, resources = resources)
        snapshot.validate(); LanSyncScope.validateGraph(snapshot)
        return plan.copy(snapshot = snapshot, heldReviews = emptyList())
    }

    fun baseline(plan: LanSyncPlan, previous: LanSyncBaseline): Pair<Map<String, String>, Map<String, Set<String>>> {
        if (plan.heldReviews.isEmpty()) return plan.snapshot.hashes() to LanSyncMoments.components(plan.snapshot)
        validate(plan)
        val proofs = plan.heldReviews
        val blocked = proofs.flatMap { it.recordKeys }.toSet()
        val heldAncestors = proofs.flatMap { it.ancestorHashes.entries }.associate { it.key to it.value }
        val heldComponents = proofs.flatMap { it.ancestorComponents.entries }.associate { it.key to it.value }
        require(heldAncestors.all { (key, hash) -> previous.hashes[key] == hash } &&
            heldComponents.all { (key, members) -> previous.componentRecords[key] == members }) { "SYNC_REVIEW_BASELINE_CHANGED" }
        val excluded = plan.snapshot.units().filterValues { rows -> rows.any { it.key in blocked } }.keys + proofs.map { it.unit } + heldAncestors.keys
        return (plan.snapshot.hashes().filterKeys { it !in excluded } + heldAncestors) to
            (LanSyncMoments.components(plan.snapshot).filterKeys { it !in excluded } + heldComponents)
    }

    private fun validate(plan: LanSyncPlan) {
        val proofs = plan.heldReviews
        require(proofs.map { it.unit }.toSet().size == proofs.size && proofs.size <= 100_000) { "SYNC_REVIEW_IDENTITIES" }
        val keys = proofs.flatMap { it.recordKeys }
        require(keys.size == keys.toSet().size && keys.size <= 100_000) { "SYNC_REVIEW_IDENTITIES" }
        proofs.forEach { proof ->
            require(proof.binding.matches(Regex("[a-f0-9]{64}")) && proof.computerDevice != proof.phoneDevice &&
                proof.computerDevice == plan.snapshot.deviceId) { "SYNC_REVIEW_DEVICE" }
            require((proof.computer + proof.phone).all { it.key in proof.recordKeys }) { "SYNC_REVIEW_IDENTITIES" }
            val ancestor = LanSyncBaseline(proof.phoneDevice, ownerId = proof.computerDevice,
                hashes = proof.ancestorHashes, componentRecords = proof.ancestorComponents)
            require(proof.proposalHash == lanSyncUnitHashes(mapOf(proof.unit to plan.snapshot.records.filter { it.key in proof.recordKeys })).getValue(proof.unit) &&
                reviewBinding(proof.unit, proof.computer, proof.phone, ancestor, proof.proposalHash) == proof.binding) { "SYNC_REVIEW_BINDING" }
            require(proof.resources.keys == LanSyncScope.referencedResources(proof.computer + proof.phone)) { "SYNC_REVIEW_RESOURCES" }
            require(proof.resources.keys.all { it.matches(Regex("[a-f0-9]{64}")) }) { "SYNC_REVIEW_RESOURCES" }
            LanSyncResourceBudget().total(proof.resources.values)
        }
    }
}
