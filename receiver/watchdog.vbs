' Watchdog for Hongguo feedback receiver - windowless (run by wscript.exe)
Option Explicit
Dim sh, exe, out, lines, ln, found
Set sh = CreateObject("WScript.Shell")
found = False
Set exe = sh.Exec("%ComSpec% /c netstat -ano")
out = exe.StdOut.ReadAll()
lines = Split(out, vbCrLf)
For Each ln In lines
    If InStr(ln, ":8765") > 0 And InStr(ln, "LISTENING") > 0 Then
        found = True
        Exit For
    End If
Next
If Not found Then
    sh.CurrentDirectory = "C:\Users\Admin\.zcode\workspace\default\HongguoAdSkipper\receiver"
    sh.Run """C:\Program Files\nodejs\node.exe"" ""C:\Users\Admin\.zcode\workspace\default\HongguoAdSkipper\receiver\server.js""", 0, False
    Dim fso, f
    Set fso = CreateObject("Scripting.FileSystemObject")
    If fso.FileExists("C:\Users\Admin\.zcode\workspace\default\HongguoAdSkipper\receiver\server.log") Then
        Set f = fso.OpenTextFile("C:\Users\Admin\.zcode\workspace\default\HongguoAdSkipper\receiver\server.log", 8, True)
        f.WriteLine Now & "  [watchdog] service was down, restarted"
        f.Close
    End If
End If
