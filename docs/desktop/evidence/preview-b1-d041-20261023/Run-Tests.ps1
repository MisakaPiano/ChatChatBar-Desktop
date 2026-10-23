param([ValidateSet('red','green')][string]$Round)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$out=Join-Path $PSScriptRoot "raw/$Round"
New-Item -ItemType Directory -Force $out | Out-Null
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$argsList=@('--offline','--no-daemon','--no-configuration-cache','--max-workers=1','--no-parallel','--console=plain',
 '-Dorg.gradle.jvmargs=-Xmx1024m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8','-Pkotlin.compiler.execution.strategy=in-process',':desktopApp:test',
 '--tests','*DesktopUpdateCheckerTest','--tests','*DesktopStartupGateTest','--tests','*DesktopProcessLifecycleTest')
$argsList | ConvertTo-Json | Set-Content (Join-Path $out 'arguments.json')
Copy-Item (Join-Path $repo 'app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopUpdateChecker.kt') (Join-Path $out 'DesktopUpdateChecker.kt')
Copy-Item (Join-Path $repo 'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/DesktopUpdateCheckerTest.kt') (Join-Path $out 'DesktopUpdateCheckerTest.kt')
Push-Location (Join-Path $repo 'app')
try {
 & ./gradlew.bat @argsList *> (Join-Path $out 'gradle.log')
 $code=$LASTEXITCODE
 $code | Set-Content (Join-Path $out 'exit.txt')
 if(Test-Path 'desktopApp/build/test-results/test'){Copy-Item 'desktopApp/build/test-results/test' (Join-Path $out 'junit') -Recurse}
 Write-Output "ROUND=$Round EXIT=$code"
 exit $code
}finally{Pop-Location}
