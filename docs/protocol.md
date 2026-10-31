# 通信协议规范 v1.0

本协议由三端共同遵守：`server/`（中继服务器）、`agent/`（Windows 采集端）、`android/`（安卓手机端）。

## 1. 两种连接模式

| 模式 | 适用场景 | 传输层 | 手机如何连 |
|---|---|---|---|
| **中继模式 (relay)** | 公网远程（手机在外，电脑无公网 IP） | WebSocket JSON | 手机和采集端都连到中继服务器 |
| **局域网模式 (local)** | 同一 WiFi / 网线直连 | HTTP + SSE | 手机直接访问采集端的 REST 接口 |

两种模式手机端使用同一套数据模型，切换只需改设置页的连接地址与模式。

---

## 2. 中继模式协议（WebSocket JSON）

### 2.1 认证（双方都必须先发 hello）

```json
{"type":"hello","role":"agent","token":"<TOKEN>","deviceId":"default"}
{"type":"hello","role":"phone","token":"<TOKEN>"}
```

- 服务器校验 `token` 与自身配置一致，否则断开连接。
- 成功回复：
  - agent 收到 `{"type":"hello-ok","role":"agent","deviceId":"default"}`
  - phone 收到 `{"type":"hello-ok","role":"phone","agents":["default",...]}`（在线采集端列表）
- 连接建立后 30 秒内未完成认证将被服务器断开。

### 2.2 手机 → 采集端（请求）

```json
{"type":"request","reqId":"u1","deviceId":"default","action":"metrics"}
{"type":"request","reqId":"u2","deviceId":"default","action":"processes"}
{"type":"request","reqId":"u3","deviceId":"default","action":"kill","pid":1234}
{"type":"request","reqId":"u4","deviceId":"default","action":"subscribe","intervalMs":2000}
{"type":"request","reqId":"u5","deviceId":"default","action":"unsubscribe"}
```

`deviceId` 可省略，默认 `"default"`。

### 2.3 采集端 → 手机（响应）

```json
{"type":"response","reqId":"u1","action":"metrics","ok":true,"data":{...}}
{"type":"response","reqId":"u2","action":"processes","ok":true,"data":{...}}
{"type":"response","reqId":"u3","action":"kill","ok":true}
{"type":"error","reqId":"u3","message":"结束进程失败: 拒绝访问"}
```

### 2.4 订阅推送

手机发 `subscribe` 后，采集端按 `intervalMs`（默认 2000）周期推送：

```json
{"type":"push","action":"metrics","data":{...}}
```

### 2.5 心跳

任一端可发 `{"type":"ping"}`，对方回 `{"type":"pong"}`。服务器每 30 秒 ping 一次，90 秒无响应断开。

---

## 3. 局域网模式协议（HTTP + SSE）

采集端内置 HTTP 服务，默认端口 `8668`，所有接口需携带 token：

```
Authorization: Bearer <TOKEN>     # 或 URL 参数 ?token=<TOKEN>
```

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/status` | 一次性指标快照（metrics 格式） |
| GET | `/api/processes` | 进程列表 |
| POST | `/api/kill` | body `{"pid":1234}` 结束进程 |
| GET | `/api/stream` | SSE 实时流：每 `intervalMs` 推送 `data: <metrics JSON>\n\n` |

SSE 客户端示例（Android OkHttp）：

```kotlin
val request = Request.Builder().url("http://192.168.1.10:8668/api/stream?token=$token").build()
```

---

## 4. 数据格式

### 4.1 metrics（指标快照）

```json
{
  "timestamp": 1730000000000,
  "osVersion": "Windows 11 Pro 22631",
  "uptimeSec": 86400,
  "cpu": { "usagePercent": 23.5, "cores": 8, "freqMhz": 3600 },
  "memory": { "totalBytes": 17179869184, "usedBytes": 10695475200, "usagePercent": 62.3 },
  "disks": [
    { "name": "C:", "totalBytes": 512110190592, "usedBytes": 204776120320, "usagePercent": 40.0 },
    { "name": "D:", "totalBytes": 1000204886016, "usedBytes": 600122931609, "usagePercent": 60.0 }
  ],
  "network": { "rxBytesPerSec": 152340, "txBytesPerSec": 88210 }
}
```

### 4.2 processes（进程列表）

```json
{
  "processes": [
    { "pid": 1234, "name": "chrome", "cpuPercent": 3.2, "memoryBytes": 456789012, "sessionId": 1 },
    { "pid": 4,    "name": "System", "cpuPercent": 0.1, "memoryBytes": 0, "sessionId": 0 }
  ]
}
```

- `cpuPercent` 为近 1 秒平均 CPU 占用（可能受权限影响部分进程读不到，返回 0）。
- `memoryBytes` 为工作集（Working Set）。

---

## 5. 认证与安全

- `token` 是唯一访问凭证：中继服务器、采集端、手机三处配置同一 token。
- 中继服务器校验 token 后才路由消息；采集端局域网接口同样校验。
- **不要使用默认 token**，请在配置中生成强随机串（如 `openssl rand -hex 24`）。
- 结束进程接口：普通权限可结束自己启动的进程；结束系统/其他用户进程需要管理员权限运行采集端。
