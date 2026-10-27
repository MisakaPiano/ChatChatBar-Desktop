package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.DesktopDocumentIndexReadEvidence
import java.nio.file.Path
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Opt-in adapter; caller must supply an isolated synthetic sandbox, never a user's root.
 * Only attributes of explicitly named strict descendants are observed. Native handles are owned
 * and closed by the existing reader. No directory enumeration, file contents, secret APIs or IO
 * writes. The sandbox boundary is an invocation restriction, not an OS authorization capability.
 */
internal class ExplicitSyntheticDirectoryWitnessReader(private val sandbox: Path) : TargetDirectoryWitnessReader {
    override suspend fun observe(directory: Path): TargetDirectoryWitness {
        currentCoroutineContext().ensureActive()
        val root = sandbox.toAbsolutePath().normalize()
        val target = directory.toAbsolutePath().normalize()
        check(sandbox.isAbsolute && directory.isAbsolute && target != root && target.startsWith(root)) { "TARGET_DIRECTORY_OUTSIDE_SYNTHETIC_SCOPE" }
        val reader = DesktopDocumentIndexReadEvidence({}, extendedPaths = true)
        // Verify the sandbox itself too; do not create, acquire a lock or follow a reparse point.
        reader.observe(root, directory = true)
        return TargetDirectoryWitness.fromNative(reader.observe(target, directory = true))
    }
    override fun toString(): String = "ExplicitSyntheticDirectoryWitnessReader(REDACTED)"
}
