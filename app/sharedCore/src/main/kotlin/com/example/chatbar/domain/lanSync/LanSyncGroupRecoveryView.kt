package com.example.chatbar.domain.lanSync

/** Verified retained history only; no live userdata, filesystem handles or restore authority. */
class LanSyncGroupRecoveryView internal constructor(val entry: LanSyncRecoveryEntry,
    val units: Map<String, List<LanSyncRecord>>)
