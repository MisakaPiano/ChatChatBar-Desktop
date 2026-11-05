package com.example.chatbar.desktop

import java.nio.file.Path

/** A single Windows filename component; Unicode identifiers are valid and are not rewritten. */
internal fun requireMomentStorageId(value: String) {
    val path = Path.of(value)
    require(value.isNotBlank() && value.length <= 250 && value !in setOf(".", "..") &&
        !path.isAbsolute && path.nameCount == 1 && value.none { it.code < 32 || it in "<>:\"/\\|?*" } &&
        !value.endsWith('.') && !value.endsWith(' ') &&
        !value.substringBefore('.').matches(Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])")))
}
