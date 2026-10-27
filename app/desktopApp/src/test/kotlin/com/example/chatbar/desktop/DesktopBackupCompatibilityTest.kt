package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.*
import kotlin.test.*

class DesktopBackupCompatibilityTest {
    @Test fun `unsupported official Moments remain whole archive blockers with payload free diagnostics`() {
        for (store in listOf("moment_posts", "moment_tasks")) {
            val failure = assertFailsWith<DesktopBackupCompatibilityException> {
                DesktopBackupPolicy.entity("entities/$store/synthetic.json", "{\"privateText\":\"DO_NOT_LOG\"}".toByteArray(), false)
            }
            assertEquals(DesktopBackupCompatibility.MOMENTS_UNSUPPORTED, failure.kind)
            assertFalse(failure.toString().contains("DO_NOT_LOG"))
            assertNull(failure.cause)
        }
    }

    @Test fun `entity name alone does not authorize unknown fields or external credentials`() {
        val cases = listOf(
            "entities/app_settings.json" to "{\"futureField\":\"DO_NOT_LOG\"}",
            "entities/app_settings.json" to "{\"siliconFlowApiKey\":\"DO_NOT_LOG\"}",
            "entities/new_domain/x.json" to "{\"id\":\"DO_NOT_LOG\"}")
        val expected = listOf(DesktopBackupCompatibility.ENTITY_SCHEMA_UNSUPPORTED,
            DesktopBackupCompatibility.CREDENTIAL_APPROVAL_REQUIRED, DesktopBackupCompatibility.ENTITY_UNSUPPORTED)
        cases.forEachIndexed { index, (path, bytes) ->
            val failure = assertFailsWith<DesktopBackupCompatibilityException> {
                DesktopBackupPolicy.entity(path, bytes.toByteArray(), false)
            }
            assertEquals(expected[index], failure.kind)
            assertFalse(failure.toString().contains("DO_NOT_LOG"))
            assertNull(failure.cause)
        }
    }
}
