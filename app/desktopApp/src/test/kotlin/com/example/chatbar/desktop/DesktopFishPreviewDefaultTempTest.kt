package com.example.chatbar.desktop

import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

private interface PreviewShortPaths : StdCallLibrary {
    fun GetShortPathNameW(path: WString, result: CharArray, length: Int): Int
}

class DesktopFishPreviewDefaultTempTest {
    private fun newCase(): Path {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        return Files.createTempDirectory("ccb-preview-default-temp-")
    }

    private fun alias(path: Path): Path {
        val buffer = CharArray(32768)
        val length = Native.load("kernel32", PreviewShortPaths::class.java)
            .GetShortPathNameW(WString(path.toString()), buffer, buffer.size)
        assumeTrue("8.3 alias unavailable: NOT_TESTED", length in 1 until buffer.size)
        val result = Path.of(String(buffer, 0, length))
        assumeTrue("8.3 aliases disabled: NOT_TESTED", result != path)
        return result
    }

    private fun child(parent: Path, mode: String) {
        val builder = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
            "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-Djava.io.tmpdir=$parent",
            "-cp", requireNotNull(System.getProperty("ccb.readonly.classpath")),
            PreviewDefaultTempChild::class.java.name, parent.toString(), mode,
        ).redirectErrorStream(true)
        listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").forEach(builder.environment()::remove)
        val output = Files.createTempFile("preview-default-child-", ".log")
        val process = builder.redirectOutput(output.toFile()).start()
        process.outputStream.close()
        try {
            assertTrue(process.waitFor(40, TimeUnit.SECONDS), "Owned synthetic child timeout")
            assertEquals(0, process.exitValue(), Files.readString(output))
            assertTrue(Files.readString(output).contains("PREVIEW_DEFAULT_TEMP_$mode"))
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
            Files.deleteIfExists(output)
        }
    }

    @Test fun `OS short temp expands before owned directory creation and bytes stay verified`() {
        val root = newCase()
        child(root, "WRITE")
        child(alias(root), "WRITE")
    }

    @Test fun `an explicitly supplied external alias stays rejected`() {
        val root = newCase()
        assertFailsWith<IllegalStateException> { DesktopFishPreviewStore(alias(root)) }
    }

    @Test fun `OS short temp keeps animated clipboard bytes and file custody`() {
        child(alias(newCase()), "CLIPBOARD")
    }

    @Test fun `default temp junction is refused before preview publication`() {
        val root = newCase()
        val target = Files.createDirectory(root.resolve("target"))
        val link = root.resolve("junction")
        val junction = ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        assumeTrue("Native junction unavailable: NOT_TESTED", junction.waitFor(10, TimeUnit.SECONDS) && junction.exitValue() == 0)
        child(link, "REFUSE")
        assertTrue(Files.list(target).use { paths -> paths.noneMatch { it.fileName.toString().startsWith("ccb-fish-preview-") } })
        Files.delete(link)
    }
}

object PreviewDefaultTempChild {
    @JvmStatic fun main(args: Array<String>) {
        if (args[1] == "REFUSE") {
            val result = runCatching { DesktopFishPreviewStore() }
            result.getOrNull()?.close()
            check(result.exceptionOrNull() is IllegalStateException)
        } else if (args[1] == "CLIPBOARD") {
            kotlinx.coroutines.runBlocking {
                val png = DesktopImageEditing.png(raster(3, 2, IntArray(6) { 0xffff0000.toInt() }))
                val bytes = DesktopImageTools.disguise(png)
                val clipboard = java.awt.datatransfer.Clipboard("synthetic-short-temp")
                copyDesktopImageResult(bytes, clipboard)
                val files = clipboard.getData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<*>
                val path = (files.single() as java.io.File).toPath()
                check(Files.readAllBytes(path).contentEquals(bytes))
                check(Files.isSameFile(path, path.toRealPath()))
            }
        } else {
            val store = DesktopFishPreviewStore()
            var closed = false
            try {
                val bytes = fishPreviewMp3()
                val file = store.write(bytes.inputStream(), bytes.size.toLong()) {}
                check(file.parent.parent == Path.of(args[0]).toRealPath(NOFOLLOW_LINKS))
                check(Files.readAllBytes(file).contentEquals(bytes))
                store.verify(file)
                store.close(); closed = true
                check(!Files.exists(file.parent))
            } finally { if (!closed) store.close() }
        }
        println("PREVIEW_DEFAULT_TEMP_${args[1]}")
    }
}
