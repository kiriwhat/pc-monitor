# PC 远程监控（PC Monitor）

一个**类似 Windows 任务管理器**的远程监控系统：在安卓手机上实时查看电脑的 CPU / 内存 / 磁盘 / 网络占用和进程列表，并可以**远程结束进程**。

三端架构：**Windows 采集端** 采集性能数据 → **中继服务器**（公网转发） → **安卓手机** 实时查看。

## 架构

```
┌─────────────┐    WebSocket(JSON)    ┌──────────────┐    WebSocket(JSON)    ┌─────────────┐
│  Windows 采集端 │  ─────────────────► │  中继服务器    │  ◄─────────────────  │  安卓手机    │
│ PcMonitorAgent │                     │  Node.js + ws │                     │  Android App │
│  C# / .NET 8  │                      │  公网云服务器   │                     │  Kotlin+Compose│
└─────────────┘                       └──────────────┘                       └─────────────┘
        ▲                                    │                                      ▲
        │          ┌──────────────────────────┘                                      │
        │          │ 局域网备选：电脑直接开 HTTP+SSE（端口 8668），同一 WiFi 下直连           │
        └──────────┴──────────────────────────────────────────────────────────────────┘
```

- **公网模式（推荐）**：手机在任何网络都能连回电脑，需一台有公网 IP 的云服务器跑中继程序。
- **局域网模式**：手机与电脑在同一 WiFi 下直连电脑，无需服务器。

## 功能

- 实时监控：CPU 占用率 / 核心数 / 主频、内存占用、磁盘占用（多盘）、网络上下行速率
- 实时曲线：CPU / 内存占用曲线、网络速率曲线
- 进程列表：按 CPU 或内存排序，显示 PID / 名称 / 占用
- 远程结束进程（支持整棵进程树）
- 自动重连：断线后采集端与手机端均自动重连
- 认证保护：三端共享同一 Token，未认证连接 30 秒内被踢出

## 目录结构

```
pc-monitor/
├── server/     # 中继服务器（Node.js + ws，唯一依赖）
├── agent/      # Windows 采集端（C#/.NET 8 WinForms，零第三方依赖）
├── android/    # 安卓 App（Kotlin + Jetpack Compose）
└── docs/       # 通信协议文档
```

## 快速开始

### 第一步：部署中继服务器（公网模式）

需要一台有公网 IP 的云服务器（阿里云/腾讯云等）。

```bash
# 1. 安装 Node.js 18+（建议 20+）
# 2. 上传 server/ 目录到服务器，安装依赖
cd server
npm install

# 3. 生成强随机口令
openssl rand -hex 24

# 4. 启动（TOKEN 改为你自己的）
export TOKEN=你的随机口令
node server.js

# 可选：用 pm2 守护进程
npm install -g pm2
TOKEN=你的随机口令 pm2 start server.js --name pc-monitor
pm2 save
```

> 安全提示：
> - 建议用 `pm2` 或 `systemd` 守护，崩溃自动重启。
> - 公网模式下建议在服务器防火墙只放行 8787 端口，并考虑用 nginx 做 TLS（wss://）。
> - 中继服务器本身不保存任何数据，只做转发，但请勿把 Token 泄露给他人。

### 第二步：运行 Windows 采集端

1. 下载或编译 `PcMonitorAgent.exe`（见下文「构建采集端」）。
2. 在 exe 同目录创建 `config.json`（首次运行会自动生成），内容参考 `config.example.json`：

```json
{
  "Mode": "relay",
  "ServerUrl": "ws://你的服务器IP或域名:8787",
  "Token": "与服务器一致的随机口令",
  "DeviceId": "default",
  "LocalPort": 8668,
  "PollIntervalMs": 2000
}
```

3. 双击运行，程序驻留系统托盘。右键托盘图标可查看状态、重启服务、退出。
4. **结束系统级进程**需要以管理员身份运行。

> 局域网模式：把 `Mode` 改为 `local`，电脑监听 `http://+:8668/`。
> 首次以普通用户运行需执行：`netsh http add urlacl url=http://+:8668/ user=Everyone`
> 或以管理员身份运行一次（代码注释与下文有说明）。

### 第三步：编译并安装安卓 App

1. 用 **Android Studio**（建议最新稳定版，需内置/配置 JDK 17）打开 `android/` 目录。
2. 等待 Gradle 同步完成（Gradle 8.10.2 / AGP 8.7.2 会自动下载）。
3. 连接手机（开启 USB 调试）点击 Run，或 `Build ▸ Build APK` 生成安装包。
4. 打开 App → 「设置」页：
   - 选择「公网远程」：填写中继服务器地址 `ws://服务器IP:8787`、Token、DeviceId
   - 选择「局域网」：填写 `http://电脑IP:8668`、Token
   - 点「保存并连接」，回「仪表盘」查看实时数据。

> 命令行构建（可选）：仓库未收录 `gradle-wrapper.jar`（二进制文件，GitHub 文本通道无法直接入库）。
> 用 Android Studio 打开构建**不需要**它（Android Studio 会按 `gradle-wrapper.properties` 自动下载 Gradle）。
> 若要在命令行运行 `gradlew`，请先放置官方 wrapper：
> ```
> curl -L -o android/gradle/wrapper/gradle-wrapper.jar https://raw.githubusercontent.com/gradle/gradle/v8.10.2/gradle/wrapper/gradle-wrapper.jar
> ```
> 或直接运行仓库中的一键脚本 `android/gradle/wrapper/download-wrapper.ps1`。

> 若服务器使用 wss://（HTTPS/TLS），安卓端地址同样填 `wss://...`。

## 构建采集端（可选）

需要 .NET SDK 8：

```bash
cd agent
# 发布为自包含单文件 exe（Windows x64）
dotnet publish -c Release -r win-x64 --self-contained true -o publish/win-x64
```

产物在 `agent/publish/win-x64/PcMonitorAgent.exe`，可直接拷贝到目标电脑运行。

## 通信协议

详见 [docs/protocol.md](docs/protocol.md)：中继模式 WebSocket JSON 消息（hello/request/response/push/ping/pong）、局域网模式 HTTP+SSE 接口、统一 camelCase 数据格式。

## 常见问题

| 现象 | 排查 |
| --- | --- |
| 采集端日志显示已连接，但服务器无记录 | 采集端所在电脑的系统代理（如 Clash）劫持了连接，已内置绕过；确认 ServerUrl 未被代理拦截 |
| 手机一直「未连接」 | 检查服务器是否放行 8787；三端 Token 是否一致；服务器进程是否存活（pm2 status） |
| 局域网模式打不开 | 确认同一 WiFi、防火墙放行 8668、URL ACL 已配置 |
| 结束进程失败 | 采集端需以管理员身份运行；部分系统进程受保护 |

## 免责声明

本项目仅用于个人学习与自有设备管理。远程结束进程功能有风险，请勿对非本人设备或关键系统进程随意操作。
