using System.Net;
using System.Text;
using System.Text.Json;
using PcMonitorAgent.Core;

namespace PcMonitorAgent.Network;

/// <summary>
/// 局域网直连模式：内置 HTTP 服务，手机在同一个 WiFi 下直接访问。
/// 接口见 docs/protocol.md 第 3 节，所有请求需携带 token。
/// 注意：监听 0.0.0.0 需要 URL ACL 权限，首次运行请执行：
///   netsh http add urlacl url=http://+:8668/ user=Everyone
/// （或以管理员身份运行本程序，自动生效）
/// </summary>
public class LocalHttpServer : IDisposable
{
    private readonly AppConfig _config;
    private readonly MetricSampler _metrics;
    private readonly ProcessSampler _processes;
    private HttpListener? _listener;
    private bool _running;

    // 协议统一 camelCase（docs/protocol.md）
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    public LocalHttpServer(AppConfig config, MetricSampler metrics, ProcessSampler processes)
    {
        _config = config;
        _metrics = metrics;
        _processes = processes;
    }

    public void Start()
    {
        _listener = new HttpListener();
        _listener.Prefixes.Add($"http://+:{_config.LocalPort}/");
        _listener.Start();
        _running = true;
        Log.Info($"局域网 HTTP 服务已启动，端口 {_config.LocalPort}");
        _ = Task.Run(ListenLoopAsync);
    }

    private async Task ListenLoopAsync()
    {
        while (_running && _listener != null)
        {
            HttpListenerContext ctx;
            try { ctx = await _listener.GetContextAsync(); }
            catch { break; } // 监听器停止

            _ = Task.Run(() => HandleAsync(ctx));
        }
    }

    private async Task HandleAsync(HttpListenerContext ctx)
    {
        try
        {
            if (!Authorized(ctx.Request))
            {
                await WriteJsonAsync(ctx, 401, new { error = "token 校验失败" });
                return;
            }

            var path = ctx.Request.Url?.AbsolutePath ?? "/";
            var method = ctx.Request.HttpMethod;

            if (path == "/api/status" && method == "GET")
            {
                await WriteJsonAsync(ctx, 200, _metrics.Latest);
            }
            else if (path == "/api/processes" && method == "GET")
            {
                await WriteJsonAsync(ctx, 200, new { processes = _processes.Latest });
            }
            else if (path == "/api/kill" && method == "POST")
            {
                using var reader = new StreamReader(ctx.Request.InputStream, Encoding.UTF8);
                var body = await reader.ReadToEndAsync();
                var req = JsonSerializer.Deserialize<KillRequest>(body);
                if (req?.Pid == null)
                {
                    await WriteJsonAsync(ctx, 400, new { error = "缺少 pid" });
                    return;
                }
                ProcessSampler.KillProcess(req.Pid.Value);
                Log.Info($"已结束进程 PID={req.Pid}（局域网请求）");
                await WriteJsonAsync(ctx, 200, new { ok = true });
            }
            else if (path == "/api/stream" && method == "GET")
            {
                await StreamSseAsync(ctx);
            }
            else
            {
                await WriteJsonAsync(ctx, 404, new { error = "接口不存在，可用: /api/status /api/processes /api/kill /api/stream" });
            }
        }
        catch (Exception ex)
        {
            Log.Error("局域网请求处理失败", ex);
            try { await WriteJsonAsync(ctx, 500, new { error = "服务器内部错误" }); } catch { /* 连接已断开 */ }
        }
    }

    private bool Authorized(HttpListenerRequest request)
    {
        // 1) Authorization: Bearer <token>
        var auth = request.Headers["Authorization"];
        if (!string.IsNullOrEmpty(auth) && auth.StartsWith("Bearer "))
        {
            return auth.Substring(7).Trim() == _config.Token;
        }
        // 2) URL 参数 ?token=<token>（SSE 等场景更友好）
        var q = request.QueryString["token"];
        if (!string.IsNullOrEmpty(q)) return q == _config.Token;

        return false;
    }

    /// <summary>SSE 实时流：每 intervalMs 推送一条 metrics</summary>
    private async Task StreamSseAsync(HttpListenerContext ctx)
    {
        var response = ctx.Response;
        response.StatusCode = 200;
        response.ContentType = "text/event-stream; charset=utf-8";
        response.AddHeader("Cache-Control", "no-cache");
        response.AddHeader("Connection", "keep-alive");
        response.AddHeader("Access-Control-Allow-Origin", "*");

        var interval = Math.Clamp(_config.PollIntervalMs, 500, 10000);
        using var cts = new CancellationTokenSource();

        try
        {
            while (!cts.IsCancellationRequested)
            {
                var json = JsonSerializer.Serialize(_metrics.Latest, JsonOptions);
                var line = $"data: {json}\n\n";
                var bytes = Encoding.UTF8.GetBytes(line);
                await response.OutputStream.WriteAsync(bytes, cts.Token);
                await response.OutputStream.FlushAsync(cts.Token);
                await Task.Delay(interval, cts.Token);
            }
        }
        catch (OperationCanceledException) { /* 客户端断开，正常结束 */ }
        catch (Exception ex)
        {
            Log.Warn($"SSE 流结束: {ex.Message}");
        }
    }

    private static async Task WriteJsonAsync(HttpListenerContext ctx, int status, object payload)
    {
        var bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(payload, JsonOptions));
        ctx.Response.StatusCode = status;
        ctx.Response.ContentType = "application/json; charset=utf-8";
        ctx.Response.ContentLength64 = bytes.Length;
        ctx.Response.AddHeader("Access-Control-Allow-Origin", "*");
        await ctx.Response.OutputStream.WriteAsync(bytes);
        ctx.Response.Close();
    }

    public void Dispose()
    {
        _running = false;
        try { _listener?.Stop(); } catch { }
        try { _listener?.Close(); } catch { }
    }
}

internal class KillRequest
{
    public int? Pid { get; set; }
}
