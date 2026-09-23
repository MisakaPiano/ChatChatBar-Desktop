package com.example.chatbar.data.root

import com.example.chatbar.data.snapshot.SnapshotPayloadValidationScope
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppDataRootInfrastructureTest {
    @Test
    fun `backups reserves its entire root subtree case-insensitively`() {
        assertTrue(AppDataRootInfrastructure.isReservedPath(Path.of("backups")))
        assertTrue(AppDataRootInfrastructure.isReservedPath(Path.of("BACKUPS", "snapshot")))
    }

    @Test
    fun `ownership lock is reserved only as exact root-level entry`() {
        assertTrue(AppDataRootInfrastructure.isReservedPath(Path.of(".ccb-desktop.lock")))
        assertTrue(AppDataRootInfrastructure.isReservedPath(Path.of(".CCB-DESKTOP.LOCK")))
        assertFalse(AppDataRootInfrastructure.isReservedPath(Path.of("entities", ".ccb-desktop.lock")))
    }

    @Test
    fun `installed active validation excludes only reserved infrastructure`() {
        val scope = SnapshotPayloadValidationScope.INSTALLED_ACTIVE_ROOT

        assertTrue(scope.excludesReservedInfrastructure(Path.of("backups")))
        assertTrue(scope.excludesReservedInfrastructure(Path.of(".ccb-desktop.lock")))
        assertFalse(scope.excludesReservedInfrastructure(Path.of("unrelated.txt")))
        assertFalse(scope.excludesReservedInfrastructure(Path.of("entities", ".ccb-desktop.lock")))
    }

    @Test
    fun `snapshot payload validation never excludes reserved infrastructure`() {
        val scope = SnapshotPayloadValidationScope.SNAPSHOT_PAYLOAD

        assertFalse(scope.excludesReservedInfrastructure(Path.of("backups")))
        assertFalse(scope.excludesReservedInfrastructure(Path.of(".ccb-desktop.lock")))
    }
}
