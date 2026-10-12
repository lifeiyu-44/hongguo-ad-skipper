/**
 * 红果广告跳过 · 云端服务（零依赖，Node >= 22.5，推荐 24）
 *
 * 职责：
 *   1. App 缺陷报告接收（POST /report，与 PC 局域网版格式完全兼容）+ 数据留存
 *   2. App 自动更新下发（GET /ping 版本探测、GET /update/apk 安装包下载）
 *   3. 用户邮箱注册/登录（可选邮箱验证码，走 SMTP）
 *   4. 后台管理页面（/admin）：报告浏览、APK 版本发布/回滚、用户管理
 *
 * 运行：
 *   node server.js
 * 环境变量：
 *   HONGGUO_DATA   数据目录（默认 <本文件目录>/data）
 *   HONGGUO_CONFIG 配置目录（默认 <本文件目录>/config，存放 smtp.json）
 *   PORT_HTTP      直连端口（默认 8765，0.0.0.0，供 App HTTP 访问/老设备兜底）
 *   PORT_PROXY     反代端口（默认 3100，仅 127.0.0.1，供 nginx HTTPS 转发）
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { DatabaseSync } = require('node:sqlite');

const APP_DIR = __dirname;
const DATA_DIR = process.env.HONGGUO_DATA
  ? path.resolve(process.env.HONGGUO_DATA)
  : path.join(APP_DIR, 'data');
const CONFIG_DIR = process.env.HONGGUO_CONFIG
  ? path.resolve(process.env.HONGGUO_CONFIG)
  : path.join(APP_DIR, 'config');

const PORT_HTTP = process.env.PORT_HTTP ? parseInt(process.env.PORT_HTTP, 10) : 8765;
const PORT_PROXY = process.env.PORT_PROXY ? parseInt(process.env.PORT_PROXY, 10) : 3100;

const DB_FILE = path.join(DATA_DIR, 'hongguo.db');
const LOG_FILE = path.join(DATA_DIR, 'server.log');
const REPORTS_DIR = path.join(DATA_DIR, 'reports');
const UPDATES_DIR = path.join(DATA_DIR, 'updates');
const APKS_DIR = path.join(UPDATES_DIR, 'apks');
const VERSION_FILE = path.join(UPDATES_DIR, 'version.json');
const ADMIN_INIT_FILE = path.join(DATA_DIR, 'admin-initial.txt');
const SMTP_FILE = path.join(CONFIG_DIR, 'smtp.json');

const SESSION_DAYS = 30;
const REPORT_MAX_BYTES = 30 * 1024 * 1024; // 与 PC 版一致
const JSON_MAX_BYTES = 64 * 1024;
const APK_MAX_BYTES = 220 * 1024 * 1024;

/* ---------------- 基础工具 ---------------- */

function log(...args) {
  const line = `${new Date().toLocaleString()}  ${args.join(' ')}`;
  console.log(line);
  try {
    fs.appendFileSync(LOG_FILE, line + '\n', 'utf8');
  } catch (_) {}
}

function ensureDir(d) {
  fs.mkdirSync(d, { recursive: true });
}

function readJsonSafe(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (_) {
    return null;
  }
}

function clientIp(req) {
  const fwd = req.headers['x-real-ip'] || req.headers['x-forwarded-for'];
  if (fwd) return String(fwd).split(',')[0].trim();
  return req.socket.remoteAddress || '?';
}

/** 版本号比较：a > b 返回 1，相等 0，小于 -1 */
function cmpVersion(a, b) {
  const pa = String(a || '').trim().replace(/^v/, '').split('.').map((x) => parseInt(x, 10) || 0);
  const pb = String(b || '').trim().replace(/^v/, '').split('.').map((x) => parseInt(x, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const x = pa[i] || 0;
    const y = pb[i] || 0;
    if (x !== y) return x > y ? 1 : -1;
  }
  return 0;
}

/* ---------------- 简单限流（内存，够用即可） ---------------- */

const rateBuckets = new Map(); // key -> { count, resetAt }

function rateAllow(key, limit, windowMs) {
  const now = Date.now();
  let b = rateBuckets.get(key);
  if (!b || now > b.resetAt) {
    b = { count: 0, resetAt: now + windowMs };
    rateBuckets.set(key, b);
  }
  b.count += 1;
  if (rateBuckets.size > 10000) {
    // 防内存膨胀：清掉过期桶
    for (const [k, v] of rateBuckets) {
      if (now > v.resetAt) rateBuckets.delete(k);
    }
  }
  return b.count <= limit;
}

/* ---------------- 数据库 ---------------- */

ensureDir(DATA_DIR);
ensureDir(REPORTS_DIR);
ensureDir(APKS_DIR);

const db = new DatabaseSync(DB_FILE);
db.exec(`
PRAGMA journal_mode = WAL;
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  email TEXT UNIQUE NOT NULL,
  pass_hash TEXT NOT NULL,
  pass_salt TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'user',
  disabled INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
  token TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS reports (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts_dir TEXT UNIQUE NOT NULL,
  ip TEXT,
  app_version TEXT,
  summary TEXT,
  text_len INTEGER NOT NULL DEFAULT 0,
  has_screenshot INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS versions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  version TEXT UNIQUE NOT NULL,
  note TEXT,
  apk_file TEXT NOT NULL,
  size INTEGER NOT NULL,
  sha256 TEXT,
  active INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS codes (
  email TEXT PRIMARY KEY,
  code_hash TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
`);

/** 机器用 API Key（X-Api-Key 请求头）：仅授权版本上传/发布，首次启动自动生成 */
function getApiKey() {
  const row = db.prepare("SELECT value FROM settings WHERE key = 'api_key'").get();
  if (row && row.value) return row.value;
  const key = 'hgk_' + crypto.randomBytes(24).toString('hex');
  db.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('api_key', ?)").run(key);
  log('[初始化] 已生成机器 API Key（可用 /api/admin/apikey 查看）');
  return key;
}

/** 版本接口的鉴权：admin 会话 或 X-Api-Key */
function canPublish(req) {
  const u = userForRequest(req);
  if (u && u.role === 'admin') return true;
  const key = String(req.headers['x-api-key'] || '');
  return !!key && key === getApiKey();
}

function hashPassword(password, salt) {
  return crypto.scryptSync(password, salt, 64).toString('hex');
}

function newSalt() {
  return crypto.randomBytes(16).toString('hex');
}

/* ---------------- 邮件验证码 ---------------- */

function smtpConfig() {
  const c = readJsonSafe(SMTP_FILE);
  if (!c || !c.host || !c.user || !c.pass) return null;
  return c;
}

function sendMail(to, subject, body) {
  const cfg = smtpConfig();
  if (!cfg) return Promise.reject(new Error('smtp_not_configured'));
  const smtp = require('./smtp');
  return smtp.sendMail(cfg, {
    from: cfg.from || cfg.user,
    to,
    subject,
    body,
  });
}

function issueCode(email) {
  const code = String(crypto.randomInt(100000, 1000000));
  const hash = crypto.createHash('sha256').update(code).digest('hex');
  db.prepare(
    `INSERT INTO codes (email, code_hash, expires_at, attempts) VALUES (?, ?, ?, 0)
     ON CONFLICT(email) DO UPDATE SET code_hash = excluded.code_hash,
       expires_at = excluded.expires_at, attempts = 0`
  ).run(email, hash, Date.now() + 10 * 60 * 1000);
  return code;
}

function verifyCode(email, code) {
  const row = db.prepare('SELECT * FROM codes WHERE email = ?').get(email);
  if (!row) return false;
  if (row.attempts >= 5 || Date.now() > row.expires_at) {
    db.prepare('DELETE FROM codes WHERE email = ?').run(email);
    return false;
  }
  const hash = crypto.createHash('sha256').update(String(code || '')).digest('hex');
  if (hash === row.code_hash) {
    db.prepare('DELETE FROM codes WHERE email = ?').run(email);
    return true;
  }
  db.prepare('UPDATE codes SET attempts = attempts + 1 WHERE email = ?').run(email);
  return false;
}

/* ---------------- 会话 ---------------- */

function createSession(userId) {
  const token = crypto.randomBytes(32).toString('hex');
  db.prepare('INSERT INTO sessions (token, user_id, expires_at) VALUES (?, ?, ?)').run(
    token,
    userId,
    Date.now() + SESSION_DAYS * 24 * 3600 * 1000
  );
  return token;
}

function userForRequest(req) {
  const auth = req.headers['authorization'] || '';
  const token =
    (auth.startsWith('Bearer ') ? auth.slice(7) : '') ||
    String(req.headers['x-auth-token'] || '');
  if (!token) return null;
  const row = db
    .prepare(
      `SELECT u.* FROM sessions s JOIN users u ON u.id = s.user_id
       WHERE s.token = ? AND s.expires_at > ?`
    )
    .get(token, Date.now());
  return row || null;
}

/* ---------------- 管理员初始账号 ---------------- */

function bootstrapAdmin() {
  const n = db.prepare("SELECT COUNT(*) AS c FROM users WHERE role = 'admin'").get().c;
  if (n > 0) return;
  const password = crypto.randomBytes(9).toString('base64url'); // 12 位随机
  const salt = newSalt();
  db.prepare(
    "INSERT INTO users (email, pass_hash, pass_salt, role, created_at) VALUES (?, ?, ?, 'admin', ?)"
  ).run('admin@local', hashPassword(password, salt), salt, new Date().toISOString());
  const text =
    '红果云端后台 · 初始管理员\n\n邮箱: admin@local\n密码: ' +
    password +
    '\n\n请尽快登录 /admin 并修改密码，然后删除本文件。\n';
  fs.writeFileSync(ADMIN_INIT_FILE, text, { encoding: 'utf8', mode: 0o600 });
  log('[初始化] 已创建初始管理员，凭据见:', ADMIN_INIT_FILE);
}

/* ---------------- 版本管理 ---------------- */

function activeVersion() {
  const row = db.prepare('SELECT * FROM versions WHERE active = 1 ORDER BY id DESC').get();
  return row || null;
}

function syncVersionFile() {
  const row = activeVersion();
  try {
    fs.writeFileSync(
      VERSION_FILE,
      JSON.stringify(
        { version: row ? row.version : '0.0', note: row ? row.note || '' : '' },
        null,
        2
      ),
      'utf8'
    );
  } catch (e) {
    log('[错误] 写 version.json 失败:', e.message);
  }
}

/* ---------------- HTTP 工具 ---------------- */

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

function readBody(req, maxBytes) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    let over = false;
    req.on('data', (c) => {
      if (over) return;
      size += c.length;
      if (size > maxBytes) {
        over = true;
        reject(new Error('body_too_large'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      if (!over) resolve(Buffer.concat(chunks));
    });
    req.on('error', (e) => {
      if (!over) reject(e);
    });
  });
}

async function readJson(req) {
  const buf = await readBody(req, JSON_MAX_BYTES);
  return JSON.parse(buf.toString('utf8'));
}

/** 简单 multipart/form-data 解析（沿用 PC 版，久经考验） */
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
    `${p(t.getHours())}${p(t.getMinutes())}${p(t.getSeconds())}` +
    `_r${crypto.randomBytes(2).toString('hex')}` // 云端多来源，加随机后缀防同秒冲突
  );
}

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

/* ---------------- 业务路由 ---------------- */

async function handle(req, res) {
  const url = new URL(req.url, 'http://x');
  const pathname = decodeURIComponent(url.pathname);
  const ip = clientIp(req);

  /* ---- App 兼容接口（无鉴权） ---- */

  // /ping：App 版本探测（与 PC 版字段兼容，不写日志防刷屏）
  if (req.method === 'GET' && pathname === '/ping') {
    const v = activeVersion();
    sendJson(res, 200, {
      ok: true,
      app: 'hongguo-ad-skipper',
      cloud: true,
      version: v ? v.version : '0.0',
    });
    return;
  }

  // /update/apk：下发当前激活版本的安装包
  if (req.method === 'GET' && (pathname === '/update/apk' || pathname === '/update/apk/')) {
    const v = activeVersion();
    if (!v || !fs.existsSync(v.apk_file)) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('no apk');
      return;
    }
    const stat = fs.statSync(v.apk_file);
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': stat.size,
    });
    const stream = fs.createReadStream(v.apk_file);
    stream.on('error', () => {
      log('[下载中断] 读取安装包失败');
      try { res.destroy(); } catch (_) {}
    });
    res.on('close', () => {
      try { stream.destroy(); } catch (_) {}
    });
    stream.pipe(res);
    log(`[更新] 已向 ${ip} 下发 v${v.version} 安装包（${stat.size} 字节）`);
    return;
  }

  // POST /report：App 缺陷/反馈上报（兼容 PC 版 multipart 格式）
  if (req.method === 'POST' && pathname === '/report') {
    if (!rateAllow('report:' + ip, 60, 3600 * 1000)) {
      sendJson(res, 429, { ok: false, error: 'too_many' });
      return;
    }
    let buf;
    try {
      buf = await readBody(req, REPORT_MAX_BYTES);
    } catch (e) {
      sendJson(res, 413, { ok: false, error: 'too_large' });
      return;
    }
    try {
      const ct = req.headers['content-type'] || '';
      let reportText = '';
      let imageBuf = null;
      const m = /boundary=(.+)$/.exec(ct);
      if (m) {
        const parts = parseMultipart(buf, m[1].replace(/^"|"$/g, ''));
        for (const p of parts) {
          if (p.name === 'report') reportText = p.data.toString('utf8');
          else if (p.name === 'screenshot') imageBuf = p.data;
          else if (p.filename) imageBuf = p.data;
        }
      } else {
        reportText = buf.toString('utf8');
      }
      if (!reportText && !imageBuf) {
        sendJson(res, 400, { ok: false, error: 'empty' });
        return;
      }
      // 从上报文本里猜 App 版本（App 反馈正文一般带版本号）
      let appVersion = null;
      const vm = /(?:版本|version)[：:\s]*v?(\d+\.\d+(?:\.\d+)?)/i.exec(reportText);
      if (vm) appVersion = vm[1];
      const ts = stamp();
      const dir = path.join(REPORTS_DIR, ts);
      ensureDir(dir);
      if (reportText) fs.writeFileSync(path.join(dir, '反馈.txt'), reportText, 'utf8');
      if (imageBuf) fs.writeFileSync(path.join(dir, '截图.png'), imageBuf);
      db.prepare(
        `INSERT INTO reports (ts_dir, ip, app_version, summary, text_len, has_screenshot, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?)`
      ).run(
        ts,
        ip,
        appVersion,
        reportText ? reportText.slice(0, 160).replace(/\s+/g, ' ') : '(仅截图)',
        reportText.length,
        imageBuf ? 1 : 0,
        new Date().toISOString()
      );
      log(
        `[收到反馈] 文本 ${reportText.length} 字` +
          (imageBuf ? `，截图 ${imageBuf.length} 字节` : '，无截图') +
          `（来自 ${ip}${appVersion ? '，v' + appVersion : ''}）`
      );
      sendJson(res, 200, { ok: true, dir: ts });
    } catch (e) {
      log('[错误] 处理反馈失败:', e.message);
      sendJson(res, 500, { ok: false, error: 'internal' });
    }
    return;
  }

  /* ---- 静态：后台页面 ---- */

  if (req.method === 'GET' && (pathname === '/admin' || pathname === '/admin/')) {
    try {
      const html = fs.readFileSync(path.join(APP_DIR, 'admin', 'index.html'));
      res.writeHead(200, {
        'Content-Type': 'text/html; charset=utf-8',
        'Cache-Control': 'no-store',
      });
      res.end(html);
    } catch (_) {
      sendJson(res, 500, { ok: false, error: 'admin_ui_missing' });
    }
    return;
  }

  /* ---- 认证接口 ---- */

  if (pathname.startsWith('/api/auth/') || pathname === '/api/config') {
    if (!rateAllow('auth:' + ip, 30, 60 * 1000)) {
      sendJson(res, 429, { ok: false, error: 'too_many' });
      return;
    }

    if (req.method === 'GET' && pathname === '/api/config') {
      sendJson(res, 200, {
        ok: true,
        codeRequired: !!smtpConfig(),
        service: 'hongguo-cloud',
      });
      return;
    }

    if (req.method === 'POST' && pathname === '/api/auth/code') {
      const cfg = smtpConfig();
      if (!cfg) {
        sendJson(res, 400, { ok: false, error: 'smtp_not_configured' });
        return;
      }
      let body;
      try { body = await readJson(req); } catch (_) { body = {}; }
      const email = String(body.email || '').trim().toLowerCase();
      if (!EMAIL_RE.test(email)) {
        sendJson(res, 400, { ok: false, error: 'bad_email' });
        return;
      }
      if (!rateAllow('code-mail:' + email, 1, 60 * 1000) || !rateAllow('code-ip:' + ip, 5, 3600 * 1000)) {
        sendJson(res, 429, { ok: false, error: 'too_many' });
        return;
      }
      try {
        const code = issueCode(email);
        await sendMail(email, '红果广告跳过 · 注册验证码', `您的验证码是：${code}\n\n10 分钟内有效。若非本人操作请忽略本邮件。`);
        log('[邮件] 已发送注册验证码至', email);
        sendJson(res, 200, { ok: true });
      } catch (e) {
        log('[邮件] 发送失败:', e.message);
        sendJson(res, 500, { ok: false, error: 'send_failed', detail: String(e.message || e).slice(0, 200) });
      }
      return;
    }

    if (req.method === 'POST' && pathname === '/api/auth/register') {
      let body;
      try { body = await readJson(req); } catch (_) { body = {}; }
      const email = String(body.email || '').trim().toLowerCase();
      const password = String(body.password || '');
      if (!EMAIL_RE.test(email) || password.length < 8) {
        sendJson(res, 400, { ok: false, error: 'bad_email_or_password' });
        return;
      }
      const exists = db.prepare('SELECT id FROM users WHERE email = ?').get(email);
      if (exists) {
        sendJson(res, 409, { ok: false, error: 'email_taken' });
        return;
      }
      if (smtpConfig()) {
        if (!verifyCode(email, String(body.code || ''))) {
          sendJson(res, 400, { ok: false, error: 'bad_code' });
          return;
        }
      }
      const salt = newSalt();
      const r = db
        .prepare(
          "INSERT INTO users (email, pass_hash, pass_salt, role, created_at) VALUES (?, ?, ?, 'user', ?)"
        )
        .run(email, hashPassword(password, salt), salt, new Date().toISOString());
      log('[用户] 新注册:', email);
      sendJson(res, 200, { ok: true, token: createSession(Number(r.lastInsertRowid)) });
      return;
    }

    if (req.method === 'POST' && pathname === '/api/auth/login') {
      let body;
      try { body = await readJson(req); } catch (_) { body = {}; }
      const email = String(body.email || '').trim().toLowerCase();
      const password = String(body.password || '');
      const u = db.prepare('SELECT * FROM users WHERE email = ?').get(email);
      // 统一返回 invalid，避免探测已注册邮箱
      if (!u || hashPassword(password, u.pass_salt) !== u.pass_hash) {
        sendJson(res, 401, { ok: false, error: 'invalid' });
        return;
      }
      if (u.disabled) {
        sendJson(res, 403, { ok: false, error: 'disabled' });
        return;
      }
      sendJson(res, 200, { ok: true, token: createSession(u.id), role: u.role });
      return;
    }

    if (req.method === 'POST' && pathname === '/api/auth/logout') {
      const auth = req.headers['authorization'] || '';
      const token = auth.startsWith('Bearer ') ? auth.slice(7) : String(req.headers['x-auth-token'] || '');
      if (token) db.prepare('DELETE FROM sessions WHERE token = ?').run(token);
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === 'GET' && pathname === '/api/auth/me') {
      const u = userForRequest(req);
      if (!u) { sendJson(res, 401, { ok: false, error: 'unauthorized' }); return; }
      sendJson(res, 200, { ok: true, email: u.email, role: u.role, created_at: u.created_at });
      return;
    }

    if (req.method === 'POST' && pathname === '/api/auth/password') {
      const u = userForRequest(req);
      if (!u) { sendJson(res, 401, { ok: false, error: 'unauthorized' }); return; }
      let body;
      try { body = await readJson(req); } catch (_) { body = {}; }
      const oldPw = String(body.old || '');
      const newPw = String(body['new'] || '');
      if (hashPassword(oldPw, u.pass_salt) !== u.pass_hash || newPw.length < 8) {
        sendJson(res, 400, { ok: false, error: 'bad_old_or_new' });
        return;
      }
      const salt = newSalt();
      db.prepare('UPDATE users SET pass_hash = ?, pass_salt = ? WHERE id = ?').run(
        hashPassword(newPw, salt), salt, u.id
      );
      log('[用户] 密码已修改:', u.email);
      sendJson(res, 200, { ok: true });
      return;
    }
  }

  /* ---- 管理接口（admin 角色） ---- */

  if (pathname.startsWith('/api/admin/')) {
    // 版本上传/发布接口：admin 会话 或 X-Api-Key（机器/AI 用）均可
    const isVersionRoute =
      pathname === '/api/admin/versions' || pathname === '/api/admin/versions/activate';
    const versionAuthed = isVersionRoute && canPublish(req);
    const u = versionAuthed ? null : userForRequest(req);
    if (!versionAuthed) {
      if (!u) { sendJson(res, 401, { ok: false, error: 'unauthorized' }); return; }
      if (u.role !== 'admin') { sendJson(res, 403, { ok: false, error: 'forbidden' }); return; }
    }

    if (req.method === 'GET' && pathname === '/api/admin/stats') {
      const users = db.prepare('SELECT COUNT(*) c FROM users').get().c;
      const reports = db.prepare('SELECT COUNT(*) c FROM reports').get().c;
      const v = activeVersion();
      const today = new Date().toISOString().slice(0, 10);
      const todayReports = db
        .prepare("SELECT COUNT(*) c FROM reports WHERE created_at LIKE ?")
        .get(today + '%').c;
      sendJson(res, 200, {
        ok: true,
        users,
        reports,
        todayReports,
        activeVersion: v ? { version: v.version, note: v.note, size: v.size, created_at: v.created_at } : null,
      });
      return;
    }

    if (req.method === 'GET' && pathname === '/api/admin/reports') {
      const page = Math.max(1, parseInt(url.searchParams.get('page') || '1', 10) || 1);
      const size = Math.min(50, Math.max(5, parseInt(url.searchParams.get('size') || '20', 10) || 20));
      const q = (url.searchParams.get('q') || '').trim();
      const total = q
        ? db.prepare('SELECT COUNT(*) c FROM reports WHERE summary LIKE ?').get('%' + q + '%').c
        : db.prepare('SELECT COUNT(*) c FROM reports').get().c;
      const rows = q
        ? db.prepare('SELECT * FROM reports WHERE summary LIKE ? ORDER BY id DESC LIMIT ? OFFSET ?').all('%' + q + '%', size, (page - 1) * size)
        : db.prepare('SELECT * FROM reports ORDER BY id DESC LIMIT ? OFFSET ?').all(size, (page - 1) * size);
      sendJson(res, 200, { ok: true, total, page, size, items: rows });
      return;
    }

    if (req.method === 'GET' && pathname === '/api/admin/reports/detail') {
      const id = parseInt(url.searchParams.get('id') || '0', 10) || 0;
      const row = db.prepare('SELECT * FROM reports WHERE id = ?').get(id);
      if (!row) { sendJson(res, 404, { ok: false, error: 'not_found' }); return; }
      let text = '';
      try {
        text = fs.readFileSync(path.join(REPORTS_DIR, row.ts_dir, '反馈.txt'), 'utf8');
      } catch (_) {}
      sendJson(res, 200, { ok: true, report: row, text });
      return;
    }

    if (req.method === 'GET' && pathname === '/api/admin/reports/file') {
      const id = parseInt(url.searchParams.get('id') || '0', 10) || 0;
      const kind = url.searchParams.get('kind') === 'screenshot' ? 'screenshot' : 'text';
      const row = db.prepare('SELECT * FROM reports WHERE id = ?').get(id);
      if (!row) { res.writeHead(404); res.end(); return; }
      const name = kind === 'screenshot' ? '截图.png' : '反馈.txt';
      const file = path.join(REPORTS_DIR, row.ts_dir, name);
      if (!fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
      const stat = fs.statSync(file);
      res.writeHead(200, {
        'Content-Type': kind === 'screenshot' ? 'image/png' : 'text/plain; charset=utf-8',
        'Content-Length': stat.size,
      });
      fs.createReadStream(file).pipe(res);
      return;
    }

    if (req.method === 'DELETE' && pathname === '/api/admin/reports') {
      const id = parseInt(url.searchParams.get('id') || '0', 10) || 0;
      const row = db.prepare('SELECT * FROM reports WHERE id = ?').get(id);
      if (!row) { sendJson(res, 404, { ok: false, error: 'not_found' }); return; }
      db.prepare('DELETE FROM reports WHERE id = ?').run(id);
      try {
        fs.rmSync(path.join(REPORTS_DIR, row.ts_dir), { recursive: true, force: true });
      } catch (_) {}
      log('[管理] 删除报告', row.ts_dir);
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === 'GET' && pathname === '/api/admin/versions') {
      sendJson(res, 200, { ok: true, items: db.prepare('SELECT * FROM versions ORDER BY id DESC').all() });
      return;
    }

    if (req.method === 'POST' && pathname === '/api/admin/versions') {
      // multipart: apk 文件 + version/note 字段
      const ct = req.headers['content-type'] || '';
      const m = /boundary=(.+)$/.exec(ct);
      if (!m) { sendJson(res, 400, { ok: false, error: 'multipart_required' }); return; }
      let buf;
      try {
        buf = await readBody(req, APK_MAX_BYTES);
      } catch (_) {
        sendJson(res, 413, { ok: false, error: 'too_large' });
        return;
      }
      const parts = parseMultipart(buf, m[1].replace(/^"|"$/g, ''));
      let apk = null;
      let version = '';
      let note = '';
      for (const p of parts) {
        if (p.filename && /\.apk$/i.test(p.filename)) apk = p;
        else if (p.name === 'version') version = p.data.toString('utf8').trim();
        else if (p.name === 'note') note = p.data.toString('utf8').trim();
      }
      if (!apk || !/^v?\d+\.\d+(\.\d+)?$/.test(version)) {
        sendJson(res, 400, { ok: false, error: 'need_apk_and_version' });
        return;
      }
      version = version.replace(/^v/, '');
      const dup = db.prepare('SELECT id FROM versions WHERE version = ?').get(version);
      if (dup) { sendJson(res, 409, { ok: false, error: 'version_exists' }); return; }
      const apkFile = path.join(APKS_DIR, `HongguoAdSkipper-v${version}.apk`);
      fs.writeFileSync(apkFile, apk.data);
      const r = db
        .prepare('INSERT INTO versions (version, note, apk_file, size, sha256, created_at) VALUES (?, ?, ?, ?, ?, ?)')
        .run(
          version,
          note,
          apkFile,
          apk.data.length,
          crypto.createHash('sha256').update(apk.data).digest('hex'),
          new Date().toISOString()
        );
      log(`[版本] 已上传 v${version}（${apk.data.length} 字节）`);
      sendJson(res, 200, { ok: true, id: Number(r.lastInsertRowid) });
      return;
    }

    if (req.method === 'POST' && pathname === '/api/admin/versions/activate') {
      let body;
      try { body = await readJson(req); } catch (_) { body = {}; }
      const id = parseInt(body.id || '0', 10) || 0;
      const row = db.prepare('SELECT * FROM versions WHERE id = ?').get(id);
      if (!row || !fs.existsSync(row.apk_file)) {
        sendJson(res, 404, { ok: false, error: 'not_found' });
        return;
      }
      db.prepare('UPDATE versions SET active = 0');
      db.prepare('UPDATE versions SET active = 1 WHERE id = ?').run(id);
      syncVersionFile();
      log(`[版本] v${row.version} 已设为当前下发版本`);
      sendJson(res, 200, { ok: true, version: row.version });
      return;
    }

    if (req.method === 'GET' && pathname === '/api/admin/apikey') {
      sendJson(res, 200, { ok: true, key: getApiKey() });
      return;
    }

    if (req.method === 'POST' && pathname === '/api/admin/apikey/rotate') {
      const key = 'hgk_' + crypto.randomBytes(24).toString('hex');
      db.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('api_key', ?)").run(key);
      log('[管理] API Key 已轮换');
      sendJson(res, 200, { ok: true, key });
      return;
    }

    if (req.method === 'GET' && pathname === '/api/admin/users') {
      sendJson(res, 200, {
        ok: true,
        items: db.prepare('SELECT id, email, role, disabled, created_at FROM users ORDER BY id').all(),
      });
      return;
    }

    if (req.method === 'POST' && pathname === '/api/admin/users/update') {
      let body;
      try { body = await readJson(req); } catch (_) { body = {}; }
      const id = parseInt(body.id || '0', 10) || 0;
      const target = db.prepare('SELECT * FROM users WHERE id = ?').get(id);
      if (!target) { sendJson(res, 404, { ok: false, error: 'not_found' }); return; }
      if (target.id === u.id && (body.role === 'user' || body.disabled === 1)) {
        sendJson(res, 400, { ok: false, error: 'cannot_demote_self' });
        return;
      }
      if (body.role === 'admin' || body.role === 'user') {
        db.prepare('UPDATE users SET role = ? WHERE id = ?').run(body.role, id);
      }
      if (body.disabled === 0 || body.disabled === 1) {
        db.prepare('UPDATE users SET disabled = ? WHERE id = ?').run(body.disabled, id);
      }
      log('[管理] 更新用户', target.email, JSON.stringify(body));
      sendJson(res, 200, { ok: true });
      return;
    }
  }

  /* ---- 根路径说明页 ---- */

  if (req.method === 'GET' && (pathname === '/' || pathname === '')) {
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end(
      '红果广告跳过 · 云端服务运行中\n' +
        'POST /report      App 缺陷/反馈上报\n' +
        'GET  /ping        版本探测\n' +
        'GET  /update/apk  安装包下载\n' +
        '/admin            后台管理页面\n'
    );
    return;
  }

  sendJson(res, 404, { ok: false, error: 'not_found' });
}

/* ---------------- 启动 ---------------- */

bootstrapAdmin();
getApiKey();
syncVersionFile();

// 全局兜底：保持在线优先
process.on('uncaughtException', (e) => {
  log('[崩溃拦截] 未捕获异常:', e && e.message ? e.message : String(e));
});
process.on('unhandledRejection', (e) => {
  log('[崩溃拦截] 未处理的Promise拒绝:', e && e.message ? e.message : String(e));
});

const handler = (req, res) => {
  handle(req, res).catch((e) => {
    log('[错误] 路由异常:', e && e.message ? e.message : String(e));
    try {
      sendJson(res, 500, { ok: false, error: 'internal' });
    } catch (_) {}
  });
};

// 直连端口（App HTTP / 老设备兜底）
http.createServer(handler).listen(PORT_HTTP, '0.0.0.0', () => {
  log(`[启动] HTTP 直连端口 ${PORT_HTTP}（0.0.0.0）`);
});
// 反代端口（仅本机，供 nginx HTTPS 转发）
http.createServer(handler).listen(PORT_PROXY, '127.0.0.1', () => {
  log(`[启动] 反代端口 ${PORT_PROXY}（127.0.0.1）`);
});
log('====================================');
log(' 红果广告跳过 · 云端服务已启动');
log(`数据目录: ${DATA_DIR}`);
log('====================================');
