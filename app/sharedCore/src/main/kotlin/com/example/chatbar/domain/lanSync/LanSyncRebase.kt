package com.example.chatbar.domain.lanSync

/** A newly covered type has no deletion authority before its first bilateral receipt. */
internal object LanSyncRebase {
    fun ancestorHashes(previous: LanSyncPending, baseline: LanSyncBaseline,
        computer: LanSyncSnapshot, phone: LanSyncSnapshot, computerApplied: Boolean): Map<String, String> {
        val expected = if (computerApplied) previous.expectedRemote else previous.expectedLocal
        val expectedComponents = if (computerApplied) previous.expectedRemoteComponents else previous.expectedLocalComponents
        if (baseline.revision == null) return expected
        val units = (previous.plan.snapshot.units().entries + computer.units().entries + phone.units().entries)
            .groupBy { it.key }.mapValues { (_, rows) -> rows.flatMap { it.value } }
        return expected.mapNotNull { (unit, hash) ->
            val records = units[unit].orEmpty()
            val capturedTypes = expectedComponents[unit]?.map { it.substringBefore('/') }
            val authoritative = if (capturedTypes != null) capturedTypes.all { it in baseline.coveredTypes }
                else records.isNotEmpty() && records.all { it.type in baseline.coveredTypes }
            if (authoritative) unit to hash else baseline.hashes[unit]?.let { unit to it }
        }.toMap()
    }

    fun deferred(previous: LanSyncPending?, current: LanSyncSnapshot, plan: LanSyncPlan,
        previousApplied: Boolean): LanSyncDeferredDeletion? {
        validatePolicy(plan)
        if (previous == null || !previousApplied) return null
        val common = plan.commonCoveredTypesBefore ?: return null
        val newTypes = plan.snapshot.coveredTypes - common
        if (newTypes.isEmpty()) return null
        val present = current.records.map { it.key }.toSet()
        val target = plan.snapshot.records.associateBy { it.key }
        val resolved = resolvedKeys(plan, previous.plan)
        val keys = previous.plan.snapshot.records.filter { old ->
            old.type in newTypes && old.key !in present && old.key !in resolved &&
                target[old.key]?.comparisonBody() == old.comparisonBody()
        }.map { it.key }.toSet()
        return keys.takeIf { it.isNotEmpty() }?.let { LanSyncDeferredDeletion(previous.plan.transaction, it) }
    }

    fun localPlan(plan: LanSyncPlan, deferred: LanSyncDeferredDeletion?, previous: LanSyncPlan?): LanSyncPlan {
        validatePolicy(plan)
        if (deferred == null) return plan
        require(previous != null && previous.transaction == deferred.previousTransaction &&
            plan.supersedes == previous.transaction && deferred.recordKeys.isNotEmpty() &&
            deferred.recordKeys.size <= 100_000) { "SYNC_DEFERRED_DELETION_PROOF" }
        val common = requireNotNull(plan.commonCoveredTypesBefore) { "SYNC_DEFERRED_DELETION_PROOF" }
        val old = previous.snapshot.records.associateBy { it.key }
        val records = plan.snapshot.records.associateBy { it.key }
        val resolved = resolvedKeys(plan, previous)
        require(deferred.recordKeys.all { key ->
            val record = records[key]
            record != null && record.type !in common && record.key !in resolved &&
                old[key]?.comparisonBody() == record.comparisonBody()
        }) { "SYNC_DEFERRED_DELETION_PROOF" }
        val retained = plan.snapshot.records.filterNot { it.key in deferred.recordKeys }
        val snapshot = plan.snapshot.copy(records = retained,
            resources = plan.snapshot.resources.filterKeys { it in LanSyncScope.referencedResources(retained) })
        snapshot.validate(); LanSyncScope.validateGraph(snapshot)
        return plan.copy(snapshot = snapshot)
    }

    private fun validatePolicy(plan: LanSyncPlan) {
        require(plan.commonCoveredTypesBefore?.let { plan.snapshot.coveredTypes.containsAll(it) } != false &&
            plan.resolvedUnits.size <= 100_000 && plan.resolvedUnits.all { it.length in 1..600 } &&
            plan.resolvedRecordKeys.size <= 300_000 && plan.resolvedRecordKeys.all { it.length in 1..600 }) { "SYNC_REBASE_POLICY" }
    }

    private fun resolvedKeys(plan: LanSyncPlan, previous: LanSyncPlan): Set<String> =
        plan.resolvedRecordKeys + (plan.snapshot.units().entries + previous.snapshot.units().entries)
            .filter { it.key in plan.resolvedUnits }.flatMap { it.value }.map { it.key }.toSet()

    fun ancestorComponents(previous: LanSyncPending, baseline: LanSyncBaseline, hashes: Map<String, String>,
        computerApplied: Boolean, unapplied: LanSyncSnapshot): Map<String, Set<String>> {
        val expectedHashes = if (computerApplied) previous.expectedRemote else previous.expectedLocal
        val expected = if (computerApplied) previous.expectedRemoteComponents else previous.expectedLocalComponents
        val plannedHashes = previous.plan.snapshot.hashes(); val planned = LanSyncMoments.components(previous.plan.snapshot)
        val currentHashes = unapplied.hashes(); val current = LanSyncMoments.components(unapplied)
        return hashes.mapNotNull { (key, hash) ->
            val members = when {
                hash == expectedHashes[key] && expected[key] != null -> expected[key]
                hash == baseline.hashes[key] && baseline.componentRecords[key] != null -> baseline.componentRecords[key]
                hash == plannedHashes[key] && planned[key] != null -> planned[key]
                hash == currentHashes[key] -> current[key]
                else -> null
            }
            members?.let { key to it }
        }.toMap()
    }
}
