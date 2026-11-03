package com.example.chatbar.desktop

import java.security.MessageDigest
import java.util.Properties

/** Producer-declared purpose/stage, not guessed from request content or a UI selection. */
internal data class DesktopPromptDiagnosticContext(val kind: String, val stage: String) {
    init { require(listOf(kind, stage).all { it.matches(Regex("[A-Z][A-Z0-9_]{0,95}")) }) }
}

data class DesktopPromptStageIdentity(val kind: String, val stage: String, val templateVersion: String?)

internal object DesktopPromptDiagnosticIdentity {
    val sourceSha256: String? by lazy {
        val properties = Properties()
        DesktopPromptDiagnosticIdentity::class.java.getResourceAsStream("/desktop-build.properties")
            ?.use { properties.load(it) }
        properties.getProperty("promptSourceSha256")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }

    fun identify(context: DesktopPromptDiagnosticContext, source: String? = sourceSha256): DesktopPromptStageIdentity =
        DesktopPromptStageIdentity(context.kind, context.stage, source?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let {
            MessageDigest.getInstance("SHA-256").digest("$it:${context.kind}:${context.stage}".toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }.take(16)
        })
}
