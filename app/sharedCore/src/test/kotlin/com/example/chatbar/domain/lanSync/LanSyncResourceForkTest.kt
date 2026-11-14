package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.entity.CharacterCard
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO
import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncResourceForkTest {
    private val media = "00000000-0000-0000-0000-000000000001"
    private fun card(id: String, name: String, avatar: String) = LanSyncRecord("character_cards", id,
        lanSyncJson.encodeToJsonElement(CharacterCard.serializer(), CharacterCard(id, name, avatar = avatar, createdAt = 1, updatedAt = 1)).jsonObject)
    private fun avatar(record: LanSyncRecord) = requireNotNull(record.text("avatar"))

    @Test fun conflictingAliasesForkStablyWithoutCollapsingAnExistingSameContentAliasOrAnIncomingDependent() {
        val leftHash = lanSyncHash(byteArrayOf(1)); val rightHash = lanSyncHash(byteArrayOf(2)); val tx = UUID.randomUUID().toString()
        val firstCandidate = lanSyncDerivedId(tx, "resource/images/$media/$rightHash/0")
        val leftUri = "ccb-lan-v1/images/$media/$leftHash/png"; val rightUri = "ccb-lan-v1/images/$media/$rightHash/png"
        val reservedUri = "ccb-lan-v1/images/$firstCandidate/$rightHash/png"
        val left = LanSyncSnapshot(deviceId = "computer", records = listOf(card("a", "left", leftUri), card("reserved", "reserved", reservedUri)),
            resources = mapOf(leftHash to 1L, rightHash to 1L))
        val right = LanSyncSnapshot(deviceId = "phone", records = listOf(card("a", "right", rightUri), card("dependent", "incoming", rightUri)),
            resources = mapOf(rightHash to 1L))
        LanSyncScope.validateGraph(left); LanSyncScope.validateGraph(right)
        fun plan() = LanSyncPlanner.plan(left, right, LanSyncBaseline("phone"), mapOf("character_cards/a" to LanSyncChoice.KEEP_BOTH), tx)
        val first = plan(); assertEquals(first, plan())
        val records = first.snapshot.records.associateBy { it.id }
        assertEquals(leftUri, avatar(records.getValue("a"))); assertEquals(reservedUri, avatar(records.getValue("reserved")))
        val copied = records.getValue(lanSyncDerivedId(tx, "character_cards/a"))
        assertEquals(avatar(copied), avatar(records.getValue("dependent")))
        assertNotEquals(rightUri, avatar(copied)); assertNotEquals(reservedUri, avatar(copied))
        assertEquals(rightHash, avatar(copied).split('/')[3]); assertEquals(setOf(leftHash, rightHash), first.snapshot.resources.keys)
        assertEquals(rightUri, avatar(right.records.first())); LanSyncScope.validateGraph(first.snapshot)
    }

    @Test fun sameBytesCanRetainTheirSharedMediaIdentityWhenBothRecordsAreKept() {
        val hash = lanSyncHash(byteArrayOf(1)); val uri = "ccb-lan-v1/images/$media/$hash/png"
        val left = LanSyncSnapshot(deviceId = "computer", records = listOf(card("a", "left", uri)), resources = mapOf(hash to 1L))
        val right = left.copy(deviceId = "phone", records = listOf(card("a", "right", uri)))
        val plan = LanSyncPlanner.plan(left, right, LanSyncBaseline("phone"), mapOf("character_cards/a" to LanSyncChoice.KEEP_BOTH), UUID.randomUUID().toString())
        assertEquals(listOf(uri, uri), plan.snapshot.records.map(::avatar)); assertEquals(mapOf(hash to 1L), plan.snapshot.resources)
    }
    @Test fun invalidAliasingInsideOneIncomingSideIsStillRejectedInsteadOfBeingRepaired() {
        val a = lanSyncHash(byteArrayOf(1)); val b = lanSyncHash(byteArrayOf(2)); val c = lanSyncHash(byteArrayOf(3))
        fun uri(hash: String) = "ccb-lan-v1/images/$media/$hash/png"
        val left = LanSyncSnapshot(deviceId = "computer", records = listOf(card("a", "left", uri(a))), resources = mapOf(a to 1L))
        val right = LanSyncSnapshot(deviceId = "phone", records = listOf(card("a", "right", uri(b)), card("other", "invalid alias", uri(c))),
            resources = mapOf(b to 1L, c to 1L))
        assertEquals("SYNC_RESOURCE_ID_CHANGED", assertFailsWith<IllegalArgumentException> {
            LanSyncPlanner.plan(left, right, LanSyncBaseline("phone"), mapOf("character_cards/a" to LanSyncChoice.KEEP_BOTH), UUID.randomUUID().toString())
        }.message)
    }

    @Test fun actualTwoNativeRootsKeepBothDifferentImagesWithTheSameOriginalMediaIdentityAndReopenCleanly() {
        val root = Files.createTempDirectory("synthetic-resource-fork-").toRealPath()
        try {
            fun seed(name: String, color: Int): Pair<LanSyncWorkspace, Path> {
                val data = Files.createDirectory(root.resolve(name)); val state = Files.createDirectory(root.resolve("$name-state"))
                val image = data.resolve("images/avatar.png"); Files.createDirectories(image.parent)
                val raster = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
                for (x in 0..1) for (y in 0..1) raster.setRGB(x, y, color)
                check(ImageIO.write(raster, "png", image.toFile())); raster.flush()
                val record = card("a", name, image.toString()); val path = data.resolve("entities/character_cards/a.json")
                Files.createDirectories(path.parent); LanSyncJsonFiles.write(path, JsonObject.serializer(), record.body)
                return LanSyncWorkspace(data, state, { Path.of(it) }) to image
            }
            val (computer, computerImage) = seed("computer", 0xffff0000.toInt())
            val (phone, phoneImage) = seed("phone", 0xff0000ff.toInt())
            val leftBytes = Files.readAllBytes(computerImage); val rightBytes = Files.readAllBytes(phoneImage)
            computer.capture("computer").use { left -> phone.capture("phone").use { right ->
                assertEquals(avatar(left.snapshot.records.single()).split('/')[2], avatar(right.snapshot.records.single()).split('/')[2])
                val tx = UUID.randomUUID().toString(); val plan = LanSyncPlanner.plan(left.snapshot, right.snapshot, LanSyncBaseline("phone"),
                    mapOf("character_cards/a" to LanSyncChoice.KEEP_BOTH), tx)
                val copiedId = lanSyncDerivedId(tx, "character_cards/a"); val blobs = left.blobs + right.blobs
                computer.apply(plan, left.snapshot.hashes(), "computer", blobs)
                phone.apply(plan, right.snapshot.hashes(), "phone", blobs)
                for (workspace in listOf(computer, phone)) {
                    fun load(id: String) = requireNotNull(LanSyncJsonFiles.read(workspace.dataRoot.resolve("entities/character_cards/$id.json"), CharacterCard.serializer()))
                    val original = load("a"); val copied = load(copiedId)
                    assertEquals("computer", original.name); assertEquals("phone", copied.name)
                    assertNotEquals(original.avatar, copied.avatar)
                    assertContentEquals(leftBytes, Files.readAllBytes(Path.of(requireNotNull(original.avatar))))
                    assertContentEquals(rightBytes, Files.readAllBytes(Path.of(requireNotNull(copied.avatar))))
                    workspace.capture(if (workspace === computer) "computer" else "phone").use { reopened ->
                        assertEquals(plan.snapshot.hashes(), reopened.snapshot.hashes()); assertEquals(2, reopened.snapshot.resources.size)
                    }
                }
                assertContentEquals(leftBytes, Files.readAllBytes(computerImage)); assertContentEquals(rightBytes, Files.readAllBytes(phoneImage))
            } }
        } finally { check(root.toFile().deleteRecursively()) }
    }
}
