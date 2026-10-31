package com.pcmonitor.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 指标快照（与 docs/protocol.md 4.1 节一致） */
@Serializable
data class MetricSnapshot(
    val timestamp: Long = 0,
    @SerialName("osVersion") val osVersion: String = "",
    val uptimeSec: Long = 0,
    val cpu: CpuInfo? = null,
    val memory: MemoryInfo? = null,
    val disks: List<DiskInfo> = emptyList(),
    val network: NetworkInfo? = null,
)

@Serializable
data class CpuInfo(
    val usagePercent: Double = 0.0,
    val cores: Int = 0,
    val freqMhz: Int = 0,
)

@Serializable
data class MemoryInfo(
    val totalBytes: Long = 0,
    val usedBytes: Long = 0,
    val usagePercent: Double = 0.0,
)

@Serializable
data class DiskInfo(
    val name: String = "",
    val totalBytes: Long = 0,
    val usedBytes: Long = 0,
    val usagePercent: Double = 0.0,
)

@Serializable
data class NetworkInfo(
    val rxBytesPerSec: Long = 0,
    val txBytesPerSec: Long = 0,
)

/** 进程信息（协议 4.2 节） */
@Serializable
data class ProcessInfo(
    val pid: Int = 0,
    val name: String = "",
    val cpuPercent: Double = 0.0,
    val memoryBytes: Long = 0,
    val sessionId: Int = 0,
)

@Serializable
data class ProcessList(val processes: List<ProcessInfo> = emptyList())

/** 连接设置 */
data class ConnectionSettings(
    val mode: String = "relay",      // "relay" | "local"
    val serverUrl: String = "ws://你的服务器:8787",
    val localUrl: String = "http://192.168.1.100:8668",
    val token: String = "",
    val deviceId: String = "default",
    val intervalMs: Int = 2000,
)
