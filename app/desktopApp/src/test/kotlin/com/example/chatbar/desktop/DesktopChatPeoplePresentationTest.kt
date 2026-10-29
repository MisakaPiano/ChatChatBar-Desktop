@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import javax.swing.SwingUtilities
import kotlin.test.*

class DesktopChatPeoplePresentationTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun click(scene: ImageComposeScene, label: String) {
        val node = assertNotNull(nodes(scene).asReversed().filter { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true }
            .firstNotNullOfOrNull { candidate -> generateSequence(candidate) { it.parent }.firstOrNull { it.config.contains(SemanticsActions.OnClick) } },
            "Missing $label; labels=" + nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }.distinct())
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
    }
    private fun capture(scene: ImageComposeScene, name: String) {
        val directory = System.getProperty("chatbar.test.screenshot.dir") ?: return
        val target = Path.of(directory)
        Files.createDirectories(target)
        scene.render(System.nanoTime()).use { image -> image.encodeToData()?.let { Files.write(target.resolve(name), it.bytes) } }
    }
    private suspend fun frames(scene: ImageComposeScene) { repeat(6) { scene.render(System.nanoTime()).close(); yield(); delay(15) } }

    @Test fun `wide people rail and narrow people entry open full latest status with exact edit identity`() = runBlocking(edt) {
        val parent = Files.createTempDirectory("people-presentation-")
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() })
        try {
            val person = CharacterInfo.create("Alice")
            val card = CharacterCard.create("People card", greeting = "<n=\"Alice\"/>[Hello]()\n```text\n👤姓名：Alice\n心情：calm\n```")
                .copy(characters = listOf(person))
            container.characterRepository.save(card)
            container.characterSessionService.createSessionForCharacter(card.id)
            val controller = container.primaryChatController
            controller.refresh()
            for ((width, size) in listOf(1380 to DesktopShellSize.WIDE, 540 to DesktopShellSize.COMPACT)) {
                var edit: Pair<String, String>? = null
                val scene = ImageComposeScene(width, 900) {
                    DesktopPrimaryChatPanel(controller, size, remember { DesktopComposerLayoutState() },
                        onEditPerson = { c, p -> edit = c to p })
                }
                try {
                    frames(scene)
                    if (size == DesktopShellSize.COMPACT) { click(scene, "People card"); frames(scene) }
                    if (size == DesktopShellSize.WIDE) {
                        val avatar = nodes(scene).first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("人物状态 · Alice") == true }
                        val point = avatar.boundsInRoot.center
                        scene.sendPointerEvent(PointerEventType.Press, point, button = PointerButton.Secondary)
                        scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Secondary)
                        frames(scene)
                        click(scene, "管理人物"); frames(scene)
                        click(scene, "编辑人物"); frames(scene)
                        assertEquals(card.id to person.id, edit)
                        // Returning from management can reveal the rail hover preview under the
                        // unchanged pointer. Dismiss it with a real outside click before targeting
                        // the first chat avatar underneath that floating surface.
                        val outside = Offset(800f, 800f)
                        scene.sendPointerEvent(PointerEventType.Move, outside)
                        scene.sendPointerEvent(PointerEventType.Press, outside, button = PointerButton.Primary)
                        scene.sendPointerEvent(PointerEventType.Release, outside, button = PointerButton.Primary)
                        frames(scene)
                        assertFalse(nodes(scene).any { it.config.getOrNull(SemanticsProperties.TestTag) == "person-state-preview" })
                        val bubbleAvatar = nodes(scene).last { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("人物状态 · Alice") == true }
                        val bubblePoint = bubbleAvatar.boundsInRoot.center
                        scene.sendPointerEvent(PointerEventType.Press, bubblePoint, button = PointerButton.Secondary)
                        scene.sendPointerEvent(PointerEventType.Release, bubblePoint, button = PointerButton.Secondary)
                        frames(scene)
                        assertFalse(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "本段" } == true })
                        click(scene, "管理人物"); frames(scene)
                        click(scene, "编辑人物"); frames(scene)
                        assertEquals(card.id to person.id, edit)
                    }
                    click(scene, "活跃 1"); frames(scene)
                    click(scene, "卡内人物"); frames(scene)
                    click(scene, "Alice"); frames(scene)
                    assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.contains("心情：calm") } == true })
                    assertFalse(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                        text.text == DesktopUiText.PERSON_STATUS_OPEN.zhCn } == true })
                    click(scene, "参与当前会话")
                    withTimeout(5_000) { do { frames(scene) } while (controller.state.value.people.single().enabled ||
                        nodes(scene).none { it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                            text.text == DesktopUiText.PERSON_DISABLED_HISTORY.zhCn } == true }) }
                    assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                        text.text == DesktopUiText.PERSON_DISABLED_HISTORY.zhCn } == true })
                    assertEquals(card, container.characterRepository.getById(card.id))
                    click(scene, "参与当前会话")
                    withTimeout(5_000) { do { frames(scene) } while (!controller.state.value.people.single().enabled ||
                        nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                            text.text == DesktopUiText.PERSON_DISABLED_HISTORY.zhCn } == true }) }
                    capture(scene, "sf03-status-$width.png")
                    click(scene, "编辑人物"); frames(scene)
                    assertEquals(card.id to person.id, edit)
                } catch (error: Throwable) {
                    capture(scene, "sf03-ui-failure-$width.png")
                    throw error
                } finally { scene.close() }
            }
        } finally { container.close(); parent.toFile().deleteRecursively() }
    }

    @Test fun `management navigation selects characters and brings requested person fields into view`() = runBlocking(edt) {
        val parent = Files.createTempDirectory("people-management-navigation-")
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() })
        val scope = CoroutineScope(SupervisorJob() + edt)
        try {
            val person = CharacterInfo.create("Target person").copy(profile = "Specific target profile")
            val card = CharacterCard.create("Target card", greeting = "Hi").copy(
                characters = listOf(CharacterInfo.create("First person"), person))
            container.characterRepository.save(card)
            val transfer = container.createTypedTransferController()
            val templates = container.createModelTemplateTransferController()
            val management = container.createManagementController(transfer)
            val backup = DesktopAutomaticBackupSettingsController(container.automaticBackupRuntime, scope)
            var requested by mutableStateOf<Pair<String, String>?>(card.id to person.id)
            val scene = ImageComposeScene(1000, 900) {
                DesktopManagePanel(transfer, templates, management, container.modelSettingsController, backup,
                    container.characterEditorController, container.formatCardEditorController,
                    container.worldBookEditorController, container.uiLanguageController,
                    container.appearanceController, container.connectionTestController, container.novelAiSettingsController,
                    initialPerson = requested, onInitialPersonConsumed = { requested = null })
            }
            try {
                withTimeout(7_000) {
                    do {
                        frames(scene)
                        val current = nodes(scene)
                        val targetHeading = current.singleOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "人物 · Target person" } == true }?.boundsInRoot
                        val targetField = current.singleOrNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text == person.profile }?.boundsInRoot
                        val ready = requested == null && targetHeading != null && targetHeading.height > 0 &&
                            targetHeading.top >= 64 && targetHeading.bottom <= 900 && targetField != null &&
                            targetField.height > 0 && targetField.top >= 64 && targetField.bottom <= 836
                    } while (!ready)
                }
                frames(scene)
                assertEquals(person.id, container.characterEditorController.state.value.personFocusId)
                val heading = nodes(scene).single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "人物 · Target person" } == true }
                assertTrue(heading.boundsInRoot.top >= 64 && heading.boundsInRoot.bottom <= 900, heading.boundsInRoot.toString())
                val field = nodes(scene).single { it.config.getOrNull(SemanticsProperties.EditableText)?.text == person.profile }
                assertTrue(field.boundsInRoot.height > 0 && field.boundsInRoot.top >= 64 && field.boundsInRoot.bottom <= 836,
                    field.boundsInRoot.toString())
                assertEquals(card, container.characterRepository.getById(card.id))
                capture(scene, "sf03-management-person-focus.png")
            } catch (error: Throwable) {
                capture(scene, "sf03-ui-management-failure.png")
                throw error
            } finally { scene.close() }
        } finally { scope.cancel(); container.close(); parent.toFile().deleteRecursively() }
    }
}
