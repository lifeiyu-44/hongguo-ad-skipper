$ErrorActionPreference = 'SilentlyContinue'
# 看门狗：检查 8765 端口是否在监听，不在则拉起接收服务（静默）
$listening = netstat -ano | Select-String ':8765' | Select-String 'LISTENING'
if (-not $listening) {
    Start-Process -FilePath 'C:\Program Files\nodejs\node.exe' `
        -ArgumentList '"C:\Users\Admin\.zcode\workspace\default\HongguoAdSkipper\receiver\server.js"' `
        -WorkingDirectory 'C:\Users\Admin\.zcode\workspace\default\HongguoAdSkipper\receiver' `
        -WindowStyle Hidden
    Add-Content -Path 'C:\Users\Admin\.zcode\workspace\default\HongguoAdSkipper\receiver\server.log' -Value "$(Get-Date -Format 'yyyy/MM/dd HH:mm:ss')  [看门狗] 检测到服务掉线，已自动拉起" -Encoding UTF8
}
