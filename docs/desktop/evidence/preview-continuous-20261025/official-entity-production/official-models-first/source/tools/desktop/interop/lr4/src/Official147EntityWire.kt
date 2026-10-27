package com.example.chatbar.desktop.backup

import kotlinx.serialization.json.JsonObject

/** Historical tool adapter. Production and this tool share version-aware Entity validation. */
internal object Official147EntityWire {
    fun validate(type: String, value: JsonObject, appVersionCode: Int) =
        DesktopOfficialBackupEntitySchema.validate(type, value, appVersionCode)
}
