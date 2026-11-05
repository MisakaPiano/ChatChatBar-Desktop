package com.example.chatbar.desktop.security

import com.example.chatbar.data.local.entity.ModelConfig
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class DesktopAccountStoreTest {
    @Test fun `existing main keys become ordinary renameable accounts and deselection never revives the retained raw key`() {
        for (service in DesktopAccountService.entries) {
            val key = when (service) {
                DesktopAccountService.LLM -> DesktopCredentialKey.SiliconFlowApiKey
                DesktopAccountService.NOVELAI -> DesktopCredentialKey.NovelAiToken
                DesktopAccountService.FISH -> DesktopCredentialKey.FishAudioApiKey
            }
            val raw = InMemoryDesktopSecretStore().also { it.save(key, "SYNTHETIC_ORIGINAL") }
            val store = DesktopAccountStore(raw)
            store.adoptExistingDefault(service)
            val account = store.accounts(service).single()
            assertEquals(account.id, store.selected(service))
            store.saveAccount(service, account.id, "Renamed main", account.provider, account.endpoint, null)
            store.adoptExistingDefault(service)
            assertEquals("Renamed main", store.accounts(service).single().name)
            assertEquals("SYNTHETIC_ORIGINAL", store.load(key))
            assertFails { store.deleteAccount(service, account.id) }
            if (service == DesktopAccountService.LLM) store.selectDefaultLlm(null) else store.select(service, null)
            assertNull(store.load(key))
            assertEquals("SYNTHETIC_ORIGINAL", raw.load(key))
            store.deleteAccount(service, account.id)
            store.adoptExistingDefault(service)
            assertTrue(store.accounts(service).isEmpty())
        }
    }
    private val model = ModelConfig("m", "Model", "https://synthetic.invalid/v1", "", "synthetic-model", createdAt = 1)
    @Test fun `missing protected account bundle refuses legacy fallback for every service`() {
        for (service in DesktopAccountService.entries) {
            val raw = InMemoryDesktopSecretStore()
            val key = when (service) {
                DesktopAccountService.LLM -> DesktopCredentialKey.ModelApiKey(model.id)
                DesktopAccountService.NOVELAI -> DesktopCredentialKey.NovelAiToken
                DesktopAccountService.FISH -> DesktopCredentialKey.FishAudioApiKey
            }
            raw.save(key, "SYNTHETIC_LEGACY")
            val store = DesktopAccountStore(raw)
            val id = store.saveAccount(service, null, "Named", "Provider", model.baseUrl, "SYNTHETIC_NAMED")
            store.select(service, id, if (service == DesktopAccountService.LLM) model else null)
            raw.delete(DesktopCredentialKey.AccountProfiles(service))
            assertFails { store.load(key) }
            assertFails { store.exportBundle(service, setOf(model.id)) }
            assertEquals("SYNTHETIC_LEGACY", raw.load(key))
            assertEquals("v1", raw.load(DesktopCredentialKey.AccountProfilesRequired(service)))
        }
    }
    @Test fun `failed first account save rolls back required marker and retains original key`() {
        val raw = InMemoryDesktopSecretStore()
        raw.save(DesktopCredentialKey.FishAudioApiKey, "SYNTHETIC_LEGACY")
        val failing = object : DesktopSecretStore by raw {
            override fun save(key: DesktopCredentialKey, secret: String) {
                if (key is DesktopCredentialKey.AccountProfiles) error("synthetic write failure")
                raw.save(key, secret)
            }
        }
        val store = DesktopAccountStore(failing)
        assertFails { store.saveAccount(DesktopAccountService.FISH, null, "Fish", "Fish Audio", "", "SYNTHETIC_NAMED") }
        assertNull(raw.load(DesktopCredentialKey.AccountProfilesRequired(DesktopAccountService.FISH)))
        assertEquals("SYNTHETIC_LEGACY", store.load(DesktopCredentialKey.FishAudioApiKey))
    }
    @Test fun `three independent account selections preserve legacy credentials and frozen model identity`() {
        val raw = InMemoryDesktopSecretStore()
        raw.save(DesktopCredentialKey.ModelApiKey("m"), "SYNTHETIC_LEGACY_LLM")
        raw.save(DesktopCredentialKey.NovelAiToken, "SYNTHETIC_LEGACY_NAI")
        val store = DesktopAccountStore(raw)
        val a = store.saveAccount(DesktopAccountService.LLM, null, "Main", "Actual provider", model.baseUrl, "SYNTHETIC_A")
        val b = store.saveAccount(DesktopAccountService.LLM, null, "Backup", "Other provider", model.baseUrl, "SYNTHETIC_B")
        val fish = store.saveAccount(DesktopAccountService.FISH, null, "Fish", "Fish Audio", "", "SYNTHETIC_FISH")
        val nai = store.saveAccount(DesktopAccountService.NOVELAI, null, "Drawing", "NovelAI", "", "SYNTHETIC_NAI")
        store.select(DesktopAccountService.LLM, a, model)
        val admitted = store.hydrate(model)
        store.select(DesktopAccountService.FISH, fish); store.select(DesktopAccountService.NOVELAI, nai)
        val naiIdentity = store.identity(DesktopAccountService.NOVELAI)
        store.select(DesktopAccountService.LLM, b, model)
        assertEquals(naiIdentity, store.identity(DesktopAccountService.NOVELAI))
        assertEquals("SYNTHETIC_A", admitted.apiKey); assertEquals("Actual provider", admitted.credentialAccount?.provider)
        assertEquals("SYNTHETIC_B", store.hydrate(model).apiKey)
        assertEquals("SYNTHETIC_FISH", store.load(DesktopCredentialKey.FishAudioApiKey))
        assertEquals("SYNTHETIC_NAI", store.load(DesktopCredentialKey.NovelAiToken))
        assertEquals("SYNTHETIC_LEGACY_NAI", raw.load(DesktopCredentialKey.NovelAiToken))
        assertFails { store.requireSameAccount(admitted) }
        assertFails { store.deleteAccount(DesktopAccountService.LLM, b) }
        store.select(DesktopAccountService.LLM, null, model)
        assertEquals("SYNTHETIC_LEGACY_LLM", store.hydrate(model).apiKey)
        store.deleteAccount(DesktopAccountService.LLM, b)
        val serialized = Json.encodeToString(ModelConfig.serializer(), admitted)
        assertFalse(serialized.contains("credentialAccount")); assertFalse(serialized.contains("Actual provider"))
    }
    @Test fun `incompatible endpoints and malformed account registry refuse without fallback or overwriting legacy`() {
        val raw = InMemoryDesktopSecretStore(); raw.save(DesktopCredentialKey.ModelApiKey("m"), "SYNTHETIC_LEGACY")
        val store = DesktopAccountStore(raw)
        val id = store.saveAccount(DesktopAccountService.LLM, null, "Account", "Provider", "https://other.invalid", "SYNTHETIC_KEY")
        assertFails { store.select(DesktopAccountService.LLM, id, model) }
        raw.save(DesktopCredentialKey.AccountProfiles(DesktopAccountService.LLM), "malformed")
        assertFails { store.hydrate(model) }
        assertEquals("SYNTHETIC_LEGACY", raw.load(DesktopCredentialKey.ModelApiKey("m")))
    }
    @Test fun `coroutine credential snapshot remains fixed across child work and later selection`() = runBlocking {
        val raw = InMemoryDesktopSecretStore(); val store = DesktopAccountStore(raw)
        val a = store.saveAccount(DesktopAccountService.NOVELAI, null, "A", "NovelAI", "", "SYNTHETIC_A")
        val b = store.saveAccount(DesktopAccountService.NOVELAI, null, "B", "NovelAI", "", "SYNTHETIC_B")
        val key = DesktopCredentialKey.NovelAiToken
        store.select(DesktopAccountService.NOVELAI, a)
        val frozen = DesktopCredentialCall(store, key, store.identity(DesktopAccountService.NOVELAI), store.load(key))
        withContext(frozen) {
            store.select(DesktopAccountService.NOVELAI, b)
            assertEquals("SYNTHETIC_A", async(Dispatchers.IO) { store.credentialForCall(key) }.await())
        }
        assertEquals("SYNTHETIC_B", store.credentialForCall(key))
        withContext(DesktopCredentialCall(store, key, "missing", null)) {
            assertNull(store.credentialForCall(key)) // A missing admitted key never borrows a newer key.
        }
    }
}
