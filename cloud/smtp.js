/**
 * 零依赖 SMTP 发信客户端（支持 465 隐式 TLS / 587 STARTTLS + AUTH LOGIN）
 * 适用于 QQ 邮箱、163 邮箱等个人 SMTP 授权码场景。
 */
const net = require('net');
const tls = require('tls');

/**
 * sendMail(cfg, mail)
 * cfg: { host, port, user, pass, from? }   mail: { from, to, subject, body }
 * 返回 Promise，成功 resolve 服务器结束响应文本。
 */
function sendMail(cfg, mail) {
  return new Promise((resolve, reject) => {
    const socket = cfg.port === 465
      ? tls.connect({ host: cfg.host, port: cfg.port, rejectUnauthorized: false }, onConnect)
      : net.connect({ host: cfg.host, port: cfg.port }, () => startTlsIfWanted());

    let buffer = '';
    let stage = cfg.port === 465 ? 'banner' : 'banner';
    let usingTls = cfg.port === 465;

    const b64 = (s) => Buffer.from(s, 'utf8').toString('base64');
    const encodedSubject = '=?UTF-8?B?' + b64(mail.subject) + '?=';

    function startTlsIfWanted() {
      // 587 等明文端口先走 banner -> EHLO -> STARTTLS 流程，复用同一对话状态机
    }

    function onConnect() {}

    socket.setTimeout(20 * 1000);
    socket.on('timeout', () => {
      socket.destroy();
      reject(new Error('smtp_timeout'));
    });
    socket.on('error', (e) => {
      if (stage !== 'done') reject(new Error('smtp_socket: ' + e.message));
    });

    socket.on('data', (chunk) => {
      buffer += chunk.toString('utf8');
      // 一行以 "数字+空格" 结尾才算完整响应（250-xxx 为续行）
      const m = /^(\d{3})[ ](.*)\r?\n$/s.exec(buffer.trimEnd());
      if (!m) {
        const cont = /^(\d{3})-/.test(buffer.trimEnd());
        if (!cont) return; // 响应未完，继续等
        buffer = '';
        return;
      }
      const code = parseInt(m[1], 10);
      const rest = buffer;
      buffer = '';

      if (stage === 'done') return;

      if (code >= 500) {
        stage = 'done';
        socket.end();
        reject(new Error('smtp_' + code + ': ' + m[2].slice(0, 120)));
        return;
      }

      switch (stage) {
        case 'banner':
          stage = 'ehlo';
          socket.write('EHLO hongguo.local\r\n');
          return;
        case 'ehlo':
          if (code !== 250) break;
          if (!usingTls && cfg.port !== 465 && cfg.starttls !== false) {
            stage = 'starttls';
            socket.write('STARTTLS\r\n');
            return;
          }
          stage = 'auth1';
          socket.write('AUTH LOGIN\r\n');
          return;
        case 'starttls':
          if (code !== 220) break;
          // 明文连接升级为 TLS：替换数据流
          const tlsSock = tls.connect(
            { socket, rejectUnauthorized: false, servername: cfg.host },
            () => {
              usingTls = true;
              stage = 'ehlo2';
              tlsSock.write('EHLO hongguo.local\r\n');
            }
          );
          tlsSock.on('error', (e) => {
            if (stage !== 'done') reject(new Error('smtp_tls: ' + e.message));
          });
          tlsSock.on('data', (chunk2) => socket.emit('data', chunk2));
          // 后续写操作都走 tlsSock
          Object.defineProperty(socket, 'write', {
            value: tlsSock.write.bind(tlsSock),
            configurable: true,
          });
          return;
        case 'ehlo2':
          stage = 'auth1';
          socket.write('AUTH LOGIN\r\n');
          return;
        case 'auth1':
          stage = 'auth2';
          socket.write(b64(cfg.user) + '\r\n');
          return;
        case 'auth2':
          stage = 'auth3';
          socket.write(b64(cfg.pass) + '\r\n');
          return;
        case 'auth3':
          if (code !== 235) {
            stage = 'done';
            socket.end();
            reject(new Error('smtp_auth_failed'));
            return;
          }
          stage = 'mailfrom';
          socket.write(`MAIL FROM:<${mail.from}>\r\n`);
          return;
        case 'mailfrom':
          stage = 'rcpto';
          socket.write(`RCPT TO:<${mail.to}>\r\n`);
          return;
        case 'rcpto':
          stage = 'data';
          socket.write('DATA\r\n');
          return;
        case 'data': {
          stage = 'body';
          const headers =
            `From: =?UTF-8?B?${b64('红果广告跳过')}?= <${mail.from}>\r\n` +
            `To: <${mail.to}>\r\n` +
            `Subject: ${encodedSubject}\r\n` +
            `Date: ${new Date().toUTCString()}\r\n` +
            `MIME-Version: 1.0\r\n` +
            `Content-Type: text/plain; charset=UTF-8\r\n` +
            `Content-Transfer-Encoding: base64\r\n` +
            `\r\n`;
          // 正文 base64 + 每行 76 字符（点号开头的行做防填充，普通验证码邮件无此情况）
          const bodyB64 = b64(mail.body).replace(/(.{76})/g, '$1\r\n');
          socket.write(headers + bodyB64 + '\r\n.\r\n');
          return;
        }
        case 'body':
          stage = 'quit';
          socket.write('QUIT\r\n');
          return;
        case 'quit':
          stage = 'done';
          socket.end();
          resolve(rest.trim());
          return;
      }
      // 非预期状态码
      stage = 'done';
      socket.end();
      reject(new Error('smtp_unexpected_' + code));
    });
  });
}

module.exports = { sendMail };
