package com.example.chatbar.desktop

import kotlinx.coroutines.sync.Semaphore

/** Process-wide synthesis admission includes formal chat and the accepted free picker preview. */
internal object DesktopFishTtsAdmission {
    val slots = Semaphore(5)
}
