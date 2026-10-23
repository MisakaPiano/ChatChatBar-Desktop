package com.example.chatbar.desktop.backup

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString

/** Synthetic-only fixture producer, not a user-archive entry. Public dummy password from existing fixtures. */
internal object M1SyntheticFixture {
    val output: Path get() = Path.of("app/desktopApp/build/readonly-r0/m1r1/SYNTHETIC-code84-encrypted.cbbackup").toAbsolutePath()
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.isEmpty())
        val bytes = CensusWireFixtures.bytes(prefs = officialWireJson.encodeToString(Official145Preferences()), files = emptyMap(), encrypted = true)
        Files.createDirectories(output.parent)
        // Only our single synthetic target; never overwrite unidentified bytes.
        require(!Files.exists(output))
        Files.write(output, bytes)
        val pw = CensusWireFixtures.PASSWORD.toCharArray()
        try {
            val info = DesktopBackupReadOnlyInspector().inspect(output, pw)
            check(info.encrypted && info.appVersionCode == 84 && info.envelopeVersion == 1)
            check(Files.readAllBytes(output).contentEquals(bytes))
            val wrong = CharArray(24) { 'x' }
            try {
                try { DesktopBackupReadOnlyInspector().inspect(output, wrong); error("WRONG_PASSWORD_ACCEPTED") }
                catch (e: DesktopBackupReadOnlyInspector.InspectionFailure) { check(e.stage == "AUTHENTICATION") }
            } finally { wrong.fill('\u0000') }
            println("SYNTHETIC_FIXTURE=VERIFIED_CODE84_V1_ENCRYPTED")
            println("WRONG_PASSWORD=REJECTED")
            println("SHA256=" + CensusWireFixtures.digest(bytes))
        } finally { pw.fill('\u0000') }
    }
}

internal object M1PlainFixture {
    @JvmStatic fun main(args: Array<String>) {
        require(args.isEmpty())
        val target = M1SyntheticFixture.output.resolveSibling("SYNTHETIC-code84-plain.cbbackup")
        val bytes = CensusWireFixtures.bytes(prefs = officialWireJson.encodeToString(Official145Preferences()), files = emptyMap())
        Files.write(target, bytes, java.nio.file.StandardOpenOption.CREATE_NEW)
        println("SYNTHETIC_PLAIN=CREATED")
    }
}
