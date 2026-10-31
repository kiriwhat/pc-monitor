package com.pcmonitor.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pcmonitor.app.data.MetricSnapshot
import com.pcmonitor.app.ui.components.ChartSeries
import com.pcmonitor.app.ui.components.LiveChart
import com.pcmonitor.app.viewmodel.MonitorViewModel
import java.util.Locale

@Composable
fun DashboardScreen(vm: MonitorViewModel, modifier: Modifier = Modifier) {
    val state = vm.state
    val snap = state.latest

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionBanner(
            connected = state.connected,
            hostInfo = state.hostInfo,
            error = state.error,
        )

        if (snap == null && !state.connected) {
            Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("正在连接采集端…", style = MaterialTheme.typography.bodyMedium)
                    Text("若长时间无数据，请检查「设置」中的地址与口令",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        if (snap != null) {
            // 概览指标卡
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                StatCard("CPU", snap.cpu?.usagePercent?.let { "$it%" } ?: "--", "${snap.cpu?.cores ?: 0} 核 · ${snap.cpu?.freqMhz ?: 0}MHz", Color(0xFF256EEB), Modifier.weight(1f))
                StatCard("内存", snap.memory?.usagePercent?.let { "$it%" } ?: "--", formatBytes(snap.memory?.usedBytes) + " / " + formatBytes(snap.memory?.totalBytes), Color(0xFF4CD964), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                StatCard("上行", formatRate(snap.network?.txBytesPerSec), "实时速率", Color(0xFFFF9F0A), Modifier.weight(1f))
                StatCard("下行", formatRate(snap.network?.rxBytesPerSec), "实时速率", Color(0xFFFF4D6D), Modifier.weight(1f))
            }
            // 磁盘
            if (snap.disks.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("磁盘", fontWeight = FontWeight.SemiBold)
                        snap.disks.forEach { d ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${d.name}盘", Modifier.width(36.dp), style = MaterialTheme.typography.bodySmall)
                                LinearBar(d.usagePercent / 100f, Color(0xFF256EEB), Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                Text("${d.usagePercent}%", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // 曲线
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("CPU / 内存占用", fontWeight = FontWeight.SemiBold)
                    LiveChart(
                        series = listOf(
                            ChartSeries(Color(0xFF256EEB), state.cpuHistory),
                            ChartSeries(Color(0xFF4CD964), state.memHistory),
                        ),
                        yMax = 100f,
                    )
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("网络速率（KB/s）", fontWeight = FontWeight.SemiBold)
                    LiveChart(
                        series = listOf(
                            ChartSeries(Color(0xFFFF9F0A), state.netTxHistory.map { it / 1024f }),
                            ChartSeries(Color(0xFFFF4D6D), state.netRxHistory.map { it / 1024f }),
                        ),
                        yMax = 0f, // 动态缩放
                        showFill = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionBanner(connected: Boolean, hostInfo: String, error: String?) {
    val color = if (connected) Color(0xFF34C759) else Color(0xFFFF3B30)
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(10.dp)
                    .height(10.dp),
            ) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    drawCircle(color)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    if (connected) "● 已连接" else "○ 未连接",
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                if (hostInfo.isNotEmpty() && hostInfo != "未连接") {
                    Text(hostInfo, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
                if (error != null && !connected) {
                    Text(error, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, sub: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface)
            Text(sub, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun LinearBar(ratio: Float, color: Color, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = modifier.height(8.dp)) {
        Box(Modifier.fillMaxWidth()) {
            if (ratio > 0) {
                Box(
                    Modifier
                        .fillMaxWidth(ratio.coerceIn(0f, 1f))
                        .height(8.dp),
                ) { androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawRect(color) } }
            }
        }
    }
}

private fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "--"
    val gb = bytes / 1073741824.0
    val mb = bytes / 1048576.0
    return if (gb >= 1) String.format(Locale.US, "%.1fGB", gb) else String.format(Locale.US, "%.0fMB", mb)
}

private fun formatRate(bytesPerSec: Long?): String {
    if (bytesPerSec == null) return "--"
    if (bytesPerSec >= 1048576) return String.format(Locale.US, "%.1fMB/s", bytesPerSec / 1048576.0)
    if (bytesPerSec >= 1024) return String.format(Locale.US, "%.0fKB/s", bytesPerSec / 1024.0)
    return "$bytesPerSec B/s"
}
