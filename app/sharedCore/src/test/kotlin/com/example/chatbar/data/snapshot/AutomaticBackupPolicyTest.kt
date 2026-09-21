package com.example.chatbar.data.snapshot

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomaticBackupPolicyTest {
    private val policy = AutomaticBackupPolicy()
    private val now = Instant.parse("2026-09-22T12:00:00Z")
    private val interval = Duration.ofHours(24)

    @Test
    fun `no automatic snapshot is eligible`() {
        assertTrue(policy.shouldCreateSnapshot(now, emptyList(), interval))
    }

    @Test
    fun `recent automatic snapshot is not eligible`() {
        val snapshots = listOf(snapshot(now.minus(Duration.ofHours(1)), SnapshotPurpose.AUTOMATIC))

        assertFalse(policy.shouldCreateSnapshot(now, snapshots, interval))
    }

    @Test
    fun `old automatic snapshot is eligible`() {
        val snapshots = listOf(snapshot(now.minus(Duration.ofHours(25)), SnapshotPurpose.AUTOMATIC))

        assertTrue(policy.shouldCreateSnapshot(now, snapshots, interval))
    }

    @Test
    fun `newer manual snapshot does not refresh automatic interval`() {
        val snapshots = listOf(
            snapshot(now.minus(Duration.ofHours(25)), SnapshotPurpose.AUTOMATIC),
            snapshot(now.minus(Duration.ofHours(1)), SnapshotPurpose.MANUAL),
        )

        assertTrue(policy.shouldCreateSnapshot(now, snapshots, interval))
    }

    @Test
    fun `newer pre-restore snapshot does not refresh automatic interval`() {
        val snapshots = listOf(
            snapshot(now.minus(Duration.ofHours(25)), SnapshotPurpose.AUTOMATIC),
            snapshot(now.minus(Duration.ofMinutes(30)), SnapshotPurpose.PRE_RESTORE),
        )

        assertTrue(policy.shouldCreateSnapshot(now, snapshots, interval))
    }

    @Test
    fun `malformed snapshots do not affect eligibility`() {
        val malformed = snapshot(
            createdAt = now.minus(Duration.ofMinutes(1)),
            purpose = SnapshotPurpose.AUTOMATIC,
            validation = SnapshotValidation(
                valid = false,
                issue = SnapshotValidationIssue.MALFORMED_MANIFEST,
                reason = "fixture",
            ),
        )

        assertTrue(policy.shouldCreateSnapshot(now, listOf(malformed), interval))
    }

    @Test
    fun `clock rollback does not create an automatic snapshot`() {
        val snapshots = listOf(snapshot(now.plus(Duration.ofHours(1)), SnapshotPurpose.AUTOMATIC))

        assertFalse(policy.shouldCreateSnapshot(now, snapshots, interval))
    }

    @Test
    fun `exact minimum interval boundary is eligible`() {
        val snapshots = listOf(snapshot(now.minus(interval), SnapshotPurpose.AUTOMATIC))

        assertTrue(policy.shouldCreateSnapshot(now, snapshots, interval))
    }

    private fun snapshot(
        createdAt: Instant,
        purpose: SnapshotPurpose,
        validation: SnapshotValidation = SnapshotValidation.VALID,
    ) = AppDataSnapshot(
        name = "fixture-${createdAt.epochSecond}",
        directory = Path.of("fixture-${createdAt.epochSecond}"),
        createdAt = createdAt,
        purpose = purpose,
        validation = validation,
    )
}
