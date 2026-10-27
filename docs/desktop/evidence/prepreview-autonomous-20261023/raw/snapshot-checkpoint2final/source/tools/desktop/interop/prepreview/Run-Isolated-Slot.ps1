param([Parameter(Mandatory=$true)][string]$Source)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
foreach($optionName in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')) {
 if([Environment]::GetEnvironmentVariable($optionName)){throw 'INHERITED_OPTIONS_REFUSED'}
}
$private=Join-Path $repo 'app/desktopApp/build/prepreview-private'
if(Test-Path $private){if((Get-Item -LiteralPath $private).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'PRIVATE_REPARSE_REFUSED'}}
New-Item -ItemType Directory -Force $private | Out-Null
$log=Join-Path $private ("private-console-"+[Guid]::NewGuid().ToString()+'.log')
$cp=(Get-Content (Join-Path $repo 'app/desktopApp/build/readonly-r0/runtime-classpath.txt') -Raw).Trim()
Push-Location (Join-Path $repo 'app')
try {
 & 'C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin/java.exe' '-Xmx256m' '-XX:+DisableAttachMechanism' '-XX:-HeapDumpOnOutOfMemoryError' '-Djava.awt.headless=true' -cp $cp com.example.chatbar.desktop.SlotIsolatedRestoreProbe $Source *> $log
 $code=$LASTEXITCODE
 # Real process logs stay private/ignored. Public output is a fixed token-only receipt.
 Get-Content -LiteralPath $log | Where-Object {$_ -match '^PROBE_[A-Z_]+=[A-Z_0-9]+$'}
 "ISOLATED_PROBE_EXIT=$code"
 exit $code
} finally {Pop-Location}
