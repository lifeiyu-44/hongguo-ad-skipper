@echo off
chcp 65001 >nul
title 红果广告跳过 - 反馈接收服务
cd /d "%~dp0"
echo 正在启动反馈接收服务（局域网）...
echo.
node server.js
echo.
echo 服务已停止。按任意键关闭窗口。
pause >nul
