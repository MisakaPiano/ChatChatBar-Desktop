package com.example.chatbar.desktop

/** Explicit allowlist: no repositories, settings, exceptions, environment dump or request evidence. */
internal object DesktopSupportSummary {
    enum class UpdateState { NOT_CHECKED, CHECKING, FAILED, NO_RELEASE, CURRENT, AVAILABLE }

    fun create(
        identity: DesktopBuildIdentity,
        update: UpdateState,
        property: (String) -> String? = System::getProperty,
    ): String {
        val parsed = runCatching { DesktopReleaseVersion.parse(identity.version) }.getOrNull()
        val version = if (parsed != null) identity.version else "UNAVAILABLE"
        val commit = identity.commit.takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: "UNAVAILABLE"
        val platform = when (property("os.name")) {
            "Windows 10", "Windows 11" -> "WINDOWS"
            "Linux" -> "LINUX"
            "Mac OS X" -> "MACOS"
            else -> "UNKNOWN"
        }
        val architecture = when (property("os.arch")) {
            "amd64", "x86_64" -> "X64"
            "aarch64", "arm64" -> "ARM64"
            "x86", "i386" -> "X86"
            else -> "UNKNOWN"
        }
        val java = property("java.specification.version")
            ?.takeIf { it.matches(Regex("[1-9][0-9]{0,2}")) } ?: "UNKNOWN"
        return listOf(
            "CCB_DESKTOP_SUPPORT_V1",
            "desktopVersion=$version",
            "sourceCommit=$commit",
            "channel=${when { parsed == null -> "UNKNOWN"; parsed.preview.isEmpty() -> "STABLE"; else -> "PREVIEW" }}",
            "validatedUpstream=1.4.1",
            "validatedUpstreamCommit=5e76a9cb841736bbbf3499a2e35e5789af4c5ca8",
            "platform=$platform",
            "architecture=$architecture",
            "javaFeature=$java",
            "updateCheck=${update.name}",
        ).joinToString("\n")
    }
}
