package com.example.chatbar.desktop

import java.nio.file.Path
import java.nio.file.Files
import java.util.Locale
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinDef.HWND
import kotlin.test.*

class DesktopSecondInstanceStartupTest {
    private val root = Path.of("second-instance-test-root").toAbsolutePath()
    private val resolved = DesktopDataRootResolution.Resolved(root,
        DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, root.resolveSibling("bootstrap.json"))

    @Test fun `other process already using root returns normally without starting application`() = blocked(false)
    @Test fun `same JVM already using root returns normally without starting application`() = blocked(true)

    private fun blocked(sameJvm: Boolean) {
        var bodyCalls = 0
        var closeCalls = 0
        val presented = mutableListOf<Path>()
        runDesktopApplicationWithDataRootOwnership(resolved,
            acquireOwnership = { DesktopDataRootOwnershipResult.AlreadyInUse(root,
                root.resolve(DesktopDataRootOwnership.LOCK_FILE_NAME), sameJvm) },
            closeOwnership = { closeCalls++ },
            alreadyInUsePresenter = DesktopAlreadyInUsePresenter { presented.add(it) },
            applicationBody = { bodyCalls++ })
        assertEquals(0, bodyCalls)
        assertEquals(0, closeCalls)
        assertEquals(listOf(root), presented)
    }

    @Test fun `all genuine ownership failures stay fatal and do not show in-use notice`() {
        DesktopDataRootOwnershipFailureKind.entries.forEach { kind ->
            val failure = DesktopDataRootOwnershipResult.Failure(kind, root,
                root.resolve(DesktopDataRootOwnership.LOCK_FILE_NAME), "injected failure")
            val thrown = assertFailsWith<DesktopDataRootOwnershipException> {
                runDesktopApplicationWithDataRootOwnership(resolved,
                    acquireOwnership = { failure },
                    closeOwnership = { error("Must not close unowned root") },
                    alreadyInUsePresenter = DesktopAlreadyInUsePresenter { error("Not an in-use result") },
                    applicationBody = { error("Must not start") })
            }
            assertSame(failure, thrown.result)
        }
    }

    @Test fun `presenter exception or native linkage error does not escape startup or enter application`() {
        listOf(IllegalStateException("native failure"), UnsatisfiedLinkError("native unavailable")).forEach { failure ->
            var presentations = 0
            runDesktopApplicationWithDataRootOwnership(resolved,
                acquireOwnership = { DesktopDataRootOwnershipResult.AlreadyInUse(root,
                    root.resolve(DesktopDataRootOwnership.LOCK_FILE_NAME), false) },
                closeOwnership = { error("Not acquired") },
                alreadyInUsePresenter = DesktopAlreadyInUsePresenter { presentations++; throw failure },
                applicationBody = { error("Must not start") })
            assertEquals(1, presentations)
        }
    }

    @Test fun `message uses OS locale and absolute root without internal or global-instance claims`() {
        listOf(Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE, Locale.ENGLISH, Locale.JAPANESE).forEach { locale ->
            val message = desktopAlreadyInUseMessage(root, locale)
            assertEquals("ChatChatBar", message.title)
            assertTrue(message.text.contains(root.toString()))
            assertTrue(message.text.contains(if (locale.language == "zh") "同一数据目录" else "same data directory"))
            listOf("FileLock", "sameJvm", "OverlappingFileLockException", "Failed to launch JVM", "mutex").forEach {
                assertFalse(message.text.contains(it))
            }
        }
    }

    @Test fun `native presenter forwards unicode message null startup owner and standard OK information flags`() {
        var calls = 0
        val presenter = WindowsStartupMessagePresenter(api = { object : StartupMessageBoxApi {
            override fun MessageBoxW(owner: HWND?, text: WString, caption: WString, flags: Int): Int {
                calls++
                assertNull(owner)
                assertEquals(desktopAlreadyInUseMessage(root, Locale.SIMPLIFIED_CHINESE).text, text.toString())
                assertEquals("ChatChatBar", caption.toString())
                assertEquals(0x10040, flags)
                return 1
            }
        } }, locale = { Locale.SIMPLIFIED_CHINESE })
        presenter.showAlreadyInUse(root)
        assertEquals(1, calls)
    }

    @Test fun `native API failure is contained by startup boundary`() {
        var calls = 0
        val presenter = WindowsStartupMessagePresenter(api = { object : StartupMessageBoxApi {
            override fun MessageBoxW(owner: HWND?, text: WString, caption: WString, flags: Int): Int {
                calls++; return 0
            }
        } })
        runDesktopApplicationWithDataRootOwnership(resolved,
            acquireOwnership = { DesktopDataRootOwnershipResult.AlreadyInUse(root,
                root.resolve(DesktopDataRootOwnership.LOCK_FILE_NAME), false) },
            alreadyInUsePresenter = presenter,
            applicationBody = { error("Must not start") })
        assertEquals(1, calls)
    }

    @Test fun `startup architecture keeps locking and presentation separate`() {
        val sourceRoot = Path.of("src/main/kotlin/com/example/chatbar/desktop")
        val main = Files.readString(sourceRoot.resolve("Main.kt"))
        val branch = main.substringAfter("is DesktopDataRootOwnershipResult.AlreadyInUse ->")
            .substringBefore("is DesktopDataRootOwnershipResult.Failure ->")
        assertTrue(branch.contains("alreadyInUsePresenter.showAlreadyInUse(result.appDataRoot)"))
        assertTrue(branch.contains("return"))
        assertFalse(branch.contains("throw DesktopDataRootOwnershipException"))
        assertFalse(main.contains("System.exit("))
        assertFalse(main.contains("exitProcess("))
        val ownership = Files.readString(sourceRoot.resolve("DesktopDataRootOwnership.kt"))
        assertTrue(ownership.contains("channel.tryLock()"))
        assertTrue(ownership.contains("OverlappingFileLockException"))
        assertFalse(ownership.contains("Files.delete("))
        val presenter = Files.readString(sourceRoot.resolve("DesktopAlreadyInUsePresenter.kt"))
        assertFalse(presenter.contains("SettingsRepository"))
        assertFalse(presenter.contains("DesktopAppContainer"))
        assertFalse(presenter.contains("ComposeWindow"))
        val windowsBranch = presenter.substringAfter("startsWith(\"Windows\", ignoreCase = true)) {")
            .substringBefore("} else {")
        assertTrue(windowsBranch.contains("WindowsStartupMessagePresenter()"))
        assertFalse(windowsBranch.contains("JOptionPane"))
    }
}
