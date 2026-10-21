@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path

internal fun saveR1Scene(scene: ImageComposeScene, name: String) {
    val folder = Files.createDirectories(Path.of("build/p11-gui-r1-20261022/layout-previews"))
    scene.render(System.nanoTime()).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use {
        Files.write(folder.resolve("$name.png"), it.bytes)
    } }
}
