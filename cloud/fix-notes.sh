#!/bin/bash
# 修复 curl 内联中文导致的版本说明乱码（GBK 字节被按 UTF-8 存储，原文字节已丢失只能重写）
# 在服务器上以 root 运行：bash fix-notes.sh
cat > /tmp/fix-notes.js <<'EOF'
const{DatabaseSync}=require('node:sqlite');
const db=new DatabaseSync('/opt/hongguo/data/hongguo.db');
const notes={
 '1.36.1':'修复商城推广卡片广告漏检：零宽字符归一化+宽容正则+扫描预算对齐转储(900)+卡片上部起手+识别快照增强',
 '1.36.2':'运行日志同步 logcat 镜像（AdSkip 标签）便于 adb 远程排查；升级重装本身可触发无障碍服务重绑',
 '1.36.3':'品牌直播推广页翻不出去：上滑2次无效后点击页面「返回」退出；空屏兜底限额2→4次/5分钟；空屏翻页一律上部起手避开商品墙',
 '1.36.4':'无文字直播广告翻不出去：空屏上滑2次无效后改按系统返回键（每5分钟最多1次）；上滑起手0.50→0.60屏高加大行程越过商品墙',
 '1.36.5':'修复直播推广页「返回」点击失败——改按文字坐标点按（不依赖clickable）；新增重装后无障碍未重绑的自动重绑+失败提示',
 '1.36.6':'分类节奏优化：直播推广页/画面型兜底走2秒快节奏档，倒计时类保持3.5秒慢节奏避免顶撞锁定期',
 '1.36.7':'修复空屏兜底误翻——暂停/浏览时控件隐藏的0文字画面被误当无文字广告；新增触摸事件监听，用户触摸后12秒内禁止空屏翻页（真实广告为系统自动插入不受影响）',
};
for (const [version, note] of Object.entries(notes)) {
  db.prepare('UPDATE versions SET note=? WHERE version=?').run(note, version);
}
for (const r of db.prepare('SELECT version, note FROM versions ORDER BY id DESC').all()) {
  console.log(r.version + ' | ' + r.note);
}
EOF
node /tmp/fix-notes.js
