package com.example.chatbar.data.snapshot

import java.time.Duration
import java.time.Instant

class AutomaticBackupPolicy {
    fun shouldCreateSnapshot(
        now: Instant,
        completedSnapshots: List<AppDataSnapshot>,
        minimumInterval: Duration,
    ): Boolean {
        require(!minimumInterval.isNegative) { "Minimum automatic backup interval must not be negative" }

        val latestAutomatic = completedSnapshots
            .asSequence()
            .filter { snapshot ->
                snapshot.validation.valid && snapshot.purpose == SnapshotPurpose.AUTOMATIC
            }
            .mapNotNull(AppDataSnapshot::createdAt)
            .maxOrNull()
            ?: return true

        if (now.isBefore(latestAutomatic)) return false
        return Duration.between(latestAutomatic, now) >= minimumInterval
    }
}
