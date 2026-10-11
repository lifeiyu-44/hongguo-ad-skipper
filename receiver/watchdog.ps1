$ErrorActionPreference = 'SilentlyContinue'
# Watchdog: if port 8765 is not listening, restart the receiver service (hidden).
$port = 8765
$found = $false
foreach ($line in (netstat -ano)) {
    if ($line -match ":$port\s" -and $line -match 'LISTENING') { $found = $true; break }
}
if (-not $found) {
    Start-Process -FilePath 'C:\Program Files\nodejs\node.exe' `
        -ArgumentList '"C:\Users\Admin\.zcode\workspace\default\HongguoAdSkipper\receiver\server.js"' `
        -WorkingDirectory 'C:\Users\Admin\.zcode\workspace\default\HongguoAdSkipper\receiver' `
        -WindowStyle Hidden
    Add-Content -Path 'C:\Users\Admin\.zcode\workspace\default\HongguoAdSkipper\receiver\server.log' -Value "$(Get-Date -Format 'yyyy/MM/dd HH:mm:ss')  [watchdog] service was down, restarted"
}
