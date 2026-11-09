package com.example.chatbar.domain.card

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.SaveSlotPackageStorage
import com.example.chatbar.domain.image.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class PlayerAvatarTransferTest {
    @Test fun confirmedCropOwnsDerivativeAndNeverChangesOriginal() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "player-avatar-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(root, "source.png")
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(Color.BLUE); source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        val original = source.readBytes()
        var output: File? = null
        try {
            output = File(PlayerAvatarStorage.crop(context, Uri.fromFile(source), ImageCropFractionRect(.25f, 0f, .75f, 1f)))
            assertEquals("player-avatars", output.parentFile!!.name)
            assertArrayEquals(original, source.readBytes())
            val decoded = requireNotNull(BitmapFactory.decodeFile(output.path))
            try { assertEquals(512, decoded.width); assertEquals(512, decoded.height); assertEquals(Color.BLUE, decoded.getPixel(100, 100)) }
            finally { decoded.recycle() }
        } finally { output?.delete(); root.deleteRecursively() }
    }

    @Test fun repositorySnapshotWritesCannotOverwriteNewPlayerChoicesAndDraftCanUnlink() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<com.example.chatbar.ChatBarApp>()
        kotlinx.coroutines.withTimeout(30_000) { app.dependenciesReady.first { it } }
        val session = ChatSession.create("synthetic-card", "synthetic-player")
        app.chatRepository.createSession(session)
        try {
            val role = SessionPlayerRole("synthetic-card", "synthetic-person")
            val saved = app.chatRepository.saveSessionSettingsDraft(session,
                session.copy(desktopPlayerRole = role, desktopPlayerAvatar = "synthetic-owned-avatar.png"))
            app.chatRepository.updateSession(session.copy(lastMessagePreview = "later background write"))
            val latest = requireNotNull(app.chatRepository.getSession(session.id))
            assertEquals(role, latest.desktopPlayerRole)
            assertEquals("synthetic-owned-avatar.png", latest.desktopPlayerAvatar)
            val cleared = app.chatRepository.saveSessionSettingsDraft(saved, saved.copy(desktopPlayerRole = null))
            assertNull(cleared.desktopPlayerRole)
            assertEquals("synthetic-owned-avatar.png", cleared.desktopPlayerAvatar)
            assertEquals("later background write", cleared.lastMessagePreview)
        } finally { app.chatRepository.deleteSession(session.id) }
    }

    @Test fun streamingArchiveHonorsImagePolicyAndRestoresPlayerAvatarIndependently() = runBlocking<Unit> {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "player-package-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        val picture = File(root, "images/player.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1,2,3,4)) }
        val original = picture.readBytes()
        val storage = SaveSlotPackageStorage(context)
        try {
            for (policy in listOf(SaveSlotImagePolicy.NONE, SaveSlotImagePolicy.ORIGINAL)) {
                val role = SessionPlayerRole("synthetic-card", "synthetic-person")
                val slot = storage.createPackage(baseSlot = SaveSlot.create("synthetic-session", "synthetic").copy(
                    desktopPlayerAvatar = picture.absolutePath, desktopPlayerRole = role), imagePolicy = policy,
                    includeAudio = false, messageSource = {}, ragSource = {}, voices = emptyList())
                try {
                    storage.openReader(slot).use { reader ->
                        reader.validate()
                        assertEquals(role, slot.desktopPlayerRole)
                        val restored = reader.materializePlayerAvatar()
                        if (policy == SaveSlotImagePolicy.NONE) assertNull(restored)
                        else assertArrayEquals(original, File(requireNotNull(restored)).readBytes())
                    }
                } finally { storage.delete(slot) }
            }
            assertArrayEquals(original, picture.readBytes())
        } finally { root.deleteRecursively() }
    }
}
