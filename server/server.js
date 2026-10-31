/**
 * PC Monitor 公网中继服务器
 * ----------------------------------------------
 * 场景：家里/公司的电脑没有公网 IP，手机在外面无法直连。
 * 本服务器部署在一台有公网 IP 的云服务器上，作为中转站：
 *   Windows 采集端(agent)  ->  中继服务器  <-  安卓手机(phone)
 *
 * 通信协议（JSON over WebSocket）见 docs/protocol.md
 *
 * 部署：node server.js（或 pm2 start server.js）
 * 配置：环境变量或 config.json（二选一）
 *   PORT  - 监听端口，默认 8787
 *   TOKEN - 认证口令，所有客户端必须携带相同 TOKEN 才能连接
 *
 * 安全提醒：TOKEN 是唯一的访问凭证，务必设置一个足够长的随机字符串，
 * 不要使用默认值。若被他人获取，对方即可远程查看/结束你电脑的进程。
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

// ---------- 配置 ----------
function loadConfig() {
  const cfg = { port: 8787, token: '' };
  const cfgFile = path.join(__dirname, 'config.json');
  if (fs.existsSync(cfgFile)) {
    try {
      const fileCfg = JSON.parse(fs.readFileSync(cfgFile, 'utf8'));
      Object.assign(cfg, fileCfg);
    } catch (e) {
      console.error('[config] config.json 解析失败，使用默认/环境变量配置:', e.message);
    }
  }
  if (process.env.PORT) cfg.port = Number(process.env.PORT);
  if (process.env.TOKEN) cfg.token = process.env.TOKEN;
  if (!cfg.token) {
    cfg.token = 'CHANGE_ME_TO_A_LONG_RANDOM_STRING';
  }
  return cfg;
}
const config = loadConfig();

// ---------- 设备注册表 ----------
// agents:  deviceId -> { ws, lastSeen }
// phones:  Set<ws>   （默认同一用户一部手机，如多设备可按 phoneId 管理）
const agents = new Map();
const phones = new Set();

// 连接路由表：connId -> ws（用于把 agent 的响应回传给原始手机）
let nextConnId = 1;
const connById = new Map();

function log(prefix, msg) {
  console.log(`[${new Date().toLocaleTimeString()}] [${prefix}] ${msg}`);
}

function safeSend(ws, obj) {
  if (ws && ws.readyState === ws.OPEN) {
    try {
      ws.send(JSON.stringify(obj));
    } catch (e) {
      log('error', `发送失败: ${e.message}`);
    }
  }
}

// ---------- WebSocket 服务 ----------
const server = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('PC Monitor Relay Server is running.\n');
});

const wss = new WebSocketServer({ server });

wss.on('connection', (ws, req) => {
  const connId = nextConnId++;
  connById.set(connId, ws);
  const clientIp = req.socket.remoteAddress;
  let role = null;          // 'agent' | 'phone'
  let deviceId = null;      // agent 的设备标识
  let authed = false;

  ws.isAlive = true;
  // 客户端回复 pong 时标记存活（协议层 JSON）
  ws.on('message', (raw) => {
    let parsed;
    try { parsed = JSON.parse(raw.toString()); } catch { /* 非法消息交给下方统一处理 */ }
    if (parsed && parsed.type === 'pong') ws.isAlive = true;
  });

  // 等待 hello 的超时保护：30 秒内未完成认证则断开
  const authTimer = setTimeout(() => {
    if (!authed) {
      log('security', `来自 ${clientIp} 的连接 30 秒内未完成认证，已断开`);
      ws.close();
    }
  }, 30000);

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch (e) {
      safeSend(ws, { type: 'error', message: '非法 JSON 消息' });
      return;
    }

    // ---------- 心跳 ----------
    if (msg.type === 'ping') {
      safeSend(ws, { type: 'pong' });
      return;
    }

    // ---------- 认证 ----------
    if (!authed) {
      if (msg.type !== 'hello') {
        safeSend(ws, { type: 'error', message: '请先发送 hello 完成认证' });
        return;
      }
      if (msg.token !== config.token) {
        log('security', `来自 ${clientIp} 的认证失败（token 不匹配），已断开`);
        safeSend(ws, { type: 'error', message: 'token 校验失败' });
        ws.close();
        return;
      }
      if (msg.role === 'agent') {
        role = 'agent';
        deviceId = (msg.deviceId || 'default').toString().slice(0, 64);
        const old = agents.get(deviceId);
        if (old) {
          log('agent', `设备 ${deviceId} 重新上线，踢掉旧连接`);
          old.close();
        }
        agents.set(deviceId, { ws, lastSeen: Date.now() });
        log('agent', `采集端已连接 deviceId=${deviceId}（${clientIp}），当前在线 ${agents.size} 台`);
        safeSend(ws, { type: 'hello-ok', role: 'agent', deviceId });
      } else if (msg.role === 'phone') {
        role = 'phone';
        phones.add(ws);
        log('phone', `手机端已连接（${clientIp}）`);
        safeSend(ws, { type: 'hello-ok', role: 'phone', agents: [...agents.keys()] });
      } else {
        safeSend(ws, { type: 'error', message: 'role 必须是 agent 或 phone' });
        return;
      }
      authed = true;
      clearTimeout(authTimer);
      return;
    }

    // ---------- 认证后的消息路由 ----------
    if (role === 'phone') {
      // 手机 -> 采集端
      if (msg.type === 'request') {
        const target = agents.get(msg.deviceId || 'default');
        if (!target) {
          safeSend(ws, { type: 'error', reqId: msg.reqId, message: '目标采集端不在线' });
          return;
        }
        safeSend(target.ws, { ...msg, _fromId: connId }); // 携带来源连接 ID，供响应回传
      } else if (msg.type === 'list-agents') {
        safeSend(ws, { type: 'agents', agents: [...agents.keys()] });
      }
    } else if (role === 'agent') {
      // 采集端 -> 手机
      if (msg.type === 'response' && msg._fromId) {
        const fromWs = connById.get(msg._fromId);
        delete msg._fromId;
        if (fromWs) safeSend(fromWs, msg);
      } else if (msg.type === 'push') {
        // 采集端主动推送（订阅模式），广播给所有在线手机
        for (const p of phones) safeSend(p, msg);
      }
    }
  });

  ws.on('close', () => {
    clearTimeout(authTimer);
    connById.delete(connId);
    if (role === 'agent' && deviceId) {
      const cur = agents.get(deviceId);
      if (cur && cur.ws === ws) {
        agents.delete(deviceId);
        log('agent', `采集端已断开 deviceId=${deviceId}，剩余 ${agents.size} 台`);
      }
    }
    if (role === 'phone') {
      phones.delete(ws);
      log('phone', '手机端已断开');
    }
  });

  ws.on('error', (e) => {
    log('error', `连接异常: ${e.message}`);
  });
});

// 心跳保活（应用层 JSON ping/pong，兼容 .NET / 安卓所有客户端）：
// 每 30 秒发送 {"type":"ping"}，60 秒未收到 pong 的连接视为死连接并断开
setInterval(() => {
  for (const [connId, ws] of connById.entries()) {
    if (ws.isAlive === false) {
      log('heartbeat', `连接 ${connId} 心跳超时，断开`);
      try { ws.terminate(); } catch (e) { /* 忽略 */ }
      continue;
    }
    ws.isAlive = false;
    try { safeSend(ws, { type: 'ping' }); } catch (e) { /* 忽略 */ }
  }
  // 同步清理采集端超时设备
  for (const [id, entry] of agents) {
    if (Date.now() - entry.lastSeen > 90000) {
      log('agent', `设备 ${id} 心跳超时，移除`);
      agents.delete(id);
      try { entry.ws.terminate(); } catch (e) { /* 忽略 */ }
    }
  }
}, 30000);

server.listen(config.port, () => {
  console.log('============================================');
  console.log('  PC Monitor Relay Server 已启动');
  console.log(`  监听端口 : ${config.port}`);
  console.log(`  在线采集端: 0 台`);
  console.log('--------------------------------------------');
  if (config.token === 'CHANGE_ME_TO_A_LONG_RANDOM_STRING') {
    console.warn('  [警告] 正在使用默认 TOKEN，请立即通过环境变量');
    console.warn('  或 config.json 设置强随机 TOKEN，否则任何人都能');
    console.warn('  连接你的服务器！');
  }
  console.log('============================================');
});
