#!/bin/bash
# 红果云端服务一键部署（在服务器上以 root 运行）
# 前置：/tmp/hongguo-upload/ 已上传 server.js smtp.js smtp-setup.js index.html HongguoAdSkipper.apk
set -e

echo "== 1/7 目录与文件 =="
mkdir -p /opt/hongguo/app/admin /opt/hongguo/data /opt/hongguo/config /opt/hongguo/data/backups
cp /tmp/hongguo-upload/server.js      /opt/hongguo/app/
cp /tmp/hongguo-upload/smtp.js         /opt/hongguo/app/
cp /tmp/hongguo-upload/smtp-setup.js   /opt/hongguo/app/
cp /tmp/hongguo-upload/index.html      /opt/hongguo/app/admin/
NODE=$(command -v node)
echo "node: $NODE"

echo "== 2/7 systemd 服务 =="
cat > /etc/systemd/system/hongguo-backend.service <<EOF
[Unit]
Description=Hongguo cloud backend (reports + auth + updates)
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/hongguo/app
Environment=HONGGUO_DATA=/opt/hongguo/data
Environment=HONGGUO_CONFIG=/opt/hongguo/config
ExecStart=$NODE /opt/hongguo/app/server.js
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now hongguo-backend
sleep 1
echo "-- 验证本机端口 --"
curl -s http://127.0.0.1:3100/ping && echo
curl -s http://127.0.0.1:8765/ping && echo

echo "== 3/7 nginx 8443（新增独立配置，不动现有文件） =="
cat > /etc/nginx/conf.d/hongguo.conf <<'EOF'
server {
    listen 8443 ssl;
    listen [::]:8443 ssl;
    server_name 123.57.163.149;

    ssl_certificate     /etc/letsencrypt/live/ip-123.57.163.149/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/ip-123.57.163.149/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;

    client_max_body_size 220m;

    location / {
        proxy_pass http://127.0.0.1:3100;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }
}
EOF
nginx -t
systemctl reload nginx

echo "== 4/7 证书续期后自动重载 nginx 的钩子 =="
mkdir -p /etc/letsencrypt/renewal-hooks/deploy
cat > /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh <<'EOF'
#!/bin/bash
systemctl reload nginx
EOF
chmod +x /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh

echo "== 5/7 每日备份 cron =="
cat > /etc/cron.d/hongguo-backup <<'EOF'
# 每日 4:00 备份红果云端数据（停服1-2秒保证 SQLite 一致性），保留14份
0 4 * * * root systemctl stop hongguo-backend && tar czf /opt/hongguo/data/backups/hongguo-$(date +\%Y\%m\%d).tar.gz -C /opt/hongguo/data honggu.db reports config 2>/dev/null; systemctl start hongguo-backend; ls -1t /opt/hongguo/data/backups/hongguo-*.tar.gz 2>/dev/null | tail -n +15 | xargs -r rm -f
EOF
chmod 644 /etc/cron.d/hongguo-backup

echo "== 6/7 发布初始版本 v1.34（走正式 API 链路） =="
PW=$(grep '^密码: ' /opt/hongguo/data/admin-initial.txt | awk '{print $2}')
TOK=$(curl -s -X POST http://127.0.0.1:3100/api/auth/login -H 'Content-Type: application/json' -d "{\"email\":\"admin@local\",\"password\":\"$PW\"}" | sed 's/.*"token":"\([^"]*\)".*/\1/')
if [ -z "$TOK" ] || [ ${#TOK} -lt 32 ]; then echo "登录失败"; exit 1; fi
curl -s -X POST http://127.0.0.1:3100/api/admin/versions -H "Authorization: Bearer $TOK" \
  -F "version=1.34" -F "note=初始版本(与局域网v1.34一致)" \
  -F "apk=@/tmp/hongguo-upload/HongguoAdSkipper.apk;type=application/vnd.android.package-archive" && echo
VID=$(curl -s http://127.0.0.1:3100/api/admin/versions -H "Authorization: Bearer $TOK" | sed 's/.*"id":\([0-9]*\).*/\1/')
curl -s -X POST http://127.0.0.1:3100/api/admin/versions/activate -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d "{\"id\":$VID}" && echo

echo "== 7/7 最终验证 =="
echo "-- HTTPS(服务器本机视角) --"
curl -s https://123.57.163.149:8443/ping && echo
echo "-- 版本探测 --"
curl -s https://123.57.163.149:8443/ping
echo
echo "== 部署完成 =="
echo "初始管理员信息:"
cat /opt/hongguo/data/admin-initial.txt
