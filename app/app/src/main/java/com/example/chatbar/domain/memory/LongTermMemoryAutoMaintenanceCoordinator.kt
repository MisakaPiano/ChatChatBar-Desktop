package com.example.chatbar.domain.memory

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.service.AiBackgroundWorkManager
import com.example.chatbar.domain.service.BackgroundGenerationProtectionException
import kotlinx.coroutines.CoroutineScope

fun LongTermMemoryAutoMaintenanceCoordinator(context: Context, scope: CoroutineScope,
    chatRepository: ChatRepository, settingsRepository: SettingsRepository,
    modelResolver: EffectiveModelResolver, memoryService: LongTermMemoryService): LongTermMemoryAutoMaintenanceCoordinator =
    LongTermMemoryAutoMaintenanceCoordinator(AndroidMemoryMaintenancePlatform(context), scope,
        chatRepository, settingsRepository, modelResolver, memoryService)

private class AndroidMemoryMaintenancePlatform(context: Context) : MemoryMaintenancePlatform {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    override fun onNetworkRestored(action: () -> Unit): java.io.Closeable {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { action() }
        }
        val registered = runCatching { connectivity?.registerDefaultNetworkCallback(callback); connectivity != null }.getOrDefault(false)
        return java.io.Closeable { if (registered) runCatching { connectivity?.unregisterNetworkCallback(callback) }; Unit }
    }
    override suspend fun <T> run(sessionId: String, requireValidatedInternet: Boolean, block: suspend () -> T): T = try {
        AiBackgroundWorkManager.run(sessionId, requireValidatedInternet = requireValidatedInternet) { block() }
    } catch (error: BackgroundGenerationProtectionException) {
        throw MemoryBackgroundProtectionException(error.message ?: "等待网络", error)
    }
}
