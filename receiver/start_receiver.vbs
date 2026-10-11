' Receiver launcher - fully windowless (used by the logon scheduled task).
Option Explicit
Dim sh
Set sh = CreateObject("WScript.Shell")
sh.CurrentDirectory = "C:\Users\Admin\.zcode\workspace\default\HongguoAdSkipper\receiver"
sh.Run """C:\Program Files\nodejs\node.exe"" ""C:\Users\Admin\.zcode\workspace\default\HongguoAdSkipper\receiver\server.js""", 0, False
