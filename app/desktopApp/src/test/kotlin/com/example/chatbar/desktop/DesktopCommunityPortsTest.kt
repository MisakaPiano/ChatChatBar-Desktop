package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.DesktopCredentialKey
import com.example.chatbar.desktop.security.DesktopSecretStore
import com.example.chatbar.domain.community.*
import com.example.chatbar.domain.card.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class DesktopCommunityPortsTest {
    private class Secrets : DesktopSecretStore {
        val values = mutableMapOf<DesktopCredentialKey, String>()
        override fun load(key: DesktopCredentialKey) = values[key]
        override fun save(key: DesktopCredentialKey, secret: String) { values[key] = secret }
        override fun delete(key: DesktopCredentialKey) { values.remove(key) }
    }
    @Test fun `community session uses existing protected logical key and does not overwrite malformed custody`() {
        val secrets = Secrets(); val store = DesktopCommunitySessionStore(secrets, Json)
        val session = CommunitySession("synthetic-access", "synthetic-refresh", userId = "synthetic-owner", displayName = "Synthetic")
        assertNull(store.load()); store.save(session); assertEquals(session, store.load())
        assertEquals(setOf<DesktopCredentialKey>(DesktopCredentialKey.DormantCommunitySession), secrets.values.keys)
        assertFailsWith<IllegalArgumentException> { store.save(session.copy(accessToken = "")) }
        assertEquals(session, store.load())
        secrets.values[DesktopCredentialKey.DormantCommunitySession] = "malformed-synthetic"
        assertFails { store.load() }
        assertEquals("malformed-synthetic", secrets.values[DesktopCredentialKey.DormantCommunitySession])
        store.clear(); assertTrue(secrets.values.isEmpty())
    }

    @Test fun `local upload sources reuse typed export exclude community cards and make a bounded jpeg avatar copy`(): Unit = runBlocking {
        val parent = Files.createTempDirectory("desktop-community-ports-")
        val root = Files.createDirectory(parent.resolve("root"))
        val secrets = Secrets()
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root,
            DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, parent.resolve("bootstrap.json")), secretStoreFactory = { secrets })
        try {
            val png = DesktopImageEditing.png(BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB))
            val packaged = CharacterCardPackage(card = PackagedCharacterCard(name = "Synthetic local", avatarResourceId = "avatar"),
                images = mapOf("avatar" to PackagedImage("avatar.png", Base64.getEncoder().encodeToString(png))))
            val local = app.characterTransfers.importNew(packaged)
            app.characterTransfers.importNew(packaged, communitySource = CharacterCardCommunitySource("remote", "2026-01-01", "a".repeat(64), "Remote"))
            val port = DesktopCommunityLocalPackages(app.characterRepository, app.formatCardRepository, app.worldBookRepository,
                app.characterTransfers, app.formatTransfers, app.worldBookTransfers, app.characterResourceStore, app.dataOperationCoordinator)
            assertEquals(listOf(local.id), port.candidates(CommunityItemType.CHARACTER).map { it.id })
            val before = app.characterResourceStore.readBytes(requireNotNull(local.avatar))
            val draft = port.export(CommunityItemType.CHARACTER, local.id)
            val preview = requireNotNull(draft.preview)
            assertEquals("image/jpeg", preview.contentType); assertTrue(preview.bytes.size in 1..80 * 1024)
            val decoded = DesktopImageEditing.decode(preview.bytes)
            try { assertTrue(maxOf(decoded.width, decoded.height) <= 160) } finally { decoded.flush() }
            CommunityPackagePolicy.validate(CommunityItemType.CHARACTER, draft.packageText)
            assertContentEquals(before, app.characterResourceStore.readBytes(requireNotNull(local.avatar)))
            val remote = app.characterRepository.getAll().single { it.isCommunityDownload }
            assertFails { port.export(CommunityItemType.CHARACTER, remote.id) }
        } finally { app.close(); parent.toFile().deleteRecursively() }
    }
}
