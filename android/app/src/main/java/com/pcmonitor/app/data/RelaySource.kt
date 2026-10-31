package com.pcmonitor.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 公网中继模式：手机连中继服务器 WebSocket，服务器把请求转发给采集端。
 * 协议见 docs/protocol.md（hello / request / response / push / ping / pong）。
 */
class RelaySource : MonitorSource {

    override val connected = MutableStateFlow(false)
    override val metrics: Flow<MetricSnapshot> get() = metricsChannel.receiveAsFlow()

    private val metricsChannel = Channel<MetricSnapshot>(Channel.BUFFERED)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS) // OkHttp 应用层心跳
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val pending = ConcurrentHashMap<String, CompletableDeferred<RelayMsg>>()
    private val reqIdGen = AtomicLong(0)

    @Volatile private var settings: ConnectionSettings? = null
    @Volatile private var closed = true
    @Volatile private var ws: WebSocket? = null
    private var connectJob: Job? = null

    // ---------- 生命周期 ----------

    override fun start(s: ConnectionSettings) {
        settings = s
        closed = false
        connected.value = false
        connectJob?.cancel()
        connectJob = scope.launch { connectLoop() }
    }

    override fun stop() {
        closed = true
        connectJob?.cancel()
        ws?.close(1000, "user stop")
        ws = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        connected.value = false
    }

    // ---------- 连接与重连 ----------

    private suspend fun connectLoop() {
        while (!closed) {
            val success = runCatching { openOnce() }.getOrDefault(false)
            if (closed) break
            if (!success) {
                errorChannel.trySend("中继连接失败，3 秒后重连...")
                connected.value = false
            }
            delay(3000)
        }
    }

    /** 打开一次 WebSocket 并阻塞直到关闭；返回 true=由服务器关闭(需重连) */
    private suspend fun openOnce(): Boolean {
        val s = settings ?: return false
        if (s.serverUrl.isBlank()) {
            errorChannel.trySend("请先在设置中填写中继服务器地址")
            return false
        }
        val completed = CompletableDeferred<Boolean>()
        val listener = createListener(completed, s)
        val req = Request.Builder().url(s.serverUrl.trim()).build()
        val ws = client.newWebSocket(req, listener)
        this.ws = ws
        return completed.await()
    }

    private fun createListener(completed: CompletableDeferred<Boolean>, s: ConnectionSettings) =
        object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 注册身份
                sendRaw(webSocket, RelayMsg(type = "hello", role = "phone", token = s.token))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { json.decodeFromString<RelayMsg>(text) }.getOrNull()
                    ?: return
                when (msg.type) {
                    "hello-ok" -> {
                        connected.value = true
                        // 自动订阅指标推送
                        sendRaw(webSocket, RelayMsg(
                            type = "request", reqId = nextReqId(), action = "subscribe",
                            deviceId = s.deviceId, intervalMs = s.intervalMs
                        ))
                    }
                    "response" -> msg.reqId?.let { id ->
                        pending.remove(id)?.complete(msg)
                    }
                    "push" -> if (msg.action == "metrics") {
                        val snap = msg.data?.let {
                            runCatching { json.decodeFromString<MetricSnapshot>(it.toString()) }.getOrNull()
                        }
                        snap?.let { metricsChannel.trySend(it) }
                    }
                    "error" -> msg.reqId?.let { id ->
                        pending.remove(id)?.completeExceptionally(
                            IllegalStateException(msg.message ?: "服务器错误")
                        )
                    }
                    "ping" -> sendRaw(webSocket, RelayMsg(type = "pong"))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.value = false
                if (!closed) errorChannel.trySend("中继连接异常: ${t.message}")
                completed.complete(true)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected.value = false
                completed.complete(true)
            }
        }

    // ---------- 请求 ----------

    override suspend fun fetchProcesses(): List<ProcessInfo> {
        val s = settings ?: throw IllegalStateException("数据源未启动")
        return request(s, "processes").let { msg ->
            msg.data?.let {
                runCatching { json.decodeFromString<ProcessList>(it.toString()) }.getOrNull()
            }?.processes ?: emptyList()
        }
    }

    override suspend fun killProcess(pid: Int) {
        val s = settings ?: throw IllegalStateException("数据源未启动")
        val msg = request(s, "kill", pid = pid)
        if (msg.ok != true) {
            throw IllegalStateException(msg.message ?: "结束进程失败")
        }
    }

    /** 发送 request 并等待 response（15 秒超时） */
    private suspend fun request(
        s: ConnectionSettings,
        action: String,
        pid: Int? = null,
    ): RelayMsg {
        val ws = this.ws ?: throw IllegalStateException("未连接")
        val reqId = nextReqId()
        val deferred = CompletableDeferred<RelayMsg>()
        pending[reqId] = deferred
        sendRaw(ws, RelayMsg(
            type = "request", reqId = reqId, action = action,
            deviceId = s.deviceId, pid = pid
        ))
        return withTimeoutOrNull(15_000) { deferred.await() }
            ?: throw IllegalStateException("请求超时（采集端可能不在线）")
    }

    private fun nextReqId(): String = "m${reqIdGen.incrementAndGet()}"

    private fun sendRaw(ws: WebSocket, msg: RelayMsg) {
        val text = json.encodeToString(RelayMsg.serializer(), msg)
        ws.send(text)
    }
}

/** 中继协议消息（字段与 docs/protocol.md 一致，camelCase） */
@Serializable
data class RelayMsg(
    val type: String? = null,
    val role: String? = null,
    val token: String? = null,
    val deviceId: String? = null,
    val reqId: String? = null,
    val action: String? = null,
    val pid: Int? = null,
    val intervalMs: Int? = null,
    val ok: Boolean? = null,
    @SerialName("_fromId") val fromId: Int? = null,
    val message: String? = null,
    val data: JsonElement? = null,
)
