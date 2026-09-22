package com.example.chatbar.data.snapshot

import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AutomaticBackupRetentionPolicyTest {
    private val policy = AutomaticBackupRetentionPolicy()
    private val baseTime = Instant.parse("2026-09-22T12:00:00Z")

    @Test
    fun `below limit selects no candidates`() {
        val snapshots = listOf(automatic("one", 1))

        assertTrue(policy.deletionCandidates(snapshots, maximumCount = 2).isEmpty())
    }

    @Test
    fun `exactly at limit selects no candidates`() {
        val snapshots = listOf(automatic("one", 1), automatic("two", 2))

        assertTrue(policy.deletionCandidates(snapshots, maximumCount = 2).isEmpty())
    }

    @Test
    fun `over limit selects oldest automatic snapshot`() {
        val oldest = automatic("oldest", 1)
        val newest = automatic("newest", 2)

        assertEquals(listOf(oldest), policy.deletionCandidates(listOf(newest, oldest), 1))
    }

    @Test
    fun `multiple candidates are returned oldest first`() {
        val first = automatic("first", 1)
        val second = automatic("second", 2)
        val third = automatic("third", 3)
        val fourth = automatic("fourth", 4)

        assertEquals(
            listOf(first, second),
            policy.deletionCandidates(listOf(third, first, fourth, second), 2),
        )
    }

    @Test
    fun `manual snapshot is protected`() {
        val manual = snapshot("manual", 1, SnapshotPurpose.MANUAL)

        assertTrue(policy.deletionCandidates(listOf(manual), 1).isEmpty())
    }

    @Test
    fun `pre-restore snapshot is protected`() {
        val preRestore = snapshot("pre", 1, SnapshotPurpose.PRE_RESTORE)

        assertTrue(policy.deletionCandidates(listOf(preRestore), 1).isEmpty())
    }

    @Test
    fun `invalid automatic snapshot is protected`() {
        val invalid = automatic("invalid", 1).copy(
            validation = SnapshotValidation(
                valid = false,
                issue = SnapshotValidationIssue.MALFORMED_MANIFEST,
                reason = "fixture",
            ),
        )

        assertTrue(policy.deletionCandidates(listOf(invalid), 1).isEmpty())
    }

    @Test
    fun `null purpose snapshot is protected`() {
        val unknown = snapshot("unknown", 1, purpose = null)

        assertTrue(policy.deletionCandidates(listOf(unknown), 1).isEmpty())
    }

    @Test
    fun `null created-at snapshot is protected`() {
        val missingTimestamp = snapshot("missing-time", 1, SnapshotPurpose.AUTOMATIC).copy(createdAt = null)

        assertTrue(policy.deletionCandidates(listOf(missingTimestamp), 1).isEmpty())
    }

    @Test
    fun `equal timestamp uses snapshot name as deterministic tie-breaker`() {
        val alpha = automatic("alpha", 1)
        val beta = automatic("beta", 1)
        val gamma = automatic("gamma", 1)

        assertEquals(
            listOf(alpha, beta),
            policy.deletionCandidates(listOf(beta, gamma, alpha), 1),
        )
    }

    @Test
    fun `zero maximum count is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            policy.deletionCandidates(emptyList(), 0)
        }
    }

    @Test
    fun `negative maximum count is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            policy.deletionCandidates(emptyList(), -1)
        }
    }

    private fun automatic(name: String, minute: Long): AppDataSnapshot =
        snapshot(name, minute, SnapshotPurpose.AUTOMATIC)

    private fun snapshot(
        name: String,
        minute: Long,
        purpose: SnapshotPurpose?,
    ) = AppDataSnapshot(
        name = name,
        directory = Path.of(name),
        createdAt = baseTime.plusSeconds(minute * 60),
        purpose = purpose,
        validation = SnapshotValidation.VALID,
    )
}
