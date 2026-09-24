package com.example.chatbar.domain.card

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.FormatCardUserToolConfig
import com.example.chatbar.data.local.entity.FormatCardUserToolType
import com.example.chatbar.data.repository.FormatCardRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormatCardTransferServiceTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("format-card-transfer-")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `schema one decodes with empty user tools`() {
        val decoded = service().decode(
            """{"schemaVersion":1,"name":"旧格式","content":"旧要求"}"""
        )

        assertEquals(1, decoded.schemaVersion)
        assertEquals(emptyList(), decoded.userTools)
    }

    @Test
    fun `schema two decodes ordered user tools`() {
        val decoded = service().decode(
            """
            {
              "schemaVersion": 2,
              "name": "工具示例",
              "content": "格式要求",
              "userTools": [
                {"type":"STRONG_PROMPT_SUFFIX","text":"第一项"},
                {"type":"RANDOM_NUMBER","minimum":"-2","maximum":"2"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(FORMAT_CARD_PACKAGE_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(
            listOf(FormatCardUserToolType.STRONG_PROMPT_SUFFIX, FormatCardUserToolType.RANDOM_NUMBER),
            decoded.userTools.map(FormatCardUserToolConfig::type)
        )
        assertEquals("第一项", decoded.userTools.first().text)
        assertEquals("-2", decoded.userTools.last().minimum)
        assertEquals("2", decoded.userTools.last().maximum)
    }

    @Test
    fun `schema two export preserves ordered tools and preset provenance`() = runTest {
        val repository = repository()
        val transfer = FormatCardTransferService(repository, json)
        val tools = orderedTools()
        repository.save(
            FormatCard(
                id = "source",
                name = "工具格式",
                content = "格式要求",
                userTools = tools,
                sourcePresetKey = "preset",
                sourcePresetVersion = 4,
                createdAt = 1L
            )
        )

        val exported = transfer.decode(transfer.exportJson("source"))

        assertEquals(FORMAT_CARD_PACKAGE_SCHEMA_VERSION, exported.schemaVersion)
        assertEquals("工具格式", exported.name)
        assertEquals("格式要求", exported.content)
        assertEquals(tools, exported.userTools)
        assertEquals("preset", exported.sourcePresetKey)
        assertEquals(4, exported.sourcePresetVersion)
    }

    @Test
    fun `duplicate uses fresh identity copy name and clears default and preset provenance`() = runTest {
        val repository = repository()
        val transfer = FormatCardTransferService(repository, json)
        val source = FormatCard(
            id = "source",
            name = "格式",
            content = "内容",
            userTools = orderedTools(),
            isDefault = true,
            sourcePresetKey = "preset",
            sourcePresetVersion = 2,
            createdAt = 1L
        )
        repository.save(source)
        repository.save(FormatCard(id = "copy-2", name = "格式 (2)", content = "other", createdAt = 2L))

        val duplicate = transfer.duplicate(source.id)

        assertNotEquals(source.id, duplicate.id)
        assertEquals("格式 (3)", duplicate.name)
        assertEquals(source.content, duplicate.content)
        assertEquals(source.userTools, duplicate.userTools)
        assertFalse(duplicate.isDefault)
        assertNull(duplicate.sourcePresetKey)
        assertNull(duplicate.sourcePresetVersion)
        assertTrue(duplicate.createdAt > source.createdAt)
        assertEquals(source.id, repository.getDefault()?.id)
    }

    @Test
    fun `import new resolves name conflict without replacing existing and applies provenance precedence`() = runTest {
        val repository = repository()
        val transfer = FormatCardTransferService(repository, json)
        val existing = FormatCard(id = "existing", name = " My Card ", content = "local", createdAt = 1L)
        repository.save(existing)
        val packageData = FormatCardPackage(
            name = "my card",
            content = "incoming",
            userTools = orderedTools(),
            sourcePresetKey = "package-preset",
            sourcePresetVersion = 3
        )

        val imported = transfer.importNew(packageData, presetKey = "explicit", presetVersion = 7)

        assertNotEquals(existing.id, imported.id)
        assertEquals("my card (2)", imported.name)
        assertEquals("incoming", imported.content)
        assertEquals(orderedTools(), imported.userTools)
        assertFalse(imported.isDefault)
        assertEquals("explicit", imported.sourcePresetKey)
        assertEquals(7, imported.sourcePresetVersion)
        assertEquals(existing, repository.getById(existing.id))

        val packageProvenance = transfer.importNew(packageData.copy(name = "Other"))
        assertEquals("package-preset", packageProvenance.sourcePresetKey)
        assertEquals(3, packageProvenance.sourcePresetVersion)
    }

    @Test
    fun `character default reuses only exact normalized name content and ordered tools`() = runTest {
        val repository = repository()
        val transfer = FormatCardTransferService(repository, json)
        val tools = orderedTools()
        val local = FormatCard(
            id = "local",
            name = " 格式 ",
            content = "内容",
            userTools = tools,
            isDefault = true,
            createdAt = 1L
        )
        repository.save(local)

        val reused = transfer.importCharacterDefault(
            FormatCardPackage(name = "格式", content = "内容", userTools = tools)
        )
        val contentConflict = transfer.importCharacterDefault(
            FormatCardPackage(name = "格式", content = "不同内容", userTools = tools)
        )
        val toolConflict = transfer.importCharacterDefault(
            FormatCardPackage(name = "格式", content = "内容", userTools = tools.reversed())
        )

        assertEquals(local.id, reused.id)
        assertNotEquals(local.id, contentConflict.id)
        assertNotEquals(local.id, toolConflict.id)
        assertFalse(contentConflict.isDefault)
        assertFalse(toolConflict.isDefault)
        assertEquals(local.id, repository.getDefault()?.id)
        assertEquals(local, repository.getById(local.id))
    }

    @Test
    fun `overwrite preserves local identity default and creation time while replacing transferable fields`() = runTest {
        val repository = repository()
        val transfer = FormatCardTransferService(repository, json)
        val existing = FormatCard(
            id = "existing",
            name = " Existing ",
            content = "old",
            isDefault = true,
            sourcePresetKey = "old-preset",
            sourcePresetVersion = 1,
            createdAt = 123L
        )
        repository.save(existing)
        val incoming = FormatCardPackage(
            name = "Incoming name is ignored",
            content = "new",
            userTools = orderedTools(),
            sourcePresetKey = "incoming-preset",
            sourcePresetVersion = 5
        )

        val overwritten = transfer.overwrite(existing.id, incoming)

        assertEquals(existing.id, overwritten.id)
        assertEquals("Existing", overwritten.name)
        assertEquals(123L, overwritten.createdAt)
        assertTrue(overwritten.isDefault)
        assertEquals("new", overwritten.content)
        assertEquals(orderedTools(), overwritten.userTools)
        assertEquals("incoming-preset", overwritten.sourcePresetKey)
        assertEquals(5, overwritten.sourcePresetVersion)
        assertEquals(overwritten, repository.getById(existing.id))
    }

    private fun service(): FormatCardTransferService = FormatCardTransferService(repository(), json)

    private fun repository(): FormatCardRepository = FormatCardRepository(JsonFileStorage(root))

    private fun orderedTools(): List<FormatCardUserToolConfig> = listOf(
        FormatCardUserToolConfig.strongPromptSuffix().copy(text = "第一项"),
        FormatCardUserToolConfig.randomNumber().copy(minimum = "-2", maximum = "2")
    )
}
