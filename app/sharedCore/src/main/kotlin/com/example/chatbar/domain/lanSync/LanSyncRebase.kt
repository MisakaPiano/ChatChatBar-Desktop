package com.example.chatbar.domain.lanSync

/** A newly covered type has no deletion authority before its first bilateral receipt. */
internal object LanSyncRebase {
    fun ancestorHashes(previous: LanSyncPending, baseline: LanSyncBaseline,
        computer: LanSyncSnapshot, phone: LanSyncSnapshot, computerApplied: Boolean): Map<String, String> {
        val expected = if (computerApplied) previous.expectedRemote else previous.expectedLocal
        if (baseline.revision == null) return expected
        val units = (previous.plan.snapshot.records + computer.records + phone.records).groupBy { it.unit }
        return expected.mapNotNull { (unit, hash) ->
            val records = units[unit].orEmpty()
            val authoritative = records.isNotEmpty() && records.all { it.type in baseline.coveredTypes }
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
        val keys = previous.plan.snapshot.records.filter { old ->
            old.type in newTypes && old.key !in present && old.unit !in plan.resolvedUnits &&
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
        require(deferred.recordKeys.all { key ->
            val record = records[key]
            record != null && record.type !in common && record.unit !in plan.resolvedUnits &&
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
            plan.resolvedUnits.size <= 100_000 && plan.resolvedUnits.all { it.length in 1..600 }) { "SYNC_REBASE_POLICY" }
    }
}
