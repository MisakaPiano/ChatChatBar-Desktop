package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.SaveSlot

/** The exact envelope changes made by shared/pinned147 SaveSlotPackageStorage.importPackage.
 * This does not attest provenance: immutable payload equality and package validation remain required.
 */
internal object DesktopSaveSlotLibraryEnvelope {
    fun requireCompatible(library: SaveSlot, nested: SaveSlot, packageBytes: Long) {
        val outer = requireNotNull(library.packageRef) { "MERGE_PACKAGE_ENVELOPE_REFERENCE" }
        val inner = requireNotNull(nested.packageRef) { "MERGE_PACKAGE_ENVELOPE_REFERENCE" }
        require(library.schemaVersion == 8 && nested.schemaVersion == 8 && packageBytes > 0) { "MERGE_PACKAGE_ENVELOPE_SCHEMA" }
        require(outer.byteLength <= 0 || outer.byteLength == packageBytes) { "MERGE_PACKAGE_ENVELOPE_LENGTH" }
        val sameOwner = library.id == nested.id && library.sessionId == nested.sessionId
        val imported = library.id != nested.id && outer.fileName == "${library.id}.cbsave"
        check(sameOwner || imported) { "MERGE_PACKAGE_OWNER_IDENTITY" }
        // Names/timestamps and physical package names belong to the local library. Every other
        // setting, snapshot, count, media policy and embedded payload must still match exactly.
        check(nested.copy(id = library.id, sessionId = library.sessionId, name = library.name,
            createdAt = library.createdAt, packageRef = inner.copy(fileName = outer.fileName,
                byteLength = outer.byteLength)) == library) { "MERGE_PACKAGE_ENVELOPE_CONTENT" }
    }
}
