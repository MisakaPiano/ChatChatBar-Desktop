package com.example.chatbar.ui.manage

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.ui.components.PlayerRoleControls
import com.example.chatbar.ui.kit.ChatBarTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlayerRoleControlsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun followFlagsAreIndependentAndUnlinkPreservesCard() {
        val person = CharacterInfo.create("synthetic person")
        val card = CharacterCard.create("synthetic", characters = listOf(person))
        var latest: SessionPlayerRole? = SessionPlayerRole(card.id, person.id)
        rule.setContent { ChatBarTheme {
            var role by remember { mutableStateOf(latest) }
            PlayerRoleControls(card, role) { role = it; latest = it }
        } }
        rule.onNodeWithContentDescription("姓名跟随角色").assertIsOn().performClick()
        rule.runOnIdle { assertFalse(requireNotNull(latest).followName); assertTrue(requireNotNull(latest).followAvatar) }
        rule.onNodeWithContentDescription("头像跟随角色").assertIsOn().performClick()
        rule.runOnIdle { assertFalse(requireNotNull(latest).followAvatar) }
        rule.onNodeWithText("解除角色关联").performClick()
        rule.runOnIdle { assertNull(latest); assertEquals(person, card.characters.single()) }
    }
    @Test fun userBubbleShowsPlayerAvatarAndNameWithoutChangingStoredContent() {
        val message = ChatMessage(id = "synthetic-message", sessionId = "synthetic-session", role = MessageRole.USER,
            content = "synthetic original content", createdAt = 1, updatedAt = 1)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = java.io.File(context.cacheDir, "synthetic-player-ui.png")
        val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(android.graphics.Color.GREEN); file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        try {
            rule.setContent { ChatBarTheme { com.example.chatbar.ui.components.ChatBubble(message,
                renderPlayerName = "synthetic linked player", playerAvatarPath = file.absolutePath) } }
            rule.onNodeWithText("synthetic linked player").assertIsDisplayed()
            rule.onNodeWithContentDescription("玩家头像").assertIsDisplayed()
            // Presence of an AsyncImage node does not prove that Coil finished loading it.
            rule.waitUntil(timeoutMillis = 10_000) {
                val pixels = rule.onNodeWithContentDescription("玩家头像").captureToImage().asAndroidBitmap()
                try {
                    val center = pixels.getPixel(pixels.width / 2, pixels.height / 2)
                    android.graphics.Color.green(center) > 230 && android.graphics.Color.red(center) < 20 && android.graphics.Color.blue(center) < 20
                } finally { pixels.recycle() }
            }
            rule.onNodeWithText("synthetic original content").assertIsDisplayed()
            assertEquals("synthetic original content", message.content)
            val output = java.io.File(context.getExternalFilesDir(null), "cover-gallery-evidence").apply { mkdirs() }
            val capture = rule.onRoot().captureToImage().asAndroidBitmap()
            java.io.File(output, "player-avatar-bubble.png").outputStream().use { capture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { file.delete() }
    }

}
