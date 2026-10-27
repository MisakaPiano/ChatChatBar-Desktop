package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.security.DesktopCredentialKey
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class DesktopFullBackupStagingTest {
    private fun workspace() = Files.createTempDirectory(Files.createDirectories(java.nio.file.Path.of("build/preview-synthetic/incoming").toAbsolutePath()), "case-")

    @Test fun authenticatedStagingSeparatesEveryKnownKeyBeforeIndividualFilesReachDisk() = runBlocking<Unit> {
        val workspace = workspace()
        CensusWireFixtures.input(CensusWireFixtures.bytes(encrypted = true), "staging-secrets") { source ->
            val password = CensusWireFixtures.PASSWORD.toCharArray()
            DesktopFullBackupStaging.prepare(source, password, workspace).use { staged ->
                assertTrue(password.all { it == '\u0000' })
                assertEquals(7, staged.secrets.metadata.size)
                assertEquals(6, staged.secrets.metadata.count { it.state == CredentialFieldState.NONEMPTY })
                staged.files.values.forEach { assertFalse(Files.readString(it).contains(CensusWireFixtures.SECRET)) }
                assertEquals(JsonNull, staged.preferences["novelAiToken"])
                assertEquals(JsonPrimitive(""), staged.entities.getValue("entities/model_configs/m.json")["apiKey"])
                assertFalse(staged.toString().contains(CensusWireFixtures.SECRET))
                staged.verifyUnchanged()
            }
        }
        Files.list(workspace).use { assertEquals(0L, it.count()) }
    }

    @Test fun allFourSourceFieldStatesRemainDistinctAndCloseWipesValueBuffers() {
        for ((field, expected) in listOf("" to CredentialFieldState.MISSING, "\"novelAiToken\":null" to CredentialFieldState.EXPLICIT_NULL,
            "\"novelAiToken\":\"\"" to CredentialFieldState.EMPTY, "\"novelAiToken\":\"FAKE_VOLATILE\"" to CredentialFieldState.NONEMPTY)) {
            val secrets = DesktopImportSecrets()
            secrets.preferences(backupJson.parseToJsonElement("{$field}").jsonObject)
            val entry = secrets.metadata.single { it.kind == ImportCredentialKind.NOVEL_AI }
            assertEquals(expected, entry.state)
            val reference = entry.withValue { it }
            secrets.close()
            assertTrue(reference == null || reference.all { it == '\u0000' })
            assertFails { secrets.metadata }
            assertFalse(entry.toString().contains("FAKE_VOLATILE"))
        }
    }

    @Test fun communityIsStrictDormantProtectedCustodyNeverAnApiKey() = runBlocking<Unit> {
        val raw = """{"accessToken":"FAKE_SESSION","userId":"synthetic","displayName":"Synthetic","expiresAtMillis":123}"""
        val prefs = buildJsonObject { put("communitySession", raw) }.toString()
        val workspace = workspace()
        CensusWireFixtures.input(CensusWireFixtures.bytes(prefs = prefs, files = emptyMap()), "staging-community") { source ->
            DesktopFullBackupStaging.prepare(source, null, workspace).use { staged ->
                val entry = staged.secrets.metadata.single { it.kind == ImportCredentialKind.COMMUNITY }
                assertEquals(DesktopCredentialKey.DormantCommunitySession, entry.key)
                assertEquals(JsonNull, staged.preferences["communitySession"])
                assertEquals(raw, entry.withValue { it?.concatToString() })
                assertFalse(staged.toString().contains("FAKE_SESSION"))
            }
        }
        for (expiry in listOf("\"123\"", "null", "1.0", "1e2", "true", "[]", "{}", "9223372036854775808")) {
            val bad = buildJsonObject { put("communitySession", raw.replace(":123", ":$expiry")) }.toString()
            CensusWireFixtures.input(CensusWireFixtures.bytes(prefs = bad, files = emptyMap()), "staging-community-${expiry.hashCode()}") { source ->
                val error = assertFailsWith<DesktopFullBackupSource.Refusal> { DesktopFullBackupStaging.prepare(source, null, workspace) }
                assertNull(error.cause); assertFalse(error.toString().contains("FAKE_SESSION"))
            }
            Files.list(workspace).use { assertEquals(0L, it.count()) }
        }
    }

    @Test fun lateFailureAndCancellationReleaseOwnedFilesAndNeverRetainUnprotectedKeys() = runBlocking<Unit> {
        val workspace = workspace()
        CensusWireFixtures.input(CensusWireFixtures.bytes(badInventory = true), "staging-late-failure") { source ->
            assertFailsWith<DesktopFullBackupSource.Refusal> { DesktopFullBackupStaging.prepare(source, null, workspace) }
        }
        Files.list(workspace).use { assertEquals(0L, it.count()) }
        CensusWireFixtures.input(CensusWireFixtures.bytes(), "staging-cancel") { source ->
            val task = launch(start = CoroutineStart.LAZY) { DesktopFullBackupStaging.prepare(source, null, workspace).close() }
            task.cancelAndJoin()
        }
        Files.list(workspace).use { assertEquals(0L, it.count()) }
    }

    @Test fun stagedFilesCannotChangeAfterTheirVerifiedProof() = runBlocking<Unit> {
        val workspace = workspace()
        CensusWireFixtures.input(CensusWireFixtures.bytes(), "staging-changed") { source ->
            DesktopFullBackupStaging.prepare(source, null, workspace).use { staged ->
                Files.writeString(staged.files.values.first(), "{}")
                assertFailsWith<IllegalStateException> { staged.verifyUnchanged() }
            }
        }
    }

    @Test fun identicalKeyAliasesAreConsistentAndDifferentAccountOrValueNeverSilentlyWins() {
        DesktopImportSecrets().use { secrets ->
            val model = backupJson.parseToJsonElement(CensusWireFixtures.model()).jsonObject
            secrets.entity("entities/model_configs/m.json", model)
            secrets.entity("entities/retrieval_model_config.json", model)
            assertEquals(1, secrets.metadata.size)
            assertFails { secrets.entity("entities/retrieval_model_config.json", JsonObject(model + ("baseUrl" to JsonPrimitive("https://other.invalid/v1")))) }
            assertFails { secrets.entity("entities/retrieval_model_config.json", JsonObject(model + ("apiKey" to JsonPrimitive("FAKE_OTHER")))) }
        }
    }
}
