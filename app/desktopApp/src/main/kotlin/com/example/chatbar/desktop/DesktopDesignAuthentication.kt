package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.model.hasConfiguredAuthentication

/** Safe header projection: credentials and complete endpoint URLs never enter presentation state. */
internal data class DesktopDesignAuthentication(
    val modelId: String? = null, val model: String = "未选择设计模型",
    val provider: String = "—", val configured: Boolean = false,
    val status: String = DesktopDesignFailure.MODEL.text,
)

internal suspend fun desktopExactDesignModel(resolver: EffectiveModelResolver, app: AppSettings, id: String?): ModelConfig? =
    if (id.isNullOrBlank()) resolver.defaultImageModel(app) else resolver.availableChatModels(app).firstOrNull { it.id == id }

internal fun desktopDesignAuthentication(model: ModelConfig?, app: AppSettings): DesktopDesignAuthentication {
    model ?: return DesktopDesignAuthentication()
    val configured = model.hasConfiguredAuthentication(app)
    return DesktopDesignAuthentication(model.id, model.displayName,
        runCatching { java.net.URI(model.baseUrl).host }.getOrNull() ?: "未配置服务地址", configured,
        if (configured) "认证配置可用" else "设计模型缺少可用认证，请打开模型设置安全保存凭据")
}
