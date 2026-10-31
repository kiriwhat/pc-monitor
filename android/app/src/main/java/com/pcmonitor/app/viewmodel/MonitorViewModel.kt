package com.pcmonitor.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pcmonitor.app.data.ConnectionSettings
import com.pcmonitor.app.data.MetricSnapshot
import com.pcmonitor.app.data.MonitorSource
import com.pcmonitor.app.data.ProcessInfo
import com.pcmonitor.app.data.SettingsStore
import com.pcmonitor.app.data.createSource
import com.pcmonitor.app.data.sourceErrors
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val connected: Boolean = false,
        val latest: MetricSnapshot? = null,
        val cpuHistory: List<Float> = emptyList(),
        val memHistory: List<Float> = emptyList(),
        val netRxHistory: List<Long> = emptyList(),
        val netTxHistory: List<Long> = emptyList(),
        val processes: List<ProcessInfo> = emptyList(),
        val loadingProcesses: Boolean = false,
        val error: String? = null,
        val settings: ConnectionSettings = ConnectionSettings(),
        val hostInfo: String = "未连接",
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val store by lazy { SettingsStore(getApplication()) }
    private var source: MonitorSource? = null
    private var metricsJob: Job? = null

    init {
        _state.update { it.copy(settings = store.load()) }
        viewModelScope.launch {
            sourceErrors.collect { msg ->
                _state.update { it.copy(error = msg) }
            }
        }
    }

    /** 启动连接（使用当前设置） */
    fun startConnection() {
        metricsJob?.cancel()
        source?.stop()

        val settings = store.load()
        val s = createSource(settings)
        source = s

        // 连接状态
        viewModelScope.launch {
            s.connected.collect { ok ->
                _state.update { it.copy(connected = ok) }
            }
        }
        // 指标流 -> 历史缓冲（保留最近 120 点）
        metricsJob = viewModelScope.launch {
            s.metrics.collect { snap ->
                _state.update { st ->
                    st.copy(
                        latest = snap,
                        cpuHistory = (st.cpuHistory + snap.cpu?.usagePercent?.toFloat().orZero()).takeLast(120),
                        memHistory = (st.memHistory + snap.memory?.usagePercent?.toFloat().orZero()).takeLast(120),
                        netRxHistory = (st.netRxHistory + snap.network?.rxBytesPerSec ?: 0).takeLast(120),
                        netTxHistory = (st.netTxHistory + snap.network?.txBytesPerSec ?: 0).takeLast(120),
                        hostInfo = "${snap.osVersion} · 开机 ${formatUptime(snap.uptimeSec)}",
                        error = null,
                    )
                }
            }
        }

        s.start(settings)
        refreshProcesses()
    }

    /** 保存设置并重启连接 */
    fun saveAndConnect(settings: ConnectionSettings) {
        store.save(settings)
        _state.update { it.copy(settings = settings, error = null) }
        startConnection()
    }

    /** 刷新进程列表 */
    fun refreshProcesses() {
        val s = source ?: return
        _state.update { it.copy(loadingProcesses = true) }
        viewModelScope.launch {
            runCatching { s.fetchProcesses() }
                .onSuccess { procs ->
                    _state.update {
                        it.copy(processes = procs.sortedByDescending { p -> p.cpuPercent },
                            loadingProcesses = false)
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(loadingProcesses = false,
                            error = "获取进程列表失败: ${e.message}")
                    }
                }
        }
    }

    /** 结束远程进程 */
    fun killProcess(pid: Int, name: String) {
        val s = source ?: return
        viewModelScope.launch {
            runCatching { s.killProcess(pid) }
                .onSuccess { refreshProcesses() }
                .onFailure { e ->
                    _state.update { it.copy(error = "结束 $name 失败: ${e.message}") }
                }
        }
    }

    /** 连接状态详情（供设置页测试） */
    fun testConnection(callback: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val s = createSource(store.load())
                // 快速验证：拉一次进程列表
                s.start(store.load())
                kotlinx.coroutines.delay(1500)
                val procs = s.fetchProcesses()
                s.stop()
                "连接成功，检测到 ${procs.size} 个进程"
            }.onSuccess { callback(it) }
                .onFailure { callback("连接失败: ${it.message}") }
        }
    }

    private fun Float?.orZero(): Float = this ?: 0f

    private fun formatUptime(sec: Long): String {
        val d = sec / 86400
        val h = (sec % 86400) / 3600
        val m = (sec % 3600) / 60
        return if (d > 0) "${d}天${h}小时" else if (h > 0) "${h}小时${m}分" else "${m}分钟"
    }

    override fun onCleared() {
        source?.stop()
        super.onCleared()
    }
}
