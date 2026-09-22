package com.example.chatbar.data.snapshot

class AutomaticBackupRetentionPolicy {
    fun deletionCandidates(
        completedSnapshots: List<AppDataSnapshot>,
        maximumCount: Int,
    ): List<AppDataSnapshot> {
        require(maximumCount >= 1) { "Maximum automatic snapshot count must be at least one" }

        val newestFirst = completedSnapshots
            .asSequence()
            .filter { snapshot ->
                snapshot.validation.valid &&
                    snapshot.purpose == SnapshotPurpose.AUTOMATIC &&
                    snapshot.createdAt != null
            }
            .sortedWith(
                compareByDescending<AppDataSnapshot> { it.createdAt }
                    .thenByDescending(AppDataSnapshot::name),
            )
            .toList()

        return newestFirst
            .drop(maximumCount)
            .sortedWith(
                compareBy<AppDataSnapshot> { it.createdAt }
                    .thenBy(AppDataSnapshot::name),
            )
    }
}
