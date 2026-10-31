package com.pcmonitor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pcmonitor.app.ui.DashboardScreen
import com.pcmonitor.app.ui.ProcessesScreen
import com.pcmonitor.app.ui.SettingsScreen
import com.pcmonitor.app.ui.theme.PcMonitorTheme
import com.pcmonitor.app.viewmodel.MonitorViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PcMonitorTheme {
                AppScaffold()
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    DASHBOARD("仪表盘", Icons.Default.Speed),
    PROCESSES("进程", Icons.Default.Memory),
    SETTINGS("设置", Icons.Default.Settings),
}

@Composable
private fun AppScaffold() {
    val vm: MonitorViewModel = viewModel()
    var tab by remember { mutableStateOf(Tab.DASHBOARD) }

    // 启动时自动连接（已有保存的设置）
    LaunchedEffect(Unit) {
        val s = vm.state.settings
        if (s.token.isNotEmpty() || s.serverUrl.isNotEmpty() || s.localUrl.isNotEmpty()) {
            vm.startConnection()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        }
    ) { padding ->
        val contentModifier = Modifier.padding(padding)
        when (tab) {
            Tab.DASHBOARD -> DashboardScreen(vm, contentModifier)
            Tab.PROCESSES -> ProcessesScreen(vm, contentModifier)
            Tab.SETTINGS -> SettingsScreen(vm, contentModifier)
        }
    }
}
