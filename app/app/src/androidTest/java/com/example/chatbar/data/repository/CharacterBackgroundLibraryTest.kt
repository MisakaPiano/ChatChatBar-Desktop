package com.example.chatbar.data.repository

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CharacterBackgroundLibraryTest {
    @Test fun concurrentLibraryChangesMergeIntoLatestCardAndSurviveColdRead() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "synthetic-gallery-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir(): File = root }
        try {
            val repo = CharacterRepository(JsonFileStorage(context))
            val card = CharacterCard.create("synthetic").copy(chatBackground="default.png",coverImage="cover.png",basicSetting="unchanged")
            repo.save(card)
            coroutineScope {
                (1..8).map { i -> async { repo.updateBackgroundLibrary(card.id) { current ->
                    current.copy(chatBackgroundImages = current.chatBackgroundImages + "original-$i.png")
                } } }.awaitAll()
            }
            val cold = CharacterRepository(JsonFileStorage(context)).getById(card.id)!!
            assertEquals(8, cold.chatBackgroundImages.size)
            assertEquals(card.basicSetting, cold.basicSetting)
            assertEquals(card.coverImage, cold.coverImage)
            assertEquals(card.chatBackground, cold.chatBackground)
        } finally { root.deleteRecursively() }
    }
}
