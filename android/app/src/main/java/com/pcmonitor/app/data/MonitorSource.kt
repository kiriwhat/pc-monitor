package com.pcmonitor.app.data

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 监控数据源统一接口。
 * 两种实现：
 *  - LocalSource：局域网模式，直连采集端 HTTP/SSE
 *  - RelaySource：公网模式，经中继服务器 WebSocket 转发
 */
interface MonitorSource {
    /** 连接状态：true=已连接并完成认证 */
    val connected: MutableStateFlow<Boolean>

    /** 实时指标推送流（订阅后周期性到达） */
    val metrics: Flow<MetricSnapshot>

    /** 一次性拉取进程列表 */
    suspend fun fetchProcesses(): List<ProcessInfo>

    /** 结束远程进程 */
    suspend fun killProcess(pid: Int)

    /** 启动连接（根据设置） */
    fun start(settings: ConnectionSettings)

    /** 停止并释放 */
    fun stop()
}

/** 创建数据源：根据设置的模式返回对应实现 */
fun createSource(settings: ConnectionSettings): MonitorSource =
    if (settings.mode == "local") LocalSource() else RelaySource()

/** 错误通道（非致命提示，如单次请求失败） */
internal val errorChannel = Channel<String>(Channel.BUFFERED)
val sourceErrors: Flow<String> get() = errorChannel.receiveAsFlow()
