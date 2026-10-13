# 红果广告反馈自动处理规程

本文件是「每2分钟自动接收反馈并处理」任务的详细操作规程。任务本身的指令只有两行，真正的处理细节以本文件为准。

## 目录约定

- 反馈目录：`C:\Users\Admin\.zcode\workspace\default\HongguoAdSkipper\receiver\reports`
- 每条反馈 = 一个以时间戳命名的子目录，内含：
  - `反馈.txt`：设备信息、识别快照、屏幕可见文字、运行日志、App 版本与 Android 版本
  - `截图.png`：用户点反馈按钮时自动抓取的画面（新版本均带）
- 已处理台账：同目录下 `processed.txt`（每行一个已处理目录名；不存在则创建）

## 处理流程

1. 列出 `reports` 下**未记录在 processed.txt** 的子目录。没有则只回「无新反馈」，不做任何其他事。
2. 对每个新反馈：
   a. 用 Read 工具查看 `截图.png`（直接看图判断画面内容）；读取 `反馈.txt` 全文。
   b. 判断该广告为何没被自动跳过（识别特征缺失 / 上滑手势无效 / 时序问题 / 版本过旧 / 截图授权问题），给出结论。
   c. 能明确修复时，在 `app/src/main/java/com/hongguo/adskip/` 下做针对性修改（特征正则、手势参数等）。
      - **铁律**：倒计时文字在屏幕上时绝不允许任何翻页动作；手机与平板行为保持一致；不要改动与本次问题无关的逻辑。
      - 递增 `app/build.gradle.kts` 的 versionCode（+1）与 versionName（小数点后一位+1），同步更新 `strings.xml` 的 version_label。
      - 构建：
        `cmd //c "set JAVA_HOME=C:\Users\Admin\.zcode\workspace\default\HongguoAdSkipper\.buildtools\jdk-17.0.20.1+1&& cd /d C:\Users\Admin\.zcode\workspace\default\HongguoAdSkipper && gradlew.bat assembleDebug --no-daemon"`
      - 构建成功后：
        - APK 复制到桌面并命名「红果广告跳过-vX.Y.apk」，删除桌面其它「红果广告跳过*.apk」旧文件；
        - 同步升级源：复制到 `receiver/updates/HongguoAdSkipper.apk`，写 `receiver/updates/version.json` 内容 `{"version":"X.Y"}`（服务实时读取，无需重启）；
        - 云端发版（可选）：登录云端 admin API 上传 APK 并激活版本；**备注必须先写入 UTF-8 文件再 `-F "note=<备注文件"` 上传**，严禁命令行内联中文——Windows 会按 GBK 编码命令行参数，云端按 UTF-8 存储即成乱码（历史乱码用 `cloud/fix-notes.sh` 在服务器上运行修复）；
        - `git add -A && git commit`；`git push origin main`（仓库已配代理 http://127.0.0.1:12334；失败可试 `git -c http.proxy= -c https.proxy= push` 直连；再失败留待下次补推）；
        - `gh release create vX.Y` 上传两个资产（HongguoAdSkipper-vX.Y.apk 与 HongguoAdSkipper.apk）；若报 TLS 超时，加 `HTTPS_PROXY=http://127.0.0.1:12334 HTTP_PROXY=http://127.0.0.1:12334` 前缀重试。
      - 构建失败：`git checkout --` 回退本次改动，记录失败原因。
   d. 无法定位明确原因时：不要改代码，把分析结论写清楚。
3. 处理完把目录名追加到 `processed.txt`（UTF-8）。

## 汇报格式

以「【新反馈】」开头：反馈时间与设备 → 截图内容/广告类型 → 未跳过或异常的原因判断 → 采取的动作（修了什么并发布了 vX.Y / 或需要用户配合做什么）。无新反馈时只回「无新反馈」。

## 隐私

反馈截图仅供分析广告特征，不得外传或提交进仓库。
