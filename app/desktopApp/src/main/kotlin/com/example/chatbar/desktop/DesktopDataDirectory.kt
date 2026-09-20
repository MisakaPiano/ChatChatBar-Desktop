package com.example.chatbar.desktop

import java.nio.file.Path

object DesktopDataDirectory {
    const val DIRECTORY_NAME = "ChatChatBarDesktop"

    fun resolve(): Path {
        val userHome = System.getProperty("user.home")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(Path::of)
            ?: Path.of(".").toAbsolutePath()

        return resolve(System.getenv(), userHome)
    }

    fun resolve(environment: Map<String, String>, userHome: Path): Path {
        val localAppData = environment["LOCALAPPDATA"]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(Path::of)
        val dataRoot = localAppData ?: userHome.resolve("AppData").resolve("Local")

        return dataRoot.resolve(DIRECTORY_NAME).normalize()
    }
}
