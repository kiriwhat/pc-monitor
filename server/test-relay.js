/**
 * 中继服务器端到端自测（可选）
 * 运行：先启动 node server.js，再开另一个终端执行 node test-relay.js
 * 模拟流程：
 *   1) 采集端(agent) 用正确 token 连接并注册
 *   2) 手机(phone) 用正确 token 连接
 *   3) phone 发送 metrics 请求 -> 服务器路由给 agent -> agent 回复 -> 回传 phone
 *   4) 手机用错误 token 连接应被拒绝
 */
const WebSocket = require('ws');

const TOKEN = process.env.TEST_TOKEN || 'test-secret-token-123';
const URL = process.env.TEST_URL || 'ws://127.0.0.1:8787';
let passed = 0;
let failed = 0;

function check(name, cond) {
  if (cond) { passed++; console.log(`  ✓ ${name}`); }
  else { failed++; console.log(`  ✗ ${name}`); }
}

function connect(role, token, deviceId) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(URL);
    ws.on('open', () => {
      ws.send(JSON.stringify({ type: 'hello', role, token, deviceId }));
    });
    ws.on('message', (raw) => {
      const msg = JSON.parse(raw.toString());
      if (msg.type === 'hello-ok') resolve({ ws, msg });
      else if (msg.type === 'error') { ws.close(); reject(new Error(msg.message)); }
    });
    ws.on('error', reject);
    setTimeout(() => reject(new Error('connect timeout')), 5000);
  });
}

async function main() {
  console.log('== 测试开始 ==\n');

  // 1. agent 注册
  console.log('[1] 采集端用正确 token 注册');
  const agent = await connect('agent', TOKEN, 'test-pc');
  check('agent 收到 hello-ok', agent.msg.role === 'agent' && agent.msg.deviceId === 'test-pc');

  // 2. phone 注册
  console.log('[2] 手机用正确 token 注册');
  const phone = await connect('phone', TOKEN);
  check('phone 收到 hello-ok 且能看到在线设备', phone.msg.agents.includes('test-pc'));

  // 3. 请求路由：phone -> server -> agent -> phone
  console.log('[3] 手机请求 metrics，采集端回复');
  const respPromise = new Promise((resolve) => {
    phone.ws.on('message', (raw) => {
      const msg = JSON.parse(raw.toString());
      if (msg.type === 'response' && msg.reqId === 'req-1') resolve(msg);
    });
  });
  agent.ws.on('message', (raw) => {
    const msg = JSON.parse(raw.toString());
    if (msg.type === 'request' && msg.action === 'metrics' && msg.reqId === 'req-1') {
      agent.ws.send(JSON.stringify({
        type: 'response', reqId: 'req-1', action: 'metrics', ok: true,
        data: { timestamp: Date.now(), cpu: { usage: 12.3 } },
        _fromId: msg._fromId, // 原样带回来源连接 ID，服务器据此回传
      }));
    }
  });
  phone.ws.send(JSON.stringify({ type: 'request', reqId: 'req-1', action: 'metrics', deviceId: 'test-pc' }));
  const resp = await Promise.race([respPromise, new Promise((_, rej) => setTimeout(() => rej(new Error('响应超时')), 5000))]);
  check('metrics 响应正确回传', resp.ok === true && resp.data.cpu.usage === 12.3);

  // 4. 错误 token 应被拒绝
  console.log('[4] 错误 token 连接应被拒绝');
  const badRejected = await new Promise((resolve) => {
    const ws = new WebSocket(URL);
    ws.on('open', () => ws.send(JSON.stringify({ type: 'hello', role: 'phone', token: 'wrong' })));
    ws.on('message', (raw) => {
      const msg = JSON.parse(raw.toString());
      if (msg.type === 'error') resolve(true);
    });
    ws.on('close', () => resolve(true));
    setTimeout(() => resolve(false), 5000);
  });
  check('错误 token 被拒绝', badRejected);

  agent.ws.close();
  phone.ws.close();

  console.log(`\n== 结果: ${passed} 通过, ${failed} 失败 ==`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error('测试失败:', e.message); process.exit(1); });
