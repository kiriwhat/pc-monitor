namespace PcMonitorAgent.Core;

/// <summary>极简文件日志（%LOCALAPPDATA%\PcMonitorAgent\agent.log）</summary>
public static class Log
{
    private static readonly object Lock = new();
    private static string? _path;

    private static string Path
    {
        get
        {
            if (_path == null)
            {
                var dir = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
                dir = System.IO.Path.Combine(dir, "PcMonitorAgent");
                Directory.CreateDirectory(dir);
                _path = System.IO.Path.Combine(dir, "agent.log");
            }
            return _path;
        }
    }

    public static void Info(string msg) => Write("INFO", msg);
    public static void Warn(string msg) => Write("WARN", msg);
    public static void Error(string msg, Exception? ex = null) =>
        Write("ERROR", ex == null ? msg : $"{msg} | {ex.GetType().Name}: {ex.Message}");

    private static void Write(string level, string msg)
    {
        try
        {
            lock (Lock)
            {
                File.AppendAllText(Path,
                    $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] [{level}] {msg}{Environment.NewLine}");
            }
        }
        catch
        {
            // 日志失败不影响主流程
        }
    }
}
