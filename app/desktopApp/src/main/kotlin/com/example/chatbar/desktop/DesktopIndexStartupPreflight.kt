package com.example.chatbar.desktop

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.Memory
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinNT
import java.io.IOException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class DesktopIndexPreflightCode {
    NO_DOMAIN, DOMAIN_PRESENT_UNSUPPORTED, OWNERSHIP_MISSING, UNSAFE_PATH,
    READ_ERROR, BUDGET_EXCEEDED, CHANGED_DURING_CHECK,
}

/** No path, exception text, payload or business-readiness assertion is exposed. */
internal data class DesktopIndexPreflightResult(val code: DesktopIndexPreflightCode,
    val examinedEntries: Long, val examinedDirectories: Long, val examinedRegularFiles: Long,
    val externalFileLinksProvenAbsent: Boolean = false)

/** Metadata probe budgets, NOT a journal schema or an official data format limit. */
internal data class DesktopIndexPreflightBudget(val entries: Long = 256, val depth: Int = 4,
    val nameChars: Int = 255, val totalNameChars: Long = 8192,
    val totalPathChars: Long = 32768, val ancestors: Int = 64,
    val fileBytes: Long = 64L * 1024 * 1024) {
    init {
        require(entries in 1..256 && depth in 0..4 && nameChars in 1..255 &&
            totalNameChars in 1..8192 && totalPathChars in 1..32768 && ancestors in 1..64 &&
            fileBytes in 0..64L * 1024 * 1024)
    }
}

internal enum class DesktopIndexPreflightStep { ROOT_OBSERVED, DIRECTORY_OPENED, ENTRY_OBSERVED, BEFORE_RECHECK }

/** Startup metadata admission only; no protocol parsing, file-content reads, recovery or new root lock.
 * NIO seams/hooks allow deterministic IO/cancellation tests; default code only reads metadata.
 * Existing ownership is cooperative: observation/recheck detects drift, not every Windows TOCTOU.
 */
internal class DesktopIndexStartupPreflight(
    private val root: Path,
    private val budget: DesktopIndexPreflightBudget = DesktopIndexPreflightBudget(),
    private val onStep: suspend (DesktopIndexPreflightStep) -> Unit = {},
    private val attributes: (Path) -> BasicFileAttributes = { Files.readAttributes(it, BasicFileAttributes::class.java, NOFOLLOW_LINKS) },
    private val directory: (Path) -> DirectoryStream<Path> = { Files.newDirectoryStream(it) },
    private val identity: (Path, Boolean) -> Any? = ::readWindowsIdentity,
) {
    suspend fun inspect(): DesktopIndexPreflightResult = withContext(Dispatchers.IO) { Probe().run() }

    private class Refused(val code: DesktopIndexPreflightCode) : Exception()
    private data class Stamp(val key: Any, val created: Any, val modified: Any, val size: Long, val directory: Boolean)

    private inner class Probe {
        private val observed = linkedMapOf<Path, Stamp>()
        private val identities = hashMapOf<Any, Path>()
        private val absent = mutableListOf<Path>()
        private var entries = 0L; private var directories = 0L; private var files = 0L
        private var names = 0L; private var paths = 0L; private var bytes = 0L

        suspend fun run(): DesktopIndexPreflightResult = try {
            currentCoroutineContext().ensureActive()
            requireOwnership()
            if (!System.getProperty("os.name").startsWith("Windows") || !root.isAbsolute ||
                root != root.normalize() || root.toString().startsWith("\\\\")) refuse(DesktopIndexPreflightCode.UNSAFE_PATH)
            if (root.nameCount > budget.ancestors) refuse(DesktopIndexPreflightCode.BUDGET_EXCEEDED)
            var ancestor = root.root
            observe(ancestor, mustBeDirectory = true)
            for (part in root) {
                if (!safeName(part.toString())) refuse(DesktopIndexPreflightCode.UNSAFE_PATH)
                ancestor = ancestor.resolve(part); observe(ancestor, mustBeDirectory = true)
            }
            if (!root.toRealPath(NOFOLLOW_LINKS).toString().equals(root.toString(), ignoreCase = true))
                refuse(DesktopIndexPreflightCode.UNSAFE_PATH)
            onStep(DesktopIndexPreflightStep.ROOT_OBSERVED)
            val entities = findChild(root, "entities")
            val domain = entities?.let { observe(it, true); findChild(it, CANDIDATE_DOMAIN) }
            if (domain != null) { observe(domain, true); scanDomain(domain, 0) }
            onStep(DesktopIndexPreflightStep.BEFORE_RECHECK)
            requireOwnership()
            for ((path, stamp) in observed) recheck(path, stamp)
            for (path in absent) {
                currentCoroutineContext().ensureActive()
                try { attributes(path); refuse(DesktopIndexPreflightCode.CHANGED_DURING_CHECK) }
                catch (_: NoSuchFileException) { /* Missing only after its safe parent was rechecked. */ }
            }
            requireOwnership()
            result(if (domain == null) DesktopIndexPreflightCode.NO_DOMAIN else DesktopIndexPreflightCode.DOMAIN_PRESENT_UNSUPPORTED)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (rejected: Refused) { result(rejected.code) }
        catch (_: ArithmeticException) { result(DesktopIndexPreflightCode.BUDGET_EXCEEDED) }
        catch (_: IOException) { result(DesktopIndexPreflightCode.READ_ERROR) }
        catch (_: SecurityException) { result(DesktopIndexPreflightCode.READ_ERROR) }
        catch (_: Exception) { result(DesktopIndexPreflightCode.READ_ERROR) }

        private fun requireOwnership() {
            if (!DesktopDataRootOwnership.isHeld(root)) refuse(DesktopIndexPreflightCode.OWNERSHIP_MISSING)
        }

        private suspend fun findChild(parent: Path, name: String): Path? {
            var found: Path? = null
            enumerate(parent) { child ->
                if (child.fileName.toString().equals(name, ignoreCase = true)) {
                    if (child.fileName.toString() != name) refuse(DesktopIndexPreflightCode.UNSAFE_PATH)
                    found = child
                }
            }
            if (found == null) absent.add(parent.resolve(name))
            return found
        }

        private suspend fun scanDomain(parent: Path, depth: Int) {
            enumerate(parent) { child ->
                if (Math.addExact(depth, 1) > budget.depth) refuse(DesktopIndexPreflightCode.BUDGET_EXCEEDED)
                val stamp = observe(child, false)
                onStep(DesktopIndexPreflightStep.ENTRY_OBSERVED)
                if (stamp.directory) scanDomain(child, depth + 1)
            }
        }

        private suspend fun enumerate(parent: Path, visit: suspend (Path) -> Unit) {
            // Revalidate already observed ancestors immediately before opening each directory.
            for ((path, stamp) in observed) if (parent.startsWith(path)) recheck(path, stamp)
            val seenNames = hashSetOf<String>()
            directory(parent).use { stream ->
                directories = add(directories, 1, budget.entries)
                onStep(DesktopIndexPreflightStep.DIRECTORY_OPENED)
                for (child in stream) {
                    currentCoroutineContext().ensureActive(); requireOwnership()
                    if (child.parent != parent || !safeName(child.fileName.toString()) ||
                        !seenNames.add(child.fileName.toString().lowercase(Locale.ROOT))) refuse(DesktopIndexPreflightCode.UNSAFE_PATH)
                    entries = add(entries, 1, budget.entries)
                    names = add(names, child.fileName.toString().length.toLong(), budget.totalNameChars)
                    visit(child)
                }
            }
        }

        private suspend fun observe(path: Path, mustBeDirectory: Boolean): Stamp {
            currentCoroutineContext().ensureActive()
            val a = attributes(path)
            validate(path, a, mustBeDirectory)
            val stamp = stamp(path, a)
            observed[path]?.let { if (it != stamp) refuse(DesktopIndexPreflightCode.CHANGED_DURING_CHECK); return it }
            val previous = identities.putIfAbsent(stamp.key, path)
            if (previous != null && previous != path) refuse(DesktopIndexPreflightCode.UNSAFE_PATH)
            paths = add(paths, path.toString().length.toLong(), budget.totalPathChars)
            if (!a.isDirectory) { files = add(files, 1, budget.entries); bytes = add(bytes, a.size(), budget.fileBytes) }
            observed[path] = stamp
            return stamp
        }

        private suspend fun recheck(path: Path, old: Stamp) {
            currentCoroutineContext().ensureActive(); requireOwnership()
            val a = try { attributes(path) } catch (_: NoSuchFileException) { refuse(DesktopIndexPreflightCode.CHANGED_DURING_CHECK) }
            if (identity(path, a.isDirectory) != old.key || a.creationTime() != old.created || a.isDirectory != old.directory ||
                (path.startsWith(root) && (a.lastModifiedTime() != old.modified || (!a.isDirectory && a.size() != old.size))))
                refuse(DesktopIndexPreflightCode.CHANGED_DURING_CHECK)
            validate(path, a, old.directory)
        }

        private fun validate(path: Path, a: BasicFileAttributes, mustBeDirectory: Boolean) {
            if (a.isSymbolicLink || a.isOther ||
                (mustBeDirectory && !a.isDirectory) || (!a.isDirectory && !a.isRegularFile)) refuse(DesktopIndexPreflightCode.UNSAFE_PATH)
            val native = Kernel32.INSTANCE.GetFileAttributes(path.toString())
            if (native == -1) refuse(DesktopIndexPreflightCode.READ_ERROR)
            if (native and 0x400 != 0 || (native and 0x10 != 0) != a.isDirectory) refuse(DesktopIndexPreflightCode.UNSAFE_PATH)
        }
        private fun stamp(path: Path, a: BasicFileAttributes) = Stamp(
            identity(path, a.isDirectory) ?: throw Refused(DesktopIndexPreflightCode.UNSAFE_PATH),
            // BasicFileAttributes.size is unspecified for directories. Windows enumeration and
            // a later direct query can report 0 and 4096 for the same unchanged directory.
            a.creationTime(), a.lastModifiedTime(), if (a.isDirectory) 0L else a.size(), a.isDirectory)
        private fun add(current: Long, amount: Long, maximum: Long): Long = Math.addExact(current, amount).also {
            if (amount < 0 || it > maximum) refuse(DesktopIndexPreflightCode.BUDGET_EXCEEDED)
        }
        private fun result(code: DesktopIndexPreflightCode) = DesktopIndexPreflightResult(code, entries, directories, files)
        private fun refuse(code: DesktopIndexPreflightCode): Nothing = throw Refused(code)
    }

    private fun safeName(name: String): Boolean {
        if (name.length > budget.nameChars) throw Refused(DesktopIndexPreflightCode.BUDGET_EXCEEDED)
        return safeWindowsName(name)
    }

    companion object {
        private data class WindowsIdentity(val volume: Long, val fileId: List<Byte>)

        // JDK17 Windows BasicFileAttributes.fileKey() is null. Use read-attributes-only
        // OPEN_EXISTING handles, never a data stream/new ownership lock. OPEN_REPARSE_POINT
        // prevents following the final link; ancestors still require observation/rechecks.
        internal fun readWindowsIdentity(path: Path, directory: Boolean, extendedPaths: Boolean = false): Any {
            val api = Kernel32.INSTANCE
            val handle = api.CreateFile(if (extendedPaths) DesktopArchiveFiles.nativePath(path) else path.toString(), WinNT.FILE_READ_ATTRIBUTES,
                WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE or WinNT.FILE_SHARE_DELETE,
                null, WinNT.OPEN_EXISTING,
                WinNT.FILE_FLAG_BACKUP_SEMANTICS or WinNT.FILE_FLAG_OPEN_REPARSE_POINT, null)
            if (handle == WinBase.INVALID_HANDLE_VALUE) throw IOException("Metadata handle unavailable")
            try {
                val basic = WinBase.FILE_BASIC_INFO()
                if (!api.GetFileInformationByHandleEx(handle, 0 /* FileBasicInfo */, basic.pointer, DWORD(basic.size().toLong())))
                    throw IOException("Metadata attributes unavailable")
                basic.read()
                if (basic.FileAttributes and 0x400 != 0 || (basic.FileAttributes and 0x10 != 0) != directory)
                    throw Refused(DesktopIndexPreflightCode.UNSAFE_PATH)
                return Memory(24).use { info -> // FILE_ID_INFO: volume serial + 128-bit file ID.
                    if (!api.GetFileInformationByHandleEx(handle, 18 /* FileIdInfo */, info, DWORD(24)))
                        throw IOException("Metadata identity unavailable")
                    WindowsIdentity(info.getLong(0), info.getByteArray(8, 16).toList())
                }
            } finally {
                if (!api.CloseHandle(handle)) throw IOException("Metadata handle close failed")
            }
        }

        // Design candidate only; not registered and never created by this probe.
        private const val CANDIDATE_DOMAIN = "document_index_transactions"
        internal fun safeWindowsName(name: String): Boolean {
            val base = name.substringBefore('.').uppercase(Locale.ROOT)
            return name.isNotEmpty() && name != "." && name != ".." && !name.last().isWhitespace() && !name.endsWith('.') &&
                name.none { it.code < 32 || it in "<>:\"/\\|?*" } &&
                !Regex(".*~[0-9]+(?:\\..*)?").matches(name) &&
                base !in setOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$") &&
                !Regex("(?:COM|LPT)[1-9¹²³]").matches(base)
        }
    }
}
