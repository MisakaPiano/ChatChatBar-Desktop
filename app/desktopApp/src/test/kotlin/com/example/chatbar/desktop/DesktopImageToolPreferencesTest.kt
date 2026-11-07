package com.example.chatbar.desktop

import androidx.compose.ui.unit.IntSize
import com.example.chatbar.data.local.JsonFileStorage
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopImageToolPreferencesTest {
    @Test fun `imported brush preferences survive reopen and field edits wait outside maintenance`() = runBlocking {
        val root = Files.createTempDirectory("brush-owner-")
        val gate = DesktopDataOperationCoordinator()
        val release = CompletableDeferred<Unit>()
        try {
            val storage = JsonFileStorage(root, gate)
            val original = buildJsonObject {
                put("brushSize", 48); put("brushType", "Black")
                put("novelAiToken", "synthetic-only-not-a-credential")
                put("futureField", buildJsonObject { put("preserved", true) })
            }
            storage.saveSingleton("desktop_backup_preferences", original, JsonObject.serializer())
            val controller = DesktopImageToolPreferences(storage, gate)
            assertEquals(DesktopBrushPreferences(48f, "Black", true), controller.load())
            val path = root.resolve("entities/desktop_backup_preferences.json")
            val before = Files.readAllBytes(path)
            val entered = CompletableDeferred<Unit>()
            val maintenance = launch(start = CoroutineStart.UNDISPATCHED) {
                gate.withExclusiveMaintenance { entered.complete(Unit); release.await() }
            }
            entered.await()
            val size = async(start = CoroutineStart.UNDISPATCHED) { controller.save(sizeDp = 64f) }
            val type = async(start = CoroutineStart.UNDISPATCHED) { controller.save(type = "White") }
            assertFalse(size.isCompleted); assertFalse(type.isCompleted)
            assertContentEquals(before, Files.readAllBytes(path))
            release.complete(Unit); maintenance.join(); size.await(); type.await()
            val reopened = DesktopImageToolPreferences(JsonFileStorage(root, gate), gate)
            assertEquals(DesktopBrushPreferences(64f, "White", true), reopened.load())
            val after = Json.parseToJsonElement(Files.readString(path)).jsonObject
            assertEquals(original["futureField"], after["futureField"])
            assertEquals(original["novelAiToken"], after["novelAiToken"])
            storage.saveSingleton("desktop_backup_preferences", JsonObject(after + ("brushType" to JsonPrimitive("FutureBrush"))), JsonObject.serializer())
            assertEquals("FutureBrush", reopened.load().type)
            reopened.save(sizeDp = 40f)
            assertEquals("FutureBrush", reopened.load().type)
            Files.writeString(path, "broken original")
            assertFails { reopened.load() }; assertFails { reopened.save(type = "Black") }
            assertEquals("broken original", Files.readString(path))
        } finally { release.complete(Unit); gate.closeAndDrain(); check(root.toFile().deleteRecursively()) }
    }

    @Test fun `displayed brush diameter maps to source radius without changing original pixels`() {
        val source = BufferedImage(1000, 1000, BufferedImage.TYPE_INT_ARGB)
        val before = source.pixels()
        for ((canvas, density, expectedRadius) in listOf(Triple(IntSize(200, 200), 1f, 90),
            Triple(IntSize(400, 400), 2f, 90), Triple(IntSize(400, 400), 1f, 45))) {
            val radius = desktopBrushRadius(36f, canvas, density)
            val painted = DesktopImageTools.paint(source, listOf(.5f to .5f), radius, 0xff000000.toInt())
            // A normalized midpoint maps to floor(.5 * (1000 - 1)) = 499.
            assertEquals(0xff000000.toInt(), painted.getRGB(499 + expectedRadius - 1, 499))
            assertEquals(0, painted.getRGB(499 + expectedRadius + 1, 499))
        }
        assertContentEquals(before, source.pixels())
    }
}
