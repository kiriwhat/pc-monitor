using System.Text.Json;

namespace PcMonitorAgent.Core;

/// <summary>
/// 采集端配置。配置文件 config.json 位于 exe 同目录。
/// </summary>
public class AppConfig
{
    /// <summary>连接模式：relay=公网中继 | local=局域网直连</summary>
    public string Mode { get; set; } = "relay";

    /// <summary>中继服务器地址（Mode=relay 时使用），支持 ws:// 或 wss://</summary>
    public string ServerUrl { get; set; } = "ws://127.0.0.1:8787";

    /// <summary>认证口令：中继服务器、采集端、手机三处必须一致</summary>
    public string Token { get; set; } = "";

    /// <summary>设备标识（中继模式下用于区分多台电脑）</summary>
    public string DeviceId { get; set; } = "default";

    /// <summary>局域网模式 HTTP 端口（Mode=local 时使用）</summary>
    public int LocalPort { get; set; } = 8668;

    /// <summary>指标采样与推送间隔（毫秒）</summary>
    public int PollIntervalMs { get; set; } = 2000;

    private static string ConfigPath =>
        Path.Combine(AppContext.BaseDirectory, "config.json");

    private static string DefaultConfigPath =>
        Path.Combine(AppContext.BaseDirectory, "config.example.json");

    public static AppConfig Load()
    {
        var config = new AppConfig();
        try
        {
            if (File.Exists(ConfigPath))
            {
                var json = File.ReadAllText(ConfigPath);
                config = JsonSerializer.Deserialize<AppConfig>(json) ?? new AppConfig();
            }
        }
        catch (Exception ex)
        {
            Log.Warn($"读取 config.json 失败，使用默认配置: {ex.Message}");
        }

        // 自动生成默认配置文件（首次运行时）
        if (!File.Exists(ConfigPath))
        {
            try
            {
                File.WriteAllText(ConfigPath, JsonSerializer.Serialize(config, new JsonSerializerOptions { WriteIndented = true }));
                Log.Info("已生成默认配置文件: " + ConfigPath);
            }
            catch (Exception ex)
            {
                Log.Warn($"写入默认配置失败: {ex.Message}");
            }
        }
        return config;
    }

    public void Validate()
    {
        if (string.IsNullOrWhiteSpace(Token))
        {
            Log.Warn("配置中 Token 为空！任何人都可能连接到你的设备，请设置强随机口令。");
        }
        if (Mode == "relay" && !ServerUrl.StartsWith("ws://") && !ServerUrl.StartsWith("wss://"))
        {
            throw new InvalidOperationException("ServerUrl 必须以 ws:// 或 wss:// 开头");
        }
    }
}
