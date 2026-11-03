package com.example.chatbar.domain.image

import kotlin.math.sqrt

/** NovelAI frontend b94bdee: normalize imported effective sigma, scale once for the actual latent canvas. */
internal object NovelAiVarietyPlusPolicy {
    const val DEFAULT_SIGMA = 58.0
    fun resolutionScale(width: Int, height: Int): Double {
        require(width >= 8 && height >= 8) { "Variety+ 请求尺寸无效" }
        return sqrt((width / 8).toDouble() * (height / 8) / (104 * 152))
    }
}
