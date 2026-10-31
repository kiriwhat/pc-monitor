using System.Diagnostics;

namespace PcMonitorAgent.Core;

/// <summary>
/// 进程列表采样器：每秒采样所有进程 CPU 时间，计算占用率（平均每个核心）。
/// 说明：CPU% 为近 1 秒平均值；部分系统进程可能因权限读不到 CPU 时间，按 0 处理。
/// </summary>
public class ProcessSampler : IDisposable
{
    private readonly System.Threading.Timer _timer;
    private readonly object _lock = new();
    private readonly Dictionary<int, (double cpuTimeMs, long ticks)> _prev = new();
    private List<ProcessInfo> _latest = new();
    private readonly double _cores = Environment.ProcessorCount;

    public ProcessSampler()
    {
        _timer = new System.Threading.Timer(_ => Sample(), null, Timeout.Infinite, Timeout.Infinite);
    }

    public void Start() => _timer.Change(1000, 1000);

    /// <summary>取最新进程列表（线程安全）</summary>
    public List<ProcessInfo> Latest
    {
        get { lock (_lock) return _latest; }
    }

    private void Sample()
    {
        try
        {
            var now = Stopwatch.GetTimestamp();
            var result = new List<ProcessInfo>();
            var seen = new HashSet<int>();

            foreach (var p in Process.GetProcesses())
            {
                seen.Add(p.Id);
                double cpuMs = 0;
                try { cpuMs = p.TotalProcessorTime.TotalMilliseconds; } catch { /* 权限不足 */ }

                double cpuPercent = 0;
                if (_prev.TryGetValue(p.Id, out var prevEntry))
                {
                    var deltaCpu = cpuMs - prevEntry.cpuTimeMs;
                    var deltaTicks = now - prevEntry.ticks;
                    var deltaSec = deltaTicks / (double)Stopwatch.Frequency;
                    if (deltaSec > 0 && deltaCpu >= 0)
                    {
                        // 平均每个核心的占用率（0-100 直观区间）
                        cpuPercent = Math.Round(deltaCpu / deltaSec / _cores * 100.0, 1);
                        if (cpuPercent < 0) cpuPercent = 0;
                    }
                }
                _prev[p.Id] = (cpuMs, now);

                long memoryBytes = 0;
                try { memoryBytes = p.WorkingSet64; } catch { /* 权限不足 */ }

                int sessionId = 0;
                try { sessionId = p.SessionId; } catch { /* 权限不足 */ }

                string name;
                try { name = p.ProcessName; } catch { name = "unknown"; }
                if (string.IsNullOrWhiteSpace(name)) name = "system";

                result.Add(new ProcessInfo
                {
                    Pid = p.Id,
                    Name = name,
                    CpuPercent = cpuPercent,
                    MemoryBytes = memoryBytes,
                    SessionId = sessionId,
                });
            }

            // 清理已退出的进程记录，防止字典无限增长
            foreach (var pid in _prev.Keys.Where(k => !seen.Contains(k)).ToList())
                _prev.Remove(pid);

            lock (_lock) _latest = result;
        }
        catch (Exception ex)
        {
            Log.Error("进程采样失败", ex);
        }
    }

    public static void KillProcess(int pid)
    {
        using var p = Process.GetProcessById(pid);
        p.Kill(entireProcessTree: true);
    }

    public void Dispose() => _timer.Dispose();
}

public class ProcessInfo
{
    public int Pid { get; set; }
    public string Name { get; set; } = "";
    public double CpuPercent { get; set; }
    public long MemoryBytes { get; set; }
    public int SessionId { get; set; }
}
