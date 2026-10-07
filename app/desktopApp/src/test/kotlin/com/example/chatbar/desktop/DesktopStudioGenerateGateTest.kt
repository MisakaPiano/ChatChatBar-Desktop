package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import kotlin.test.*

class DesktopStudioGenerateGateTest {
    private val valid = NovelAiStudioDraft(basePrompt = "inline landscape")
    private val opus = NovelAiAccountUsage(100, 3, true, 50.0, false)
    private val configured = DesktopNovelAiStudioState(ready = true, credentialConfigured = true)

    @Test fun `configured known account retains shared free allowance and paid costs`(): Unit = runBlocking {
        fixture { f ->
            f.secrets.save(DesktopCredentialKey.NovelAiToken, "fake-only")
            val controller = f.controller { opus }
            controller.load()
            val state = controller.state.value
            assertTrue(state.credentialConfigured)
            assertEquals(opus, state.account)
            assertTrue(desktopCanGenerate(state, valid, false))
            assertNull(state.accountUi.error)
            for ((model, kind) in listOf(NovelAiImageModel.V4_5_FULL to NovelAiGenerationChargeKind.FREE,
                NovelAiImageModel.V5_FULL to NovelAiGenerationChargeKind.V5_ALLOWANCE)) {
                val cost = NovelAiImageCostEstimator.estimate(NovelAiGenerationSettings(model = model), state.account)
                assertEquals(kind, cost.kind)
                assertEquals("生成免费", desktopGenerateLabel(false, state.credentialConfigured, cost))
            }
            val paid = NovelAiImageCostEstimator.estimate(NovelAiGenerationSettings(count = 2), state.account)
            assertEquals(NovelAiGenerationChargeKind.ANLAS, paid.kind)
            assertTrue(desktopGenerateLabel(false, true, paid).contains("${paid.anlas} Anlas"))
        }
    }

    @Test fun `account fetch failure retains eligibility warning and conservative cost`(): Unit = runBlocking {
        fixture { f ->
            f.secrets.save(DesktopCredentialKey.NovelAiToken, "fake-only")
            val controller = f.controller { error("raw private provider detail") }
            controller.load()
            val state = controller.state.value
            assertTrue(state.credentialConfigured)
            assertNull(state.account)
            assertFalse(state.accountUi.loading)
            assertEquals("账户信息获取失败；免费资格未确认", state.accountUi.error)
            assertFalse(state.toString().contains("raw private provider detail"))
            assertTrue(desktopCanGenerate(state, valid, false))
            assertConservative(state)
        }
    }

    @Test fun `initial loading with credential enables valid input without a false free claim`(): Unit = runBlocking {
        fixture { f ->
            f.secrets.save(DesktopCredentialKey.NovelAiToken, "fake-only")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val controller = f.controller { entered.complete(Unit); release.await(); opus }
            val work = async { controller.load() }
            try {
                withTimeout(10_000) { entered.await() }
                val state = controller.state.value
                assertTrue(state.ready && state.credentialConfigured && state.accountUi.loading)
                assertNull(state.account)
                assertTrue(desktopCanGenerate(state, valid, false))
                assertConservative(state)
            } finally { release.complete(Unit); work.await() }
            assertEquals(opus, controller.state.value.account)
        }
    }

    @Test fun `refresh rechecks credential save and removal without account calls when missing`(): Unit = runBlocking {
        fixture { f ->
            var calls = 0
            val controller = f.controller { calls++; opus }
            controller.load()
            assertMissing(controller.state.value)
            assertEquals(0, calls)
            // Exercise the production container injection too, with an empty in-memory SecretStore.
            f.container.novelAiStudioController.refreshAccount()
            assertMissing(f.container.novelAiStudioController.state.value)
            f.secrets.save(DesktopCredentialKey.NovelAiToken, "fake-only")
            controller.refreshAccount()
            assertEquals(1, calls)
            assertTrue(desktopCanGenerate(controller.state.value, valid, false))
            f.secrets.delete(DesktopCredentialKey.NovelAiToken)
            controller.refreshAccount()
            assertEquals(1, calls)
            assertMissing(controller.state.value)
        }
    }

    @Test fun `blank base prompt is ineligible`() {
        for (text in listOf("", " ", "\n\t")) assertFalse(desktopCanGenerate(configured, valid.copy(basePrompt = text), false))
        assertTrue(desktopCanGenerate(configured, valid, false))
    }

    @Test fun `active size validation follows shared authority`() {
        for (model in NovelAiImageModel.entries) {
            val draft = valid.copy(selectedModel = model)
            val invalid = draft.withActiveSettings(draft.activeSettings.copy(customWidth = 2048, customHeight = 2048))
            assertNotNull(invalid.activeSettings.sizeValidationError())
            assertFalse(desktopCanGenerate(configured, invalid, false))
            assertTrue(desktopCanGenerate(configured, draft, false))
        }
    }

    @Test fun `guidance validity follows shared model rules without clearing references`() {
        val missingBase = valid.copy(imageGuidance = NovelAiImageGuidanceDraft(action = NovelAiGenerationAction.IMAGE_TO_IMAGE))
        assertNotNull(missingBase.imageGuidance.validationError(missingBase.selectedModel))
        assertFalse(desktopCanGenerate(configured, missingBase, false))
        val missingReference = valid.copy(imageGuidance = NovelAiImageGuidanceDraft(referenceMode = NovelAiReferenceMode.PRECISE))
        assertFalse(desktopCanGenerate(configured, missingReference, false))
        val pausedReference = missingReference.copy(selectedModel = NovelAiImageModel.V5_FULL)
        assertNull(pausedReference.imageGuidance.validationError(pausedReference.selectedModel))
        assertTrue(desktopCanGenerate(configured, pausedReference, false))
    }

    @Test fun `busy always presents Stop independent of account or credential state`() {
        for (state in listOf(configured, configured.copy(credentialConfigured = false),
            configured.copy(accountUi = com.example.chatbar.ui.imageprompt.NovelAiAccountUiState(loading = true)),
            configured.copy(accountUi = com.example.chatbar.ui.imageprompt.NovelAiAccountUiState(error = "failed")))) {
            assertFalse(desktopCanGenerate(state, valid, true))
            assertEquals("停止当前任务 · 2/4", desktopGenerateLabel(true, state.credentialConfigured, null, "2/4"))
        }
        val panel = Files.readString(Path.of("src/main/kotlin/com/example/chatbar/desktop/DesktopNovelAiStudioPanel.kt"))
        assertTrue(panel.contains("enabled = busy || desktopCanGenerate(state, d, busy)"))
        assertTrue(panel.contains("if (busy) controller.stop() else scope.launch { controller.generate() }"))
        assertFalse(panel.contains("state.account != null"))
    }

    @Test fun `credential disappearing before runtime launch rejects before transport or persistence`(): Unit = runBlocking {
        fixture { f ->
            f.secrets.save(DesktopCredentialKey.NovelAiToken, "fake-only")
            val controller = f.controller { opus }
            controller.load()
            assertTrue(desktopCanGenerate(controller.state.value, valid, false))
            var requests = 0
            var writes = 0
            val client = OkHttpClient.Builder().addInterceptor { requests++; error("No network permitted") }.build()
            val runtime = DesktopNovelAiGenerationRuntime(f.secrets, { _, _ -> writes++; error("Must not persist") }, client)
            f.secrets.delete(DesktopCredentialKey.NovelAiToken)
            val failure = assertFailsWith<DesktopNovelAiRequestException> { runtime.generate(valid, maxRateLimitRetries = 0) }
            assertTrue(failure.message.orEmpty().contains("安全保存 NovelAI 凭据"))
            assertEquals(0, requests)
            assertEquals(0, writes)
        }
    }

    @Test fun `unreadable credential fails closed with a safe error and no account call`(): Unit = runBlocking {
        fixture { f ->
            var calls = 0
            val controller = f.controller(presence = { error("private secret-store diagnostic") }) { calls++; opus }
            controller.load()
            val state = controller.state.value
            assertFalse(state.credentialConfigured)
            assertFalse(desktopCanGenerate(state, valid, false))
            assertNull(state.account)
            assertEquals(0, calls)
            assertTrue(state.accountUi.error.orEmpty().contains("安全凭据"))
            assertFalse(state.toString().contains("private secret-store diagnostic"))
        }
    }

    @Test fun `refresh failure after known account does not retain a false free cost`(): Unit = runBlocking {
        fixture { f ->
            f.secrets.save(DesktopCredentialKey.NovelAiToken, "fake-only")
            var fail = false
            val controller = f.controller { if (fail) error("fixture unavailable") else opus }
            controller.load()
            fail = true
            controller.refreshAccount()
            assertTrue(desktopCanGenerate(controller.state.value, valid, false))
            assertNull(controller.state.value.account)
            assertConservative(controller.state.value)
        }
    }

    @Test fun `unknown account cost retains shared encoding and extra vibe details`() {
        val guidance = NovelAiImageGuidanceDraft(referenceMode = NovelAiReferenceMode.VIBE,
            vibes = List(5) { NovelAiVibeReferenceDraft(encodedVibe = "inline-fixture") })
        val cost = NovelAiImageCostEstimator.estimate(valid.activeSettings, null, guidance, vibeCacheMisses = 2)
        assertEquals(NovelAiGenerationChargeKind.ANLAS, cost.kind)
        assertTrue(cost.encodingAnlas > 0 && cost.extraVibeAnlas > 0)
        val label = desktopGenerateLabel(false, true, cost)
        assertTrue(label.contains("${cost.anlas} Anlas"))
        assertTrue(label.contains("含编码 ${cost.encodingAnlas}"))
        assertTrue(label.contains("含额外 Vibe ${cost.extraVibeAnlas}"))
        assertFalse(label.contains("免费"))
    }

    @Test fun `readiness and history application block new generation only`() {
        assertFalse(desktopCanGenerate(configured.copy(ready = false), valid, false))
        assertFalse(desktopCanGenerate(configured.copy(applyingHistory = true), valid, false))
        assertTrue(desktopCanGenerate(configured, valid, false))
    }

    private fun assertConservative(state: DesktopNovelAiStudioState) {
        for (model in NovelAiImageModel.entries) {
            val cost = NovelAiImageCostEstimator.estimate(NovelAiGenerationSettings(model = model), state.account)
            assertEquals(NovelAiGenerationChargeKind.ANLAS, cost.kind)
            assertTrue(cost.anlas > 0)
            assertEquals("生成消耗 ${cost.anlas} Anlas", desktopGenerateLabel(false, state.credentialConfigured, cost))
        }
    }

    private fun assertMissing(state: DesktopNovelAiStudioState) {
        assertFalse(state.credentialConfigured)
        assertNull(state.accountUi.usage)
        assertFalse(state.accountUi.loading)
        assertEquals("未配置 Token", state.accountUi.error)
        assertFalse(desktopCanGenerate(state, valid, false))
        assertEquals("未配置 Token", desktopGenerateLabel(false, state.credentialConfigured, null))
    }

    private class Fixture {
        val root = Files.createTempDirectory("p7-generate-gate-")
        val secrets = InMemoryDesktopSecretStore()
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")), secretStoreFactory = { secrets })
        fun controller(presence: suspend () -> Boolean = { !secrets.load(DesktopCredentialKey.NovelAiToken).isNullOrBlank() },
            account: suspend () -> NovelAiAccountUsage) = DesktopNovelAiStudioController(
            container.jsonFileStorage, container.characterResourceStore, UnconfiguredDesktopFilePicker,
            container.dataOperationCoordinator, {}, container.novelAiGenerationRuntime,
            DesktopNovelAiGuidance(container.appDataRoot, container.characterResourceStore, secrets), container.taskRuntime,
            container.novelAiInfrastructure, container.settingsRepository, container.effectiveModelResolver,
            container.characterRepository, account, presence)
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.container.close(); fixture.root.toFile().deleteRecursively() }
    }
}
