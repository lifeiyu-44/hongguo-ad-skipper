// 看门狗检查（由 watchdog.vbs 以隐藏方式调用，本身零窗口）：
// 探测本地 8765 服务是否存活；掉线则静默重启（windowsHide，无任何窗口）
const http = require('http');
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');

function logLine(msg) {
  try {
    fs.appendFileSync(
      path.join(__dirname, 'server.log'),
      new Date().toLocaleString() + '  ' + msg + '\n'
    );
  } catch (_) {}
}

function restart() {
  const child = spawn(process.execPath, [path.join(__dirname, 'server.js')], {
    detached: true,
    stdio: 'ignore',
    windowsHide: true,
    cwd: __dirname,
  });
  child.unref();
  logLine('[watchdog] service was down, restarted');
  process.exit(0);
}

const req = http.get(
  { host: '127.0.0.1', port: 8765, path: '/ping', timeout: 3000 },
  (res) => {
    res.resume();
    process.exit(0);
  }
);
req.on('timeout', () => {
  req.destroy();
  restart();
});
req.on('error', () => restart());
