package com.example.chatbar.domain.image

fun String.normalizeDanbooruTagQuery(): String {
    val collapsed = replace(Regex("\\s+"), " ").trim().take(80)
    val containsCjk = collapsed.any { char ->
        char.code in 0x3400..0x9FFF || char.code in 0xF900..0xFAFF
    }
    return if (containsCjk) collapsed.replace(" ", "") else collapsed.replace(" ", "_")
}
