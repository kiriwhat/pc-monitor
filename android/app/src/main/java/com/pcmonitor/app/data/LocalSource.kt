package com.pcmonitor.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import java.util.concurrent.TimeUnit

/**
 * 局域网模式：直连采集端内置 HTTP 服务。
 *  - /api/status        一次性快照
 *  - /api/processes     进程列表
 *  - /api/kill          结束进程
 *  - /api/stream        SSE 实时推送（自动重连）
 */
class LocalSource : MonitorSource {

    override val connected = MutableStateFlow(false)
    override val metrics: Flow<MetricSnapshot> get() = metricsChannel.receiveAsFlow()

    private val metricsChannel = Channel<MetricSnapshot>(Channel.BUFFERED)
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE 长连接
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    @Volatile private var settings: ConnectionSettings? = null
    @Volatile private var closed = true
    private var streamJob: Job? = null

    override fun start(s: ConnectionSettings) {
        settings = s
        closed = false
        connected.value = false
        streamJob?.cancel()
        val base = normalizeUrl(s.localUrl)
        streamJob = scope.launch { connectLoop(base, s.token) }
    }

    private suspend fun connectLoop(base: String, token: String) {
        while (!closed) {
            val ok = runCatching { streamOnce(base, token) }.getOrDefault(false)
            if (closed) break
            if (!ok) {
                errorChannel.trySend("数据流中断，2 秒后重连...")
                connected.value = false
            }
            delay(2000)
        }
    }

    /** 执行一次 SSE 流，返回 true=正常结束(需重连) false=异常 */
    private suspend fun streamOnce(base: String, token: String): Boolean {
        val req = Request.Builder()
            .url("$base/api/stream?token=$token")
            .header("Authorization", "Bearer $token")
            .build()
        val response: Response = client.newCall(req).execute()
        if (!response.isSuccessful) {
            errorChannel.trySend("连接失败 HTTP ${response.code}（检查地址与 token）")
            connected.value = false
            return false
        }
        connected.value = true
        response.body?.use { body ->
            val source = body.source()
            while (!closed) {
                val line = source.readUtf8Line() ?: return true // 服务端关闭流
                if (line.startsWith("data: ")) {
                    val payload = line.removePrefix("data: ").trim()
                    if (payload.isNotEmpty()) {
                        runCatching { json.decodeFromString<MetricSnapshot>(payload) }
                            .onSuccess { metricsChannel.trySend(it) }
                    }
                }
            }
        }
        return true
    }

    override suspend fun fetchProcesses(): List<ProcessInfo> {
        val s = settings ?: throw IllegalStateException("数据源未启动")
        val base = normalizeUrl(s.localUrl)
        val req = Request.Builder()
            .url("$base/api/processes?token=${s.token}")
            .header("Authorization", "Bearer ${s.token}")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val text = resp.body?.string() ?: throw IOException("空响应")
            return json.decodeFromString<ProcessList>(text).processes
        }
    }

    override suspend fun killProcess(pid: Int) {
        val s = settings ?: throw IllegalStateException("数据源未启动")
        val base = normalizeUrl(s.localUrl)
        val req = Request.Builder()
            .url("$base/api/kill?token=${s.token}")
            .header("Authorization", "Bearer ${s.token}")
            .post("{\"pid\":$pid}".toRequestBody())
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("结束进程失败: ${resp.body?.string() ?: "HTTP ${resp.code}"}")
            }
        }
    }

    override fun stop() {
        closed = true
        streamJob?.cancel()
        streamJob = null
        connected.value = false
    }

    private fun String.toRequestBody(): okhttp3.RequestBody =
        okhttp3.RequestBody.create(
            okhttp3.MediaType.parse("application/json; charset=utf-8"),
            this
        )

    private fun normalizeUrl(url: String): String =
        url.trim().trimEnd('/').ifEmpty { "http://127.0.0.1:8668" }
}
