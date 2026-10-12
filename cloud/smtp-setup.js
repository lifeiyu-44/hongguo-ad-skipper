/**
 * SMTP 授权码自助设置（在服务器上自己运行，授权码不经过任何人）
 *
 *   node smtp-setup.js
 *
 * 按提示输入 SMTP 服务器（如 smtp.qq.com）、邮箱账号、授权码，
 * 写入 ../config/smtp.json（权限 600），随后云端服务立即支持邮箱验证码。
 * 常见授权码获取方式：
 *   QQ 邮箱：设置 -> 账号 -> 开启 SMTP 服务 -> 生成授权码
 *   163 邮箱：设置 -> POP3/SMTP/IMAP -> 开启 SMTP -> 设置授权码
 */
const fs = require('fs');
const path = require('path');
const readline = require('readline');

const CONFIG_DIR = process.env.HONGGUO_CONFIG
  ? path.resolve(process.env.HONGGUO_CONFIG)
  : path.join(__dirname, 'config');
const SMTP_FILE = path.join(CONFIG_DIR, 'smtp.json');

const rl = readline.createInterface({ input: process.stdin, output: process.stdout });

function ask(q) {
  return new Promise((resolve) => rl.question(q, resolve));
}

function askHidden(q) {
  return new Promise((resolve) => {
    const stdin = process.stdin;
    const wasRaw = stdin.isRaw;
    if (wasRaw) stdin.setRawMode(true);
    let out = '';
    process.stdout.write(q);
    const onData = (ch) => {
      ch = String(ch);
      if (ch === '\r' || ch === '\n' || ch === '\u0004') {
        stdin.removeListener('data', onData);
        if (wasRaw) stdin.setRawMode(false);
        process.stdout.write('\n');
        resolve(out);
      } else if (ch === '\u0003') {
        process.exit(1);
      } else if (ch === '\u007f' || ch === '\b') {
        if (out) out = out.slice(0, -1);
      } else {
        out += ch;
      }
    };
    stdin.on('data', onData);
  });
}

(async () => {
  console.log('红果云端 · SMTP 发信设置（写入 ' + SMTP_FILE + '）');
  console.log('QQ 邮箱填 smtp.qq.com / 465；163 邮箱填 smtp.163.com / 465\n');
  const host = ((await ask('SMTP 服务器: ')) || '').trim();
  const port = parseInt((await ask('端口(默认465): ')) || '465', 10) || 465;
  const user = ((await ask('邮箱账号: ')) || '').trim();
  const pass = await askHidden('SMTP 授权码(输入不显示): ');
  if (!host || !user || !pass) {
    console.log('信息不完整，未保存。');
    process.exit(1);
  }
  fs.mkdirSync(CONFIG_DIR, { recursive: true });
  fs.writeFileSync(
    SMTP_FILE,
    JSON.stringify({ host, port, user, pass, from: user }, null, 2),
    { encoding: 'utf8', mode: 0o600 }
  );
  console.log('已保存。云端服务的邮箱验证码即刻生效（无需重启）。');

  // 立即自测一封（可选）
  const test = ((await ask('给 ' + user + ' 发一封测试邮件验证配置？(y/N): ')) || '').trim().toLowerCase();
  if (test === 'y') {
    try {
      const { sendMail } = require('./smtp');
      await sendMail({ host, port, user, pass, from: user }, {
        from: user,
        to: user,
        subject: '红果云端 SMTP 配置测试',
        body: '配置成功！收到本邮件说明 SMTP 发信正常。',
      });
      console.log('测试邮件已发送，请查收（含垃圾箱）。');
    } catch (e) {
      console.log('发送失败：' + e.message + '（请检查授权码/端口，可重新运行本脚本）');
    }
  }
  rl.close();
  process.exit(0);
})();
