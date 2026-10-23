param([Parameter(Mandatory=$true)][string]$SelectedFile)
$ErrorActionPreference = 'Stop'
try {
    $repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
    $cpFile = Join-Path $repo 'app/desktopApp/build/readonly-r0/runtime-classpath.txt'
    if (-not (Test-Path -LiteralPath $cpFile -PathType Leaf)) { throw 'NOT_PREPARED' }
    if (-not $env:JAVA_HOME) { throw 'JDK17_REQUIRED' }
    $java = Join-Path $env:JAVA_HOME 'bin/java.exe'
    $version = [IO.File]::ReadAllText((Join-Path $env:JAVA_HOME 'release'))
    if ($version -notmatch 'JAVA_VERSION="17\.') { throw 'JDK17_REQUIRED' }
    $cp = [IO.File]::ReadAllText($cpFile).Trim()
    # Inherit the real terminal: Java Console.readPassword masks input. No redirected stdin or transcript.
    & $java -Xmx768m -XX:+DisableAttachMechanism -XX:-HeapDumpOnOutOfMemoryError -Djava.awt.headless=true -cp $cp com.example.chatbar.desktop.backup.RealArchiveReadOnlyTool --single-file-read-only $SelectedFile
    exit $LASTEXITCODE
} catch {
    # Do not print PowerShell exception/argument/source path.
    Write-Output 'R0=REFUSED_LAUNCH_ENVIRONMENT'
    exit 2
}
