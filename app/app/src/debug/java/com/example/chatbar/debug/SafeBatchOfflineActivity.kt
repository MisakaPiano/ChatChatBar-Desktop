package com.example.chatbar.debug

import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.NovelAiStudioRepository
import com.example.chatbar.domain.image.*
import com.example.chatbar.ui.components.ImageBatchOptionsEditor
import com.example.chatbar.ui.kit.*
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.msgpack.core.MessagePack

/** Debug APK only; fake interceptor is terminal and cannot open a network socket. */
class SafeBatchOfflineActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dataContext = object : ContextWrapper(this) {
            override fun getFilesDir(): File = File(super.getFilesDir(), "safe-batch-offline").also { it.mkdirs() }
        }
        setContent {
            val scope = rememberCoroutineScope()
            var configuration by remember { mutableStateOf(ImageBatchConfiguration(4, true, 10, ImageBatchPacing(fixedSeconds = 1))) }
            var valid by remember { mutableStateOf(true) }
            var consent by remember { mutableStateOf(false) }
            var scenario by remember { mutableStateOf("成功") }
            var origin by remember { mutableStateOf(ImageBatchOrigin.CHAT_MANUAL) }
            var status by remember { mutableStateOf("未启动；不读取账号、不联网、不扣费") }
            var persisted by remember { mutableStateOf("") }
            var requests by remember { mutableStateOf(listOf<Int>()) }
            var job by remember { mutableStateOf<Job?>(null) }
            var control by remember { mutableStateOf(ImageBatchControl()) }
            suspend fun readSaved() {
                val storage = JsonFileStorage(dataContext)
                val studio = NovelAiStudioRepository(storage, NovelAiImageStorage(dataContext))
                studio.initialize()
                val batches = studio.history.first()
                val messages = ChatRepository(storage).getMessages("offline-session").filter { it.generatedFromMessageId == "offline-anchor" }
                persisted = "磁盘保留：会话 ${messages.map { it.images.size }}；工作室 ${batches.map { it.images.size }}"
            }
            LaunchedEffect(Unit) { readSaved() }
            DisposableEffect(Unit) { onDispose { job?.cancel() } }
            ChatBarTheme {
                Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CbText("离线批量验收 · 合成数据")
                    CbText("仅验证真实批次策略、请求解析和保存。不是实机账号生图，也不证明完整 Studio/会话界面的所有生命周期。")
                    if (job?.isActive != true) {
                        ImageBatchOptionsEditor(configuration, { configuration = it; consent = false }, { valid = it })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CbButton("会话保存", { origin = ImageBatchOrigin.CHAT_MANUAL })
                            CbButton("工作室保存", { origin = ImageBatchOrigin.STUDIO })
                        }
                        CbText("当前保存入口：$origin")
                        listOf("成功", "每批估算3Anlas", "计费未知", "首批429", "HTTP402", "不确定断线").forEach { name ->
                            CbButton(name, { scenario = name; consent = false }, variant = if (scenario == name) ButtonVariant.Default else ButtonVariant.Outline)
                        }
                        Row { CbText("确认本次合成方案", Modifier.weight(1f)); CbSwitch(consent, { consent = it }) }
                        CbButton("开始离线任务", {
                            val plan = configuration.freeze(origin)
                            val selected = scenario
                            control = ImageBatchControl(); requests = emptyList()
                            job = scope.launch {
                                val storage = JsonFileStorage(dataContext)
                                val imageStorage = NovelAiImageStorage(dataContext)
                                val studio = NovelAiStudioRepository(storage, imageStorage)
                                val chat = ChatRepository(storage)
                                if (chat.getSession("offline-session") == null) chat.createSession(ChatSession.create("synthetic-card", "离线批量测试").copy(id = "offline-session"))
                                if (chat.getMessage("offline-anchor", "offline-session") == null) chat.addMessage(ChatMessage.create("offline-session", MessageRole.ASSISTANT, "完全合成锚点").copy(id = "offline-anchor"))
                                val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAAD0lEQVR4nGP4zwAE/xkaAAt+An+rqPSSAAAAAElFTkSuQmCC")
                                var attempts = 0
                                val client = OkHttpClient.Builder().addInterceptor { chain ->
                                    attempts++
                                    val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer)
                                    val n = Json.parseToJsonElement(buffer.readUtf8()).jsonObject.getValue("parameters").jsonObject.getValue("n_samples").jsonPrimitive.int
                                    synchronized(this@SafeBatchOfflineActivity) { requests = requests + n }
                                    if (selected == "不确定断线") throw java.io.EOFException("synthetic accepted then disconnected")
                                    val code = if (selected == "首批429" && attempts == 1) 429 else if (selected == "HTTP402") 402 else 200
                                    val payload = if (code == 200) List(n) { frame(png) }.fold(byteArrayOf()) { a, c -> a + c } else byteArrayOf()
                                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
                                        .header("Retry-After", "2").body(payload.toResponseBody("application/octet-stream".toMediaType())).build()
                                }.build()
                                try {
                                    val prompt = NovelAiPromptPlan("synthetic offline fixture", emptyList())
                                    ImageBatchRunner().run(plan, control, quote = {
                                        if (selected == "计费未知") null else ImageBatchQuote(if (selected == "每批估算3Anlas") 3 else 0, 100)
                                    }, onProgress = { status = it.label() }, generateAndSave = { count ->
                                        val settings = NovelAiGenerationSettings(count = count)
                                        val images = mutableListOf<ByteArray>()
                                        NovelAiImageService(client).generate("synthetic-offline-no-key", prompt, settings.imageSize(), settings,
                                            maxRateLimitRetries = 0, safeBatchRequest = true).collect { event ->
                                            when (event) {
                                                is NovelAiImageEvent.Final -> images += event.image
                                                is NovelAiImageEvent.Error -> if (event.httpCode == 429) throw ImageBatchRateLimited(event.retryAfterMillis ?: 2000) else error(event.message + "；不自动重发")
                                                else -> Unit
                                            }
                                        }
                                        check(images.size == count)
                                        withContext(NonCancellable) {
                                            val id = UUID.randomUUID().toString()
                                            val paths = withContext(Dispatchers.IO) { images.map { imageStorage.save(id, it) } }
                                            if (plan.origin == ImageBatchOrigin.STUDIO) studio.saveHistory(NovelAiGenerationHistoryEntry(
                                                id, novelAiHistoryImages(paths, 0), NovelAiStudioDraft(basePrompt = "synthetic offline fixture").toRecipe(settings)))
                                            else chat.addMessageAfter(ChatMessage.create("offline-session", MessageRole.ASSISTANT, "", images = paths,
                                                generatedFromMessageId = "offline-anchor", generatedImageMetadata = paths.map { prompt.toGeneratedImageMetadata(it, settings.imageSize()) }), "offline-anchor")
                                            readSaved()
                                        }
                                        images.size
                                    })
                                } catch (error: Throwable) {
                                    status = if (error is CancellationException) "已停止；已保存图片保留，未发下一批" else error.message.orEmpty()
                                } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
                            }
                        }, enabled = valid && consent)
                    }
                    CbText("实际模拟请求 n_samples：$requests")
                    CbText(status)
                    CbText(persisted)
                    if (job?.isActive == true) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CbButton("暂停", { control.pause() }); CbButton("继续", { control.resume() }); CbButton("停止", { job?.cancel() })
                    }
                    CbButton("重新从磁盘读取", { scope.launch { readSaved() } })
                }
            }
        }
    }
    private fun frame(image: ByteArray): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(2); packer.packString("event_type"); packer.packString("final")
        packer.packString("image"); packer.packBinaryHeader(image.size); packer.writePayload(image); packer.close()
        val body = packer.toByteArray()
        return ByteBuffer.allocate(body.size + 4).putInt(body.size).put(body).array()
    }
}
