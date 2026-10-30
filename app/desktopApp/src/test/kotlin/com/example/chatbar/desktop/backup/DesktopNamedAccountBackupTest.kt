package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.desktop.security.*
import com.example.chatbar.desktop.*
import java.nio.file.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopNamedAccountBackupTest {
    @Test fun `taking a legacy model removes only its old named binding while BOTH preserves the PC model`() {
        val account = DesktopApiAccount("pc", "Main", "Provider", "https://synthetic.invalid", "SYNTHETIC_PC")
        val old = DesktopAccountBundle(accounts = listOf(account), models = mapOf("m" to "pc", "other" to "pc"))
        val replacement = mergeNamedAccounts(old, null, DesktopAccountService.LLM, false,
            setOf("m", "other"), { it }, { it == "m" })
        assertNull(replacement.bundle.models["m"])
        assertEquals("pc", replacement.bundle.models["other"])
        assertEquals(old.accounts, replacement.bundle.accounts)
        val both = mergeNamedAccounts(old, null, DesktopAccountService.LLM, false,
            setOf("m", "other", "fork"), { if (it == "m") "fork" else it }, { it == "m" })
        assertEquals(old.models, both.bundle.models)
        assertNull(both.bundle.models["fork"])
    }
    @Test fun `different named binding creates a model choice even when raw legacy keys agree`() = runBlocking {
        val root = Files.createTempDirectory("ccb-account-choice-")
        try {
            val model = ModelConfig("m", "Model", "https://synthetic.invalid", "", "model", createdAt = 1)
            val entity = backupJson.encodeToJsonElement(ModelConfig.serializer(), model).jsonObject
            val raw = InMemoryDesktopSecretStore().also { it.save(DesktopCredentialKey.ModelApiKey("m"), "SYNTHETIC_SHARED_LEGACY") }
            val old = DesktopAccountStore(raw)
            val id = old.saveAccount(DesktopAccountService.LLM, null, "Main", "Provider", model.baseUrl, "SYNTHETIC_PC")
            old.select(DesktopAccountService.LLM, id, model)
            val phoneModel = JsonObject(entity + ("apiKey" to JsonPrimitive("SYNTHETIC_SHARED_LEGACY")))
            val file = Files.write(root.resolve("synthetic.cbbackup"), CensusWireFixtures.bytes(
                files = mapOf("entities/model_configs/m.json" to phoneModel.toString().toByteArray())))
            DesktopFullBackupStaging.prepare(file, null, Files.createDirectory(root.resolve("work"))).use { phone ->
                DesktopMergeCredentials.capture(mapOf("entities/model_configs/m.json" to entity), phone, old).use { credentials ->
                    assertEquals(setOf("model_configs:m"), credentials.dataConflictKeys)
                }
            }
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    @Test fun `credential-free relocation preserves the protected named account without adding model conflicts`() = runBlocking {
        val root = Files.createTempDirectory("ccb-account-relocation-")
        try {
            val model = ModelConfig("m", "Model", "https://synthetic.invalid", "", "model", createdAt = 1)
            val entity = backupJson.encodeToJsonElement(ModelConfig.serializer(), model).jsonObject
            val old = DesktopAccountStore(InMemoryDesktopSecretStore())
            val id = old.saveAccount(DesktopAccountService.LLM, null, "Main", "Provider", model.baseUrl, "SYNTHETIC_PC")
            old.select(DesktopAccountService.LLM, id, model)
            val file = Files.write(root.resolve("synthetic.cbbackup"), CensusWireFixtures.bytes(
                files = mapOf("entities/model_configs/m.json" to entity.toString().toByteArray())))
            DesktopFullBackupStaging.prepare(file, null, Files.createDirectory(root.resolve("work"))).use { phone ->
                val pc = mapOf("entities/model_configs/m.json" to entity)
                DesktopMergeCredentials.capture(pc, phone, old).use { credentials ->
                    assertTrue(credentials.dataConflictKeys.isEmpty())
                    val preview = DesktopMergePreview.create(pc, phone.entities, emptyMap(), emptyMap(), credentialConflicts = credentials.dataConflictKeys)
                    val selected = preview.select(emptyMap())
                    val plan = DesktopMergeDestinationPlan.create(selected, emptyMap(), emptyMap())
                    val target = Files.createDirectory(root.resolve("target"))
                    val protected = Files.createDirectory(root.resolve("protected"))
                    val ownership = DesktopDataRootOwnership.acquire(DesktopDataRootResolution.Resolved(target,
                        DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json"))) as DesktopDataRootOwnershipResult.Acquired
                    ownership.ownership.use {
                        val authority = DesktopFreshRootAuthority(target, protected, MergeRootSyntheticProtector)
                        val pending = authority.begin()
                        credentials.writeNewRoot(target, authority, pending, plan, selected, emptyMap(), true)
                        assertEquals("SYNTHETIC_PC", DesktopAccountStore(pending.store).hydrate(model).apiKey)
                    }
                }
            }
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    @Test fun `full new-root merge keeps legacy keys and both frozen named identities with model fork`() = runBlocking {
        val base = Files.createTempDirectory("ccb-account-merge-")
        try {
            val model = ModelConfig("m", "Model", "https://synthetic.invalid/v1", "", "synthetic", createdAt = 1)
            val pcModel = backupJson.encodeToJsonElement(ModelConfig.serializer(), model).jsonObject
            val raw = InMemoryDesktopSecretStore().also { it.save(DesktopCredentialKey.ModelApiKey("m"), "SYNTHETIC_PC_LEGACY") }
            val old = DesktopAccountStore(raw)
            val id = old.saveAccount(DesktopAccountService.LLM, null, "Main", "Provider", model.baseUrl, "SYNTHETIC_PC_ACCOUNT")
            old.select(DesktopAccountService.LLM, id, model)
            val incoming = DesktopAccountBundle(accounts = listOf(DesktopApiAccount(id, "Main", "Provider", model.baseUrl, "SYNTHETIC_PHONE_ACCOUNT")),
                models = mapOf("m" to id), legacy = mapOf("m" to "SYNTHETIC_PHONE_LEGACY"))
            val prefs = buildJsonObject { put("desktopLlmAccounts", DesktopAccountStore.encode(incoming, DesktopAccountService.LLM)) }.toString()
            val phoneModel = JsonObject(pcModel + ("apiKey" to JsonPrimitive("SYNTHETIC_PHONE_ACCOUNT")))
            val file = Files.write(base.resolve("synthetic.cbbackup"), CensusWireFixtures.bytes(prefs = prefs,
                files = mapOf("entities/model_configs/m.json" to phoneModel.toString().toByteArray())))
            DesktopFullBackupStaging.prepare(file, null, Files.createDirectory(base.resolve("work"))).use { phone ->
                val pc = mapOf("entities/model_configs/m.json" to pcModel)
                DesktopMergeCredentials.capture(pc, phone, old).use { credentials ->
                    val preview = DesktopMergePreview.create(pc, phone.entities, emptyMap(), emptyMap(), credentialConflicts = credentials.dataConflictKeys)
                    val selected = preview.select(preview.conflicts.associate { it.key to DesktopMergePreview.Choice.BOTH })
                    val plan = DesktopMergeDestinationPlan.create(selected, emptyMap(), emptyMap())
                    val target = Files.createDirectory(base.resolve("target"))
                    val protected = Files.createDirectory(base.resolve("credentials"))
                    val ownership = DesktopDataRootOwnership.acquire(DesktopDataRootResolution.Resolved(target,
                        DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json"))) as DesktopDataRootOwnershipResult.Acquired
                    ownership.ownership.use {
                        val authority = DesktopFreshRootAuthority(target, protected, MergeRootSyntheticProtector)
                        val pending = authority.begin()
                        credentials.writeNewRoot(target, authority, pending, plan, selected,
                            credentials.globalConflicts.associate { it.kind to DesktopMergeCredentials.Choice.KEEP_COMPUTER }, true)
                        val restored = DesktopAccountStore(pending.store)
                        assertEquals("v1", pending.store.load(DesktopCredentialKey.AccountProfilesRequired(DesktopAccountService.LLM)))
                        val fork = plan.identity.value("model_configs", "m")
                        assertNotEquals("m", fork)
                        assertEquals("SYNTHETIC_PC_ACCOUNT", restored.hydrate(model).apiKey)
                        assertEquals("SYNTHETIC_PHONE_ACCOUNT", restored.hydrate(model.copy(id = fork)).apiKey)
                        assertEquals("SYNTHETIC_PC_LEGACY", restored.legacyValue(DesktopCredentialKey.ModelApiKey("m")))
                        assertEquals("SYNTHETIC_PHONE_LEGACY", restored.legacyValue(DesktopCredentialKey.ModelApiKey(fork)))
                        assertEquals("SYNTHETIC_PC_LEGACY", raw.load(DesktopCredentialKey.ModelApiKey("m")))
                    }
                }
            }
        } finally { Files.walk(base).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    @Test fun `selected named accounts round trip through encrypted transport and never reach staged preferences`() = runBlocking {
        val root = Files.createTempDirectory("ccb-named-backup-")
        try {
            val model = ModelConfig("m", "Model", "https://synthetic.invalid/v1", "", "synthetic", createdAt = 1)
            val entity = backupJson.encodeToJsonElement(ModelConfig.serializer(), model).jsonObject
            val source = Files.writeString(root.resolve("model.json"), entity.toString())
            val raw = InMemoryDesktopSecretStore().also { it.save(DesktopCredentialKey.ModelApiKey("m"), "SYNTHETIC_OLD") }
            val store = DesktopAccountStore(raw)
            val llm = store.saveAccount(DesktopAccountService.LLM, null, "Main", "Provider", model.baseUrl, "SYNTHETIC_LLM")
            store.saveAccount(DesktopAccountService.LLM, null, "Unused", "Other", model.baseUrl, "SYNTHETIC_UNSELECTED")
            store.select(DesktopAccountService.LLM, llm, model)
            val fish = store.saveAccount(DesktopAccountService.FISH, null, "Fish", "Fish", "", "SYNTHETIC_FISH")
            store.select(DesktopAccountService.FISH, fish)
            val entities = mapOf("entities/model_configs/m.json" to entity)
            DesktopBackupExportCredentials.capture(store, entities, setOf("models")).use { all ->
                assertEquals(2, DesktopAccountStore.decode(assertNotNull(all.preferences(BackupPreferences()).desktopLlmAccounts), DesktopAccountService.LLM).accounts.size)
            }
            DesktopBackupExportCredentials.capture(store, entities, emptySet()).use { captured ->
                val preferences = captured.preferences(BackupPreferences())
                assertNull(preferences.desktopFishAccounts)
                val bundle = DesktopAccountStore.decode(assertNotNull(preferences.desktopLlmAccounts), DesktopAccountService.LLM)
                assertEquals(listOf(llm), bundle.accounts.map { it.id }); assertEquals("SYNTHETIC_OLD", bundle.legacy["m"])
                val output = root.resolve("synthetic.cbbackup")
                val password = "synthetic-only-password".toCharArray()
                DesktopFullBackupWriter.export(mapOf("entities/model_configs/m.json" to source), output, password.copyOf(), credentials = captured)
                DesktopFullBackupStaging.prepare(output, password.copyOf(), Files.createDirectory(root.resolve("work"))).use { staged ->
                    assertFalse(staged.preferences.toString().contains("SYNTHETIC_"))
                    assertFalse(staged.entities.toString().contains("SYNTHETIC_"))
                    val named = staged.secrets.metadata.single { it.kind == ImportCredentialKind.DESKTOP_LLM_ACCOUNTS }
                    val restored = named.withValue { DesktopAccountStore.decode(assertNotNull(it).concatToString(), DesktopAccountService.LLM) }
                    assertEquals(bundle, restored)
                }
                password.fill('\u0000')
            }
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    @Test fun `merge retains both account identities and remaps only chosen incoming model bindings`() {
        val a = DesktopApiAccount("same", "Main", "Provider", "https://synthetic.invalid", "SYNTHETIC_A")
        val b = a.copy(credential = "SYNTHETIC_B")
        val old = DesktopAccountBundle(accounts = listOf(a), models = mapOf("m" to "same"))
        val phone = DesktopAccountBundle(accounts = listOf(b), models = mapOf("m" to "same"), legacy = mapOf("m" to "SYNTHETIC_PHONE_OLD"))
        val result = mergeNamedAccounts(old, phone, DesktopAccountService.LLM, false, setOf("m", "fork"), { "fork" }, { true })
        assertEquals(2, result.bundle.accounts.size)
        assertEquals("same", result.bundle.models["m"])
        assertNotEquals("same", result.bundle.models["fork"])
        assertEquals("SYNTHETIC_PHONE_OLD", result.legacy["fork"])
        assertEquals("SYNTHETIC_B", result.bundle.accounts.single { it.id == result.bundle.models["fork"] }.credential)
    }
}
