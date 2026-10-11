@echo off
title 红果广告跳过 - 反馈接收服务
echo 正在检查反馈接收服务状态...
netstat -ano | findstr ":8765" | findstr "LISTENING" >nul 2>&1
if %errorlevel%==0 (
    echo.
    echo 服务已经在运行中，无需重复启动。
    echo.
    pause >nul
    exit /b
)
cd /d "%~dp0"
echo 正在启动反馈接收服务（局域网）...
echo.
node server.js
echo.
echo 服务已停止。按任意键关闭窗口。
pause >nul
