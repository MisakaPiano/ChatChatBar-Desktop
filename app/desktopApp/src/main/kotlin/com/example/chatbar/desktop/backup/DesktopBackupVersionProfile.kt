package com.example.chatbar.desktop.backup

/** A known historical encoding variant, never authentication or permission to skip validation. */
internal object DesktopBackupVersionProfile {
    fun historicalDesktop147(manifest: InspectionManifest): Boolean =
        manifest.formatVersion == 2 && manifest.appVersionCode == 86 &&
            manifest.appVersionName == "CCB Desktop / official 1.4.7 transport compatibility"
}
