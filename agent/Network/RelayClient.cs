using System.Net;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using PcMonitorAgent.Core;

namespace PcMonitorAgent.Network;

/// <summary>
/// 公网中继客户端：通过 WebSocket 连接中继服务器，响应手机的
/// metrics / processes / kill / subscribe 请求，并周期推送指标。
/// 断线自动重连（指数退避 3s → 30s）。
/// </summary>
public class RelayClient : IDisposable
{
    private readonly AppConfig _config;
    private readonly MetricSampler _metrics;
    private readonly ProcessSampler _processes;
    private readonly CancellationTokenSource _cts = new();
    private ClientWebSocket? _ws;
    private volatile bool _subscribed;
    private int _subIntervalMs;
    private System.Threading.Timer? _pushTimer;
    private bool _disposed;

    public event Action<bool>? ConnectionChanged;

    public RelayClient(AppConfig config, MetricSampler metrics, ProcessSampler processes)
    {
        _config = config;
        _metrics = metrics;
        _processes = processes;
    }

    public void Start()
    {
        _ = Task.Run(() => RunAsync(_cts.Token));
    }

    private async Task RunAsync(CancellationToken ct)
    {
        var delay = TimeSpan.FromSeconds(3);
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await ConnectLoopAsync(ct);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                Log.Warn($"中继连接异常，{delay.TotalSeconds:0}s 后重试: {ex.Message}");
                ConnectionChanged?.Invoke(false);
            }

            try { await Task.Delay(delay, ct); } catch (OperationCanceledException) { break; }
            delay = TimeSpan.FromSeconds(Math.Min(30, delay.TotalSeconds * 2)); // 指数退避
        }
    }

    private async Task ConnectLoopAsync(CancellationToken ct)
    {
        using var ws = new ClientWebSocket();
        // 直连中继/局域网服务器：禁用系统代理（如 Clash 等代理软件会劫持本地 WebSocket 连接）
        ws.Options.Proxy = new WebProxy();
        _ws = ws;
        await ws.ConnectAsync(new Uri(_config.ServerUrl), ct);
        Log.Info($"已连接中继服务器 {_config.ServerUrl}");
        ConnectionChanged?.Invoke(true);

        // 注册
        await SendAsync(ws, new RelayMessage
        {
            Type = "hello",
            Role = "agent",
            Token = _config.Token,
            DeviceId = _config.DeviceId,
        }, ct);

        // 接收循环
        var buffer = new byte[65536];
        while (ws.State == WebSocketState.Open)
        {
            var text = await ReceiveTextAsync(ws, buffer, ct);
            if (text == null) break; // 连接已关闭
            RelayMessage? msg = null;
            try
            {
                msg = RelayMessage.Parse(text);
            }
            catch (Exception ex)
            {
                Log.Error($"收到非法 JSON 消息(前100字): {Truncate(text, 100)} | {ex.Message}");
            }
            if (msg != null) await HandleMessageAsync(ws, msg, ct);
        }
    }

    private static string Truncate(string s, int max) =>
        s.Length <= max ? s : s.Substring(0, max) + "...";

    private async Task HandleMessageAsync(ClientWebSocket ws, RelayMessage msg, CancellationToken ct)
    {
        switch (msg.Type)
        {
            case "hello-ok":
                Log.Info($"注册成功 role={msg.Role} deviceId={msg.DeviceId}");
                break;

            case "request":
                await HandleRequestAsync(ws, msg, ct);
                break;

            case "pong":
                // 心跳响应，无需处理
                break;

            case "ping":
                // 服务器心跳，回复 pong 保持连接
                await SendAsync(ws, new RelayMessage { Type = "pong" }, ct);
                break;
        }
    }

    private async Task HandleRequestAsync(ClientWebSocket ws, RelayMessage req, CancellationToken ct)
    {
        try
        {
            switch (req.Action)
            {
                case "metrics":
                    await SendAsync(ws, Response(req, new { ok = true, action = "metrics", data = _metrics.Latest }), ct);
                    break;

                case "processes":
                    await SendAsync(ws, Response(req, new { ok = true, action = "processes", data = new { processes = _processes.Latest } }), ct);
                    break;

                case "kill":
                    if (req.Pid is int pid)
                    {
                        ProcessSampler.KillProcess(pid);
                        Log.Info($"已结束进程 PID={pid}（远程请求）");
                        await SendAsync(ws, Response(req, new { ok = true, action = "kill" }), ct);
                    }
                    else
                    {
                        await SendAsync(ws, Response(req, new { ok = false, action = "kill", message = "缺少 pid 参数" }), ct);
                    }
                    break;

                case "subscribe":
                    _subscribed = true;
                    _subIntervalMs = Math.Clamp(req.IntervalMs ?? _config.PollIntervalMs, 500, 10000);
                    StartPushTimer(ws);
                    await SendAsync(ws, Response(req, new { ok = true, action = "subscribe" }), ct);
                    Log.Info($"开始周期推送（{_subIntervalMs}ms）");
                    break;

                case "unsubscribe":
                    _subscribed = false;
                    _pushTimer?.Dispose();
                    _pushTimer = null;
                    await SendAsync(ws, Response(req, new { ok = true, action = "unsubscribe" }), ct);
                    break;

                default:
                    await SendAsync(ws, Response(req, new { ok = false, action = req.Action, message = $"未知操作 {req.Action}" }), ct);
                    break;
            }
        }
        catch (Exception ex)
        {
            Log.Error($"处理请求 {req.Action} 失败", ex);
            try
            {
                await SendAsync(ws, Response(req, new { ok = false, action = req.Action, message = ex.Message }), ct);
            }
            catch { /* 连接已断开 */ }
        }
    }

    private RelayMessage Response(RelayMessage req, object payload)
    {
        var dict = new Dictionary<string, object?>();
        foreach (var prop in payload.GetType().GetProperties())
            dict[prop.Name] = prop.GetValue(payload);

        var msg = new RelayMessage
        {
            Type = "response",
            ReqId = req.ReqId,
            Ok = dict.TryGetValue("ok", out var ok) && ok is bool b && b,
            FromId = req.FromId, // 关键：原样带回来源 ID，服务器才能回传给手机
        };
        if (dict.TryGetValue("action", out var action) && action is string s) msg.Action = s;
        if (dict.TryGetValue("message", out var message) && message is string m) msg.Message = m;
        if (dict.TryGetValue("data", out var data) && data != null)
            msg.Data = JsonSerializer.SerializeToElement(data, RelayMessage.Options);
        return msg;
    }

    private void StartPushTimer(ClientWebSocket ws)
    {
        _pushTimer?.Dispose();
        _pushTimer = new System.Threading.Timer(_ =>
        {
            if (!_subscribed || ws.State != WebSocketState.Open) return;
            try
            {
                var msg = new RelayMessage
                {
                    Type = "push",
                    Action = "metrics",
                    Data = JsonSerializer.SerializeToElement(_metrics.Latest, RelayMessage.Options),
                };
                _ = ws.SendAsync(new ArraySegment<byte>(Encoding.UTF8.GetBytes(msg.Serialize())),
                    WebSocketMessageType.Text, true, _cts.Token);
            }
            catch { /* 连接断开时静默 */ }
        }, null, _subIntervalMs, _subIntervalMs);
    }

    private static async Task<string?> ReceiveTextAsync(ClientWebSocket ws, byte[] buffer, CancellationToken ct)
    {
        var sb = new StringBuilder();
        while (true)
        {
            var result = await ws.ReceiveAsync(new ArraySegment<byte>(buffer), ct);
            if (result.MessageType == WebSocketMessageType.Close) return null; // 服务端关闭
            sb.Append(Encoding.UTF8.GetString(buffer, 0, result.Count));
            if (result.EndOfMessage) break;
        }
        return sb.ToString();
    }

    private static async Task SendAsync(ClientWebSocket ws, RelayMessage msg, CancellationToken ct)
    {
        var bytes = Encoding.UTF8.GetBytes(msg.Serialize());
        await ws.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, ct);
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _cts.Cancel();
        _pushTimer?.Dispose();
        try { _ws?.Dispose(); } catch { }
        _cts.Dispose();
    }
}
