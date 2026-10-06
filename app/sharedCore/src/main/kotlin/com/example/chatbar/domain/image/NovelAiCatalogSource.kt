package com.example.chatbar.domain.image

import kotlinx.coroutines.flow.StateFlow

interface NovelAiCompletionCatalog {
    val completionVersion: StateFlow<String>
    suspend fun prepareCompletion()
    suspend fun streamCompletion(query: String, onCandidate: suspend (NovelAiTagCandidate) -> Unit, onWarning: (String) -> Unit)
}

interface NovelAiDictionarySource {
    fun lookup(word: String): String?
    fun search(query: String): List<NovelAiTagCandidate>
    suspend fun prepareCompletion()
    suspend fun streamCompletion(query: String, onCandidate: suspend (NovelAiTagCandidate) -> Unit, onWarning: (String) -> Unit)
}
