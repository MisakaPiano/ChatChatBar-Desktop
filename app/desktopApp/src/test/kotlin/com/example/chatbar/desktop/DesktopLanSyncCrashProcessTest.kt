package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.lanSync.*
import kotlinx.serialization.json.*
import java.nio.file.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DesktopLanSyncCrashProcessTest {
    @Test fun `hard process halt rolls forward in fresh process and refuses foreign mutation`() {
        for (foreign in listOf(false, true)) {
            val root = Files.createTempDirectory("ccb-lan-crash-").toRealPath()
            child(root, "halt", 73)
            assertEquals("A新", DesktopLanSyncCrashChild.read(root, "a"))
            assertEquals("B旧", DesktopLanSyncCrashChild.read(root, "b"))
            if (foreign) DesktopLanSyncCrashChild.write(root, "b", "独立用户编辑")
            child(root, if (foreign) "refuse" else "recover", 0)
            assertEquals(if (foreign) "独立用户编辑" else "B新", DesktopLanSyncCrashChild.read(root, "b"))
        }
    }

    private fun child(root: Path, mode: String, expectedExit: Int) {
        val log = root.resolve("$mode.log")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
            "-cp", requireNotNull(System.getProperty("ccb.readonly.classpath")),
            DesktopLanSyncCrashChild::class.java.name, root.toString(), mode)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        process.outputStream.close()
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "synthetic crash child timeout")
            assertEquals(expectedExit, process.exitValue(), Files.readString(log).take(1500))
        } finally { if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS) }
    }
}

object DesktopLanSyncCrashChild {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private fun data(root: Path) = root.resolve("data")
    private fun card(id: String, name: String) = LanSyncRecord("character_cards", id,
        json.encodeToJsonElement(CharacterCard.serializer(), CharacterCard(id, name, createdAt = 1, updatedAt = 1)).jsonObject)
    fun write(root: Path, id: String, name: String) {
        val path = data(root).resolve("entities/character_cards/$id.json")
        Files.createDirectories(path.parent)
        Files.writeString(path, card(id, name).body.toString())
    }
    fun read(root: Path, id: String) = json.parseToJsonElement(
        Files.readString(data(root).resolve("entities/character_cards/$id.json"))).jsonObject.getValue("name").jsonPrimitive.content
    @JvmStatic fun main(args: Array<String>) {
        val root = Path.of(args[0]).toRealPath()
        check(root.fileName.toString().startsWith("ccb-lan-crash-"))
        val state = root.resolve("state")
        val workspace = LanSyncWorkspace(data(root), state, { data(root).resolve(it) }, observeJournal = { stage, index ->
            // No exception unwinding, finally cleanup or shutdown hook can publish the remaining entry.
            if (args[1] == "halt" && stage == "ENTRY" && index == 0) Runtime.getRuntime().halt(73)
        })
        if (args[1] == "halt") {
            write(root, "a", "A旧"); write(root, "b", "B旧")
            val plan = LanSyncPlan(UUID.randomUUID().toString(), LanSyncSnapshot(deviceId = "synthetic",
                records = listOf(card("a", "A新"), card("b", "B新"))), emptyMap())
            Files.writeString(root.resolve("transaction.txt"), plan.transaction, StandardOpenOption.CREATE_NEW)
            workspace.capture("synthetic").use { workspace.apply(plan, it.snapshot.hashes(), "synthetic", emptyMap()) }
            error("HALT_NOT_REACHED")
        }
        val transaction = Files.readString(root.resolve("transaction.txt"))
        check(!workspace.applied(transaction))
        if (args[1] == "refuse") {
            val result = runCatching { workspace.recover() }
            check(result.exceptionOrNull()?.message == "SYNC_FOREIGN_CHANGE_RECOVERY_REFUSED")
            check(!workspace.applied(transaction))
            check(read(root, "b") == "独立用户编辑")
        } else {
            check(args[1] == "recover")
            workspace.recover()
            check(workspace.applied(transaction))
            check(read(root, "a") == "A新" && read(root, "b") == "B新")
            workspace.recover()
        }
        println("LAN_CRASH_${args[1].uppercase()}_PASS")
    }
}
