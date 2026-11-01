@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.image.NovelAiAccountUsage
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.WindowsSafeModelStorageKeyPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class DesktopStudioCredentialRecoveryTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    @Test fun `identity read distinguishes unavailable storage from absent integration and preserves cancellation`() {
        assertEquals(DesktopStudioAccountRead.Available(null), readDesktopStudioAccountIdentity { null })
        assertEquals(DesktopStudioAccountRead.Unavailable, readDesktopStudioAccountIdentity { error(PRIVATE_ERROR) })
        assertFailsWith<CancellationException> { readDesktopStudioAccountIdentity { throw CancellationException("synthetic-stop") } }
        val failure = assertFailsWith<DesktopStudioCredentialReadException> { studioCredentialRead { error(PRIVATE_ERROR) } }
        assertNull(failure.cause)
        assertFalse(failure.toString().contains(PRIVATE_ERROR))
    }

    @Test fun `model catalog failure projection preserves cancellation and contains no private error`() = runBlocking {
        assertFailsWith<CancellationException> { readDesktopStudioModels { throw CancellationException("synthetic-stop") } }
        val unknown = readDesktopStudioModels { error(PRIVATE_ERROR) }
        assertEquals(DesktopStudioModelCatalog.Unavailable(DesktopStudioModelReadFailure.UNAVAILABLE), unknown)
        val protected = readDesktopStudioModels {
            throw DesktopSecretStoreException(DesktopSecretStoreFailureKind.PROTECTION_FAILURE, PRIVATE_ERROR)
        }
        assertEquals(DesktopStudioModelCatalog.Unavailable(DesktopStudioModelReadFailure.PROTECTED_STORAGE), protected)
        assertFalse(unknown.toString().contains(PRIVATE_ERROR))
        assertFalse(protected.toString().contains(PRIVATE_ERROR))
    }

    @Test fun `original plaintext model refusal stays in Studio recovery without changing its source`() = runBlocking(edt) {
        withFixture { f ->
            val model = ModelConfig("sf12-refused-model", "Synthetic refused model", "https://fixture.invalid/v1",
                "SYNTHETIC_UNSAFE_JSON_KEY", "synthetic", createdAt = 1)
            val key = WindowsSafeModelStorageKeyPolicy.storageKey(model.id)
            f.container.jsonFileStorage.saveEntity("model_configs", key, model, ModelConfig.serializer())
            val before = f.raw.snapshot()
            assertFailsWith<UnsafeDesktopPlaintextCredentialException> { f.container.modelRepository.initialize() }
            assertEquals(DesktopStudioModelCatalog.Unavailable(DesktopStudioModelReadFailure.UNSAFE_CREDENTIALS), f.controller.availableModels())
            assertEquals(model, f.container.jsonFileStorage.loadEntity("model_configs", key, ModelConfig.serializer()))
            assertEquals(before, f.raw.snapshot())
            assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.container.dataOperationCoordinator.state)
            val originalFile = f.container.appDataRoot.resolve("entities/model_configs/$key.json")
            val originalBytes = Files.readAllBytes(originalFile)
            for (width in listOf(360, 1100)) {
                val release = CompletableDeferred<Unit>()
                val started = CompletableDeferred<Unit>()
                val task = f.container.taskRuntime.launchNovelAi("synthetic-model-recovery-work") { started.complete(Unit); release.await() }
                withTimeout(5_000) { started.await() }
                val activity = DesktopModalActivity()
                var modelSettingsRequests = 0
                var dataRequests = 0
                val scene = f.scene(width, activity, { modelSettingsRequests++ }, { dataRequests++ })
                try {
                    scene.until { has("关闭模型提示") }
                    assertEquals(1, activity.count)
                    assertFalse(scene.has("NovelAI 账户读取失败"))
                    assertTrue(scene.texts().none { "SYNTHETIC_UNSAFE_JSON_KEY" in it || "sf12-refused-model" in it })
                    scene.shot("studio-model-problem-$width.png")
                    for (label in listOf("重试模型读取", "模型设置", "备份与恢复")) {
                        val text = scene.nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == label } == true &&
                            it.config.contains(SemanticsActions.GetTextLayoutResult) }
                        val layouts = mutableListOf<TextLayoutResult>()
                        assertTrue(text.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
                        assertTrue(layouts.isNotEmpty() && layouts.all { it.lineCount == 1 }, "$label must remain a single text line at width $width")
                        assertTrue(text.boundsInRoot.left >= 16f && text.boundsInRoot.right <= width - 16f)
                    }
                    scene.click("关闭模型提示")
                    scene.until { !has("关闭模型提示") && activity.count == 0 }
                    assertEquals(DesktopTaskStatus.RUNNING, f.container.taskRuntime.tasks.value.single { it.taskId == task }.status)
                    assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.container.dataOperationCoordinator.state)
                    assertTrue(scene.nodes().any { it.config.contains(SemanticsActions.SetText) })
                    scene.click("查看模型读取问题")
                    scene.click("重试模型读取")
                    scene.until { has("关闭模型提示") }
                    scene.click("模型设置")
                    assertEquals(1, modelSettingsRequests)
                    scene.until { !has("关闭模型提示") }
                    scene.click("查看模型读取问题")
                    scene.click("备份与恢复")
                    assertEquals(1, dataRequests)
                    assertContentEquals(originalBytes, Files.readAllBytes(originalFile))
                    assertEquals(before, f.raw.snapshot())
                    assertFailsWith<UnsafeDesktopPlaintextCredentialException> { f.container.modelRepository.initialize() }
                } finally { release.complete(Unit); scene.close() }
            }
            val recoveryScene = f.scene(1100, DesktopModalActivity())
            try {
                recoveryScene.until { has("关闭模型提示") }
                // Explicit synthetic external repair for retry only; the product UI never rewrites this file.
                f.container.jsonFileStorage.saveEntity("model_configs", key, model.copy(apiKey = ""), ModelConfig.serializer())
                recoveryScene.click("重试模型读取")
                recoveryScene.until { !has("关闭模型提示") && !has("查看模型读取问题") }
                recoveryScene.shot("studio-model-recovered-1100.png")
            } finally { recoveryScene.close() }
            val recovered = assertIs<DesktopStudioModelCatalog.Available>(f.controller.availableModels())
            assertTrue(recovered.models.any { it.id == model.id && it.apiKey.isBlank() })
            assertEquals(before, f.raw.snapshot())
        }
    }

    @Test fun `credential dialog close settings and retry retain workspace work and all protected slots`() = runBlocking(edt) {
        for ((mode, width) in listOf(Fault.PROTECTION to 360, Fault.MISSING to 1100)) withFixture { f ->
            val before = f.raw.snapshot()
            val writes = f.raw.naiWrites.get()
            val draft = f.controller.draft.value
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val task = f.container.taskRuntime.launchNovelAi("synthetic-held-work") { started.complete(Unit); release.await() }
            withTimeout(5_000) { started.await() }
            f.raw.fault = mode
            val activity = DesktopModalActivity()
            val scene = f.scene(width, activity)
            try {
                scene.until { has("关闭账户提示") }
                assertEquals(1, activity.count)
                scene.shot("studio-credential-problem-$width.png")
                assertNoLeak(scene)
                scene.click("关闭账户提示")
                scene.until { !has("关闭账户提示") && activity.count == 0 }
                assertEquals(DesktopTaskStatus.RUNNING, f.container.taskRuntime.tasks.value.single { it.taskId == task }.status)
                assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.container.dataOperationCoordinator.state)
                assertTrue(scene.nodes().any { it.config.contains(SemanticsActions.SetText) })
                scene.click("刷新")
                scene.until { has("关闭账户提示") }
                scene.click("API 账户设置")
                scene.until { f.container.apiAccountController.opened.value == DesktopAccountService.NOVELAI && has("API 命名账户") }
                assertFalse(scene.has("关闭账户提示"))
                assertNoLeak(scene)
                assertEquals(writes, f.raw.naiWrites.get())
                scene.click("关闭")
                scene.until { f.container.apiAccountController.opened.value == null }
                scene.click("刷新")
                scene.until { has("关闭账户提示") }
                f.raw.fault = Fault.NONE
                scene.click("重试读取")
                scene.until { !has("关闭账户提示") && f.controller.state.value.account != null &&
                    texts().any { it.startsWith("当前账户：") } }
                assertEquals(2, f.providerReads.get(), "only initial load and explicit successful retry call the fake provider")
                assertEquals(0, f.raw.legacyReadsWhileBroken.get())
                assertEquals(before, f.raw.snapshot())
                assertEquals(writes, f.raw.naiWrites.get())
                assertEquals(draft, f.controller.draft.value)
                assertEquals(DesktopTaskStatus.RUNNING, f.container.taskRuntime.tasks.value.single { it.taskId == task }.status)
                scene.shot("studio-credential-recovered-$width.png")
            } finally { release.complete(Unit); scene.close() }
        }
    }

    @Test fun `existing Fish account draft is not covered switched or discarded by Studio recovery`() = runBlocking(edt) {
        withFixture { f ->
            f.container.apiAccountController.opened.value = DesktopAccountService.FISH
            val scene = f.scene(1100, DesktopModalActivity())
            try {
                scene.until { has("添加账户") }
                scene.click("添加账户")
                val fields = scene.accountFields()
                assertEquals(3, fields.size)
                fields.first().config[SemanticsActions.SetText].action!!(AnnotatedString("未保存的 Fish 草稿"))
                fields[1].config[SemanticsActions.SetText].action!!(AnnotatedString("Fish test provider"))
                fields[2].config[SemanticsActions.SetText].action!!(AnnotatedString("FAKE_UNSAVED_FISH"))
                scene.frames()
                val draftFields = scene.accountFields().map { it.config.getOrNull(SemanticsProperties.EditableText) }
                val before = f.raw.snapshot()
                f.raw.fault = Fault.PROTECTION
                f.container.apiAccounts.saveAccount(DesktopAccountService.FISH, null, "其他已保存账户", "Fish Audio", "", "FAKE_OTHER_FISH")
                scene.until { f.controller.state.value.accountUi.error == STUDIO_ACCOUNT_READ_FAILURE }
                assertEquals(DesktopAccountService.FISH, f.container.apiAccountController.opened.value)
                assertFalse(scene.has("关闭账户提示"))
                assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "未保存的 Fish 草稿" })
                assertEquals(draftFields, scene.accountFields().map { it.config.getOrNull(SemanticsProperties.EditableText) })
                scene.click("关闭")
                scene.until { has("放弃当前账户编辑？") }
                scene.click("继续编辑")
                assertEquals(DesktopAccountService.FISH, f.container.apiAccountController.opened.value)
                assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "未保存的 Fish 草稿" })
                assertFalse(scene.has("关闭账户提示"))
                assertEquals(before, f.raw.snapshot())
                assertEquals(0, f.raw.legacyReadsWhileBroken.get())
            } finally { scene.close() }
        }
    }

    private enum class Fault { NONE, PROTECTION, MISSING }
    private class FaultStore : DesktopSecretStore {
        val delegate = InMemoryDesktopSecretStore()
        @Volatile var fault = Fault.NONE
        val naiWrites = AtomicInteger()
        val legacyReadsWhileBroken = AtomicInteger()
        private val keys = listOf(DesktopCredentialKey.NovelAiToken, DesktopCredentialKey.AccountProfiles(DesktopAccountService.NOVELAI),
            DesktopCredentialKey.AccountProfilesRequired(DesktopAccountService.NOVELAI))
        override fun load(key: DesktopCredentialKey): String? {
            if (fault != Fault.NONE && key == DesktopCredentialKey.NovelAiToken) legacyReadsWhileBroken.incrementAndGet()
            if (key == DesktopCredentialKey.AccountProfiles(DesktopAccountService.NOVELAI)) when (fault) {
                Fault.PROTECTION -> throw DesktopSecretStoreException(DesktopSecretStoreFailureKind.PROTECTION_FAILURE, PRIVATE_ERROR)
                Fault.MISSING -> return null
                Fault.NONE -> Unit
            }
            return delegate.load(key)
        }
        override fun save(key: DesktopCredentialKey, secret: String) { if (key in keys) naiWrites.incrementAndGet(); delegate.save(key, secret) }
        override fun delete(key: DesktopCredentialKey) { if (key in keys) naiWrites.incrementAndGet(); delegate.delete(key) }
        fun snapshot() = keys.associateWith(delegate::load)
    }
    private class Fixture {
        val root = Files.createTempDirectory("studio-credential-recovery-")
        val raw = FaultStore()
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
            root.resolve("bootstrap.json")), secretStoreFactory = { raw })
        val providerReads = AtomicInteger()
        val controller = DesktopNovelAiStudioController(container.jsonFileStorage, container.characterResourceStore,
            UnconfiguredDesktopFilePicker, container.dataOperationCoordinator, {}, container.novelAiGenerationRuntime,
            DesktopNovelAiGuidance(container.appDataRoot, container.characterResourceStore, container.desktopSecretStore),
            container.taskRuntime, container.novelAiInfrastructure, container.settingsRepository, container.effectiveModelResolver,
            container.characterRepository, { providerReads.incrementAndGet(); NovelAiAccountUsage(100, 3, true, 50.0, false) },
            { !container.desktopSecretStore.load(DesktopCredentialKey.NovelAiToken).isNullOrBlank() },
            credentialIdentity = { container.apiAccounts.identity(DesktopAccountService.NOVELAI) },
            freezeCredential = { container.apiAccounts.captureCall(DesktopCredentialKey.NovelAiToken, DesktopAccountService.NOVELAI) })
        fun scene(width: Int, activity: DesktopModalActivity, onModelSettings: () -> Unit = {},
            onDataSettings: () -> Unit = {}) = ImageComposeScene(width, 900, coroutineContext = edtDispatcher) {
            CompositionLocalProvider(LocalDesktopApiAccounts provides container.apiAccountController, LocalDesktopModalActivity provides activity) {
                DesktopNovelAiStudioPanel(controller, onDataSettings = onDataSettings, onModelSettings = { onModelSettings() })
                DesktopApiAccountDialog()
            }
        }
        private val edtDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
        }
    }
    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try {
            f.raw.save(DesktopCredentialKey.NovelAiToken, FAKE_KEY)
            f.container.apiAccountController.adoptExisting(DesktopAccountService.NOVELAI)
            f.controller.load()
            block(f)
        } finally { f.container.close(); f.root.toFile().deleteRecursively() }
    }
    private suspend fun ImageComposeScene.frames() { repeat(4) { render().close(); yield(); delay(15) } }
    private suspend fun ImageComposeScene.until(condition: ImageComposeScene.() -> Boolean) = withTimeout(15_000) {
        do { frames() } while (!condition())
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.matches(label: String): Boolean = config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
        config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true || children.any { it.matches(label) }
    private fun ImageComposeScene.has(label: String) = nodes().any { it.matches(label) }
    private fun ImageComposeScene.texts() = nodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
    private fun ImageComposeScene.accountFields(): List<SemanticsNode> {
        fun editable(n: SemanticsNode): List<SemanticsNode> =
            (if (n.config.contains(SemanticsActions.SetText)) listOf(n) else emptyList()) + n.children.flatMap(::editable)
        return semanticsOwners.map { it.rootSemanticsNode }.filter { it.matches("API 命名账户") }
            .map(::editable).minBy { it.size }.sortedBy { it.boundsInRoot.top }
    }
    private suspend fun ImageComposeScene.click(label: String) {
        val n = nodes().first { it.matches(label) && it.config.contains(SemanticsActions.OnClick) }
        assertFalse(n.config.contains(SemanticsProperties.Disabled))
        assertTrue(n.config[SemanticsActions.OnClick].action!!.invoke()); frames()
    }
    private fun assertNoLeak(scene: ImageComposeScene) {
        assertTrue(scene.texts().none { FAKE_KEY in it || PRIVATE_ERROR in it })
    }
    private fun ImageComposeScene.shot(name: String) {
        val target = System.getenv("CCB_SELECTED_FIX_REVIEW_DIR") ?: return
        val file = Path.of(target).resolve(name); Files.createDirectories(file.parent)
        render().use { image -> image.encodeToData()!!.use { Files.write(file, it.bytes) } }
    }
    private companion object {
        const val FAKE_KEY = "SYNTHETIC_NAI_SF12_CREDENTIAL"
        const val PRIVATE_ERROR = "SYNTHETIC_RAW_PROTECTED_STORE_FAILURE"
    }
}
