package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.DesktopArchiveFiles
import com.example.chatbar.desktop.DesktopIndexInspectionCode
import com.example.chatbar.desktop.JReadRefusal
import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.*

/** Cloud fixture prerequisite proof; does not loosen production archive-path validation. */
class DesktopArchiveTempIdentityTest {
    private interface Paths : StdCallLibrary {
        fun GetLongPathName(path: String, buffer: CharArray, length: Int): Int
    }

    @Test fun `observe default temp refusal separately from owned canonical temp actual native read`() = runBlocking {
        val review = Path.of(requireNotNull(System.getenv("CCB_ARCHIVE_PATH_REVIEW_DIR")))
        val canonicalRoot = Path.of(requireNotNull(System.getenv("CCB_ARCHIVE_CANONICAL_TEMP")))
        require(Files.isDirectory(review) && Files.isDirectory(canonicalRoot))
        val payload = byteArrayOf(1, 2, 3, 4)
        val raw = Files.createTempFile("ccb-archive-native-prerequisite-", ".bin")
        val canonical = Files.createTempFile(canonicalRoot, "ccb-archive-native-prerequisite-", ".bin")
        try {
            Files.write(raw, payload); Files.write(canonical, payload)
            val api = Native.load("kernel32", Paths::class.java, W32APIOptions.UNICODE_OPTIONS)
            val buffer = CharArray(1025)
            val length = api.GetLongPathName(DesktopArchiveFiles.nativePath(raw), buffer, buffer.size)
            require(length in 1..1024)
            val longPath = String(buffer, 0, length).removePrefix("\\\\?\\")
            val rawResult = runCatching { DesktopArchiveFiles.hash(raw, 4) }
            val canonicalResult = runCatching { DesktopArchiveFiles.hash(canonical, 4) }
            val refusal = (rawResult.exceptionOrNull() as? JReadRefusal)?.code
            val sameName = raw.toAbsolutePath().normalize().toString().equals(longPath, ignoreCase = true)
            Files.writeString(review.resolve("temp-path-native-proof.json"), buildJsonObject {
                put("rawTemporaryPath", raw.toString()); put("nativeLongPath", longPath)
                put("sameCanonicalName", sameName)
                put("rawReadPassed", rawResult.isSuccess)
                put("rawRefusalCode", refusal?.name.orEmpty())
                put("canonicalReadPassed", canonicalResult.isSuccess)
                put("canonicalRefusalCode", (canonicalResult.exceptionOrNull() as? JReadRefusal)?.code?.name.orEmpty())
                put("canonicalTemp", canonicalRoot.toString())
            }.toString())
            assertEquals(DesktopBackupPolicy.digest(payload), canonicalResult.getOrThrow())
            if (rawResult.isFailure) {
                assertFalse(sameName)
                assertEquals(DesktopIndexInspectionCode.UNSAFE_PATH, refusal)
            } else assertEquals(DesktopBackupPolicy.digest(payload), rawResult.getOrThrow())
        } finally {
            Files.deleteIfExists(raw); Files.deleteIfExists(canonical)
        }
    }
}
