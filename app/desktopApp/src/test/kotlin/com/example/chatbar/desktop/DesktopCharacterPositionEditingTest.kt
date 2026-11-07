@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.example.chatbar.domain.image.*
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopCharacterPositionEditingTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private val original = NovelAiStudioDraft(selectedModel = NovelAiImageModel.V5_FULL,
        v5Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, useCharacterPositions = true),
        characters = listOf(NovelAiCharacterPromptDraft(id = "selected", prompt = "Synthetic person", center = DesignedCharacterCenter(.2f, .3f)),
            NovelAiCharacterPromptDraft(id = "other", prompt = "Synthetic other", center = DesignedCharacterCenter(.6f, .6f))))

    private class View(val scene: ImageComposeScene) {
        fun nodes(): List<SemanticsNode> {
            fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
        }
        suspend fun frames() { repeat(4) { scene.render().close(); delay(20) } }
        fun text(old: String, new: String) {
            val field = nodes().single { it.config.getOrNull(SemanticsProperties.EditableText)?.text == old &&
                it.config.contains(SemanticsActions.SetText) }
            assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(new)))
        }
        suspend fun undo(text: String) {
            val field = nodes().single { it.config.getOrNull(SemanticsProperties.EditableText)?.text == text &&
                it.config.contains(SemanticsActions.SetText) }
            assertTrue(field.config[SemanticsActions.RequestFocus].action!!.invoke())
            frames()
            scene.sendKeyEvent(KeyEvent(Key.Z, KeyEventType.KeyDown, isCtrlPressed = true))
            scene.sendKeyEvent(KeyEvent(Key.Z, KeyEventType.KeyUp, isCtrlPressed = true))
            frames()
        }
        fun click(label: String) {
            fun has(n: SemanticsNode): Boolean = n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
                n.children.any(::has)
            val action = nodes().first { has(it) && it.config.contains(SemanticsActions.OnClick) }
            assertFalse(action.config.contains(SemanticsProperties.Disabled), label)
            assertTrue(action.config[SemanticsActions.OnClick].action!!.invoke())
        }
    }

    @Test fun `actual numeric controls merge queued axes without losing the first coordinate or other role state`() = runBlocking(edt) {
        val changes = mutableListOf<(NovelAiStudioDraft) -> NovelAiStudioDraft>()
        val scene = ImageComposeScene(640, 720, coroutineContext = edt) {
            Column(Modifier.fillMaxSize()) { DesktopCharacterPositions(original) { changes.add(it) } }
        }
        try {
            val view = View(scene); view.frames()
            view.text("20", "90"); view.text("30", "70")
            assertEquals(2, changes.size)
            val result = changes.fold(original) { current, change -> change(current) }
            assertEquals(DesignedCharacterCenter(.9f, .7f), result.characters.first().center)
            assertEquals(original.characters[1], result.characters[1])
            assertEquals(original.copy(characters = result.characters), result)
        } finally { scene.close() }
    }

    @Test fun `queued position edits retain their original model person and custom mode admission`() = runBlocking(edt) {
        val changes = mutableListOf<(NovelAiStudioDraft) -> NovelAiStudioDraft>()
        val scene = ImageComposeScene(640, 720, coroutineContext = edt) {
            Column(Modifier.fillMaxSize()) { DesktopCharacterPositions(original) { changes.add(it) } }
        }
        try {
            val view = View(scene); view.frames(); view.text("20", "90")
            val change = changes.single()
            val cases = listOf(
                "model switched" to original.copy(selectedModel = NovelAiImageModel.V4_5_FULL),
                "person disabled" to original.copy(characters = original.characters.map { if (it.id == "selected") it.copy(enabled = false) else it }),
                "custom mode disabled" to original.copy(v5Settings = original.v5Settings.copy(useCharacterPositions = false)))
            for ((label, current) in cases) assertEquals(current, change(current), label)
            val newer = original.copy(characters = original.characters.map { if (it.id == "selected") it.copy(center = DesignedCharacterCenter(.4f, .8f)) else it })
            assertEquals(DesignedCharacterCenter(.9f, .8f), change(newer).characters.first().center)
        } finally { scene.close() }
    }

    @Test fun `queued position mode and reset controls never apply to a different selected model`() = runBlocking(edt) {
        val changes = mutableListOf<(NovelAiStudioDraft) -> NovelAiStudioDraft>()
        val scene = ImageComposeScene(640, 720, coroutineContext = edt) {
            Column(Modifier.fillMaxSize()) { DesktopCharacterPositions(original) { changes.add(it) } }
        }
        try {
            val view = View(scene); view.frames(); view.click("自动"); view.click("均匀重置")
            assertEquals(2, changes.size)
            val switched = original.copy(selectedModel = NovelAiImageModel.V4_5_FULL,
                v45Settings = original.v45Settings.copy(useCharacterPositions = true))
            for (change in changes) assertEquals(switched, change(switched))
            assertFalse(changes[0](original).activeSettings.useCharacterPositions)
            assertTrue(changes[1](original).characters.all { it.center == null })
        } finally { scene.close() }
    }

    @Test fun `actual position field undo belongs to each stable person even when their displayed values match`() = runBlocking(edt) {
        val histories = DesktopStudioTextHistories()
        var draft by mutableStateOf(original.copy(characters = original.characters.map {
            if (it.id == "other") it.copy(center = DesignedCharacterCenter(.9f, .6f)) else it
        }))
        val scene = ImageComposeScene(640, 720, coroutineContext = edt) {
            CompositionLocalProvider(LocalDesktopStudioTextHistories provides histories) {
                Column(Modifier.fillMaxSize()) { DesktopCharacterPositions(draft) { draft = it(draft) } }
            }
        }
        try {
            val view = View(scene); view.frames(); view.text("20", "90"); view.frames()
            assertEquals(.9f, draft.characters.first().center!!.x)
            view.click("角色 2"); view.frames(); view.undo("90")
            assertEquals(.9f, draft.characters[1].center!!.x, "Role2 must not inherit Role1's old20 undo")
            assertEquals(.9f, draft.characters[0].center!!.x)
            view.click("角色 1"); view.frames(); view.undo("90")
            assertEquals(.2f, draft.characters[0].center!!.x, "Role1 retains its own local undo")
            assertEquals(.9f, draft.characters[1].center!!.x)
        } finally { scene.close() }
    }

    @Test fun `V5 fractional percent is visible from stored draft and after a fresh position editor opens`() = runBlocking(edt) {
        var shown by mutableStateOf(true)
        var draft by mutableStateOf(original.copy(characters = original.characters.map {
            if (it.id == "selected") it.copy(center = DesignedCharacterCenter(.173f, .287f)) else it
        }))
        val scene = ImageComposeScene(640, 720, coroutineContext = edt) {
            Column(Modifier.fillMaxSize()) { if (shown) DesktopCharacterPositions(draft) { draft = it(draft) } }
        }
        try {
            val view = View(scene); view.frames()
            fun values() = view.nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text }
            assertEquals(listOf("17.3", "28.7"), values())
            view.text("17.3", "42.5"); view.frames()
            val stored = Json.encodeToString(NovelAiStudioDraft.serializer(), draft)
            shown = false; view.frames()
            draft = Json.decodeFromString(NovelAiStudioDraft.serializer(), stored)
            shown = true; view.frames()
            assertEquals(listOf("42.5", "28.7"), values())
            assertEquals(DesignedCharacterCenter(.425f, .287f), draft.characters.first().center)
            assertEquals(original.characters[1], draft.characters[1])
        } finally { scene.close() }
    }
}
