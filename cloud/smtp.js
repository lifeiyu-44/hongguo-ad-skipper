/**
 * 零依赖 SMTP 发信客户端（支持 465 隐式 TLS / 587 STARTTLS，AUTH LOGIN）
 * 适用于 QQ 邮箱、163 邮箱等个人 SMTP 授权码场景。
 *
 * 协议要点：
 *  - SMTP 响应可能多行（"250-xxx" 为续行，末行是「3位数字+空格+文本」），
 *    且 TCP 会任意分片 —— 必须按行缓冲，遇到末行才认为一段响应结束；
 *  - 发信成功的权威信号是 DATA 结束后的 "250 ...queued"，拿到即可结束。
 */
const net = require('net');
const tls = require('tls');

/**
 * sendMail(cfg, mail) -> Promise<string>
 * cfg : { host, port, user, pass, from?, starttls? }
 * mail: { to, subject, body, fromName? }
 */
function sendMail(cfg, mail) {
  return new Promise((resolve, reject) => {
    const host = cfg.host;
    const port = parseInt(cfg.port || 465, 10) || 465;
    // 隐式 TLS：465 端口默认走（QQ/163 常用）；cfg.tls 可显式覆盖（测试/特殊服务器用）
    const implicitTls = cfg.tls === true || (cfg.tls !== false && port === 465);
    const allowStartTls = !implicitTls && cfg.starttls !== false;
    // servername 传 IP 会触发 Node 弃用警告且被忽略，IP 场景下不传
    const sniName = /^\d{1,3}(\.\d{1,3}){3}$/.test(host) ? undefined : host;

    let socket = null;
    let finished = false;
    let stage = 'greeting';
    let lineBuf = '';
    let respLines = [];

    const b64 = (s) => Buffer.from(String(s == null ? '' : s), 'utf8').toString('base64');

    const done = (err, info) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      try {
        socket && socket.end();
      } catch (_) {}
      if (err) reject(err instanceof Error ? err : new Error(String(err)));
      else resolve(info || 'ok');
    };

    const timer = setTimeout(() => done(new Error('smtp_timeout')), 25000);

    const send = (line) => {
      if (finished) return;
      try {
        socket.write(line + '\r\n');
      } catch (e) {
        done(new Error('smtp_socket: ' + e.message));
      }
    };

    function buildMessage() {
      const fromAddr = cfg.from || cfg.user;
      const head =
        `From: =?UTF-8?B?${b64(mail.fromName || '红果广告跳过')}?= <${fromAddr}>\r\n` +
        `To: <${mail.to}>\r\n` +
        `Subject: =?UTF-8?B?${b64(mail.subject || '')}?=\r\n` +
        `Date: ${new Date().toUTCString()}\r\n` +
        `MIME-Version: 1.0\r\n` +
        `Content-Type: text/plain; charset=UTF-8\r\n` +
        `Content-Transfer-Encoding: base64\r\n` +
        `\r\n`;
      // base64 正文每 76 字符折行；base64 字母表不含行首 '.'，无需 dot-stuffing
      const bodyB64 = b64(mail.body || '').replace(/(.{76})/g, '$1\r\n');
      return head + bodyB64;
    }

    function onResponse(code, text) {
      if (finished) return;
      switch (stage) {
        case 'greeting':
          if (code !== 220) return done(new Error('smtp_bad_greeting_' + code));
          stage = 'ehlo';
          return send('EHLO hongguo.local');
        case 'ehlo':
          if (code !== 250) return done(new Error('smtp_bad_ehlo_' + code));
          if (implicitTls || !allowStartTls) {
            stage = 'auth';
            return send('AUTH LOGIN');
          }
          stage = 'starttls';
          return send('STARTTLS');
        case 'starttls':
          if (code !== 220) return done(new Error('smtp_starttls_refused_' + code));
          return upgradeToTls();
        case 'ehlo2':
          if (code !== 250) return done(new Error('smtp_bad_ehlo_' + code));
          stage = 'auth';
          return send('AUTH LOGIN');
        case 'auth': // 服务器请求用户名（334 后 base64 提示）
          if (code !== 334) return done(new Error('smtp_auth_rejected_' + code));
          stage = 'authUser';
          return send(b64(cfg.user));
        case 'authUser': // 服务器请求密码
          if (code !== 334) return done(new Error('smtp_auth_rejected_' + code));
          stage = 'authPass';
          return send(b64(cfg.pass));
        case 'authPass':
          if (code !== 235) return done(new Error('smtp_auth_failed_' + code));
          stage = 'mailFrom';
          return send(`MAIL FROM:<${cfg.from || cfg.user}>`);
        case 'mailFrom':
          if (code !== 250) return done(new Error('smtp_mailfrom_failed_' + code));
          stage = 'rcptTo';
          return send(`RCPT TO:<${mail.to}>`);
        case 'rcptTo':
          if (code !== 250 && code !== 251) return done(new Error('smtp_rcpt_failed_' + code));
          stage = 'data';
          return send('DATA');
        case 'data':
          if (code !== 354) return done(new Error('smtp_data_refused_' + code));
          stage = 'endOfData';
          try {
            socket.write(buildMessage() + '\r\n.\r\n');
          } catch (e) {
            return done(new Error('smtp_socket: ' + e.message));
          }
          return;
        case 'endOfData':
          // 权威成功信号：服务器已接收并排队
          if (code !== 250) return done(new Error('smtp_send_failed_' + code));
          send('QUIT');
          stage = 'done';
          return done(null, text);
        default:
          return done(new Error('smtp_unexpected_state_' + stage));
      }
    }

    function onData(chunk) {
      if (finished) return;
      lineBuf += chunk.toString('utf8');
      let idx;
      while ((idx = lineBuf.indexOf('\n')) !== -1) {
        const line = lineBuf.slice(0, idx).replace(/\r$/, '');
        lineBuf = lineBuf.slice(idx + 1);
        respLines.push(line);
        // 末行形如 "250 OK" / 裸 "250"；"250-" 是续行，继续等
        if (/^\d{3}(\s|$)/.test(line)) {
          const code = parseInt(line.slice(0, 3), 10);
          const text = respLines.join(' | ');
          respLines = [];
          onResponse(code, text);
          if (finished) return;
        }
      }
    }

    function attach(sock, isTls) {
      socket = sock;
      sock.setTimeout(25000);
      sock.on('data', onData);
      sock.on('timeout', () => done(new Error('smtp_timeout')));
      sock.on('error', (e) => done(new Error((isTls ? 'smtp_tls: ' : 'smtp_socket: ') + e.message)));
      sock.on('close', () => {
        if (!finished) done(new Error('smtp_closed_early'));
      });
    }

    function upgradeToTls() {
      const plain = socket;
      try {
        plain.removeAllListeners('data');
        plain.removeAllListeners('timeout');
        plain.removeAllListeners('close');
      } catch (_) {}
      const sec = tls.connect(
        {
          socket: plain,
          servername: sniName,
          // 自建工具：不为证书链/中间盒问题阻断发信（内容仍全程加密）
          rejectUnauthorized: false,
        },
        () => {
          attach(sec, true);
          stage = 'ehlo2';
          send('EHLO hongguo.local');
        }
      );
      sec.on('error', (e) => done(new Error('smtp_tls: ' + e.message)));
    }

    if (implicitTls) {
      const sec = tls.connect(
        { host, port, servername: sniName, rejectUnauthorized: false },
        () => attach(sec, true)
      );
      sec.on('error', (e) => done(new Error('smtp_tls: ' + e.message)));
    } else {
      const plain = net.connect({ host, port }, () => attach(plain, false));
      plain.on('error', (e) => done(new Error('smtp_socket: ' + e.message)));
    }
  });
}

module.exports = { sendMail };
