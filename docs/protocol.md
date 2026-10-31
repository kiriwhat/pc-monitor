# PC Monitor 通信协议 v1.0

三端（Windows 采集端 / 中继服务器 / 安卓手机）统一使用 JSON 消息，**所有字段一律 camelCase**。

## 1. 连接与认证（中继模式）

- 传输：WebSocket（`ws://` 或 `wss://`），默认端口 **8787**。
- 双方建连后，客户端必须在 **30 秒**内发送 `hello` 完成认证，否则服务器断开连接。
- Token 三端（服务器 / 采集端 / 手机）必须一致。

### 1.1 注册（采集端 / 手机）

```json
{"type": "hello", "role": "agent", "token": "...", "deviceId": "default"}
{"type": "hello", "role": "phone", "token": "..."}
```

- `role`: `agent`=Windows 采集端，`phone`=安卓手机。
- `deviceId`: 采集端唯一标识（多台电脑区分用）。

服务器校验通过后回复：

```json
{"type": "hello-ok", "role": "agent", "deviceId": "default"}
```

校验失败回复：

```json
{"type": "error", "message": "invalid token"}
```

### 1.2 心跳（应用层 ping/pong）

服务器每 30 秒向所有客户端发送一次：

```json
{"type": "ping"}
```

客户端应尽快回复：

```json
{"type": "pong"}
```

> 注意：心跳使用**应用层 JSON 消息**而非 WebSocket 原生 ping 帧，
> 以兼容 .NET ClientWebSocket 与 OkHttp 客户端（原生帧在部分客户端会触发解析异常）。

## 2. 请求 / 响应（中继模式）

手机发送 `request`，服务器按 `deviceId` 路由到对应采集端；采集端的 `response` 必须原样带回服务器注入的 `_fromId`，服务器据此回传给手机。

### 2.1 请求（手机 → 服务器）

```json
{
  "type": "request",
  "reqId": "m1",
  "action": "metrics",
  "deviceId": "default"
}
```

动作 `action`：

| action | 附加参数 | 说明 |
| --- | --- | --- |
| `metrics` | 无 | 拉取一次实时指标快照 |
| `processes` | 无 | 拉取进程列表 |
| `kill` | `pid` | 结束指定进程（含子进程树） |
| `subscribe` | `intervalMs` | 订阅周期推送（500~10000ms） |
| `unsubscribe` | 无 | 取消订阅 |

### 2.2 响应（采集端 → 手机，经服务器转发）

```json
{
  "type": "response",
  "reqId": "m1",
  "ok": true,
  "action": "metrics",
  "data": { ... }
}
```

`ok=false` 时携带 `message` 说明原因。

### 2.3 周期推送（采集端 → 手机，经服务器转发）

```json
{
  "type": "push",
  "action": "metrics",
  "data": { ... }
}
```

## 3. 局域网直连模式

手机与采集端同一网络时，可绕过服务器直接访问采集端内置 HTTP 服务（默认端口 **8668**）。

- 认证：请求头 `Authorization: Bearer <token>`，或 URL 参数 `?token=<token>`。
- 监听 `0.0.0.0` 需 URL ACL：`netsh http add urlacl url=http://+:8668/ user=Everyone`，或以管理员身份运行采集端。

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/api/status` | GET | 实时指标快照 |
| `/api/processes` | GET | 进程列表 |
| `/api/kill` | POST | 结束进程，body `{"pid": 123}` |
| `/api/stream` | GET | SSE 实时流（每 `intervalMs` 推送一条 `data: {...}`） |

## 4. 数据格式

### 4.1 指标快照 `metrics` / `push` 的 data

```json
{
  "timestamp": 1730000000000,
  "osVersion": "Windows 11 Pro 22631",
  "uptimeSec": 123456,
  "cpu": { "usagePercent": 3.2, "cores": 20, "freqMhz": 2112 },
  "memory": { "totalBytes": 16800000000, "usedBytes": 13400000000, "usagePercent": 79.8 },
  "disks": [
    { "name": "C:", "totalBytes": 256000000000, "usedBytes": 120000000000, "usagePercent": 47.0 }
  ],
  "network": { "rxBytesPerSec": 102400, "txBytesPerSec": 51200 }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `timestamp` | long | Unix 毫秒 |
| `osVersion` | string | 操作系统版本（含 build） |
| `uptimeSec` | long | 开机时长（秒） |
| `cpu.usagePercent` | double | CPU 总占用（0-100） |
| `cpu.cores` | int | 逻辑核心数 |
| `cpu.freqMhz` | int | 主频（MHz，读不到为 0） |
| `memory.totalBytes/usedBytes` | ulong/long | 字节 |
| `memory.usagePercent` | double | 内存占用（0-100） |
| `disks[]` | array | 每个固定盘：`name`（去掉结尾 `\\`）、`totalBytes`、`usedBytes`、`usagePercent` |
| `network.rxBytesPerSec/txBytesPerSec` | long | 上下行速率（字节/秒） |

### 4.2 进程列表 `processes` 的 data

```json
{
  "processes": [
    { "pid": 1234, "name": "chrome", "cpuPercent": 5.2, "memoryBytes": 524288000, "sessionId": 1 }
  ]
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `pid` | int | 进程 ID |
| `name` | string | 进程名（不含扩展名） |
| `cpuPercent` | double | 近 1 秒占用率（平均每个核心，0-100） |
| `memoryBytes` | long | 工作集内存（字节） |
| `sessionId` | int | 会话 ID |

> 说明：部分系统进程因权限读不到 CPU 时间，占用率按 0 处理；
> 采集端权限越高，能看到的进程越完整。
