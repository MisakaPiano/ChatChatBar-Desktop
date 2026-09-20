package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import java.nio.file.Path

class DesktopAppContainer(val appDataRoot: Path) {
    val jsonFileStorage = JsonFileStorage(appDataRoot)
}
