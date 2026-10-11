' Watchdog launcher - fully windowless.
' Runs node watchdog_check.js with a hidden window (style 0).
' Do NOT use WshShell.Exec here: it spawns a visible console window (the blank black flash).
Option Explicit
Dim sh
Set sh = CreateObject("WScript.Shell")
sh.CurrentDirectory = "C:\Users\Admin\.zcode\workspace\default\HongguoAdSkipper\receiver"
sh.Run """C:\Program Files\nodejs\node.exe"" ""C:\Users\Admin\.zcode\workspace\default\HongguoAdSkipper\receiver\watchdog_check.js""", 0, False
