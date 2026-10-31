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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pcmonitor.app.data.ProcessInfo
import com.pcmonitor.app.viewmodel.MonitorViewModel
import java.util.Locale

@Composable
fun ProcessesScreen(vm: MonitorViewModel, modifier: Modifier = Modifier) {
    val state = vm.state
    var sortByCpu by remember { mutableStateOf(true) }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("进程列表（${state.processes.size}）", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.refreshProcesses() }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = sortByCpu,
                onClick = {
                    if (!sortByCpu) {
                        sortByCpu = true
                        vm.refreshProcesses()
                    }
                },
                label = { Text("按 CPU 排序") },
            )
            FilterChip(
                selected = !sortByCpu,
                onClick = {
                    if (sortByCpu) {
                        sortByCpu = false
                        vm.refreshProcesses()
                    }
                },
                label = { Text("按内存排序") },
            )
        }

        if (state.loadingProcesses && state.processes.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        val items = if (sortByCpu)
            state.processes.sortedByDescending { it.cpuPercent }
        else
            state.processes.sortedByDescending { it.memoryBytes }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items, key = { it.pid }) { p ->
                ProcessRow(p, onKill = { vm.killProcess(p.pid, p.name) })
            }
        }
    }
}

@Composable
private fun ProcessRow(p: ProcessInfo, onKill: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.Medium, maxLines = 1)
                Text("PID ${p.pid} · ${formatMb(p.memoryBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Text(
                "${p.cpuPercent}%",
                color = if (p.cpuPercent >= 50) Color(0xFFFF3B30)
                else if (p.cpuPercent >= 20) Color(0xFFFF9F0A)
                else Color(0xFF256EEB),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                modifier = Modifier.width(72.dp),
            )
            OutlinedButton(onClick = onKill, modifier = Modifier.height(34.dp)) {
                Text("结束", color = Color(0xFFFF3B30), fontSize = 13.sp)
            }
        }
    }
}

private fun formatMb(bytes: Long): String =
    String.format(Locale.US, "%.0f MB", bytes / 1048576.0)
