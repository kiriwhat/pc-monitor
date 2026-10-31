/**
 * 端到端链路测试：模拟安卓手机连接中继，向采集端请求真实数据。
 * 前置：1) 中继服务器已启动 (node server.js)
 *       2) 采集端已启动并连接同一中继（token 一致）
 * 运行：node test-phone.js [token]
 */
const WebSocket = require('ws');

const TOKEN = process.argv[2] || process.env.TEST_TOKEN || 'test-secret-token-123';
const URL = process.env.TEST_URL || 'ws://127.0.0.1:8787';

function send(ws, obj) { ws.send(JSON.stringify(obj)); }

async function main() {
  const ws = new WebSocket(URL);
  await new Promise((res, rej) => { ws.on('open', res); ws.on('error', rej); });

  // 手机注册
  send(ws, { type: 'hello', role: 'phone', token: TOKEN });
  const hello = await new Promise((res) => ws.on('message', (raw) => {
    const m = JSON.parse(raw.toString());
    if (m.type === 'hello-ok') res(m);
  }));
  console.log(`已连接中继，在线采集端: ${JSON.stringify(hello.agents)}`);
  if (!hello.agents.includes('default')) {
    console.error('采集端不在线！请确认 PcMonitorAgent 已启动并连上中继。');
    process.exit(1);
  }

  // 请求指标
  send(ws, { type: 'request', reqId: 't1', action: 'metrics', deviceId: 'default' });
  const metrics = await new Promise((res) => ws.on('message', (raw) => {
    const m = JSON.parse(raw.toString());
    if (m.type === 'response' && m.reqId === 't1') res(m);
  }));
  console.log('\n== 指标响应 ==');
  console.log(`CPU: ${metrics.data.cpu.usagePercent}% (${metrics.data.cpu.cores} 核, ${metrics.data.cpu.freqMhz}MHz)`);
  console.log(`内存: ${(metrics.data.memory.usedBytes / 1073741824).toFixed(1)}/${(metrics.data.memory.totalBytes / 1073741824).toFixed(1)} GB (${metrics.data.memory.usagePercent}%)`);
  console.log(`磁盘: ${metrics.data.disks.map(d => `${d.name} ${d.usagePercent}%`).join(', ')}`);
  console.log(`网络: ↓${(metrics.data.network.rxBytesPerSec / 1024).toFixed(0)}KB/s ↑${(metrics.data.network.txBytesPerSec / 1024).toFixed(0)}KB/s`);
  console.log(`系统: ${metrics.data.osVersion}, 开机 ${Math.floor(metrics.data.uptimeSec / 3600)}h`);

  // 请求进程列表
  send(ws, { type: 'request', reqId: 't2', action: 'processes', deviceId: 'default' });
  const procs = await new Promise((res) => ws.on('message', (raw) => {
    const m = JSON.parse(raw.toString());
    if (m.type === 'response' && m.reqId === 't2') res(m);
  }));
  console.log(`\n== 进程列表（共 ${procs.data.processes.length} 个）==`);
  procs.data.processes
    .sort((a, b) => b.cpuPercent - a.cpuPercent)
    .slice(0, 5)
    .forEach(p => console.log(`  ${p.name} PID=${p.pid} CPU=${p.cpuPercent}% 内存=${(p.memoryBytes / 1048576).toFixed(0)}MB`));

  // 订阅推送测试
  send(ws, { type: 'request', reqId: 't3', action: 'subscribe', intervalMs: 1500, deviceId: 'default' });
  const push = await new Promise((res) => ws.on('message', (raw) => {
    const m = JSON.parse(raw.toString());
    if (m.type === 'push') res(m);
  }));
  console.log(`\n== 订阅推送 == 收到 push: CPU=${push.data.cpu.usagePercent}% ✓`);

  console.log('\n端到端链路测试全部通过！');
  ws.close();
  process.exit(0);
}

main().catch((e) => { console.error('测试失败:', e.message); process.exit(1); });
