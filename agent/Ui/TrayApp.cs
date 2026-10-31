using System.Reflection;
using PcMonitorAgent.Core;

namespace PcMonitorAgent.Ui;

/// <summary>系统托盘：显示连接状态，提供菜单操作（重启服务 / 打开日志 / 退出）</summary>
public class TrayApp : IDisposable
{
    private readonly AppConfig _config;
    private readonly Action _onRestart;
    private readonly Action _onExit;
    private NotifyIcon? _tray;
    private volatile bool _connected;
    private bool _disposed;

    public TrayApp(AppConfig config, Action onRestart, Action onExit)
    {
        _config = config;
        _onRestart = onRestart;
        _onExit = onExit;
    }

    public void SetConnected(bool connected)
    {
        _connected = connected;
        UpdateTray();
    }

    /// <summary>供外部组件弹气泡提示</summary>
    public void ShowBalloonTip(int timeoutMs, string title, string text) =>
        _tray?.ShowBalloonTip(timeoutMs, title, text, ToolTipIcon.Info);

    private void UpdateTray()
    {
        if (_tray == null || _disposed) return;
        try
        {
            var modeText = _config.Mode == "relay" ? "中继" : "局域网";
            _tray.Text = _connected
                ? $"PcMonitorAgent - {modeText}模式 在线"
                : $"PcMonitorAgent - {modeText}模式 离线(重连中)";
        }
        catch { /* 托盘文本超长等异常忽略 */ }
    }

    public void Run()
    {
        // WinForms 必须跑在 STA 线程
        _tray = new NotifyIcon
        {
            Icon = LoadIcon(),
            Text = "PcMonitorAgent 启动中...",
            Visible = true,
        };

        var menu = new ContextMenuStrip();
        menu.Items.Add("当前状态", null, (_, _) => ShowStatus());
        menu.Items.Add("打开配置文件夹", null, (_, _) => OpenConfigFolder());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("重启服务", null, (_, _) => _onRestart());
        menu.Items.Add("退出", null, (_, _) => _onExit());

        _tray.ContextMenuStrip = menu;
        _tray.DoubleClick += (_, _) => ShowStatus();

        UpdateTray();
        System.Windows.Forms.Application.Run();
    }

    private void ShowStatus()
    {
        var modeText = _config.Mode == "relay" ? "公网中继" : "局域网直连";
        var stateText = _connected ? "在线" : "离线（自动重连中）";
        var detail = _config.Mode == "relay"
            ? $"服务器: {_config.ServerUrl}"
            : $"监听端口: {_config.LocalPort}";
        _tray?.ShowBalloonTip(3000, "PcMonitorAgent",
            $"状态: {stateText}\n模式: {modeText}\n{detail}", ToolTipIcon.Info);
    }

    private void OpenConfigFolder()
    {
        try
        {
            System.Diagnostics.Process.Start("explorer.exe", $"/select,\"{AppContext.BaseDirectory}config.json\"");
        }
        catch (Exception ex)
        {
            Log.Warn($"打开配置目录失败: {ex.Message}");
        }
    }

    private static Icon LoadIcon()
    {
        try
        {
            var path = Path.Combine(AppContext.BaseDirectory, "app.ico");
            if (File.Exists(path)) return new Icon(path);
        }
        catch { /* 图标加载失败则用系统默认 */ }
        return SystemIcons.Application;
    }

    public void Dispose()
    {
        _disposed = true;
        if (_tray != null)
        {
            _tray.Visible = false;
            _tray.Dispose();
            _tray = null;
        }
    }
}
