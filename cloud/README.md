# 云端服务部署说明

零依赖 Node 服务（Node >= 22.5，服务器为 24.x），与 PC 局域网版 `receiver/server.js` 接口兼容。

## 目录约定（服务器）

```
/opt/hongguo/
  app/       server.js smtp.js smtp-setup.js admin/index.html
  data/      hongguo.db reports/ updates/ backups/ server.log admin-initial.txt
  config/    smtp.json  （由 smtp-setup.js 生成，600 权限）
```

systemd 单元 `hongguo-backend.service` 设置 `HONGGUO_DATA=/opt/hongguo/data`、
`HONGGUO_CONFIG=/opt/hongguo/config`。

## 端口

- `8765`（0.0.0.0）HTTP 直连：App 上报/更新，老设备兜底（需阿里云安全组放行）
- `3100`（127.0.0.1）反代后端：nginx `8443` HTTPS 复用 IP 证书转发（需安全组放行 8443）

App 默认地址：`https://123.57.163.149:8443/report`（v1.35 起）

## 接口一览

| 接口 | 说明 |
|---|---|
| POST /report | App 缺陷上报（multipart: report + screenshot），60 次/时/IP |
| GET /ping | 版本探测，返回 `{version}` |
| GET /update/apk | 下发当前激活版本 APK |
| GET /admin | 后台管理页面 |
| /api/auth/* | 注册（可选邮箱验证码）/登录/登出/改密码 |
| /api/admin/* | 报告、版本、用户管理（admin 角色） |

## 运维

```bash
systemctl status hongguo-backend     # 服务状态
journalctl -u hongguo-backend -f     # 实时日志（也写 data/server.log）
node /opt/hongguo/app/smtp-setup.js  # 配置/更换 SMTP 授权码（交互式，不走聊天）
```

- 初始管理员：首次启动生成 `data/admin-initial.txt`（邮箱 admin@local + 随机密码），改密后删除
- 备份：`/etc/cron.d/hongguo-backup` 每日 4:00 停服打包 data（db+reports）到 `data/backups/`，保留 14 份
- 证书：复用 `/etc/letsencrypt/live/ip-123.57.163.149/`（6 天短证书，certbot 每 12h 自动续期，
  `renewal-hooks/deploy/reload-nginx.sh` 续期后重载 nginx）
