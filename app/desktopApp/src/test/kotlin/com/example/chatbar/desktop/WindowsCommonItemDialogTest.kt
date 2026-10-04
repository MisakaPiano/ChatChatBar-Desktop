package com.example.chatbar.desktop

import java.awt.EventQueue
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class WindowsCommonItemDialogTest {
    private val json = DesktopFileType("JSON", listOf("json"))

    @Test fun `filters preserve every existing caller extension and normalize suffixes`() {
        listOf(listOf("json"), listOf("png"), listOf("json", "png"),
            listOf("png", "jpg", "jpeg", "webp", "gif"), listOf("txt", "md", "json")).forEach { extensions ->
            val request = DesktopDialogRequest(DesktopDialogKind.OPEN, DesktopFileType("files", extensions))
            assertEquals(extensions.joinToString(";") { "*.$it" }, request.pattern)
            assertEquals(extensions.first(), request.defaultExtension)
        }
        val normalized = DesktopDialogRequest(DesktopDialogKind.SAVE,
            DesktopFileType("images", listOf(" .PNG ", "*.JpG", "png"), ".PNG"), "中文角色.PNG")
        assertEquals("*.png;*.jpg", normalized.pattern)
        assertEquals("png", normalized.defaultExtension)
        assertEquals("中文角色.PNG", normalized.suggestedName)
        assertFailsWith<IllegalArgumentException> { DesktopDialogRequest(DesktopDialogKind.OPEN, DesktopFileType("bad", listOf("json;*"))) }
    }

    @Test fun `save preserves supported suffix and appends default otherwise without writing`() {
        val request = DesktopDialogRequest(DesktopDialogKind.SAVE, DesktopFileType("cards", listOf("json", "png")))
        val root = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()
        listOf("card.JSON" to "card.JSON", "card.png" to "card.png", "角色" to "角色.json", "card.txt" to "card.txt.json").forEach { (input, output) ->
            assertEquals(root.resolve(output), request.resultPath(root.resolve(input).toString()))
        }
        assertEquals(root.resolve("card.json"), request.resultPath(root.resolve("child/../card.json").toString()))
        assertFailsWith<IllegalArgumentException> { request.resultPath("virtual-shell-item") }
    }

    @Test fun `open save and folder preserve options and release all resources in order`() {
        DesktopDialogKind.entries.forEach { kind ->
            val fake = Fake()
            WindowsCommonItemDialog({ 123L }, fake).use { service ->
                val request = DesktopDialogRequest(kind, if (kind == DesktopDialogKind.FOLDER) null else json, "角色.json")
                assertEquals(Path.of(fake.path).normalize(), service.pick(request))
                assertEquals((0x8000 or request.requiredOptions), fake.options)
                assertEquals(123L, fake.owner)
                assertEquals(kind, fake.kind)
                if (kind == DesktopDialogKind.FOLDER) {
                    assertFalse("filter" in fake.events)
                    assertTrue("title" in fake.events)
                } else assertEquals("*.json", fake.pattern)
                if (kind == DesktopDialogKind.SAVE) {
                    assertEquals("角色.json", fake.name)
                    assertEquals("json", fake.extension)
                }
            }
            assertEquals(listOf("free-path", "release-item", "release-dialog", "uninit"), fake.events.takeLast(4))
            assertEquals(1, fake.threads.distinct().size)
            assertTrue(fake.threads.singleOrNull()?.startsWith("CCB-native-file-dialog-STA") ?: fake.threads.all { it.startsWith("CCB-native-file-dialog-STA") })
        }
    }

    @Test fun `cancel alone returns null while real HRESULT error is explicit and releases`() {
        listOf(WINDOWS_DIALOG_CANCELLED, 0x80004005.toInt()).forEach { hr ->
            val fake = Fake().apply { showResult = hr }
            WindowsCommonItemDialog({ 123L }, fake).use { service ->
                if (hr == WINDOWS_DIALOG_CANCELLED) assertNull(service.pick(DesktopDialogRequest(DesktopDialogKind.OPEN, json)))
                else assertTrue(assertFailsWith<IllegalStateException> {
                    service.pick(DesktopDialogRequest(DesktopDialogKind.OPEN, json))
                }.message!!.contains("80004005"))
            }
            assertEquals(listOf("release-dialog", "uninit"), fake.events.takeLast(2))
            assertFalse("result" in fake.events)
        }
        assertEquals(0x800704C7.toInt(), WINDOWS_DIALOG_CANCELLED)
    }

    @Test fun `every setup and retrieval failure releases precisely the resources acquired`() {
        listOf("init", "create", "options", "set-options", "filter", "extension", "name", "show", "result", "display", "read").forEach { step ->
            val fake = Fake().apply { failAt = step }
            WindowsCommonItemDialog({ 123L }, fake).use { service ->
                assertSame(fake.failure, assertFailsWith<IllegalStateException> {
                    service.pick(DesktopDialogRequest(DesktopDialogKind.SAVE, json, "card.json"))
                })
            }
            assertEquals(if (step == "init") 0 else 1, fake.events.count { it == "uninit" }, step)
            assertEquals(if (step in listOf("init", "create")) 0 else 1, fake.events.count { it == "release-dialog" }, step)
            assertEquals(if (step in listOf("display", "read")) 1 else 0, fake.events.count { it == "release-item" }, step)
            assertEquals(if (step == "read") 1 else 0, fake.events.count { it == "free-path" }, step)
        }
    }

    @Test fun `missing owner fails explicitly before initializing COM`() {
        val fake = Fake()
        WindowsCommonItemDialog({ error("No owner") }, fake).use { service ->
            assertFailsWith<IllegalStateException> { service.pick(DesktopDialogRequest(DesktopDialogKind.OPEN, json)) }
        }
        assertTrue(fake.events.isEmpty())
    }

    @Test fun `EDT stays responsive and concurrent native dialog is rejected without another COM operation`() {
        val fake = Fake()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        fake.onShow = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        WindowsCommonItemDialog({ 123L }, fake).use { service ->
            EventQueue.invokeLater {
                try { service.pick(DesktopDialogRequest(DesktopDialogKind.OPEN, json)) }
                catch (error: Throwable) { failure.set(error) }
                finally { completed.countDown() }
            }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                EventQueue.invokeAndWait {
                    assertFailsWith<IllegalStateException> { service.pick(DesktopDialogRequest(DesktopDialogKind.OPEN, json)) }
                }
                assertEquals(1, fake.events.count { it == "init" })
            } finally { release.countDown() }
            assertTrue(completed.await(10, TimeUnit.SECONDS))
            assertNull(failure.get())
        }
    }

    @Test fun `one service reuses its STA and cannot be used after shutdown`() {
        val fake = Fake()
        val service = WindowsCommonItemDialog({ 123L }, fake)
        repeat(3) { service.prepare(DesktopDialogRequest(DesktopDialogKind.OPEN, json)) }
        service.close()
        assertEquals(1, fake.threads.distinct().size)
        assertEquals(3, fake.events.count { it == "uninit" })
        assertFailsWith<java.util.concurrent.RejectedExecutionException> { service.prepare(DesktopDialogRequest(DesktopDialogKind.OPEN, json)) }
    }

    private class Fake : CommonDialogBackend {
        val events = mutableListOf<String>()
        val threads = mutableListOf<String>()
        val failure = IllegalStateException("injected native error")
        var failAt: String? = null
        var showResult = 0
        var options = 0
        var owner = 0L
        var kind: DesktopDialogKind? = null
        var pattern: String? = null
        var name: String? = null
        var extension: String? = null
        var onShow: () -> Unit = {}
        val path = Path.of(System.getProperty("java.io.tmpdir"), "card.json").toAbsolutePath().toString()
        private fun step(name: String) {
            events += name
            threads += Thread.currentThread().name
            if (failAt == name) throw failure
        }
        override fun initializeSta(): AutoCloseable { step("init"); return AutoCloseable { step("uninit") } }
        override fun create(kind: DesktopDialogKind): CommonDialogHandle {
            step("create"); this.kind = kind
            return object : CommonDialogHandle {
                override fun getOptions(): Int { step("options"); return 0x8000 or 0x200 }
                override fun setOptions(options: Int) { step("set-options"); this@Fake.options = options }
                override fun setFilter(description: String, pattern: String) { step("filter"); this@Fake.pattern = pattern }
                override fun setDefaultExtension(extension: String) { step("extension"); this@Fake.extension = extension }
                override fun setFileName(name: String) { step("name"); this@Fake.name = name }
                override fun setTitle(title: String) { step("title") }
                override fun show(owner: Long): Int { step("show"); this@Fake.owner = owner; onShow(); return showResult }
                override fun result(): CommonDialogItem {
                    step("result")
                    return object : CommonDialogItem {
                        override fun displayName(): CommonDialogPath {
                            step("display")
                            return object : CommonDialogPath {
                                override fun read(): String { step("read"); return path }
                                override fun close() { step("free-path") }
                            }
                        }
                        override fun close() { step("release-item") }
                    }
                }
                override fun close() { step("release-dialog") }
            }
        }
    }
}
