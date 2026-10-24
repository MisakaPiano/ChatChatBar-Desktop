package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.*
import com.example.chatbar.desktop.security.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class DesktopMergeCredentialsTest {
    private class FakeStore : DesktopSecretStore {
        val values = linkedMapOf<DesktopCredentialKey, String>()
        override fun load(key: DesktopCredentialKey) = values[key]
        override fun save(key: DesktopCredentialKey, secret: String) { values[key] = secret }
        override fun delete(key: DesktopCredentialKey) { values.remove(key) }
    }
    private suspend fun fixture(prefs: String = "{}", modelKey: String = "FAKE_PHONE_MODEL", action: suspend (Path, DesktopFullBackupStaging, FakeStore, Map<String, JsonObject>) -> Unit) {
        val base = Files.createTempDirectory(Files.createDirectories(Path.of("build/preview-synthetic/merge-credentials").toAbsolutePath()), "case-")
        val input = Files.write(base.resolve("synthetic.cbbackup"), CensusWireFixtures.bytes(prefs = prefs,
            files = mapOf("entities/model_configs/m.json" to CensusWireFixtures.model(key = "\"$modelKey\"").toByteArray(Charsets.UTF_8))))
        val old = FakeStore().apply { values[DesktopCredentialKey.ModelApiKey("m")] = "FAKE_COMPUTER_MODEL" }
        DesktopFullBackupStaging.prepare(input, null, Files.createDirectory(base.resolve("work"))).use { phone ->
            action(base, phone, old, phone.entities)
        }
    }
    private suspend fun root(base: Path, protector: SecretProtector = MergeRootSyntheticProtector,
        action: suspend (Path, DesktopFreshRootAuthority, DesktopFreshRootAuthority.Pending) -> Unit) {
        val target = Files.createDirectory(base.resolve("root")); val credentials = Files.createDirectory(base.resolve("credentials"))
        val owner = DesktopDataRootOwnership.acquire(DesktopDataRootResolution.Resolved(target, DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json"))) as DesktopDataRootOwnershipResult.Acquired
        owner.ownership.use { val authority = DesktopFreshRootAuthority(target, credentials, protector); action(target, authority, authority.begin()) }
    }
    private fun preview(pc: Map<String, JsonObject>, phone: DesktopFullBackupStaging, credentials: DesktopMergeCredentials) =
        DesktopMergePreview.create(pc, phone.entities, emptyMap(), emptyMap(), credentialConflicts = credentials.dataConflictKeys)

    @Test fun `different model keys participate in keep take and both choices without touching original store`() = runBlocking<Unit> {
        for (choice in listOf(DesktopMergePreview.Choice.KEEP_PC, DesktopMergePreview.Choice.TAKE_PHONE, DesktopMergePreview.Choice.BOTH)) fixture { base, phone, old, pc ->
            val before = old.values.toMap()
            DesktopMergeCredentials.capture(pc, phone, old).use { credentials ->
                val preview = preview(pc, phone, credentials); assertTrue(DesktopMergePreview.Reason.CREDENTIAL_CONFLICT in preview.conflicts.single().reasons)
                val selected = preview.select(mapOf(preview.conflicts.single().key to choice))
                val plan = DesktopMergeDestinationPlan.create(selected, emptyMap(), emptyMap())
                root(base) { target, authority, pending ->
                    credentials.writeNewRoot(target, authority, pending, plan, selected, emptyMap(), true)
                    if (choice == DesktopMergePreview.Choice.BOTH) {
                        assertEquals("FAKE_COMPUTER_MODEL", pending.store.load(DesktopCredentialKey.ModelApiKey("m")))
                        assertEquals("FAKE_PHONE_MODEL", pending.store.load(DesktopCredentialKey.ModelApiKey(plan.identity.value("model_configs", "m"))))
                    } else assertEquals(if (choice == DesktopMergePreview.Choice.KEEP_PC) "FAKE_COMPUTER_MODEL" else "FAKE_PHONE_MODEL", pending.store.load(DesktopCredentialKey.ModelApiKey("m")))
                    assertFails { authority.verifyAndResolve() }
                }
            }
            assertEquals(before, old.values)
        }
    }

    @Test fun `global conflicts require explicit choices and confirmation before first write`() = runBlocking<Unit> {
        fixture("""{"novelAiToken":"FAKE_PHONE_NAI"}""") { base, phone, old, pc ->
            old.values[DesktopCredentialKey.NovelAiToken] = "FAKE_COMPUTER_NAI"
            DesktopMergeCredentials.capture(pc, phone, old).use { credentials ->
                val preview = preview(pc, phone, credentials); val selected = preview.select(preview.conflicts.associate { it.key to DesktopMergePreview.Choice.KEEP_PC })
                val plan = DesktopMergeDestinationPlan.create(selected, emptyMap(), emptyMap())
                root(base) { target, authority, pending ->
                    assertEquals("MERGE_CREDENTIAL_CONFIRMATION_REQUIRED", assertFails { credentials.writeNewRoot(target, authority, pending, plan, selected, emptyMap(), false) }.message)
                    assertEquals("MERGE_CREDENTIAL_EXPLICIT_CHOICES_REQUIRED", assertFails { credentials.writeNewRoot(target, authority, pending, plan, selected, emptyMap(), true) }.message)
                    assertTrue(Files.list(pending.credentialRoot).use { it.findAny().isEmpty })
                    credentials.writeNewRoot(target, authority, pending, plan, selected, mapOf(ImportCredentialKind.NOVEL_AI to DesktopMergeCredentials.Choice.TAKE_PHONE), true)
                    assertEquals("FAKE_PHONE_NAI", pending.store.load(DesktopCredentialKey.NovelAiToken))
                    assertEquals("FAKE_COMPUTER_MODEL", pending.store.load(DesktopCredentialKey.ModelApiKey("m")))
                }
            }
            assertEquals("FAKE_COMPUTER_NAI", old.values[DesktopCredentialKey.NovelAiToken])
        }
    }

    @Test fun `empty incoming values keep protected computer keys and original field states`() = runBlocking<Unit> {
        fixture("""{"novelAiToken":null,"fishAudioKey":""}""", modelKey = "") { base, phone, old, pc ->
            old.values[DesktopCredentialKey.NovelAiToken] = "FAKE_COMPUTER_NAI"
            DesktopMergeCredentials.capture(pc, phone, old).use { credentials ->
                val states = credentials.incomingStates.associate { it.kind to it.state }
                assertEquals(CredentialFieldState.EXPLICIT_NULL, states[ImportCredentialKind.NOVEL_AI]); assertEquals(CredentialFieldState.EMPTY, states[ImportCredentialKind.FISH])
                assertEquals(CredentialFieldState.MISSING, states[ImportCredentialKind.COMMUNITY]); assertEquals(CredentialFieldState.EMPTY, states[ImportCredentialKind.MODEL])
                val selected = preview(pc, phone, credentials).select(emptyMap()); val plan = DesktopMergeDestinationPlan.create(selected, emptyMap(), emptyMap())
                root(base) { target, authority, pending ->
                    credentials.writeNewRoot(target, authority, pending, plan, selected, emptyMap(), true)
                    assertEquals("FAKE_COMPUTER_NAI", pending.store.load(DesktopCredentialKey.NovelAiToken)); assertEquals("FAKE_COMPUTER_MODEL", pending.store.load(DesktopCredentialKey.ModelApiKey("m")))
                }
            }
        }
    }

    @Test fun `unavailable observation changed keys and closed buffers never mean absent`() = runBlocking<Unit> {
        fixture { base, phone, old, pc ->
            val unavailable = object : DesktopSecretStore {
                override fun load(key: DesktopCredentialKey): String? = error("FAKE_DO_NOT_LEAK")
                override fun save(key: DesktopCredentialKey, secret: String) = error("NO_WRITE")
                override fun delete(key: DesktopCredentialKey) = error("NO_DELETE")
            }
            val failure = assertFails { DesktopMergeCredentials.capture(pc, phone, unavailable) }
            assertEquals("MERGE_CREDENTIAL_OBSERVATION_UNAVAILABLE", failure.message); assertFalse(failure.toString().contains("FAKE_DO_NOT_LEAK"))
            val captured = DesktopMergeCredentials.capture(pc, phone, old)
            old.values[DesktopCredentialKey.ModelApiKey("m")] = "FAKE_CHANGED"
            assertEquals("MERGE_COMPUTER_CREDENTIAL_CHANGED", assertFails { captured.verifyComputerUnchanged() }.message)
            captured.close(); assertFails { captured.incomingStates }
            assertFalse(captured.toString().contains("FAKE_CHANGED"))
        }
    }

    @Test fun `partial protected readback failure permanently refuses completion for this preparation`() = runBlocking<Unit> {
        fixture("""{"novelAiToken":"FAKE_PHONE_NAI"}""") { base, phone, old, pc ->
            val before = old.values.toMap()
            val bad = object : SecretProtector {
                override fun protect(plaintext: ByteArray) = MergeRootSyntheticProtector.protect(plaintext)
                override fun unprotect(ciphertext: ByteArray): ByteArray = MergeRootSyntheticProtector.unprotect(ciphertext).also {
                    if (it.toString(Charsets.UTF_8).contains("FAKE_PHONE_NAI")) error("FAKE_MUST_NOT_LEAK")
                }
            }
            DesktopMergeCredentials.capture(pc, phone, old).use { credentials ->
                val preview = preview(pc, phone, credentials); val selected = preview.select(preview.conflicts.associate { it.key to DesktopMergePreview.Choice.KEEP_PC })
                val plan = DesktopMergeDestinationPlan.create(selected, emptyMap(), emptyMap())
                root(base, bad) { target, authority, pending ->
                    val failure = assertFails { credentials.writeNewRoot(target, authority, pending, plan, selected, emptyMap(), true) }
                    assertEquals("MERGE_CREDENTIAL_PROTECTED_WRITE_FAILED", failure.message)
                    assertFalse(failure.toString().contains("FAKE_MUST_NOT_LEAK"))
                    assertFails { authority.complete(pending, emptyMap()) }
                    assertFails { authority.verifyAndResolve() }
                }
            }
            assertEquals(before, old.values)
        }
    }

    @Test fun `Community custody requires source selection without API key equality classification`() = runBlocking<Unit> {
        val session = """{"accessToken":"FAKE_SESSION_TOKEN","userId":"synthetic-user","displayName":"Synthetic","expiresAtMillis":0}"""
        val prefs = buildJsonObject { put("communitySession", session) }.toString()
        fixture(prefs) { base, phone, old, pc ->
            old.values[DesktopCredentialKey.DormantCommunitySession] = session
            DesktopMergeCredentials.capture(pc, phone, old).use { credentials ->
                val conflict = credentials.globalConflicts.single(); assertEquals(ImportCredentialKind.COMMUNITY, conflict.kind); assertTrue(conflict.sessionUncompared)
                val preview = preview(pc, phone, credentials); val selected = preview.select(preview.conflicts.associate { it.key to DesktopMergePreview.Choice.KEEP_PC })
                val plan = DesktopMergeDestinationPlan.create(selected, emptyMap(), emptyMap())
                root(base) { target, authority, pending ->
                    credentials.writeNewRoot(target, authority, pending, plan, selected, mapOf(ImportCredentialKind.COMMUNITY to DesktopMergeCredentials.Choice.TAKE_PHONE), true)
                    assertEquals(session, pending.store.load(DesktopCredentialKey.DormantCommunitySession))
                }
            }
        }
    }

    @Test fun `unsafe plaintext computer snapshot is not silently stripped for merge`() = runBlocking<Unit> {
        val base = Files.createTempDirectory(Files.createDirectories(Path.of("build/preview-synthetic/merge-credentials").toAbsolutePath()), "unsafe-")
        val pc = Files.createDirectory(base.resolve("pc")); Files.createDirectories(pc.resolve("entities/model_configs"))
        val source = Files.writeString(pc.resolve("entities/model_configs/m.json"), CensusWireFixtures.model(key = "\"FAKE_UNSAFE_PC_KEY\""))
        val before = Files.readAllBytes(source); val work = Files.createDirectory(base.resolve("work"))
        val refusal = assertFailsWith<DesktopBackupCompatibilityException> { DesktopFullBackupMapping(pc).export(work, 86, forMerge = true) }
        assertEquals(DesktopBackupCompatibility.CREDENTIAL_APPROVAL_REQUIRED, refusal.kind)
        DesktopFullBackupMapping(pc).export(work, 86).use { ordinary ->
            assertFalse(ordinary.credentialFreeSource)
            assertFalse(Files.readString(ordinary.files.values.single()).contains("FAKE_UNSAFE_PC_KEY"))
        }
        assertContentEquals(before, Files.readAllBytes(source))
    }
}
