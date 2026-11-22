package com.example.chatbar.desktop

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

internal fun writeNewDesktopInvitationFile(path: Path, bytes: ByteArray) {
    try {
        Files.write(path, bytes, CREATE_NEW, WRITE)
    } catch (_: FileAlreadyExistsException) {
        error("SYNC_GROUP_INVITE_FILE_DESTINATION_CHANGED")
    } catch (_: IOException) {
        error("SYNC_GROUP_INVITE_FILE_UNAVAILABLE")
    }
}
