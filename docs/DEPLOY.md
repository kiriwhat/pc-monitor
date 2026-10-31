# 中继服务器部署教程（云服务器）

公网远程模式下需要一个**有公网 IP 的服务器**做中继：手机 → 服务器 → 电脑。服务器只做转发，不存储任何监控数据。

## 1. 环境准备

- 任意云厂商的 Linux 服务器（1 核 512MB 即可），系统推荐 Ubuntu 22.04 / Debian 12 / CentOS Stream 9
- 安装 Node.js 18+（推荐 20 LTS）：
  ```bash
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y nodejs      # Ubuntu/Debian
  # 或
  yum install -y nodejs           # CentOS（版本过旧时请用 nvm 安装 20+）
  ```

## 2. 上传代码

把本仓库的 `server/` 目录上传到服务器，例如 `/opt/pc-monitor-server/`：

```bash
scp -r server/ root@你的服务器:/opt/pc-monitor-server/
```

## 3. 安装依赖

```bash
cd /opt/pc-monitor-server
npm install        # 只安装 ws 一个依赖
```

## 4. 配置 Token 与端口

服务器支持两种配置方式（任选其一）：

**方式 A：环境变量（推荐）**
```bash
export TOKEN='换成一段足够长的随机口令，例如 openssl rand -hex 32'
export PORT=8787
```

**方式 B：config.json**（与 server.js 同目录）
```json
{
  "port": 8787,
  "token": "换成一段足够长的随机口令"
}
```

> 电脑采集端、安卓手机端必须使用**同一个 Token**。

## 5. 启动与开机自启

**方式 A：直接启动（测试用）**
```bash
TOKEN='你的口令' node server.js
```

**方式 B：pm2 守护（推荐生产用）**
```bash
npm install -g pm2
TOKEN='你的口令' pm2 start server.js --name pc-monitor
pm2 save
pm2 startup    # 按提示执行输出的命令，实现开机自启
```

## 6. 防火墙放行端口

以 Ubuntu UFW 为例：
```bash
ufw allow 8787/tcp
```
云厂商安全组（阿里云/腾讯云/AWS 等控制台）也需放行 **8787/TCP** 入方向。

## 7. 验证服务器

启动后用任意能连公网的机器执行仓库内脚本：

```bash
node server/test-phone.js 你的口令
```

看到「端到端链路测试全部通过！」即正常（此脚本会模拟手机连接，未连接采集端时 metrics 会显示无采集端在线，但注册与握手链路是通的）。

## 8.（可选）WSS 加密 —— 强烈建议生产使用

明文 ws 在公网传输 Token 有风险。用 Nginx 反代加 TLS：

```nginx
server {
    listen 8788 ssl;
    server_name 你的域名;
    ssl_certificate     /etc/nginx/ssl/fullchain.pem;
    ssl_certificate_key /etc/nginx/ssl/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8787;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
    }
}
```

之后手机端填 `wss://你的域名:8788`，采集端 config.json 的 `serverUrl` 同样改为 `wss://你的域名:8788`。

证书可免费申请：`apt-get install certbot python3-certbot-nginx` 后执行 `certbot --nginx -d 你的域名`。

## 9. 日常运维

- 查看日志：`pm2 logs pc-monitor`
- 修改 Token：改环境变量后 `pm2 restart pc-monitor`（三端同步修改，旧 Token 立即失效）
- 服务器不存储任何数据，重启无影响
