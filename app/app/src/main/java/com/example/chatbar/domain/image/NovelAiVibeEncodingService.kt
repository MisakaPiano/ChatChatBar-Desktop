package com.example.chatbar.domain.image

import android.content.Context
import com.example.chatbar.domain.ProxyAwareClient
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class NovelAiVibeEncodingService(context: Context,
    client: OkHttpClient = ProxyAwareClient.builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES).writeTimeout(30, TimeUnit.SECONDS).build()
) : NovelAiVibeEncodingCore(File(context.filesDir, "images/studio-guidance/vibe-cache"), client)
