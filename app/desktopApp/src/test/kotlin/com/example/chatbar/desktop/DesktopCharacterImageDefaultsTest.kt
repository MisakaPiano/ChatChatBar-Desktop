@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopCharacterImageDefaultsTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `real defaults content follows model capability without mutating hidden values in narrow and wide layouts`() = runBlocking(edt) {
        for (width in listOf(420, 1100)) {
            val parameters = NovelAiCharacterImageSettings(sampler = NovelAiSampler.DPM_PLUS_PLUS_2M_SDE,
                steps = 24, guidance = 5f, cfgRescale = .3f, varietyPlus = true)
            val original = CharacterCard("fixture", "Synthetic", defaultImageGenerationSettings = parameters, createdAt = 1, updatedAt = 1)
            var card by mutableStateOf(original)
            var global by mutableStateOf<NovelAiImageModel?>(NovelAiImageModel.V4_5_FULL)
            val scene = ImageComposeScene(width, 760, coroutineContext = edt) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    DesktopCharacterImageDefaults(card, global) { transform -> card = transform(card) }
                }
            }
            try {
                scene.until { has("Sampler · DPM++ 2M SDE ▾") }
                assertTrue(scene.has("Variety+")); assertEquals(original, card)
                global = NovelAiImageModel.V5_FULL
                scene.until { has("Sampler · DPM++ 2M SDE ▾") }
                assertFalse(scene.has("Variety+")); assertEquals(original, card)
                scene.click("生图模型 · 跟随默认 ▾"); scene.click("V4.5 Full")
                scene.until { has("Variety+") }
                assertEquals(NovelAiImageModel.V4_5_FULL, card.defaultNovelAiImageModel)
                assertEquals(parameters, card.defaultImageGenerationSettings)
                scene.click("生图模型 · V4.5 Full ▾"); scene.click("跟随默认")
                scene.until { has("Sampler · DPM++ 2M SDE ▾") }
                assertNull(card.defaultNovelAiImageModel); assertEquals(parameters, card.defaultImageGenerationSettings)
                val output = System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { Path.of(it).resolve("character-image-defaults-$width.png") }
                if (output != null) {
                    Files.createDirectories(output.parent)
                    scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(output, it.bytes) } }
                }
                scene.click("使用角色专属生图参数")
                scene.until { has("Sampler · Euler Ancestral ▾") }
                assertNull(card.defaultImageGenerationSettings)
                scene.click("使用角色专属生图参数")
                scene.until { has("Sampler · Euler Ancestral ▾") }
                assertEquals(NovelAiCharacterImageSettings(), card.defaultImageGenerationSettings)
                scene.click("恢复默认生图参数")
                scene.until { !has("恢复默认生图参数") }
                assertNull(card.defaultImageGenerationSettings)
                assertEquals(original.name, card.name); assertEquals(original.id, card.id)
            } finally { scene.close() }
        }
    }
    @Test fun `card V5 controls preserve High sampling and suspended options through model changes and explicit reset`() = runBlocking(edt) {
        for (width in listOf(420, 1100)) {
            val original = NovelAiCharacterImageSettings(sampler = NovelAiSampler.DPM_PLUS_PLUS_2M_SDE,
                steps = 31, guidance = 6.5f, cfgRescale = .4f, varietyPlus = true)
            var card by mutableStateOf(CharacterCard("v5-fixture", "Synthetic V5", defaultNovelAiImageModel = NovelAiImageModel.V5_FULL,
                defaultImageGenerationSettings = original, createdAt = 1, updatedAt = 1))
            var global by mutableStateOf<NovelAiImageModel?>(NovelAiImageModel.V4_5_FULL)
            val scene = ImageComposeScene(width, 900, coroutineContext = edt) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    DesktopCharacterImageDefaults(card, global) { card = it(card) }
                }
            }
            try {
                scene.until { has("Sampler · DPM++ 2M SDE ▾") }
                val oldSteps = scene.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Steps") == true && it.config.contains(SemanticsActions.SetProgress) }.config[SemanticsActions.SetProgress].action!!
                val oldRescale = scene.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("CFG Rescale") == true && it.config.contains(SemanticsActions.SetProgress) }.config[SemanticsActions.SetProgress].action!!
                scene.click("Sampler · DPM++ 2M SDE ▾")
                scene.until { nodes().any { it.has("Euler") && it.config.contains(SemanticsActions.OnClick) } }
                val oldSampler = scene.nodes().first { it.has("Euler") && it.config.contains(SemanticsActions.OnClick) }.config[SemanticsActions.OnClick].action!!
                scene.click("Medium"); scene.click("透明背景")
                scene.until { !has("Sampler · DPM++ 2M SDE ▾") }
                assertTrue(oldSampler()); assertTrue(oldSteps(43f)); assertTrue(oldRescale(.9f))
                assertEquals(original.copy(effort = NovelAiEffort.MEDIUM, transparentBackground = true), card.defaultImageGenerationSettings)
                scene.click("High")
                scene.until { has("Sampler · DPM++ 2M SDE ▾") }
                assertEquals(original.copy(transparentBackground = true), card.defaultImageGenerationSettings)
                scene.click("Medium")
                scene.click("生图模型 · V5 Full ▾"); scene.click("V4.5 Full")
                scene.until { has("Variety+") }
                assertFalse(scene.has("透明背景"))
                assertEquals(original.copy(effort = NovelAiEffort.MEDIUM, transparentBackground = true), card.defaultImageGenerationSettings)
                scene.click("Sampler · DPM++ 2M SDE ▾"); scene.click("Euler")
                scene.until { has("Sampler · Euler ▾") }
                assertEquals(NovelAiSampler.EULER, card.defaultImageGenerationSettings?.sampler)
                scene.click("生图模型 · V4.5 Full ▾"); scene.click("V5 Full")
                scene.until { has("透明背景") }
                scene.click("使用 NovelAI 默认采样参数")
                val reset = requireNotNull(card.defaultImageGenerationSettings)
                assertEquals(23, reset.steps); assertEquals(7f, reset.guidance)
                assertEquals(0f, reset.cfgRescale); assertEquals(NovelAiSampler.EULER_ANCESTRAL, reset.sampler)
                assertEquals(NovelAiEffort.MEDIUM, reset.effort); assertTrue(reset.transparentBackground); assertTrue(reset.varietyPlus)
                val output = System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { Path.of(it).resolve("character-v5-defaults-$width.png") }
                if (output != null) {
                    Files.createDirectories(output.parent)
                    scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(output, it.bytes) } }
                }
                scene.click("恢复默认生图参数")
                scene.until { !has("恢复默认生图参数") }
                assertNull(card.defaultImageGenerationSettings)
                global = null
                card = card.copy(defaultNovelAiImageModel = null)
                scene.until { nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Steps") == true && it.config.contains(SemanticsActions.SetProgress) } }
                val unknownSteps = scene.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Steps") == true && it.config.contains(SemanticsActions.SetProgress) }.config[SemanticsActions.SetProgress].action!!
                val unknownRescale = scene.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("CFG Rescale") == true && it.config.contains(SemanticsActions.SetProgress) }.config[SemanticsActions.SetProgress].action!!
                assertTrue(unknownSteps(33f)); assertTrue(unknownRescale(.5f))
                assertEquals(33, card.defaultImageGenerationSettings?.steps)
                assertEquals(.5f, card.defaultImageGenerationSettings?.cfgRescale)
            } finally { scene.close() }
        }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.has(label: String): Boolean =
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true || children.any { it.has(label) }
    private fun ImageComposeScene.has(label: String) = nodes().any { it.has(label) }
    private suspend fun ImageComposeScene.until(condition: ImageComposeScene.() -> Boolean) = withTimeout(5_000) {
        while (!condition()) { render(System.nanoTime()).close(); delay(10) }
        repeat(3) { render(System.nanoTime()).close(); yield() }
    }
    private suspend fun ImageComposeScene.click(label: String) {
        until { nodes().any { it.has(label) && it.config.contains(SemanticsActions.OnClick) } }
        val node = nodes().first { it.has(label) && it.config.contains(SemanticsActions.OnClick) }
        assertFalse(node.config.contains(SemanticsProperties.Disabled))
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        repeat(4) { render(System.nanoTime()).close(); delay(10) }
    }
}
