using System.Runtime.InteropServices;
using System.Net.NetworkInformation;

namespace PcMonitorAgent.Core;

/// <summary>CPU / 内存 / 磁盘 / 网络指标采样器（周期线程持续刷新 LatestSnapshot）</summary>
public class MetricSampler : IDisposable
{
    // ---- P/Invoke ----
    [StructLayout(LayoutKind.Sequential)]
    private struct FILETIME { public uint dwLowDateTime; public uint dwHighDateTime; }

    [StructLayout(LayoutKind.Sequential)]
    private struct MEMORYSTATUSEX
    {
        public uint dwLength;
        public uint dwMemoryLoad;
        public ulong ullTotalPhys;
        public ulong ullAvailPhys;
        public ulong ullTotalPageFile;
        public ulong ullAvailPageFile;
        public ulong ullTotalVirtual;
        public ulong ullAvailVirtual;
        public ulong ullAvailExtendedVirtual;
    }

    [DllImport("kernel32.dll")]
    private static extern bool GetSystemTimes(out FILETIME idle, out FILETIME kernel, out FILETIME user);

    [DllImport("kernel32.dll")]
    private static extern bool GlobalMemoryStatusEx(ref MEMORYSTATUSEX buffer);

    private readonly AppConfig _config;
    private readonly System.Threading.Timer _timer;
    private readonly object _lock = new();
    private MetricSnapshot _latest = MetricSnapshot.Empty();
    private (ulong idle, ulong total) _prevCpu;
    private (long rx, long tx) _prevNet;
    private bool _hasPrev;
    private int _cores = Environment.ProcessorCount;

    public MetricSampler(AppConfig config)
    {
        _config = config;
        _timer = new System.Threading.Timer(_ => Sample(), null, Timeout.Infinite, Timeout.Infinite);
    }

    public void Start()
    {
        Sample(); // 立即采样一次，建立基线
        _timer.Change(1000, _config.PollIntervalMs);
    }

    /// <summary>取最新指标快照（线程安全）</summary>
    public MetricSnapshot Latest
    {
        get { lock (_lock) return _latest; }
    }

    private void Sample()
    {
        try
        {
            var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            var snapshot = new MetricSnapshot { Timestamp = now, OsVersion = GetOsVersion(), UptimeSec = Environment.TickCount64 / 1000 };

            // CPU：GetSystemTimes 两次采样差值
            if (GetSystemTimes(out var idle, out var kernel, out var user))
            {
                ulong idleNow = FiletimeToUInt64(idle);
                ulong totalNow = FiletimeToUInt64(kernel) + FiletimeToUInt64(user);
                if (_hasPrev)
                {
                    ulong idleDelta = idleNow - _prevCpu.idle;
                    ulong totalDelta = totalNow - _prevCpu.total;
                    if (totalDelta > 0)
                    {
                        snapshot.Cpu = new CpuInfo
                        {
                            UsagePercent = Math.Round(100.0 * (1.0 - (double)idleDelta / totalDelta), 1),
                            Cores = _cores,
                            FreqMhz = GetCpuFreqMhz(),
                        };
                    }
                }
                _prevCpu = (idleNow, totalNow);
            }

            // 内存
            var mem = new MEMORYSTATUSEX { dwLength = (uint)Marshal.SizeOf<MEMORYSTATUSEX>() };
            if (GlobalMemoryStatusEx(ref mem))
            {
                snapshot.Memory = new MemoryInfo
                {
                    TotalBytes = mem.ullTotalPhys,
                    UsedBytes = mem.ullTotalPhys - mem.ullAvailPhys,
                    UsagePercent = Math.Round(100.0 * (mem.ullTotalPhys - mem.ullAvailPhys) / mem.ullTotalPhys, 1),
                };
            }

            // 磁盘（固定盘）
            var disks = new List<DiskInfo>();
            foreach (var d in DriveInfo.GetDrives())
            {
                if (d.DriveType != DriveType.Fixed || !d.IsReady) continue;
                var total = d.TotalSize;
                if (total <= 0) continue;
                var used = total - d.AvailableFreeSpace;
                disks.Add(new DiskInfo
                {
                    Name = d.Name.TrimEnd('\\'),
                    TotalBytes = total,
                    UsedBytes = used,
                    UsagePercent = Math.Round(100.0 * used / total, 1),
                });
            }
            snapshot.Disks = disks;

            // 网络速率
            var (rx, tx) = SumNetworkBytes();
            if (_hasPrev)
            {
                double seconds = _config.PollIntervalMs / 1000.0;
                snapshot.Network = new NetworkInfo
                {
                    RxBytesPerSec = (long)Math.Max(0, (rx - _prevNet.rx) / seconds),
                    TxBytesPerSec = (long)Math.Max(0, (tx - _prevNet.tx) / seconds),
                };
            }
            _prevNet = (rx, tx);
            _hasPrev = true;

            lock (_lock) _latest = snapshot;
        }
        catch (Exception ex)
        {
            Log.Error("指标采样失败", ex);
        }
    }

    private static ulong FiletimeToUInt64(FILETIME ft) =>
        ((ulong)ft.dwHighDateTime << 32) | ft.dwLowDateTime;

    private static (long rx, long tx) SumNetworkBytes()
    {
        long rx = 0, tx = 0;
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            if (ni.NetworkInterfaceType == NetworkInterfaceType.Tunnel) continue;
            var stats = ni.GetIPv4Statistics();
            rx += stats.BytesReceived;
            tx += stats.BytesSent;
        }
        return (rx, tx);
    }

    private static string GetOsVersion()
    {
        try
        {
            using var key = Microsoft.Win32.Registry.LocalMachine.OpenSubKey(
                @"SOFTWARE\Microsoft\Windows NT\CurrentVersion");
            if (key != null)
            {
                var name = key.GetValue("ProductName")?.ToString() ?? "Windows";
                var build = key.GetValue("CurrentBuildNumber")?.ToString();
                return build == null ? name : $"{name} {build}";
            }
        }
        catch { /* 忽略 */ }
        return Environment.OSVersion.VersionString;
    }

    private static int GetCpuFreqMhz()
    {
        try
        {
            using var key = Microsoft.Win32.Registry.LocalMachine.OpenSubKey(
                @"HARDWARE\DESCRIPTION\System\CentralProcessor\0");
            if (key != null && key.GetValue("~MHz") is int mhz) return mhz;
        }
        catch { /* 虚拟机/受限环境可能读不到 */ }
        return 0;
    }

    public void Dispose() => _timer.Dispose();
}

// ---------- 数据模型 ----------
public class CpuInfo
{
    public double UsagePercent { get; set; }
    public int Cores { get; set; }
    public int FreqMhz { get; set; }
}

public class MemoryInfo
{
    public ulong TotalBytes { get; set; }
    public ulong UsedBytes { get; set; }
    public double UsagePercent { get; set; }
}

public class DiskInfo
{
    public string Name { get; set; } = "";
    public long TotalBytes { get; set; }
    public long UsedBytes { get; set; }
    public double UsagePercent { get; set; }
}

public class NetworkInfo
{
    public long RxBytesPerSec { get; set; }
    public long TxBytesPerSec { get; set; }
}

public class MetricSnapshot
{
    public long Timestamp { get; set; }
    public string OsVersion { get; set; } = "";
    public long UptimeSec { get; set; }
    public CpuInfo? Cpu { get; set; }
    public MemoryInfo? Memory { get; set; }
    public List<DiskInfo> Disks { get; set; } = new();
    public NetworkInfo? Network { get; set; }

    public static MetricSnapshot Empty() => new();
}
