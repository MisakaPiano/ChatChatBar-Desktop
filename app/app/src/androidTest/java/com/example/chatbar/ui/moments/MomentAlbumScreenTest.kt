package com.example.chatbar.ui.moments

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.domain.moment.MomentAlbumFilter
import com.example.chatbar.domain.moment.MomentAlbumPolicy
import com.example.chatbar.ui.kit.ChatBarTheme
import java.time.LocalDate
import java.time.ZoneId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MomentAlbumScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun post(id: String, card: String, text: String, date: String) = MomentPost(
        id = id, characterCardId = card, sessionId = "session", senderName = "作者", text = text,
        scheduledAt = 0, generatedAt = LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    )
    private val posts = listOf(post("first", "a", "海边", "2026-10-01"), post("second", "b", "花园", "2026-09-30"))

    @Test fun roleFolderAndFlatTilesLocateTheOriginalPost() {
        val filter = mutableStateOf(MomentAlbumFilter())
        var target: String? = null
        val file = File(rule.activity.cacheDir, "moment-album-fixture.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val imagePosts = listOf(posts.first().copy(imagePath = file.absolutePath), posts.last())
        rule.setContent {
            ChatBarTheme {
                MomentAlbumScreen(MomentAlbumPolicy.build(imagePosts, mapOf("a" to "甲", "b" to "乙"), filter.value),
                    { filter.value = it(filter.value) }, {}, { target = it })
            }
        }
        rule.onNodeWithContentDescription("角色相册 乙，1 条").performClick()
        rule.onNodeWithContentDescription("定位朋友圈 作者：花园").performClick()
        rule.runOnIdle { assertEquals("second", target) }
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithContentDescription("角色相册 甲，1 条").assertIsDisplayed()
        rule.onNodeWithContentDescription("展开全部朋友圈").performClick()
        rule.onNodeWithContentDescription("定位朋友圈 作者：海边").performClick()
        rule.runOnIdle { assertEquals("first", target) }
    }

    @Test fun keywordAndDateFilterCanBeClearedWithoutLosingPosts() {
        val filter = mutableStateOf(MomentAlbumFilter(groupByCard = false))
        rule.setContent {
            ChatBarTheme {
                MomentAlbumScreen(MomentAlbumPolicy.build(posts, emptyMap(), filter.value),
                    { filter.value = it(filter.value) }, {}, {})
            }
        }
        rule.onNodeWithContentDescription("按日期筛选").performClick()
        rule.onNodeWithText("按月").performClick()
        rule.onNodeWithText("应用").performClick()
        rule.onNodeWithContentDescription("定位朋友圈 作者：海边").assertIsDisplayed()
        rule.onNodeWithContentDescription("搜索朋友圈").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("花园")
        rule.onNodeWithText("没有符合条件的朋友圈").assertIsDisplayed()
        rule.onNodeWithContentDescription("清除筛选").performClick()
        rule.onNodeWithContentDescription("定位朋友圈 作者：海边").assertIsDisplayed()
        rule.onNodeWithContentDescription("定位朋友圈 作者：花园").assertIsDisplayed()
    }
}
