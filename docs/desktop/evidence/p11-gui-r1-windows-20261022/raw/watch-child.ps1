$p=Get-Process -Id 14548 -ErrorAction Stop
$null=$p.Handle
$p.WaitForExit()
@{pid=14548;exitCode=$p.ExitCode;time=[DateTime]::UtcNow.ToString('o');role='frozen JVM-host child'} | ConvertTo-Json | Set-Content 'H:\ChatChatBar-Desktop\app\desktopApp\build\p11-gui-r1-windows-20261022\jvm-host-exit.json' -Encoding utf8
