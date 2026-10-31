using System.Net;
using System.Net.WebSockets;

var ws = new ClientWebSocket();
ws.Options.Proxy = new WebProxy();
try {
    await ws.ConnectAsync(new Uri("ws://127.0.0.1:8787"), CancellationToken.None);
    Console.WriteLine("CONNECTED state=" + ws.State);
    await ws.SendAsync(new ArraySegment<byte>(System.Text.Encoding.UTF8.GetBytes(
        "{\"type\":\"hello\",\"role\":\"agent\",\"token\":\"test-secret-token-123\",\"deviceId\":\"default\"}")),
        WebSocketMessageType.Text, true, CancellationToken.None);
    Console.WriteLine("HELLO SENT");
    var buf = new byte[8192];
    var result = await ws.ReceiveAsync(new ArraySegment<byte>(buf), CancellationToken.None);
    Console.WriteLine("RECV: " + System.Text.Encoding.UTF8.GetString(buf, 0, result.Count));
} catch (Exception ex) {
    Console.WriteLine("ERROR: " + ex.GetType().Name + ": " + ex.Message);
}
