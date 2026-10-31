package com.pcmonitor.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pcmonitor.app.data.ConnectionSettings
import com.pcmonitor.app.viewmodel.MonitorViewModel

@Composable
fun SettingsScreen(vm: MonitorViewModel, modifier: Modifier = Modifier) {
    val current = vm.state.settings

    var mode by remember { mutableStateOf(current.mode) }
    var serverUrl by remember { mutableStateOf(current.serverUrl) }
    var localUrl by remember { mutableStateOf(current.localUrl) }
    var token by remember { mutableStateOf(current.token) }
    var deviceId by remember { mutableStateOf(current.deviceId) }
    var intervalMs by remember { mutableStateOf(current.intervalMs.toString()) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("连接方式", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = mode == "relay", onClick = { mode = "relay" })
            Text("公网远程（中继服务器）")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = mode == "local", onClick = { mode = "local" })
            Text("局域网（同一 WiFi 直连电脑）")
        }

        if (mode == "relay") {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("中继服务器地址") },
                placeholder = { Text("ws://你的服务器IP或域名:8787") },
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("需先在云服务器部署 server/ 目录的中继程序") },
            )
        } else {
            OutlinedTextField(
                value = localUrl,
                onValueChange = { localUrl = it },
                label = { Text("电脑地址") },
                placeholder = { Text("http://192.168.x.x:8668") },
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("手机与电脑需在同一网络；电脑端运行 PcMonitorAgent 并设为 local 模式") },
            )
        }

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("口令 Token") },
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("与电脑端/中继服务器配置一致，防止他人访问") },
        )
        OutlinedTextField(
            value = deviceId,
            onValueChange = { deviceId = it },
            label = { Text("设备标识 DeviceId") },
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("中继模式下区分多台电脑，默认 default") },
        )
        OutlinedTextField(
            value = intervalMs,
            onValueChange = { intervalMs = it.filter { c -> c.isDigit() } },
            label = { Text("刷新间隔（毫秒）") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("建议 1000 - 5000，间隔越短越耗流量") },
        )

        Button(
            onClick = {
                vm.saveAndConnect(ConnectionSettings(
                    mode = mode,
                    serverUrl = serverUrl,
                    localUrl = localUrl,
                    token = token,
                    deviceId = deviceId.ifBlank { "default" },
                    intervalMs = (intervalMs.toIntOrNull() ?: 2000).coerceIn(500, 10000),
                ))
                testResult = null
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存并连接")
        }

        OutlinedButton(
            onClick = {
                testing = true
                testResult = null
                vm.testConnection { result ->
                    testing = false
                    testResult = result
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (testing) "测试中…" else "测试连接")
        }

        testResult?.let {
            Text(
                it,
                color = if (it.startsWith("连接成功")) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "使用说明：\n1. 电脑端安装 PcMonitorAgent（托盘图标运行）\n2. 公网模式：先部署中继服务器并配置 server.js 的 TOKEN\n3. 三端（服务器/电脑/手机）使用同一口令\n4. 结束系统进程需要电脑端以管理员身份运行",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
