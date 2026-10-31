using PcMonitorAgent.Core;
using PcMonitorAgent.Network;
using PcMonitorAgent.Ui;

namespace PcMonitorAgent;

/// <summary>
/// PcMonitorAgent - Windows 性能数据采集端
/// 采集 CPU/内存/磁盘/网络 + 进程列表，通过中继服务器（公网）或
/// 内置 HTTP 服务（局域网）供安卓手机远程查看。
///
/// 使用方式：
///   1. 编辑 exe 同目录 config.json（首次运行自动生成）
///   2. 双击运行，驻留系统托盘
///   3. 以管理员身份运行可结束系统级进程
/// </summary>
internal static class Program
{
    private static AppConfig? _config;
    private static MetricSampler? _metrics;
    private static ProcessSampler? _processes;
    private static RelayClient? _relay;
    private static LocalHttpServer? _localServer;
    private static TrayApp? _tray;

    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();

        try
        {
            _config = AppConfig.Load();
            _config.Validate();

            _metrics = new MetricSampler(_config);
            _processes = new ProcessSampler();
            _metrics.Start();
            _processes.Start();

            _tray = new TrayApp(_config, Restart, Exit);

            StartNetwork();

            Log.Info("PcMonitorAgent 启动完成: " +
                     $"mode={_config.Mode} token={(string.IsNullOrEmpty(_config.Token) ? "<空>" : "***")}");

            _tray.Run();
        }
        catch (Exception ex)
        {
            Log.Error("启动失败", ex);
            MessageBox.Show($"PcMonitorAgent 启动失败:\n{ex.Message}\n\n详情见日志:\n" +
                            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                                "PcMonitorAgent", "agent.log"),
                "PcMonitorAgent", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private static void StartNetwork()
    {
        _relay?.Dispose();
        _localServer?.Dispose();

        if (_config!.Mode == "relay")
        {
            _relay = new RelayClient(_config, _metrics!, _processes!);
            _relay.ConnectionChanged += on => _tray?.SetConnected(on);
            _relay.Start();
        }
        else
        {
            _localServer = new LocalHttpServer(_config, _metrics!, _processes!);
            _localServer.Start();
            _tray?.SetConnected(true); // 局域网服务常驻，视为在线
        }
    }

    private static void Restart()
    {
        Log.Info("收到重启服务请求");
        try
        {
            StartNetwork();
            _tray?.ShowBalloonTip(2000, "PcMonitorAgent", "服务已重启");
        }
        catch (Exception ex)
        {
            Log.Error("重启失败", ex);
        }
    }

    private static void Exit()
    {
        Log.Info("用户退出");
        _relay?.Dispose();
        _localServer?.Dispose();
        _metrics?.Dispose();
        _processes?.Dispose();
        _tray?.Dispose();
        Environment.Exit(0);
    }
}
