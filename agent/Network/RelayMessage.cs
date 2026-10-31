using System.Text.Json;
using System.Text.Json.Serialization;

namespace PcMonitorAgent.Network;

/// <summary>
/// 中继协议消息（与 docs/protocol.md 保持一致，全部使用 camelCase）。
/// 采集端只关心收到的 request 和发出的 response/push。
/// </summary>
public class RelayMessage
{
    public string? Type { get; set; }
    public string? Role { get; set; }
    public string? Token { get; set; }
    public string? DeviceId { get; set; }
    public string? ReqId { get; set; }
    public string? Action { get; set; }
    public int? Pid { get; set; }
    public int? IntervalMs { get; set; }
    public bool? Ok { get; set; }
    [JsonPropertyName("_fromId")]
    public int? FromId { get; set; }        // 服务器注入的来源连接 ID，响应时必须原样带回
    public string? Message { get; set; }
    public JsonElement? Data { get; set; }

    internal static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    public static RelayMessage? Parse(string json) =>
        JsonSerializer.Deserialize<RelayMessage>(json, Options);

    public string Serialize() =>
        JsonSerializer.Serialize(this, Options);
}
