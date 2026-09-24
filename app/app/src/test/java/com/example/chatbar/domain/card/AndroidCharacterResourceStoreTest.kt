package com.example.chatbar.domain.card

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.data.repository.WorldBookRepository
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidCharacterResourceStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `materialization keeps Android absolute refs and accepts whitespace base64`() {
        val filesDir = temp.newFolder("files")
        val store = AndroidCharacterResourceStore(filesDir) { error("asset not expected") }

        val imageRef = store.materializeImage(
            PackagedImage("avatar.PNG", "aGVs\r\nbG8="),
            timestamp = 1L,
            resourceId = "image",
        )
        val documentRef = store.materializeDocument(
            PackagedDocument("bad:name?.txt", "txt", "文档"),
            timestamp = 1L,
            resourceId = "document",
        )

        assertTrue(File(imageRef).isAbsolute)
        assertTrue(File(documentRef).isAbsolute)
        assertEquals(File(filesDir, "images").canonicalFile, requireNotNull(File(imageRef).parentFile).canonicalFile)
        assertEquals(File(filesDir, "documents").canonicalFile, requireNotNull(File(documentRef).parentFile).canonicalFile)
        assertArrayEquals("hello".toByteArray(), store.readBytes(imageRef))
        assertEquals("文档", store.readText(documentRef))
    }

    @Test
    fun `asset image materialization delegates to Android asset reader`() {
        val filesDir = temp.newFolder("asset-files")
        val requested = mutableListOf<String>()
        val store = AndroidCharacterResourceStore(filesDir) { path ->
            requested += path
            ByteArrayInputStream("asset bytes".toByteArray())
        }

        val reference = store.materializeImage(
            PackagedImage("asset.webp", "asset:cards/asset.webp"),
            timestamp = 2L,
            resourceId = "asset",
        )

        assertEquals(listOf("cards/asset.webp"), requested)
        assertArrayEquals("asset bytes".toByteArray(), File(reference).readBytes())
    }

    @Test
    fun `delete owned refuses unrelated absolute path`() {
        val filesDir = temp.newFolder("delete-files")
        val outside = temp.newFile("outside.bin").apply { writeText("keep") }
        val store = AndroidCharacterResourceStore(filesDir) { error("asset not expected") }

        assertThrows(IllegalArgumentException::class.java) { store.deleteOwned(outside.absolutePath) }

        assertTrue(outside.exists())
        assertFalse(File(filesDir, "images").exists())
    }

    @Test
    fun `shared transfer core keeps Android absolute entity refs and asset materialization`() = runTest {
        val filesDir = temp.newFolder("core-files")
        val storage = JsonFileStorage(temp.newFolder("core-data").toPath())
        val resources = AndroidCharacterResourceStore(filesDir) { path ->
            ByteArrayInputStream("asset:$path".toByteArray())
        }
        val core = CharacterCardTransferCore(
            characterRepository = CharacterRepository(storage),
            worldBookRepository = WorldBookRepository(storage),
            formatCardRepository = FormatCardRepository(storage),
            resources = resources,
            promptPolicy = TestPromptPolicy,
            ragCleanup = CharacterDocumentRagCleanup {},
            json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val imported = core.importNew(
            CharacterCardPackage(
                card = PackagedCharacterCard(
                    name = "Android Card",
                    avatarResourceId = "avatar",
                    chatBackgroundResourceId = "background",
                ),
                images = linkedMapOf(
                    "avatar" to PackagedImage(
                        "avatar.png",
                        Base64.getEncoder().encodeToString("avatar".toByteArray()),
                    ),
                    "background" to PackagedImage("background.jpg", "asset:cards/background.jpg"),
                ),
                documents = listOf(PackagedDocument("notes.txt", "txt", "notes")),
            ),
        )

        assertTrue(requireNotNull(imported.avatar).let(::File).isAbsolute)
        assertTrue(requireNotNull(imported.chatBackground).let(::File).isAbsolute)
        assertTrue(imported.customDocuments.single().filePath.let(::File).isAbsolute)
        assertArrayEquals("avatar".toByteArray(), File(imported.avatar!!).readBytes())
        assertArrayEquals(
            "asset:cards/background.jpg".toByteArray(),
            File(imported.chatBackground!!).readBytes(),
        )
        assertEquals("notes", File(imported.customDocuments.single().filePath).readText())
    }

    private object TestPromptPolicy : CharacterTransferPromptPolicy {
        override fun defaultCharacterNaiNegativePrompt(): String = "test"
        override fun effectiveCharacterNaiNegativePrompt(value: String): String = value.ifBlank { "test" }
    }
}
