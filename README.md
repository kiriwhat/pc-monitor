# PC 远程监控（PcMonitor）

用手机远程监控 Windows 电脑性能的完整开源方案，类似 Windows 任务管理器的手持版。

支持**公网远程**（人在外面也能看家里的电脑）与**局域网直连**（同一 WiFi 下零配置）两种模式，实时展示 CPU / 内存 / 磁盘 / 网络曲线，并可查看进程列表、远程结束进程。

```
┌──────────────┐   WebSocket   ┌────────────────┐   WebSocket   ┌──────────────────┐
│  安卓手机端   │ ◄───────────► │  中继服务器     │ ◄───────────► │  Windows 采集端  │
│  (Kotlin+Compose) │   JSON 协议  │ (Node.js + ws) │   JSON 协议  │  (C#/.NET 8)     │
└──────────────┘               └────────────────┘               └──────────────────┘
        ▲ 局域网模式可直连（HTTP + SSE，端口 8668）▲
```

## 功能特性

- **实时监控**：CPU 使用率/核数/频率、内存、磁盘、网络上下行速率，曲线图 120 点滚动
- **进程管理**：进程列表按 CPU/内存排序，一键远程结束进程
- **双模式连接**：公网中继（经云服务器转发）或局域网直连（HTTP + SSE）
- **安全认证**：统一 Token 口令，防止他人访问；公网部署建议套 WSS + HTTPS
- **轻量自绘图表**：安卓端用 Compose Canvas 自绘曲线，无三方图表库依赖

## 目录结构

```
pc-monitor/
├── server/          # 中继服务器（Node.js，仅依赖 ws）
│   ├── server.js          # 服务器主程序
│   ├── test-relay.js      # 服务器自测脚本
│   ├── test-phone.js      # 模拟手机端到端测试脚本
│   └── wstest/            # 最小 C# WebSocket 回归测试项目
├── agent/           # Windows 采集端（C#/.NET 8 WinForms，零外部 NuGet）
│   ├── Core/              # 配置/日志/指标采样/进程采样
│   ├── Network/           # 中继 WebSocket 客户端 + 局域网 HTTP 服务
│   ├── Ui/                # 系统托盘
│   └── config.example.json
├── android/         # 安卓端（Kotlin + Jetpack Compose）
│   └── app/src/main/java/com/pcmonitor/app/
│       ├── data/          # 数据模型 / 双模式数据源 / 设置存储
│       ├── viewmodel/     # 状态管理
│       └── ui/            # 仪表盘 / 进程列表 / 设置 / 自绘图表
└── docs/
    └── protocol.md        # 通信协议 v1.0（权威定义）
```

## 快速开始

### 第 1 步：电脑端（被监控的 Windows）

1. 发布采集端（需 .NET SDK 8）：
   ```
   dotnet publish agent/PcMonitorAgent.csproj -c Release -r win-x64 --self-contained true -o agent/publish/win-x64
   ```
   或直接使用已发布的 `PcMonitorAgent.exe`（免安装，自包含）。
2. 在 exe 同目录放置 `config.json`（首次启动也会自动生成）：
   ```json
   {
     "mode": "relay",
     "serverUrl": "ws://你的服务器:8787",
     "token": "你的强口令",
     "deviceId": "default",
     "localPort": 8668,
     "pollIntervalMs": 1500
   }
   ```
   - `mode: "relay"` 公网模式；`mode: "local"` 局域网模式（监听 8668 端口）
   - 局域网模式如需监听 0.0.0.0（允许局域网内其他设备访问），以管理员运行一次：
     `netsh http add urlacl url=http://+:8668/ user=Everyone`
3. 运行 exe，系统托盘出现图标即表示在线（托盘菜单可重启服务 = 热重载配置）。

### 第 2 步：中继服务器（公网模式才需要，可用云服务器）

参见 [docs/DEPLOY.md](docs/DEPLOY.md)。

### 第 3 步：安卓端

1. 用 Android Studio（Ladybug 及以上，自带 JDK 17）打开 `android/` 目录，等待 Gradle 同步完成。
2. 连接手机（开启开发者模式 / USB 调试），或 Build → Build APK(s) 后手动安装。
3. 打开 App 进入「设置」：
   - **公网模式**：选「公网远程」，填中继服务器地址 `ws://服务器IP:8787` 与 Token；
   - **局域网模式**：选「局域网」，填 `http://电脑IP:8668` 与 Token。
4. 保存并连接，回到「仪表盘」即可看到实时数据；「进程」页可排序与结束进程。

> 构建要求：JDK 17、Gradle 8.10.2（项目含 wrapper 配置）、Android SDK 34。首次同步需联网下载依赖。
> 仓库未包含二进制文件 `gradle-wrapper.jar` 与 `app.ico`（GitHub 文件 API 仅支持文本）：用 Android Studio 打开时会自动生成 wrapper jar；采集端缺少 `app.ico` 时 csproj 已条件跳过，可正常编译。

## 安全提示

- 三端（采集端 / 服务器 / 手机）使用**同一个强 Token**，采集端与服务器各自独立校验。
- 公网部署务必：服务器只开放 8787 端口；生产环境建议给中继套 **WSS**（Nginx/Caddy 反代 + TLS），手机端填 `wss://` 地址。
- Token 泄露等于拿到你电脑的控制权（可结束进程），请妥善保管、定期更换。
- 结束**系统关键进程**需要电脑端以管理员身份运行采集端，否则会被拒绝。

## 通信协议

完整定义见 [docs/protocol.md](docs/protocol.md)（JSON over WebSocket，字段 camelCase）。要点：

- 认证：连接后发 `hello{role,token,deviceId}`，服务器回 `hello-ok`
- 请求：`request{reqId,action,deviceId}`，action 含 `metrics` / `processes` / `kill` / `subscribe` / `unsubscribe`
- 推送：订阅后采集端按 `intervalMs` 周期 `push` 指标快照
- 心跳：应用层 JSON `ping` / `pong`（兼容 .NET / Java 各 WebSocket 实现）
- 局域网模式：HTTP + SSE（`/api/status`、`/api/processes`、`/api/kill`、`/api/stream`）

## 技术栈

| 端 | 语言/框架 | 说明 |
|---|---|---|
| 采集端 | C# / .NET 8 WinForms + P/Invoke | 零外部 NuGet；GetSystemTimes 采样 CPU，GlobalMemoryStatusEx 采样内存 |
| 中继服务器 | Node.js + ws | 单依赖；token 认证 + 请求路由 + 应用层心跳 |
| 安卓端 | Kotlin + Jetpack Compose + OkHttp | 双模式数据源；Compose Canvas 自绘曲线 |

## 已知限制

- 安卓端与采集端已完成真实链路验证（中继模式端到端测试通过）；安卓 App 请用 Android Studio 构建后真机验证。
- 局域网模式代码已实现，实测需在采集端本机配置 URL ACL 或管理员运行。

## License

MIT
