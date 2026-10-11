$action = New-ScheduledTaskAction -Execute "C:\Program Files\nodejs\node.exe" -Argument '"C:\Users\Admin\.zcode\workspace\default\HongguoAdSkipper\receiver\server.js"'
$trigger = New-ScheduledTaskTrigger -AtLogOn
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero)
Register-ScheduledTask -TaskName "HongguoFeedbackReceiver" -Action $action -Trigger $trigger -Settings $settings -Force -Description "红果广告跳过-反馈接收服务（开机自启）"
Write-Output "scheduled-task-created"
