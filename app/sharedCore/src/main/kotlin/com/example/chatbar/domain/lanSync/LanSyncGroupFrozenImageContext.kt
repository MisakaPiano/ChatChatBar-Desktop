package com.example.chatbar.domain.lanSync

/** A frozen owner projection, never a path or a query against today's repositories. */
sealed interface LanSyncGroupFrozenImageContext {
    class Inspection(val view: LanSyncGroupNodeView) : LanSyncGroupFrozenImageContext
    class Action(val review: LanSyncGroupActionReview) : LanSyncGroupFrozenImageContext
    class Recovery(val view: LanSyncGroupRecoveryView) : LanSyncGroupFrozenImageContext
}

data class LanSyncGroupImageState(val preview: LanSyncImagePreview? = null, val error: String? = null) {
    override fun toString() = "LanSyncGroupImageState(ready=${preview != null}, error=$error, contents=REDACTED)"
}

internal fun LanSyncGroupFrozenImageContext.owns(record: LanSyncRecord, reference: String): Boolean {
    fun inspection(view: LanSyncGroupNodeView): Sequence<LanSyncRecord> = sequence {
        val plan = view.plan ?: return@sequence
        yieldAll(plan.before.records)
        yieldAll(plan.plan.snapshot.records)
        plan.units.values.forEach { row -> row.incoming.values.forEach { yieldAll(it.decoded.payload.records) } }
    }
    val rows: Sequence<LanSyncRecord> = when (this) {
        is LanSyncGroupFrozenImageContext.Inspection -> inspection(view)
        is LanSyncGroupFrozenImageContext.Recovery -> view.units.values.asSequence().flatten()
        is LanSyncGroupFrozenImageContext.Action -> sequence {
            review.source?.units?.values?.forEach { unit ->
                yieldAll(unit.current); unit.versions.values.forEach { yieldAll(it) }
            }
            review.incoming?.reductions?.values?.forEach { unit -> yieldAll(unit.current); yieldAll(unit.proposed) }
            review.recovery?.let { yieldAll(it.currentRecords); yieldAll(it.restoredRecords) }
            review.resolution?.units?.values?.forEach { unit ->
                yieldAll(unit.current); yieldAll(unit.proposed); unit.versions.values.forEach { yieldAll(it) }
            }
            review.resolutionBasis?.let { yieldAll(inspection(it)) }
        }
    }
    return rows.any { it == record && LanSyncReviewImages.references(it).any { image -> image.reference == reference } }
}
