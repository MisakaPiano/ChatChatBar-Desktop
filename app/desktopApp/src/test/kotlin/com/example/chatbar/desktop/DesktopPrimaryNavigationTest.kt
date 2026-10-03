package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopPrimaryNavigationTest {
    private val root = Path.of("navigation-test-root").toAbsolutePath()
    private val destination = root.resolveSibling("navigation-test-destination")
    private val idle = DesktopDataRootSwitchState.Idle(
        currentRoot = root,
        provenance = DesktopDataRootProvenance.CLI_OVERRIDE,
        supported = true,
    )

    @Test
    fun `default route and user navigation persist across controller observations`() {
        val navigation = DesktopPrimaryNavigationController()
        assertEquals(DesktopPrimaryRoute.CHAT, navigation.currentRoute(idle))
        assertTrue(navigation.navigate(DesktopPrimaryRoute.MANAGE, idle))
        assertEquals(DesktopPrimaryRoute.MANAGE, navigation.selectedRoute.value)
        repeat(3) { assertEquals(DesktopPrimaryRoute.MANAGE, navigation.currentRoute(idle)) }
        assertTrue(navigation.navigate(DesktopPrimaryRoute.TOOLS, idle))
        assertEquals(DesktopPrimaryRoute.TOOLS, navigation.currentRoute(idle))
        assertTrue(navigation.navigate(DesktopPrimaryRoute.DATA, idle))
        assertEquals(DesktopPrimaryRoute.DATA, navigation.currentRoute(idle))
    }

    @Test
    fun `Manage route remains selected while management or transfer is busy`() {
        val navigation = DesktopPrimaryNavigationController()
        assertTrue(navigation.navigate(DesktopPrimaryRoute.MANAGE, idle))
        assertFalse(navigation.navigateFromManageWhenIdle(DesktopPrimaryRoute.CHAT, idle,
            managementBusy = true, transferBusy = false))
        assertEquals(DesktopPrimaryRoute.MANAGE, navigation.selectedRoute.value)
        assertFalse(navigation.navigateFromManageWhenIdle(DesktopPrimaryRoute.CHAT, idle,
            managementBusy = false, transferBusy = true))
        assertEquals(DesktopPrimaryRoute.MANAGE, navigation.selectedRoute.value)
        assertTrue(navigation.navigateFromManageWhenIdle(DesktopPrimaryRoute.CHAT, idle,
            managementBusy = false, transferBusy = false))
        assertEquals(DesktopPrimaryRoute.CHAT, navigation.selectedRoute.value)
    }

    @Test
    fun `unified import work and unknown modal block root and section navigation`() {
        val navigation = DesktopPrimaryNavigationController()
        navigation.navigate(DesktopPrimaryRoute.MANAGE, idle)
        assertFalse(navigation.navigateFromManageWhenIdle(DesktopPrimaryRoute.CHAT, idle,
            managementBusy = false, transferBusy = false, unifiedBusy = true))
        assertFalse(navigation.navigateFromManageWhenIdle(DesktopPrimaryRoute.DATA, idle,
            managementBusy = false, transferBusy = false, unknownOpen = true))
        assertEquals(DesktopPrimaryRoute.MANAGE, navigation.selectedRoute.value)
        assertFalse(canSwitchManageSection(false, false, true, false))
        assertFalse(canSwitchManageSection(false, false, false, true))
        assertTrue(canSwitchManageSection(false, false, false, false))
    }

    @Test
    fun `every non-idle migration state forces data and locks navigation until idle`() {
        val navigation = DesktopPrimaryNavigationController()
        navigation.navigate(DesktopPrimaryRoute.MANAGE, idle)
        val failure = DesktopDataRootMigrationResult.Failure(
            kind = DesktopDataRootMigrationFailureKind.PREPARATION,
            sourceRoot = root,
            destinationRoot = destination,
        )
        val lockedStates = listOf(
            DesktopDataRootSwitchState.CandidateSelected(root, idle.provenance, destination),
            DesktopDataRootSwitchState.Migrating(root, idle.provenance, destination),
            DesktopDataRootSwitchState.RetryableFailure(root, idle.provenance, destination, failure),
            DesktopDataRootSwitchState.RestartRequired(root, idle.provenance, destination, null, failure),
        )
        lockedStates.forEach { state ->
            assertEquals(DesktopPrimaryRoute.DATA, navigation.currentRoute(state))
            assertFalse(navigation.navigate(DesktopPrimaryRoute.CHAT, state))
            assertEquals(DesktopPrimaryRoute.MANAGE, navigation.selectedRoute.value)
        }
        assertEquals(DesktopPrimaryRoute.MANAGE, navigation.currentRoute(idle))
        assertTrue(navigation.navigate(DesktopPrimaryRoute.CHAT, idle))
        assertEquals(DesktopPrimaryRoute.CHAT, navigation.currentRoute(idle))
    }

    @Test
    fun `navigation neither constructs a new container nor writes application data`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-navigation-")
        val dataRoot = parent.resolve("data")
        val container = DesktopAppContainer(
            DesktopDataRootResolution.Resolved(
                appDataRoot = dataRoot,
                provenance = DesktopDataRootProvenance.CLI_OVERRIDE,
                bootstrapPath = parent.resolve("bootstrap.json"),
            ),
            secretStoreFactory = { InMemoryDesktopSecretStore() },
        )
        try {
            val taskRuntime = container.taskRuntime
            val drafts = container.editorDraftRepository
            val navigation = DesktopPrimaryNavigationController()
            DesktopPrimaryRoute.entries.forEach { route ->
                navigation.navigate(route, idle)
                assertEquals(route, navigation.currentRoute(idle))
                assertSame(taskRuntime, container.taskRuntime)
                assertSame(drafts, container.editorDraftRepository)
            }
            assertFalse(Files.exists(dataRoot))
        } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }
}
