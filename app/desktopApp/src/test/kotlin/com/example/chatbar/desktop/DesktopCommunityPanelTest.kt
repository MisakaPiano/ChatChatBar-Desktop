@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.*
import com.example.chatbar.domain.community.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import kotlin.test.*

class DesktopCommunityPanelTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `actual bilingual community panel preserves preview import conflict and explicit publication boundaries`() = runBlocking(edt) {
        for ((width, language) in listOf(420 to DesktopUiLanguage.ZH_CN, 1100 to DesktopUiLanguage.EN)) {
            val parent = Files.createTempDirectory("community-ui-")
            val secrets = InMemoryDesktopSecretStore()
            val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(Files.createDirectory(parent.resolve("data")),
                DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")), secretStoreFactory = { secrets },
                bundledAssets = { """{"entries":[]}""".toByteArray() })
            try {
                val source = PackagedCharacterCard(name = "Synthetic local", greeting = "Local greeting", creator = "Synthetic creator",
                    characterVersion = "Synthetic version", defaultImagePrompt = "synthetic positive image words", defaultImageNegativePrompt = "synthetic negative image words")
                val local = app.characterTransfers.importNew(CharacterCardPackage(card = source))
                val data = CharacterCardPackage(card = source.copy(greeting = "Remote greeting"))
                val raw = app.transferJson.encodeToString(CharacterCardPackage.serializer(), data)
                val item = CommunityItemDto(id = "remote", type = "character", title = local.name, authorUserId = "owner",
                    authorName = "Synthetic owner", sourceLocalName = local.name, description = "Synthetic detail",
                    filePath = "owner/packages/character/test.json", sha256 = CommunityPackagePolicy.sha256(raw.toByteArray()),
                    sizeBytes = raw.toByteArray().size.toLong(), schemaVersion = 9, updatedAt = "2026-01-01")
                DesktopCommunitySessionStore(secrets, app.transferJson).save(CommunitySession("synthetic-access", userId = "owner", displayName = "Synthetic owner"))
                MockWebServer().use { server ->
                    val downloads = AtomicInteger(); val mutations = AtomicInteger()
                    server.dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            if (request.method !in listOf("GET", "HEAD") && request.path != "/rest/v1/rpc/increment_community_download_count") mutations.incrementAndGet()
                            return when {
                                request.path!!.startsWith("/rest/v1/community_runtime_config?") -> MockResponse().setBody("""[{"enabled":true}]""")
                                request.path!!.startsWith("/rest/v1/community_items?") -> MockResponse().setBody(app.transferJson.encodeToString(listOf(item)))
                                request.path!!.startsWith("/storage/v1/object/public/community-packages/") -> MockResponse().setBody(raw)
                                request.path == "/rest/v1/rpc/increment_community_download_count" -> { downloads.incrementAndGet(); MockResponse().setBody("null") }
                                request.path == "/functions/v1/submit-community-item" -> MockResponse().setBody("{}")
                                else -> MockResponse().setResponseCode(404)
                            }
                        }
                    }
                    val typed = app.createTypedTransferController(UnconfiguredDesktopFilePicker)
                    val owner = app.createCommunityController(typed, CommunityConfiguration(server.url("/").toString(), "synthetic-public"),
                        httpFactory = { OkHttpClient.Builder().callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build() },
                        browse = { error("No real browser in Scene test") })
                    owner.refresh(); withTimeout(5_000) { while (owner.state.value.busy) delay(10) }
                    val management = app.createManagementController(typed)
                    val strings = DesktopUiStrings(language)
                    val palette = desktopSemanticColors(com.example.chatbar.data.local.entity.ThemeMode.SYSTEM,
                        com.example.chatbar.domain.appearance.DefaultThemeColorHsv, systemDark = width == 420)
                    val scene = ImageComposeScene(width, 820, coroutineContext = edt) {
                        CompositionLocalProvider(LocalDesktopUiStrings provides strings, LocalDesktopPalette provides palette) {
                            Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.background)) { DesktopCommunityPanel(owner, management) }
                        }
                    }
                    try {
                        scene.until { !owner.state.value.busy && owner.state.value.displayName != null }
                        scene.click(local.name)
                        scene.until { owner.state.value.detailPayload != null && !owner.state.value.busy }
                        assertEquals(0, downloads.get()); assertEquals(0, mutations.get())
                        scene.assertCardReviewFields(source)
                        scene.visible(strings(DesktopUiText.CLOSE), width)
                        scene.visible(strings(DesktopUiText.COMMUNITY_IMPORT), width)
                        scene.shot("community-detail-$width.png")
                        scene.click(strings(DesktopUiText.CLOSE))
                        scene.click(strings(DesktopUiText.COMMUNITY_IMPORT))
                        scene.until { typed.state.value.pendingConflict != null && !owner.state.value.busy }
                        scene.click(strings(DesktopUiText.CANCEL))
                        scene.until { typed.state.value.pendingConflict == null }
                        assertEquals(listOf(local), app.characterRepository.getAll())
                        assertEquals(1, downloads.get())
                        scene.click(strings(DesktopUiText.COMMUNITY_MINE))
                        scene.until { owner.state.value.mine && !owner.state.value.busy }
                        scene.click("${strings(DesktopUiText.COMMUNITY_UPLOAD)} · ${strings(DesktopUiText.SELECT_OPTION)} ▾")
                        scene.click(strings(DesktopUiText.CHARACTERS))
                        scene.until { owner.state.value.uploadType == CommunityItemType.CHARACTER && !owner.state.value.busy }
                        scene.click(strings(DesktopUiText.COMMUNITY_PREPARE))
                        scene.until { owner.state.value.prepared != null && !owner.state.value.busy }
                        assertEquals(local.name, owner.state.value.prepared!!.title)
                        assertIs<CommunityImportPayload.Character>(owner.state.value.preparedPayload)
                        scene.assertCardReviewFields(source)
                        scene.visible(strings(DesktopUiText.COMMUNITY_PUBLISH), width)
                        scene.visible(strings(DesktopUiText.CLOSE), width)
                        scene.shot("community-publish-preview-$width.png")
                        scene.click(strings(DesktopUiText.CLOSE))
                        scene.until { owner.state.value.uploadType == null }
                        assertEquals(0, mutations.get())
                        scene.click(strings(DesktopUiText.DELETE))
                        scene.until { owner.state.value.deleting != null }
                        scene.shot("community-delete-confirm-$width.png")
                        scene.click(strings(DesktopUiText.CLOSE))
                        scene.until { owner.state.value.deleting == null }
                        assertEquals(0, mutations.get()); assertEquals(listOf(local), app.characterRepository.getAll())
                    } finally { scene.close() }
                }
            } finally { app.close(); parent.toFile().deleteRecursively() }
        }
    }
    @Test fun `preview chunks preserve astral text and original bytes at the layout boundary`() {
        val original = "a".repeat(3_999) + "😀" + "尾".repeat(4_000)
        val chunks = original.communityPreviewChunks()
        assertEquals(original, chunks.joinToString(""))
        assertTrue(chunks.all { !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.matches(label: String): Boolean = config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
        config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true || children.any { it.matches(label) }
    private fun ImageComposeScene.action(label: String) = nodes().last { it.matches(label) && it.config.contains(SemanticsActions.OnClick) }
    private fun ImageComposeScene.assertCardReviewFields(card: PackagedCharacterCard) {
        val visibleText = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString("\n") { it.text }
        for (value in listOf(card.creator, card.characterVersion, card.defaultImagePrompt, card.defaultImageNegativePrompt)) assertTrue(visibleText.contains(value))
    }
    private suspend fun ImageComposeScene.click(label: String) {
        val node = action(label); assertFalse(node.config.contains(SemanticsProperties.Disabled))
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        repeat(3) { render().close(); delay(20) }
    }
    private fun ImageComposeScene.visible(label: String, width: Int) {
        val bounds = action(label).boundsInRoot
        assertTrue(bounds.width > 0 && bounds.height > 0 && bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= 820, "$label: $bounds")
    }
    private suspend fun ImageComposeScene.until(condition: () -> Boolean) = withTimeout(15_000) {
        while (!condition()) { render().close(); delay(20) }
        repeat(3) { render().close(); delay(20) }
    }
    private fun ImageComposeScene.shot(name: String) {
        val output = System.getenv("CCB_FORMAT_AI_REVIEW_DIR") ?: return
        val path = Path.of(output).resolve(name); Files.createDirectories(path.parent)
        render().use { image -> image.encodeToData()!!.use { Files.write(path, it.bytes) } }
    }
}
