package com.example.chatbar.domain.image

import java.security.MessageDigest

/** Conservative local reservations survive stale subscription reads, never persisted as payment authority. */
class ImageBatchAccountLedger {
    private var previous: NovelAiAccountUsage? = null
    private var anlasReserved = 0L
    private var allowanceReserved = 0
    @Synchronized fun reconcile(server: NovelAiAccountUsage): NovelAiAccountUsage {
        previous?.let { before ->
            anlasReserved = (anlasReserved - (before.anlas - server.anlas).coerceAtLeast(0)).coerceAtLeast(0)
            val old = before.approximateV5Images
            val next = server.approximateV5Images
            if (old != null && next != null) allowanceReserved = (allowanceReserved - (old - next).coerceAtLeast(0)).coerceAtLeast(0)
        }
        previous = server
        return server.copy(anlas = (server.anlas - anlasReserved).coerceAtLeast(0),
            v5AllowanceExhausted = server.v5AllowanceExhausted ||
                server.approximateV5Images?.let { it <= allowanceReserved } == true)
    }
    @Synchronized fun reserve(cost: NovelAiGenerationCost, count: Int) {
        anlasReserved += cost.anlas.toLong()
        if (cost.kind == NovelAiGenerationChargeKind.V5_ALLOWANCE) allowanceReserved += count
    }
}

object ManualImageAccountLedgers {
    private val entries = LinkedHashMap<String, ImageBatchAccountLedger>()
    @Synchronized fun forCredential(token: String): ImageBatchAccountLedger {
        val key = MessageDigest.getInstance("SHA-256").digest(token.trim().toByteArray()).joinToString("") { "%02x".format(it) }
        return entries.getOrPut(key) { ImageBatchAccountLedger() }
    }
}
