package com.example.chatbar.desktop

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.runBlocking

/** Only observes the explicitly task-owned synthetic 1008 profile; never business data. */
internal object TraceSyntheticStartup {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val base = Path.of("H:/ChatChatBar-Desktop/app/desktopApp/build/preview-continuous-private-20261025/windows-runtime-merge-ui-1008")
        check(Files.readString(base.resolve("TASK_OWNER.json")).contains("CCB_DESKTOP_SYNTHETIC_WINDOWS_RUNTIME"))
        val root = base.resolve("data")
        val acquired = DesktopDataRootOwnership.acquire(DesktopDataRootResolution.Resolved(root,
            DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("unused-bootstrap"))) as DesktopDataRootOwnershipResult.Acquired
        acquired.ownership.use {
            repeat(8) { round ->
                val attributes = linkedMapOf<Path, List<Any>>()
                val identities = linkedMapOf<Path, Any?>()
                val result = DesktopIndexStartupPreflight(root, attributes = { path ->
                    val a = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    val values = listOf(a.creationTime(), a.lastModifiedTime(), a.size(), a.isDirectory)
                    val old = attributes.putIfAbsent(path, values)
                    if (old != null && old != values) println("ATTRIBUTE_DRIFT round=$round path=$path before=$old after=$values")
                    a
                }, identity = { path, directory ->
                    val value = DesktopIndexStartupPreflight.readWindowsIdentity(path, directory)
                    val old = identities.putIfAbsent(path, value)
                    if (old != null && old != value) println("IDENTITY_DRIFT round=$round path=$path before=$old after=$value")
                    value
                }).inspect()
                println("ROUND=$round RESULT=${result.code}")
            }
        }
    }
}
