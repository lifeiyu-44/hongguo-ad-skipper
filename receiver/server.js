/**
 * 红果广告跳过 · 反馈接收服务（局域网，零依赖）
 *
 * 用法：node server.js  或双击「启动反馈接收服务.bat」
 * 手机/平板在 App 设置里填入：http://<本机IP>:8765/report
 * 收到的反馈（文本+截图）保存到本目录的 reports/ 下。
 */
const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');

const PORT = process.env.PORT ? parseInt(process.env.PORT, 10) : 8765;
const OUT_DIR = path.join(__dirname, 'reports');
const INDEX_FILE = path.join(OUT_DIR, 'index.txt');
const LOG_FILE = path.join(__dirname, 'server.log');

/** 同时输出到控制台和日志文件（便于排查手机是否连到过本服务） */
function log(...args) {
  const line = `${new Date().toLocaleString()}  ${args.join(' ')}`;
  console.log(line);
  try {
    fs.appendFileSync(LOG_FILE, line + '\n', 'utf8');
  } catch (_) {
  }
}

function ensureDir(d) {
  fs.mkdirSync(d, { recursive: true });
}

/** 简单 multipart/form-data 解析（够用即可） */
function parseMultipart(buffer, boundary) {
  const parts = [];
  const boundaryBuf = Buffer.from('--' + boundary);
  let index = buffer.indexOf(boundaryBuf);
  while (index !== -1) {
    const next = buffer.indexOf(boundaryBuf, index + boundaryBuf.length);
    if (next === -1) break;
    const section = buffer.subarray(index + boundaryBuf.length, next);
    const headerEnd = section.indexOf('\r\n\r\n');
    if (headerEnd !== -1) {
      const header = section.subarray(0, headerEnd).toString('utf8');
      let body = section.subarray(headerEnd + 4);
      if (body.length >= 2 && body[body.length - 2] === 0x0d && body[body.length - 1] === 0x0a) {
        body = body.subarray(0, body.length - 2);
      }
      const nameMatch = /name="([^"]+)"/.exec(header);
      const fileMatch = /filename="([^"]*)"/.exec(header);
      parts.push({
        name: nameMatch ? nameMatch[1] : '',
        filename: fileMatch ? fileMatch[1] : null,
        data: body,
      });
    }
    index = next;
  }
  return parts;
}

function stamp() {
  const t = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return (
    `${t.getFullYear()}${p(t.getMonth() + 1)}${p(t.getDate())}_` +
    `${p(t.getHours())}${p(t.getMinutes())}${p(t.getSeconds())}`
  );
}

const server = http.createServer((req, res) => {
  log(`[请求] ${req.method} ${req.url} 来自 ${req.socket.remoteAddress}`);
  if (req.method === 'POST' && req.url.startsWith('/report')) {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      chunks.push(c);
      size += c.length;
      if (size > 30 * 1024 * 1024) req.destroy();
    });
    req.on('end', () => {
      try {
        const body = Buffer.concat(chunks);
        const ct = req.headers['content-type'] || '';
        const m = /boundary=(.+)$/.exec(ct);
        let reportText = '';
        let imageBuf = null;
        if (m) {
          const parts = parseMultipart(body, m[1].replace(/^"|"$/g, ''));
          for (const p of parts) {
            if (p.name === 'report') reportText = p.data.toString('utf8');
            else if (p.name === 'screenshot') imageBuf = p.data;
            else if (p.filename) imageBuf = p.data; // 兼容未命名的文件部件
          }
        } else {
          reportText = body.toString('utf8');
        }
        if (!reportText && !imageBuf) {
          res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ ok: false, error: 'empty' }));
          return;
        }
        const ts = stamp();
        const dir = path.join(OUT_DIR, ts);
        ensureDir(dir);
        if (reportText) fs.writeFileSync(path.join(dir, '反馈.txt'), reportText, 'utf8');
        if (imageBuf) fs.writeFileSync(path.join(dir, '截图.png'), imageBuf);
        fs.appendFileSync(
          INDEX_FILE,
          `${ts}  来自 ${req.socket.remoteAddress}  文本${reportText.length}字 ${imageBuf ? '含截图' : '无截图'}\n`,
          'utf8'
        );
        log(
          `[收到反馈] 文本 ${reportText.length} 字` +
            `${imageBuf ? `，截图 ${imageBuf.length} 字节` : '，无截图'}` +
            `（来自 ${req.socket.remoteAddress}）`
        );
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ ok: true, dir: ts }));
      } catch (e) {
        log('[错误] 处理失败:', e.message);
        res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('error');
      }
    });
  } else {
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end(
      '红果广告跳过 · 反馈接收服务运行中\n' +
        'POST /report 上传反馈\n' +
        '反馈保存到: ' + OUT_DIR + '\n'
    );
  }
});

ensureDir(OUT_DIR);
server.listen(PORT, '0.0.0.0', () => {
  log('====================================');
  log(' 红果广告跳过 · 反馈接收服务已启动');
  log('====================================');
  log('监听端口: ' + PORT + '（局域网可访问）');
  log('反馈保存到: ' + OUT_DIR);
  log('把下面任意一个地址填进 App 的「上报服务器地址」：');
  for (const [name, addrs] of Object.entries(os.networkInterfaces())) {
    for (const a of addrs || []) {
      if (a.family === 'IPv4' && !a.internal) {
        log(`   http://${a.address}:${PORT}/report   （${name}）`);
      }
    }
  }
  log('保持本窗口开着即可；关闭窗口则服务停止。');
});
