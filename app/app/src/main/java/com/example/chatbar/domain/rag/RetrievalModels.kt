package com.example.chatbar.domain.rag

data class RetrievalIntent(
    val topic: List<String> = emptyList(),
    val queries: List<String> = emptyList(),
    val entities: List<String> = emptyList()
) {
    val shouldRecall: Boolean
        get() = topic.isNotEmpty() || queries.isNotEmpty() || entities.isNotEmpty()
}

typealias RetrievalPlan = RetrievalIntent
