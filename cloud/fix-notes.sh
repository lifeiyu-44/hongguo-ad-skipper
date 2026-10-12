#!/bin/bash
# 修复 curl 内联中文导致的版本说明乱码（GBK 字节被按 UTF-8 存储）
cat > /tmp/fix-notes.js <<'EOF'
const{DatabaseSync}=require('node:sqlite');
const db=new DatabaseSync('/opt/hongguo/data/hongguo.db');
db.prepare('UPDATE versions SET note=? WHERE version=?').run('一键截图上报/账号注册/重置云端地址/后台截图修复','1.35.2');
db.prepare('UPDATE versions SET note=? WHERE version=?').run('修复：旧局域网地址残留导致外网无法上报/更新','1.35.1');
db.prepare('UPDATE versions SET note=? WHERE version=?').run('在线更新提醒：服务运行期间每6小时检查，新版发通知栏，点击即更新','1.35.3');
for (const r of db.prepare('SELECT version, note FROM versions ORDER BY id DESC').all()) {
  console.log(r.version + ' | ' + r.note);
}
EOF
node /tmp/fix-notes.js
